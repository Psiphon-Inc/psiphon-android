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
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Set;

import ch.boye.httpclientandroidlib.HttpResponse;
import ch.boye.httpclientandroidlib.HttpStatus;
import ch.boye.httpclientandroidlib.client.methods.HttpPost;
import ch.boye.httpclientandroidlib.client.utils.URIBuilder;
import ch.boye.httpclientandroidlib.entity.ByteArrayEntity;
import ch.boye.httpclientandroidlib.impl.client.DefaultHttpClient;
import ch.boye.httpclientandroidlib.impl.conn.SingleClientConnManager;
import ch.boye.httpclientandroidlib.params.BasicHttpParams;
import ch.boye.httpclientandroidlib.params.HttpConnectionParams;
import ch.boye.httpclientandroidlib.params.HttpParams;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

//ch.boye.httpclientandroidlib.impl.conn.SingleClientConnManager is deprecated
@SuppressWarnings("deprecation")

public class MeekClient {

    final static int SESSION_ID_LENGTH = 32;
    final static int MAX_PAYLOAD_LENGTH = 0x10000;
    final static int INIT_POLL_INTERVAL_MILLISECONDS = 100;
    final static int MAX_POLL_INTERVAL_MILLISECONDS = 5000;
    final static double POLL_INTERVAL_MULTIPLIER = 1.5;
    final static int MEEK_SERVER_TIMEOUT_MILLISECONDS = 20000;

    final static String HTTP_POST_CONTENT_TYPE = "application/octet-stream";
    final static String HTTP_POST_MEEK_SESSION_ID_HEADER_NAME = "X-Session-ID";
    final static String HTTP_POST_PSIPHON_SERVER_HEADER_NAME = "X-Target-Address";
    
    final private String mPsiphonServerAddress;
    final private String mFrontingDomain;
    final private String mRelayServerHost;
    final private int mRelayServerPort;
    private Thread mAcceptThread;
    private ServerSocket mServerSocket;
    private int mLocalPort = -1;
    private Set<Socket> mClients;
    
    public MeekClient(
            String psiphonServerAddress,
            String frontingDomain) {
        mPsiphonServerAddress = psiphonServerAddress;
        mFrontingDomain = frontingDomain;
        mRelayServerHost = null;
        mRelayServerPort = -1;
    }

    public MeekClient(
            String psiphonServerAddress,
            int psiphonServerPort,
            String relayServerHost,
            int relayServerPort) {
        mPsiphonServerAddress = psiphonServerAddress;
        mFrontingDomain = null;
        mRelayServerHost = relayServerHost;
        mRelayServerPort = relayServerPort;
    }

    public void start() throws IOException {
        stop();
        mServerSocket = new ServerSocket();
        // TODO: bind to loopback?
        mServerSocket.bind(null);
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
                        } catch (IOException e) {
                            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
                        }                        
                    }
                });
        mAcceptThread.start();
    }
    
    public int getLocalPort() {
        return mLocalPort;
    }
    
    public void stop() {
        if (mServerSocket != null) {
            closeHelper(mServerSocket);
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
            closeHelper(socket);
        }
        mClients.clear();
    }
    
    private void runClient(Socket socket) {
        InputStream socketInputStream = null;
        OutputStream socketOutputStream = null;
        SingleClientConnManager connManager = new SingleClientConnManager();
        try {
            socketInputStream = socket.getInputStream();
            socketOutputStream = socket.getOutputStream();
            String meekSessionID = Utils.Base64.encode(Utils.generateSecureRandomBytes(SESSION_ID_LENGTH));
            byte[] payloadBuffer = new byte[MAX_PAYLOAD_LENGTH];
            int pollInternalMilliseconds = INIT_POLL_INTERVAL_MILLISECONDS;

            HttpParams httpParams = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, MEEK_SERVER_TIMEOUT_MILLISECONDS);
            HttpConnectionParams.setSoTimeout(httpParams, MEEK_SERVER_TIMEOUT_MILLISECONDS);
            DefaultHttpClient httpClient = new DefaultHttpClient(connManager, httpParams);
            URI uri = null;
            if (mFrontingDomain != null) {
                uri = new URIBuilder().setScheme("https").setHost(mFrontingDomain).setPath("/").build();
            } else {
                uri = new URIBuilder().setScheme("http").setHost(mRelayServerHost).setPort(mRelayServerPort).setPath("/").build();                    
            }

            while (true) {
                socket.setSoTimeout(pollInternalMilliseconds);
                int payloadLength = 0;
                try {
                    payloadLength = socketInputStream.read(payloadBuffer);
                } catch (SocketTimeoutException e) {
                    // In this case, we POST with no content -- this is for polling the server
                }

                HttpPost httpPost = new HttpPost(uri);
                ByteArrayEntity entity = new ByteArrayEntity(payloadBuffer, 0, payloadLength);
                entity.setContentType(HTTP_POST_CONTENT_TYPE);
                httpPost.setEntity(entity);

                httpPost.addHeader(HTTP_POST_MEEK_SESSION_ID_HEADER_NAME, meekSessionID);
                httpPost.addHeader(HTTP_POST_PSIPHON_SERVER_HEADER_NAME, mPsiphonServerAddress);                

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
                    closeHelper(responseInputStream);
                }

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
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } catch (URISyntaxException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } catch (UnsupportedOperationException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } catch (IllegalStateException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } catch (IllegalArgumentException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } catch (NullPointerException e) {
            MyLog.e(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        } finally {
            connManager.shutdown();
            closeHelper(socketInputStream);
            closeHelper(socketOutputStream);
            closeHelper(socket);
        }
    }
    
    public static void closeHelper(Closeable closable) {
        try {
            closable.close();
        } catch (IOException e) {
            MyLog.w(R.string.meek_error, MyLog.Sensitivity.NOT_SENSITIVE, e);
        }
    }
}
