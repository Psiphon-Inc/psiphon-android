/*
 * Copyright (c) 2014, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

/*
 * Ported from meek Go client:
 * https://gitweb.torproject.org/pluggable-transports/meek.git/blob/HEAD:/meek-client/meek-client.go
 * 
 * Notable changes from the Go version, as required for Psiphon Android:
 * - uses VpnService protectSocket(), via Tun2Socks.IProtectSocket and ProtectedPlainSocketFactory, to
 *   exclude connections to the meek relay from the VPN interface
 * - in-process logging
 * - there's no SOCKS interface; the target Psiphon server is fixed when the meek client is constructed
 *   we're making multiple meek clients anyway -- one per target server -- in order to test connections
 *   to different meek relays or via different fronts
 * - unfronted mode, which is HTTP only (with obfuscated cookies used to pass params from client to relay)
 * - initial meek server poll is made with no delay in order to time connection responsiveness
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthProtocolState;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthStateHC4;
import org.apache.http.auth.Credentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPostHC4;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.util.InetAddressUtils;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieOrigin;
import org.apache.http.cookie.CookieSpec;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.cookie.MalformedCookieException;
import org.apache.http.entity.ByteArrayEntityHC4;
import org.apache.http.impl.client.BasicCookieStoreHC4;
import org.apache.http.impl.client.BasicCredentialsProviderHC4;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.cookie.BasicClientCookieHC4;
import org.apache.http.impl.cookie.BrowserCompatSpecHC4;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtilsHC4;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import ch.ethz.ssh2.crypto.ObfuscatedSSH;

import com.psiphon3.psiphonlibrary.ServerInterface.FrontingSSLConnectionSocketFactory;
import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedPlainConnectionSocketFactory;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.psiphonlibrary.Utils.RequestTimeoutAbort;

public class MeekClient {

    final static int MEEK_PROTOCOL_VERSION = 2;
    final static int MAX_PAYLOAD_LENGTH = 0x10000;
    final static int MIN_POLL_INTERVAL_MILLISECONDS = 1;
    final static int IDLE_POLL_INTERVAL_MILLISECONDS = 100;
    final static int MAX_POLL_INTERVAL_MILLISECONDS = 5000;
    final static double POLL_INTERVAL_MULTIPLIER = 1.5;
    final static int MEEK_SERVER_TIMEOUT_MILLISECONDS = 20000;
    final static int ABORT_POLL_MILLISECONDS = 100;

    final static String HTTP_POST_CONTENT_TYPE = "application/octet-stream";
    
    final private MeekProtocol mProtocol;
    final private Tun2Socks.IProtectSocket mProtectSocket;
    final private ServerInterface mServerInterface;
    final private String mPsiphonClientSessionId;
    final private String mPsiphonServerAddress;
    final private String mFrontingDomain;
    final private String mFrontingHost;
    final private String mMeekServerHost;
    final private int mMeekServerPort;
    final private String mCookieEncryptionPublicKey;
    final private String mObfuscationKeyword;
    private Thread mAcceptThread;
    private ServerSocket mServerSocket;
    private int mLocalPort = -1;
    private Set<Socket> mClients;
    private final Context mContext;
    private final AtomicBoolean mPrintedProxyAuth = new AtomicBoolean(false);
    
    public enum MeekProtocol {FRONTED, UNFRONTED};

    public interface IAbortIndicator {
        public boolean shouldAbort();
    }
    
    public MeekClient(
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            String psiphonClientSessionId,
            String psiphonServerAddress,
            String cookieEncryptionPublicKey,
            String obfuscationKeyword,
            String frontingDomain,
            String frontingHost,
    		Context context) {
    	mContext = context.getApplicationContext();
        mProtocol = MeekProtocol.FRONTED;
        mProtectSocket = protectSocket;
        mServerInterface = serverInterface;
        mPsiphonClientSessionId = psiphonClientSessionId;
        mPsiphonServerAddress = psiphonServerAddress;
        mCookieEncryptionPublicKey = cookieEncryptionPublicKey;
        mObfuscationKeyword = obfuscationKeyword;
        mFrontingDomain = frontingDomain;
        mFrontingHost = frontingHost;
        mMeekServerHost = null;
        mMeekServerPort = -1;
    }

    public MeekClient(
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            String psiphonClientSessionId,
            String psiphonServerAddress,
            String cookieEncryptionPublicKey,
            String obfuscationKeyword,
            String meekServerHost,
            int meekServerPort,
            Context context) {
    	mContext = context.getApplicationContext();
        mProtocol = MeekProtocol.UNFRONTED;
        mProtectSocket = protectSocket;
        mServerInterface = serverInterface;
        mPsiphonClientSessionId = psiphonClientSessionId;
        mPsiphonServerAddress = psiphonServerAddress;
        mCookieEncryptionPublicKey = cookieEncryptionPublicKey;
        mObfuscationKeyword = obfuscationKeyword;
        mFrontingDomain = null;
        mFrontingHost = null;
        mMeekServerHost = meekServerHost;
        mMeekServerPort = meekServerPort;
    }
    
    public MeekProtocol getProtocol() {
        return mProtocol;
    }

    public synchronized void start() throws IOException {
        stop();
        mServerSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        mLocalPort = mServerSocket.getLocalPort();
        mClients = new HashSet<Socket>();
        mAcceptThread = new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            while(true) {
                                final Socket finalSocket = mServerSocket.accept();
                                registerClient(finalSocket);
                                Thread clientThread = new Thread(
                                        new Runnable() {
                                           @Override
                                           public void run() {
                                               try {
                                                   runClient(finalSocket);
                                               } finally {
                                                   unregisterClient(finalSocket);
                                               }
                                           }
                                        });
                                clientThread.start();
                            }
                        } catch (NullPointerException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (SocketException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (IOException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        }                        
                    }
                });
        mAcceptThread.start();
    }
    
    public synchronized int getLocalPort() {
        return mLocalPort;
    }
    
    public synchronized void stop() {
        if (mServerSocket != null) {
            Utils.closeHelper(mServerSocket);
            try {
                mAcceptThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopClients();
            mServerSocket = null;
            mAcceptThread = null;
            mLocalPort = -1;
        }
    }
    
    private synchronized void registerClient(Socket socket) {
        mClients.add(socket);
    }
    
    private synchronized void unregisterClient(Socket socket) {        
        mClients.remove(socket);
    }
    
    private synchronized void stopClients() {
        // Note: not actually joining client threads
        for (Socket socket : mClients) {
            Utils.closeHelper(socket);
        }
        mClients.clear();
    }
    
    private void runClient(Socket socket) {
        InputStream socketInputStream = null;
        OutputStream socketOutputStream = null;
        HttpClientConnectionManager connManager = null;
        CloseableHttpClient httpClient = null;
        try {
            socketInputStream = socket.getInputStream();
            socketOutputStream = socket.getOutputStream();
            Cookie cookie = makeCookie();
            byte[] payloadBuffer = new byte[MAX_PAYLOAD_LENGTH];
            int pollIntervalMilliseconds = MIN_POLL_INTERVAL_MILLISECONDS;
            
            RegistryBuilder<ConnectionSocketFactory> registryBuilder = RegistryBuilder.<ConnectionSocketFactory> create();
            
            if (mFrontingDomain != null) {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null,  null,  null);
                // Don't verify certificate hostname.
                // With a TLS MiM attack in place, and server certs verified, we'll fail to connect because the client
                // will refuse to connect. That's not a successful outcome.
                // See https://github.com/Psiphon-Labs/psiphon-tunnel-core/blob/master/psiphon/meekConn.go for more details
                FrontingSSLConnectionSocketFactory sslSocketFactory = new FrontingSSLConnectionSocketFactory(
                        mProtectSocket, sslContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
                registryBuilder.register("https",  sslSocketFactory);
            } else if (mMeekServerPort == 443) {
                SSLContextBuilder sslContextBuilder = new SSLContextBuilder();
                sslContextBuilder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
                // Don't verify certificate hostname, and allow self-signed certs
                FrontingSSLConnectionSocketFactory sslSocketFactory = new FrontingSSLConnectionSocketFactory(
                        mProtectSocket, sslContextBuilder.build(), SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
                registryBuilder.register("https",  sslSocketFactory);
            }
            
            //Always register http scheme for HTTP proxy support
            ProtectedPlainConnectionSocketFactory plainSocketFactory = new ProtectedPlainConnectionSocketFactory(mProtectSocket);
            registryBuilder.register("http",  plainSocketFactory); 
            Registry<ConnectionSocketFactory> socketFactoryRegistry = registryBuilder.build();

            // Use ProtectedDnsResolver to resolve the fronting domain outside of the tunnel
            DnsResolver dnsResolver = ServerInterface.getDnsResolver(mProtectSocket, mServerInterface);
            connManager = new BasicHttpClientConnectionManager(socketFactoryRegistry,
                    ManagedHttpClientConnectionFactory.INSTANCE, DefaultSchemePortResolver.INSTANCE , dnsResolver);

            CookieSpecProvider nonValidatingCookieSpecProvider = new CookieSpecProvider() {
                public CookieSpec create(HttpContext context) {
                    return new BrowserCompatSpecHC4() {
                        @Override
                        public void validate(Cookie cookie, CookieOrigin origin)
                                throws MalformedCookieException {
                        }
                    };
                }
            };
            
            Registry<CookieSpecProvider> cookieSpecRegistry = RegistryBuilder.<CookieSpecProvider>create()
                    .register("novalidation", nonValidatingCookieSpecProvider)
                    .build();
                
            RequestConfig.Builder requestBuilder = RequestConfig.custom()
                    .setConnectTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS)
                    .setConnectionRequestTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS)
                    .setSocketTimeout(MEEK_SERVER_TIMEOUT_MILLISECONDS)
                    .setCookieSpec("novalidation");
            
            CookieStore cookieStore = new BasicCookieStoreHC4();
            HttpClientContext httpClientContext = HttpClientContext.create();
            httpClientContext.setCookieStore(cookieStore);
            
            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(mContext);
            if (proxySettings != null)
            {
            	HttpHost httpproxy = new HttpHost(proxySettings.proxyHost, proxySettings.proxyPort);
            	requestBuilder.setProxy(httpproxy);
            	Credentials proxyCredentials = PsiphonData.getPsiphonData().getProxyCredentials();
            	if(proxyCredentials != null)
            	{
            		CredentialsProvider credentialsProvider = new BasicCredentialsProviderHC4();
            		credentialsProvider.setCredentials(AuthScope.ANY, proxyCredentials);
            		httpClientContext.setCredentialsProvider(credentialsProvider);
            	}
            }
            
            httpClient = HttpClientBuilder
                    .create()
                    .setConnectionManager(connManager)
                    .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                    .disableAutomaticRetries()
                    .build();

            URI uri = null;
            if (mFrontingDomain != null) {
                uri = new URIBuilder().setScheme("https").setHost(mFrontingDomain).setPath("/").build();
            } else if (mMeekServerPort == 443) {
                uri = new URIBuilder().setScheme("https").setHost(mMeekServerHost).setPath("/").build();
            } else {
                uri = new URIBuilder().setScheme("http").setHost(mMeekServerHost).setPort(mMeekServerPort).setPath("/").build();                    
            }

            while (true) {
                // TODO: read in a separate thread (or asynchronously) to allow continuous requests while streaming downloads
                socket.setSoTimeout(pollIntervalMilliseconds);
                int payloadLength = 0;
                try {
                    payloadLength = socketInputStream.read(payloadBuffer);
                } catch (SocketTimeoutException e) {
                    // In this case, we POST with no content -- this is for polling the server
                }
                if (payloadLength == -1) {
                    // EOF
                    break;
                }

                // (comment from meek-client.go)
                // Retry loop, which assumes entire request failed (underlying
                // transport protocol such as SSH will fail if extra bytes are
                // replayed in either direction due to partial request success
                // followed by retry).
                // This retry mitigates intermittent failures between the client
                // and front/server.
                int retry;
                for (retry = 1; retry >= 0; retry--) {
                    HttpPostHC4 httpPost = new HttpPostHC4(uri);
                    ByteArrayEntityHC4 entity = new ByteArrayEntityHC4(payloadBuffer, 0, payloadLength);
                    entity.setContentType(HTTP_POST_CONTENT_TYPE);
                    httpPost.setEntity(entity);
                    httpPost.setConfig(requestBuilder.build());

                    if (mFrontingDomain != null) {
                        httpPost.addHeader("Host", mFrontingHost);
                        if (!InetAddressUtils.isIPv4Address(mFrontingDomain)) {
                            httpPost.addHeader("X-Psiphon-Fronting-Address", mFrontingDomain);
                        }
                    }
                    httpPost.addHeader("Cookie", String.format("%s=%s", cookie.getName(), cookie.getValue()));
                    
                    CloseableHttpResponse response = null;
                    HttpEntity rentity = null;
                    
                    try {
                        RequestTimeoutAbort timeoutAbort = new RequestTimeoutAbort(httpPost);
                        new Timer(true).schedule(timeoutAbort, MEEK_SERVER_TIMEOUT_MILLISECONDS);
                        try {
                            response = httpClient.execute(httpPost, httpClientContext);
                        } catch (IOException e) {
                            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                            // Retry (or abort)
                            continue;
                        } finally {
                            timeoutAbort.cancel();
                        }
                        int statusCode = response.getStatusLine().getStatusCode();
                        if (statusCode != HttpStatus.SC_OK) {
                            MyLog.w(R.string.meek_http_request_error, MyLog.Sensitivity.NOT_SENSITIVE, statusCode);
                            // Retry (or abort)
                            continue;
                        }
                        
                        AuthStateHC4 authState = (AuthStateHC4) httpClientContext.getAttribute(HttpClientContext.PROXY_AUTH_STATE);
                        AuthScheme authScheme = authState.getAuthScheme(); 
                        
                        //Log proxy auth only once per meek session 
                        if (mPrintedProxyAuth.compareAndSet(false, true) && 
                                authState.getState() == AuthProtocolState.SUCCESS && authScheme != null) {
                            MyLog.g("ProxyAuthentication", "Scheme", authScheme.getSchemeName());
                        }
                        
                        List<Cookie> responseCookies = httpClientContext.getCookieStore().getCookies();
                        for (Cookie responseCookie : responseCookies) {
                            if (responseCookie.getName().equals(cookie.getName()) &&
                                    !responseCookie.getValue().equals(cookie.getValue())) {
                                cookie = responseCookie;
                                break;
                            }
                        }
                        
                        boolean receivedData = false;
                        rentity = response.getEntity();
                        if (rentity != null) {
                            InputStream responseInputStream = rentity.getContent();
                            try {
                                int readLength;
                                while ((readLength = responseInputStream.read(payloadBuffer)) != -1) {
                                    receivedData = true;
                                    socketOutputStream.write(payloadBuffer, 0 , readLength);
                                }
                            } finally {
                                Utils.closeHelper(responseInputStream);
                            }
                        }
                        
                        if (payloadLength > 0 || receivedData) {
                            pollIntervalMilliseconds = MIN_POLL_INTERVAL_MILLISECONDS;
                        } else if (pollIntervalMilliseconds == MIN_POLL_INTERVAL_MILLISECONDS) {
                            pollIntervalMilliseconds = IDLE_POLL_INTERVAL_MILLISECONDS;
                        } else {
                            pollIntervalMilliseconds = (int)(pollIntervalMilliseconds*POLL_INTERVAL_MULTIPLIER);
                        }
                        if (pollIntervalMilliseconds > MAX_POLL_INTERVAL_MILLISECONDS) {
                            pollIntervalMilliseconds = MAX_POLL_INTERVAL_MILLISECONDS;
                        }
                    } finally {
                        if (rentity == null && response != null) {
                            rentity = response.getEntity();
                        }
                        if (rentity != null) {
                            EntityUtilsHC4.consume(rentity);
                        }
                        Utils.closeHelper(response);

                        httpPost.releaseConnection();
                    }
                    // Success: exit retry loop
                    break;
                }
                if (retry < 0) {
                    // All retries failed, so abort this meek client session
                    break;
                }
            }
        } catch (ClientProtocolException e) {
        	MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IOException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (URISyntaxException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (UnsupportedOperationException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalStateException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalArgumentException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NullPointerException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (KeyManagementException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (JSONException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (GeneralSecurityException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } finally {
            Utils.closeHelper(httpClient);
            Utils.closeHelper(socketInputStream);
            Utils.closeHelper(socketOutputStream);
            Utils.closeHelper(socket);
        }
    }
    
    private Cookie makeCookie()
            throws JSONException, GeneralSecurityException, IOException {

        JSONObject payload = new JSONObject();
        payload.put("v", MEEK_PROTOCOL_VERSION);
        payload.put("s", mPsiphonClientSessionId);
        payload.put("p", mPsiphonServerAddress);

        // NaCl crypto_box: http://nacl.cr.yp.to/box.html
        // The recipient public key is known and trusted (embedded in the signed APK)
        // The sender public key is ephemeral (recipient does not authenticate sender)
        // The nonce is fixed as as 0s; the one-time, single-use ephemeral public key is sent with the box
        
        org.abstractj.kalium.keys.PublicKey recipientPublicKey = new org.abstractj.kalium.keys.PublicKey(
                Utils.Base64.decode(mCookieEncryptionPublicKey));
        org.abstractj.kalium.keys.KeyPair ephemeralKeyPair = new org.abstractj.kalium.keys.KeyPair();
        byte[] nonce = new byte[org.abstractj.kalium.SodiumConstants.NONCE_BYTES]; // Java bytes arrays default to 0s
        org.abstractj.kalium.crypto.Box box = new org.abstractj.kalium.crypto.Box(recipientPublicKey, ephemeralKeyPair.getPrivateKey());
        byte[] message = box.encrypt(nonce, payload.toString().getBytes("UTF-8"));
        byte[] ephemeralPublicKeyBytes = ephemeralKeyPair.getPublicKey().toBytes();
        byte[] encryptedPayload = new byte[ephemeralPublicKeyBytes.length + message.length];
        System.arraycopy(ephemeralPublicKeyBytes, 0, encryptedPayload, 0, ephemeralPublicKeyBytes.length);
        System.arraycopy(message, 0, encryptedPayload, ephemeralPublicKeyBytes.length, message.length);

        String cookieValue;
        if (mObfuscationKeyword != null) {
            final int OBFUSCATE_MAX_PADDING = 32;
            ObfuscatedSSH obfuscator = new ObfuscatedSSH(mObfuscationKeyword, OBFUSCATE_MAX_PADDING);
            byte[] obfuscatedSeedMessage = obfuscator.getSeedMessage();
            byte[] obfuscatedPayload = new byte[encryptedPayload.length];
            System.arraycopy(encryptedPayload, 0, obfuscatedPayload, 0, encryptedPayload.length);
            obfuscator.obfuscateOutput(obfuscatedPayload);
            byte[] obfuscatedCookieValue = new byte[obfuscatedSeedMessage.length + obfuscatedPayload.length];
            System.arraycopy(obfuscatedSeedMessage, 0, obfuscatedCookieValue, 0, obfuscatedSeedMessage.length);
            System.arraycopy(obfuscatedPayload, 0, obfuscatedCookieValue, obfuscatedSeedMessage.length, obfuscatedPayload.length);
            cookieValue = Utils.Base64.encode(obfuscatedCookieValue);
        } else {
            cookieValue = Utils.Base64.encode(encryptedPayload);
        }

        // Select a random-ish cookie key (which will be observable and subject to fingerprinting in unfronted mode)
        final String cookieKeyValues = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        char cookieKey = cookieKeyValues.toCharArray()[Utils.insecureRandRange(0, cookieKeyValues.length()-1)];

        return new BasicClientCookieHC4(String.valueOf(cookieKey), cookieValue);
    }
}
