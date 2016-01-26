/*
 * Copyright (c) 2013, Psiphon Inc.
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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthProtocolState;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthStateHC4;
import org.apache.http.auth.Credentials;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.protocol.RequestClientConnControl;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.RouteInfo.LayerType;
import org.apache.http.conn.routing.RouteInfo.TunnelType;
import org.apache.http.entity.BufferedHttpEntityHC4;
import org.apache.http.impl.DefaultConnectionReuseStrategyHC4;
import org.apache.http.impl.auth.BasicSchemeFactoryHC4;
import org.apache.http.impl.auth.DigestSchemeFactoryHC4;
import org.apache.http.impl.auth.HttpAuthenticator;
import org.apache.http.impl.auth.NTLMSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProviderHC4;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.execchain.TunnelRefusedException;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.BasicHttpContextHC4;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.RequestTargetHostHC4;
import org.apache.http.protocol.RequestUserAgentHC4;
import org.apache.http.util.EntityUtilsHC4;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Pair;
import ch.ethz.ssh2.Connection;

import com.mifmif.common.regex.Generex;
import com.psiphon3.psiphonlibrary.MeekClient.IAbortIndicator;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.ServerInterface.ServerEntry;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class ServerSelector implements IAbortIndicator
{
    // TargetProtocolState tracks which set of protocols are currently
    // targeted for the current round of connection attempts. This
    // class also defines the sequence of targets, and the priority
    // order of protocols when a server supports multiple protocols.
    public static class TargetProtocolState
    {
        private int mCurrentTarget = 0;

        @SuppressWarnings("unchecked")
        private List<List<String>> mTargets =
                Arrays.asList(
                        Arrays.asList(PsiphonConstants.RELAY_PROTOCOL_OSSH,
                                      PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_OSSH,
                                      PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_HTTPS_OSSH,
                                      PsiphonConstants.RELAY_PROTOCOL_FRONTED_MEEK_OSSH),

                        Arrays.asList(PsiphonConstants.RELAY_PROTOCOL_OSSH),

                        Arrays.asList(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_OSSH),

                        Arrays.asList(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_HTTPS_OSSH),

                        Arrays.asList(PsiphonConstants.RELAY_PROTOCOL_FRONTED_MEEK_OSSH)
                        );
        
        public synchronized void rotateTarget()
        {
            MyLog.w(R.string.rotating_target_protocol_state, MyLog.Sensitivity.NOT_SENSITIVE);
            mCurrentTarget = (mCurrentTarget + 1) % mTargets.size();
        }
        
        public synchronized String selectProtocol(ServerEntry serverEntry)
        {
            for (String protocol : mTargets.get(mCurrentTarget))
            {
                if (serverEntry.supportsProtocol(protocol))
                {
                    return protocol;
                }
            }
            return null;
        }
        
        public synchronized String currentProtocols()
        {
            StringBuilder currentProtocolsBuilder = new StringBuilder();
            for (String protocol : mTargets.get(mCurrentTarget))
            {
                currentProtocolsBuilder.append(protocol);
                currentProtocolsBuilder.append(" ");
            }
            return currentProtocolsBuilder.toString().trim();
        }
    }
    
    private final int NUM_THREADS = 10;

    private final int SHUTDOWN_POLL_MILLISECONDS = 50;
    private final int RESULTS_POLL_MILLISECONDS = 100;
    private final int SHUTDOWN_TIMEOUT_MILLISECONDS = 1000;
    private final int MAX_WORK_TIME_MILLISECONDS = 20000;

    private TargetProtocolState targetProtocolState = null;
    private Connection.IStopSignalPending stopSignalPending = null;
    private Tun2Socks.IProtectSocket protectSocket = null;
    private ServerInterface serverInterface = null;
    private Context context = null;
    private boolean protectSocketsRequired = false;
    private String clientSessionId = null;
    private List<Pair<String,String>> extraAuthParams = null;
    private Thread thread = null;
    private boolean stopFlag = false;
    private final AtomicBoolean workerPrintedProxyError = new AtomicBoolean(false);

    public MeekClient firstEntryMeekClient = null;
    public boolean firstEntryUsingHTTPProxy = false;
    public Socket firstEntrySocket = null;
    public Connection firstEntrySshConnection = null;
    public String firstEntryIpAddress = null;

    ServerSelector(
            TargetProtocolState targetProtocolState,
            Connection.IStopSignalPending stopSignalPending,
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            Context context)
    {
        this.targetProtocolState = targetProtocolState;
        this.stopSignalPending = stopSignalPending;
        this.protectSocket = protectSocket;
        this.serverInterface = serverInterface;
        this.context = context;
    }

    // MeekClient.IAbortIndicator
    @Override
    public boolean shouldAbort() {
        return this.stopFlag;
    }

    class CheckServerWorker implements Runnable
    {
        MeekClient meekClient = null;
        boolean usingHTTPProxy = false;
        ServerEntry entry = null;
        boolean responded = false;
        boolean completed = false;
        long responseTime = -1;
        String sshErrorMessage = "";
        Socket socket = null;
        Connection sshConnection = null;

        CheckServerWorker(ServerEntry entry)
        {
            this.entry = entry;
        }

        @Override
        public void run()
        {
            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(context);
            long startTime = SystemClock.elapsedRealtime();

            try
            {
                String protocol = ServerSelector.this.targetProtocolState.selectProtocol(this.entry);
                
                // This check is already performed in the coordinator which filters out workers for
                // server entries that don't support the target protocol, but we're leaving this here
                // anyways.
                if (protocol == null)
                {
                    // The server does not support a target protocol
                    return;
                }

                // Even though the ServerEntry here is a clone, assigning to it
                // works because ServerSelector merges it back in for the servers
                // that respond.
                
                this.entry.connType = protocol;

                if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_OSSH))
                {
                    if (proxySettings != null) {
                        this.usingHTTPProxy = true;

                        HttpHost proxyHttpHost = new HttpHost(proxySettings.proxyHost,
                                proxySettings.proxyPort);
                        
                        HttpHost targetHttpHost = new HttpHost(this.entry.ipAddress,
                                this.entry.getPreferredReachablityTestPort());
                        
						this.socket = httpTunnelSocket(protectSocketsRequired,
								MAX_WORK_TIME_MILLISECONDS, proxyHttpHost, targetHttpHost, 
								PsiphonData.getPsiphonData().getProxyCredentials());

                    }
                    else
                    {
                        this.socket = connectSocket(protectSocketsRequired,
                                MAX_WORK_TIME_MILLISECONDS, this.entry.ipAddress,
                                this.entry.getPreferredReachablityTestPort());

                    }
                }

                // Meek cases:
                // 1. Create a new meek client with the selected meek configuration. The meek client
                //    for the selected connection will be managed by TunnelCore. All others will
                //    be shutdown by ServerSelector.
                // 2. Start the meek client, which is a localhost server listening on a OS assigned port
                // 3. The meek client is a static port forward to the selected Psiphon server, so call
                //    connectSocket with the meek client address in place of the Psiphon server

                else if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_OSSH) ||
                        protocol.equals(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_HTTPS_OSSH))
                {

                    this.meekClient = new MeekClient(
                    		
                            protectSocketsRequired ? ServerSelector.this.protectSocket : null,
                            ServerSelector.this.serverInterface,
                            ServerSelector.this.clientSessionId,
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.getPreferredReachablityTestPort()),
                            this.entry.meekCookieEncryptionPublicKey,
                            this.entry.meekObfuscatedKey,
                            this.entry.ipAddress,
                            this.entry.meekServerPort,
                            context);
                    this.meekClient.start();

                    // NOTE: don't call doVpnProtect when using meekClient -- that breaks the localhost connection
                    this.socket = connectSocket(false, MAX_WORK_TIME_MILLISECONDS, "127.0.0.1", this.meekClient.getLocalPort());
                }

                else if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_FRONTED_MEEK_OSSH))
                {
                    Collections.shuffle(this.entry.meekFrontingAddresses);
                    // meekFrontingAddresses is now always populated with at least meekFrontingDomain
                    String frontingAddress = this.entry.meekFrontingAddresses.get(0);
                    
                    // IMPORTANT: This is done at server selection time, not when the (potentially large) embedded
                    // server list is read and decoded (in the ServerInterface constructor), because that can be CPU
                    // intensive and can hang the app.
                    if (this.entry.meekFrontingAddressesRegex.length() > 0)
                    {
                        frontingAddress = new Generex(this.entry.meekFrontingAddressesRegex).random();
                    }
                    
                    this.meekClient = new MeekClient(
                            protectSocketsRequired ? ServerSelector.this.protectSocket : null,
                            ServerSelector.this.serverInterface,
                            ServerSelector.this.clientSessionId,
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.getPreferredReachablityTestPort()),
                            this.entry.meekCookieEncryptionPublicKey,
                            this.entry.meekObfuscatedKey,
                            frontingAddress,
                            this.entry.meekFrontingHost,
                            context);
                    this.meekClient.start();

                    // NOTE: don't call doVpnProtect when using meekClient -- that breaks the localhost connection
                    this.socket = connectSocket(false, MAX_WORK_TIME_MILLISECONDS, "127.0.0.1", this.meekClient.getLocalPort());

                    this.entry.front = frontingAddress;
                }

                if (this.socket != null)
                {
                    try
                    {
                        this.sshConnection = TunnelCore.establishSshConnection(
                                                    ServerSelector.this.stopSignalPending,
                                                    this.socket,
                                                    entry,
                                                    ServerSelector.this.clientSessionId,
                                                    ServerSelector.this.extraAuthParams);
                    }
                    catch (IOException e)
                    {
                        this.sshErrorMessage = e.getMessage();
                    }
                        
                    this.responded = (this.sshConnection != null);
                }
            }
            catch (ClosedByInterruptException e) {}
            catch (InterruptedIOException e) {}
            catch (IllegalArgumentException e)
            {
                // Avoid printing the same message multiple times in the case of a network proxy error
                if (proxySettings != null &&
                        workerPrintedProxyError.compareAndSet(false, true))
                {
                    MyLog.e(R.string.network_proxy_connect_exception, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, e.getLocalizedMessage());
                }
            }
            catch (ConnectException e)
            {
                // Avoid printing the same message multiple times in the case of a network proxy error
                if (proxySettings != null &&
                        workerPrintedProxyError.compareAndSet(false, true))
                {
                    MyLog.e(R.string.network_proxy_connect_exception, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, e.getLocalizedMessage());
                }
            }
            catch (SocketException e)
            {
                // Avoid printing the same message multiple times in the case of a network proxy error
                if (proxySettings != null &&
                        workerPrintedProxyError.compareAndSet(false, true))
                {
                    MyLog.e(R.string.network_proxy_connect_exception, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, e.getLocalizedMessage());
                }
            }
            catch (IOException e)
            {
                if (proxySettings != null)
                {
                    MyLog.w(R.string.network_proxy_connect_exception, MyLog.Sensitivity.NOT_SENSITIVE, e.getLocalizedMessage());
                }
            } 
            catch (HttpException e) 
            {
                if (proxySettings != null) 
                {
                    MyLog.w(R.string.network_proxy_connect_exception, MyLog.Sensitivity.NOT_SENSITIVE, e.getLocalizedMessage());
                }
            }
            finally
            {
                if (!this.responded)
                {
                    if (this.sshConnection != null)
                    {
                        this.sshConnection.close();
                        this.sshConnection = null;
                    }

                    if (this.meekClient != null)
                    {
                        this.meekClient.stop();
                        this.meekClient = null;
                    }

                    if (this.socket != null)
                    {
                        try
                        {
                            this.socket.close();
                        }
                        catch (IOException e) {}
                        this.socket = null;
                    }
                }
            }

            this.responseTime = SystemClock.elapsedRealtime() - startTime;
            this.completed = true;
        }

        private Socket connectSocket(boolean protectSocketsRequired, long timeout, String host, int port)
        	    throws IOException
        	    {
        	        SocketChannel channel = SocketChannel.open();

        	        if (protectSocketsRequired)
        	        {
        	            // We may need to except this connection from the VpnService tun interface
        	            protectSocket.doVpnProtect(channel.socket());
        	        }

        	        channel.configureBlocking(false);
        	        channel.connect(new InetSocketAddress(host, port));
        	        Selector selector = Selector.open();
        	        channel.register(selector, SelectionKey.OP_CONNECT);

        	        long startTime = SystemClock.elapsedRealtime();

        	        boolean success = true;
        	        while (selector.select(SHUTDOWN_POLL_MILLISECONDS) == 0)
        	        {
        	            if (startTime + timeout <= SystemClock.elapsedRealtime() || stopFlag)
        	            {
        	                success = false;
        	                break;
        	            }
        	        }

        	        if (success)
        	        {
        	            success = channel.finishConnect();
        	        }

        	        selector.close();

        	        if (success)
        	        {
        	            channel.configureBlocking(true);
        	            return channel.socket();
        	        }
        	        else
        	        {
        	            channel.close();
        	            return null;
        	        }
        	    }

        private Socket httpTunnelSocket(boolean protectSocketsRequired, long timeout, HttpHost proxy, HttpHost target,
                Credentials credentials) throws IOException, HttpException {
            HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory = ManagedHttpClientConnectionFactory.INSTANCE;
            ConnectionConfig connectionConfig = ConnectionConfig.DEFAULT;
            RequestConfig requestConfig = RequestConfig.DEFAULT;
            HttpProcessor httpProcessor = new ImmutableHttpProcessor(
                    new RequestTargetHostHC4(), new RequestClientConnControl(),
                    new RequestUserAgentHC4());
            HttpRequestExecutor requestExec = new HttpRequestExecutor();
            ProxyAuthenticationStrategy proxyAuthStrategy = ProxyAuthenticationStrategy.INSTANCE;
            HttpAuthenticator authenticator = new HttpAuthenticator();
            AuthStateHC4 proxyAuthState = new AuthStateHC4();
            ConnectionReuseStrategy reuseStrategy = DefaultConnectionReuseStrategyHC4.INSTANCE;

            Lookup<AuthSchemeProvider> authSchemeRegistry = RegistryBuilder
                    .<AuthSchemeProvider> create()
                    .register(AuthSchemes.BASIC, new BasicSchemeFactoryHC4())
                    .register(AuthSchemes.DIGEST, new DigestSchemeFactoryHC4())
                    .register(AuthSchemes.NTLM, new NTLMSchemeFactory())
                    .build();
            HttpHost host = target;
            if (host.getPort() <= 0) {
                host = new HttpHost(host.getHostName(), 80, host.getSchemeName());
            }
            final HttpRoute route = new HttpRoute(
                    host,
                    requestConfig.getLocalAddress(),
                    proxy, false, TunnelType.TUNNELLED, LayerType.PLAIN);

            final ManagedHttpClientConnection conn = connFactory.create(
                    route, connectionConfig);
            final HttpContext context = new BasicHttpContextHC4();
            HttpResponse response;

            final HttpRequest connect = new BasicHttpRequest(
                    "CONNECT", host.toHostString(), HttpVersion.HTTP_1_1);

            // Populate the execution context
            context.setAttribute(HttpCoreContext.HTTP_TARGET_HOST, target);
            context.setAttribute(HttpCoreContext.HTTP_CONNECTION, conn);
            context.setAttribute(HttpCoreContext.HTTP_REQUEST, connect);
            context.setAttribute(HttpClientContext.HTTP_ROUTE, route);
            context.setAttribute(HttpClientContext.PROXY_AUTH_STATE, proxyAuthState);
            context.setAttribute(HttpClientContext.AUTHSCHEME_REGISTRY, authSchemeRegistry);
            context.setAttribute(HttpClientContext.REQUEST_CONFIG, requestConfig);

            if (credentials != null) {
                final BasicCredentialsProviderHC4 credsProvider = new BasicCredentialsProviderHC4();
                credsProvider.setCredentials(AuthScope.ANY, credentials);
                context.setAttribute(HttpClientContext.CREDS_PROVIDER, credsProvider);
            }

            requestExec.preProcess(connect, httpProcessor, context);

            for (;;) {
                if (!conn.isOpen()) {
                    Socket socket = connectSocket(protectSocketsRequired,
                            MAX_WORK_TIME_MILLISECONDS, proxy.getHostName(),
                            proxy.getPort());
                    if (socket != null) {
                        conn.bind(socket);
                    }
                }

                authenticator.generateAuthResponse(connect, proxyAuthState, context);

                response = requestExec.execute(connect, conn, context);

                final int status = response.getStatusLine().getStatusCode();
                if (status < 200) {
                    throw new HttpException("Unexpected response to CONNECT request: " +
                            response.getStatusLine());
                }
                if (authenticator.isAuthenticationRequested(proxy, response,
                        proxyAuthStrategy, proxyAuthState, context)) {
                    if (authenticator.handleAuthChallenge(proxy, response,
                            proxyAuthStrategy, proxyAuthState, context)) {
                        // Retry request
                        if (reuseStrategy.keepAlive(response, context)) {
                            // Consume response content
                            final HttpEntity entity = response.getEntity();
                            EntityUtilsHC4.consume(entity);
                        } else {
                            conn.close();
                        }
                        // discard previous auth header
                        connect.removeHeaders(AUTH.PROXY_AUTH_RESP);
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }

            final int status = response.getStatusLine().getStatusCode();

            if (status > 299) {

                // Buffer response content
                final HttpEntity entity = response.getEntity();
                if (entity != null) {
                    response.setEntity(new BufferedHttpEntityHC4(entity));
                }

                conn.close();
                throw new TunnelRefusedException("CONNECT refused by proxy: " +
                        response.getStatusLine(), response);
            }

            AuthStateHC4 authState = (AuthStateHC4) context.getAttribute(HttpClientContext.PROXY_AUTH_STATE);
            AuthScheme authScheme = authState.getAuthScheme();
            if (authState.getState() == AuthProtocolState.SUCCESS && authScheme != null) {
                MyLog.g("ProxyAuthentication", "Scheme", authScheme.getSchemeName());
            }

            return conn.getSocket();
        }
    }

    class Coordinator implements Runnable
    {
        @Override
        public void run()
        {
            // Run until we have results (> 0) or abort requested.
            // Each run restarts from scratch: any pending responses
            // after MAX_WORK_TIME_MILLISECONDS are aborted and a new
            // queue of candidates is assembled.
            while (!stopFlag)
            {
                MyLog.v(R.string.selecting_server, MyLog.Sensitivity.NOT_SENSITIVE);
                MyLog.g("TargetProtocols", "protocols", ServerSelector.this.targetProtocolState.currentProtocols());

                if (runOnce())
                {
                    // We have a server
                    break;
                }
                
                // After failing to establish a connection, rotate to the next
                // set of target protocols.
                ServerSelector.this.targetProtocolState.rotateTarget();

                // After failing to establish a TCP connection, perform the same
                // steps as we do when an SSH connection fails:
                // throttle a bit, and fetch remote servers (if not fetched recently).
                try
                {
                    ServerSelector.this.serverInterface.fetchRemoteServerList(
                            protectSocketsRequired ? protectSocket : null);
                }
                catch (PsiphonServerInterfaceException requestException)
                {
                    MyLog.w(R.string.TunnelService_FetchRemoteServerListFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);
                }

                // 1-2 second delay before retrying
                // (same as Windows client, see comment in ConnectionManager.cpp)
                try
                {
                    Thread.sleep(1000 + (long)(Math.random()*1000.0));
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private boolean runOnce()
        {
            boolean printedWaitingMessage = false;
            while (!Utils.hasNetworkConnectivity(context))
            {
                if (!printedWaitingMessage)
                {
                    MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);
                    printedWaitingMessage = true;
                }

                if (stopFlag)
                {
                    return false;
                }
                try
                {
                    // Sleep 1 second before checking again
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    return false;
                }
            }

            // Update resolvers to match underlying network interface
            Utils.updateDnsResolvers(context);

            // Adapted from Psiphon Windows client module server_list_reordering.cpp; see comments there.
            // Revision: https://bitbucket.org/psiphon/psiphon-circumvention-system/src/881d32d09e3a/Client/psiclient/server_list_reordering.cpp

            ArrayList<ServerEntry> serverEntries = serverInterface.getServerEntries();
            ArrayList<CheckServerWorker> workers = new ArrayList<CheckServerWorker>();

            // Remember the original first entry
            ServerEntry originalFirstEntry = null;
            if (serverEntries.size() > 0)
            {
                originalFirstEntry = serverEntries.get(0);
            }

            // Unlike the Windows implementation, we're using a proper thread pool.
            // We still prioritize the first few servers (first enqueued into the
            // work queue) along with a randomly prioritized list of servers
            // from deeper in the list. Assumes the default Executors.newFixedThreadPool
            // priority is FIFO.
            // NEW: Don't prioritize the first few servers any more, to give equal waiting
            // to older servers and to newer servers.

            if (serverEntries.size() > NUM_THREADS)
            {
                Collections.shuffle(serverEntries.subList(1, serverEntries.size()));
            }

            ExecutorService threadPool = Executors.newFixedThreadPool(NUM_THREADS);

            String egressRegion = PsiphonData.getPsiphonData().getEgressRegion();

            MyLog.g("SelectedRegion", "regionCode", egressRegion);

            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(context);
            MyLog.g("ProxyChaining", "enabled",
                    proxySettings == null ? "False" : "True");
            // Note that workers will still call getSystemProxySettings().  This is in case the
            // system proxy settings actually do change while the pool is running, and the log
            // above will not reflect that change.

            if (proxySettings != null)
            {
            	
                Credentials proxyCredentials = PsiphonData.getPsiphonData().getProxyCredentials();
                MyLog.g("ProxyCredentials", "present",
                		proxyCredentials == null ? "False" : "True");
                MyLog.i(R.string.network_proxy_connect_information, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                        proxySettings.proxyHost + ":" + proxySettings.proxyPort);
            }

            // Reset this flag before running the workers.
            workerPrintedProxyError.set(false);

            for (ServerEntry entry : serverEntries)
            {
                if (-1 != entry.getPreferredReachablityTestPort() &&
                        entry.hasOneOfTheseCapabilities(PsiphonConstants.SUFFICIENT_CAPABILITIES_FOR_TUNNEL) &&
                        entry.inRegion(egressRegion) &&
                        null != ServerSelector.this.targetProtocolState.selectProtocol(entry))
                {
                    CheckServerWorker worker = new CheckServerWorker(entry);
                    threadPool.submit(worker);
                    workers.add(worker);
                }
            }

            try
            {
                // Wait for either all tasks to complete, an abort request, or the
                // maximum work time.

                // ...now, we also stop when we get some results. We check for
                // results in 100ms. time periods, which based on observed real
                // world data will contain clusters of multiple results (good for load
                // balancing). This early exit allows us to wait for some results
                // before starting the tunnel for the first time.

                for (int wait = 0;
                     !threadPool.awaitTermination(0, TimeUnit.MILLISECONDS) &&
                     !stopFlag &&
                     wait <= MAX_WORK_TIME_MILLISECONDS;
                     wait += SHUTDOWN_POLL_MILLISECONDS)
                {
                    // Periodic 100ms. (RESULTS_POLL_MILLISECONDS) has-results check
                    // Note: assumes RESULTS_POLL_MILLISECONDS is a multiple of SHUTDOWN_POLL_MILLISECONDS
                    if (wait > 0 && (wait % RESULTS_POLL_MILLISECONDS) == 0)
                    {
                        int resultCount = 0;
                        boolean workQueueIsFinished = true;
                        for (CheckServerWorker worker : workers)
                        {
                            resultCount += worker.responded ? 1 : 0;
                            if (!worker.completed)
                            {
                                workQueueIsFinished = false;
                            }
                        }
                        if (resultCount > 0)
                        {
                            // Use the results we have so far
                            stopFlag = true;
                            break;
                        }
                        if (workQueueIsFinished)
                        {
                            break;
                        }
                    }

                    Thread.sleep(SHUTDOWN_POLL_MILLISECONDS);
                }

                threadPool.shutdownNow();
                threadPool.awaitTermination(SHUTDOWN_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            for (CheckServerWorker worker : workers)
            {
                // Only log info for candidates for which a connection was attempted (not all servers in queue)
                if (worker.completed)
                {
                    MyLog.g(
                        "ServerResponseCheck",
                        "ipAddress", worker.entry.ipAddress,
                        "connType", worker.entry.connType,
                        "front", worker.entry.front,
                        "responded", worker.responded,
                        "responseTime", worker.responseTime,
                        "sshErrorMessage", worker.sshErrorMessage,
                        "regionCode", worker.entry.regionCode);
    
                    MyLog.d(
                        String.format("server: %s, responded: %s, response time: %d",
                                worker.entry.ipAddress, worker.responded ? "Yes" : "No", worker.responseTime));
                }
            }

            // Build a list of all servers that responded. We randomly shuffle the
            // resulting list for some client-side load balancing. Any server
            // that responded within the last RESULTS_POLL_MILLISECONDS is considered
            // equally qualified for any position towards the top of the list.

            ArrayList<ServerEntry> respondingServers = new ArrayList<ServerEntry>();

            for (CheckServerWorker worker : workers)
            {
                // NOTE: used to filter by worker.responseTime <= fastestResponseTime*RESPONSE_TIME_THRESHOLD_FACTOR,
                // to only consider the "fast" responders for random selection. Now that we exit the process
                // early in 100ms. time period chunks, we should consider all responders to be within the "fast" threshold.
                if (worker.responded)
                {
                    respondingServers.add(worker.entry);
                }
            }

            Collections.shuffle(respondingServers);

            // If the original first entry is a faster responder, keep it as the first entry.
            // This is to increase the chance that users have a "consistent" outbound IP address,
            // while also taking performance and load balancing into consideration (this is
            // a fast responder; and it ended up as the first entry randomly).
            if (originalFirstEntry != null)
            {
                for (int i = 0; i < respondingServers.size(); i++)
                {
                    if (respondingServers.get(i).ipAddress.equals(originalFirstEntry.ipAddress))
                    {
                        if (i != 0)
                        {
                            respondingServers.add(0, respondingServers.remove(i));
                        }
                        break;
                    }
                }
            }

            // Merge back into server entry list. MoveEntriesToFront will move
            // these servers to the top of the list in the order submitted. Any
            // other servers, including non-responders and new servers discovered
            // while this process ran will remain in position after the move-to-front
            // list. By using the ConnectionManager's ServerList object we ensure
            // there's no conflict while reading/writing the persistent server list.

            if (respondingServers.size() > 0)
            {
                serverInterface.moveEntriesToFront(respondingServers);

                MyLog.v(R.string.preferred_servers, MyLog.Sensitivity.NOT_SENSITIVE, respondingServers.size());

                // Keep open (and return) the socket to the new #1 server; close the others.
                ServerEntry firstEntry = respondingServers.get(0);
                for (CheckServerWorker worker : workers)
                {
                    if (worker.responded)
                    {
                        assert(worker.socket != null);

                        if (worker.entry.ipAddress.equals(firstEntry.ipAddress))
                        {
                            MyLog.g("SelectedServer",
                                    "ipAddress", firstEntry.ipAddress,
                                    "connType", firstEntry.connType,
                                    "front", firstEntry.front);

                            // TODO: getters with mutex?
                            firstEntryMeekClient = worker.meekClient;
                            firstEntryUsingHTTPProxy = worker.usingHTTPProxy;
                            firstEntrySocket = worker.socket;
                            firstEntrySshConnection = worker.sshConnection;
                            firstEntryIpAddress = worker.entry.ipAddress;
                        }
                        else
                        {
                            if (worker.sshConnection != null)
                            {
                                worker.sshConnection.close();
                                worker.sshConnection = null;
                            }
                            if (worker.meekClient != null)
                            {
                                worker.meekClient.stop();
                                worker.meekClient = null;
                            }
                            try
                            {
                                worker.socket.close();
                            }
                            catch (IOException e) {}
                        }
                    }
                }
            }

            return (respondingServers.size() > 0);
        }
    }

    boolean CheckIPv6Support()
    {
        try
        {
            for (NetworkInterface netInt : Collections.list(NetworkInterface.getNetworkInterfaces()))
            {
                for (InetAddress address : Collections.list(netInt.getInetAddresses()))
                {
                    if (address instanceof Inet6Address)
                    {
                        return true;
                    }
                }
            }
        }
        catch (SocketException e)
        {
        }
        return false;
    }

    public void Run(
            boolean protectSocketsRequired,
            String clientSessionId,
            List<Pair<String,String>> extraAuthParams)
    {
        Abort();

        // Android 2.2 bug workaround
        // See http://code.google.com/p/android/issues/detail?id=9431
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.FROYO &&
            !CheckIPv6Support())
        {
            System.setProperty("java.net.preferIPv6Addresses", "false");
        }

        this.protectSocketsRequired = protectSocketsRequired;
        this.clientSessionId = clientSessionId;
        this.extraAuthParams = extraAuthParams;

        this.thread = new Thread(new Coordinator());
        this.thread.start();
        try
        {
            this.thread.join();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        this.thread = null;
        this.stopFlag = false;
    }

    public void Abort()
    {
        if (this.thread != null)
        {
            try
            {
                this.stopFlag = true;
                this.thread.join();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        this.thread = null;
        this.stopFlag = false;
    }
}
