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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import org.json.JSONException;
import org.json.JSONObject;

import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.HttpStatus;
import ch.boye.httpclientandroidlib.client.methods.HttpPost;
import ch.boye.httpclientandroidlib.client.utils.URIBuilder;
import ch.boye.httpclientandroidlib.conn.ClientConnectionManager;
import ch.boye.httpclientandroidlib.conn.DnsResolver;
import ch.boye.httpclientandroidlib.conn.scheme.Scheme;
import ch.boye.httpclientandroidlib.conn.scheme.SchemeRegistry;
import ch.boye.httpclientandroidlib.conn.ssl.SSLSocketFactory;
import ch.boye.httpclientandroidlib.entity.ByteArrayEntity;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;
import ch.boye.httpclientandroidlib.impl.conn.PoolingClientConnectionManager;
import ch.boye.httpclientandroidlib.impl.conn.SingleClientConnManager;
import ch.boye.httpclientandroidlib.params.BasicHttpParams;
import ch.boye.httpclientandroidlib.params.HttpConnectionParams;
import ch.boye.httpclientandroidlib.params.HttpParams;
import ch.ethz.ssh2.crypto.ObfuscatedSSH;

import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedDnsResolver;
import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedPlainSocketFactory;
import com.psiphon3.psiphonlibrary.ServerInterface.ProtectedSSLSocketFactory;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

//ch.boye.httpclientandroidlib.impl.conn.SingleClientConnManager is deprecated
@SuppressWarnings("deprecation")

public class MeekClient {

    final static int MAX_PAYLOAD_LENGTH = 0x10000;
    final static int INIT_POLL_INTERVAL_MILLISECONDS = 10;
    final static int MAX_POLL_INTERVAL_MILLISECONDS = 5000;
    final static double POLL_INTERVAL_MULTIPLIER = 2.0;
    final static int MEEK_SERVER_TIMEOUT_MILLISECONDS = 20000;
    final static int ABORT_POLL_MILLISECONDS = 100;

    final static String HTTP_POST_CONTENT_TYPE = "application/octet-stream";
    
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
    private CountDownLatch mEstablishedFirstServerConnection;
    
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
            String frontingHost) {
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
            int meekServerPort) {
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

    public synchronized void start() throws IOException {
        stop();
        mServerSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        mLocalPort = mServerSocket.getLocalPort();
        mClients = new HashSet<Socket>();
        mEstablishedFirstServerConnection = new CountDownLatch(1);
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
                            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (SocketException e) {
                            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        } catch (IOException e) {
                            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
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
            mEstablishedFirstServerConnection = null;
        }
    }
    
    public void awaitEstablishedFirstServerConnection(IAbortIndicator abortIndicator) throws InterruptedException {
        while (!mEstablishedFirstServerConnection.await(ABORT_POLL_MILLISECONDS, TimeUnit.MILLISECONDS)) {
            if (abortIndicator.shouldAbort()) {
                break;
            }
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
        ClientConnectionManager connManager = null;
        try {
            socketInputStream = socket.getInputStream();
            socketOutputStream = socket.getOutputStream();
            String cookie = makeCookie();
            byte[] payloadBuffer = new byte[MAX_PAYLOAD_LENGTH];
            int pollInternalMilliseconds = INIT_POLL_INTERVAL_MILLISECONDS;
            
            SchemeRegistry registry = new SchemeRegistry();
            if (mFrontingDomain != null) {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null,  null,  null);
                ProtectedSSLSocketFactory sslSocketFactory = new ProtectedSSLSocketFactory(
                        mProtectSocket, sslContext, SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
                registry.register(new Scheme("https", 443, sslSocketFactory));                
            } else {
                ProtectedPlainSocketFactory plainSocketFactory = new ProtectedPlainSocketFactory(mProtectSocket);
                registry.register(new Scheme("http", 80, plainSocketFactory));                
            }

            if (mProtectSocket != null) {
                // Use ProtectedDnsResolver to resolve the fronting domain outside of the tunnel
                DnsResolver dnsResolver = new ProtectedDnsResolver(mProtectSocket, mServerInterface);
                PoolingClientConnectionManager poolingConnManager = new PoolingClientConnectionManager(registry, dnsResolver);
                // We're using the pool for its ability to override the DnsResolver. Only need 1 connection.
                poolingConnManager.setDefaultMaxPerRoute(1);
                poolingConnManager.setMaxTotal(1);
                connManager = poolingConnManager;
            }
            else {
                connManager = new SingleClientConnManager(registry);
            }

            HttpParams httpParams = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, MEEK_SERVER_TIMEOUT_MILLISECONDS);
            HttpConnectionParams.setSoTimeout(httpParams, MEEK_SERVER_TIMEOUT_MILLISECONDS);
            DefaultHttpClient httpClient = new DefaultHttpClient(connManager, httpParams);
            URI uri = null;
            if (mFrontingDomain != null) {
                uri = new URIBuilder().setScheme("https").setHost(mFrontingDomain).setPath("/").build();
            } else {
                uri = new URIBuilder().setScheme("http").setHost(mMeekServerHost).setPort(mMeekServerPort).setPath("/").build();                    
            }

            while (true) {
                // TODO: read in a separate thread (or asynchronously) to allow continuous requests while streaming downloads
                socket.setSoTimeout(pollInternalMilliseconds);
                int payloadLength = 0;

                // We make the very first poll without waiting to read in order to check connection establishment
                if (mEstablishedFirstServerConnection.getCount() == 0) {
                    try {
                        payloadLength = socketInputStream.read(payloadBuffer);
                    } catch (SocketTimeoutException e) {
                        // In this case, we POST with no content -- this is for polling the server
                    }
                    if (payloadLength == -1) {
                        // EOF
                        break;
                    }
                }

                HttpPost httpPost = new HttpPost(uri);
                ByteArrayEntity entity = new ByteArrayEntity(payloadBuffer, 0, payloadLength);
                entity.setContentType(HTTP_POST_CONTENT_TYPE);
                httpPost.setEntity(entity);

                if (mFrontingDomain != null) {
                    httpPost.addHeader("Host", mFrontingHost);
                }
                httpPost.addHeader("Cookie", cookie);

                HttpResponse response = httpClient.execute(httpPost);
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode != HttpStatus.SC_OK) {
                    MyLog.e(R.string.meek_http_request_error, MyLog.Sensitivity.NOT_SENSITIVE, statusCode);
                    break;
                }
                InputStream responseInputStream = response.getEntity().getContent();
                try {
                    int readLength;
                    while ((readLength = responseInputStream.read(payloadBuffer)) != -1) {
                        socketOutputStream.write(payloadBuffer, 0 , readLength);
                    }
                } finally {
                    Utils.closeHelper(responseInputStream);
                }
                
                // Signal when first connection is established
                mEstablishedFirstServerConnection.countDown();

                if (payloadLength > 0) {
                    pollInternalMilliseconds = INIT_POLL_INTERVAL_MILLISECONDS;
                } else {
                    pollInternalMilliseconds = (int)(pollInternalMilliseconds*POLL_INTERVAL_MULTIPLIER);
                }
                if (pollInternalMilliseconds > MAX_POLL_INTERVAL_MILLISECONDS) {
                    pollInternalMilliseconds = MAX_POLL_INTERVAL_MILLISECONDS;
                }
            }
        } catch (IOException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (URISyntaxException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (UnsupportedOperationException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalStateException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (IllegalArgumentException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NullPointerException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
        } catch (NoSuchAlgorithmException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (KeyManagementException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (JSONException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } catch (GeneralSecurityException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());                    
        } finally {
            if (connManager != null) {
                connManager.shutdown();
            }
            Utils.closeHelper(socketInputStream);
            Utils.closeHelper(socketOutputStream);
            Utils.closeHelper(socket);
        }
    }
    
    private String makeCookie()
            throws JSONException, GeneralSecurityException, IOException {

        JSONObject payload = new JSONObject();
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

        return cookieKey + "=" + cookieValue;
    }
}
