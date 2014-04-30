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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
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

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import ch.ethz.ssh2.HTTPProxyException;
import ch.ethz.ssh2.crypto.Base64;
import ch.ethz.ssh2.transport.ClientServerHello;
import ch.ethz.ssh2.util.StringEncoder;

import com.psiphon3.psiphonlibrary.MeekClient.IAbortIndicator;
import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.ServerInterface.ServerEntry;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class ServerSelector implements IAbortIndicator
{
    private final int NUM_THREADS = 10;
    private final int SHUTDOWN_POLL_MILLISECONDS = 50;
    private final int RESULTS_POLL_MILLISECONDS = 100;
    private final int SHUTDOWN_TIMEOUT_MILLISECONDS = 1000;
    private final int MAX_WORK_TIME_MILLISECONDS = 20000;

    private Tun2Socks.IProtectSocket protectSocket = null;
    private ServerInterface serverInterface = null;
    private Context context = null;
    private boolean protectSocketsRequired = false;
    private Thread thread = null;
    private boolean stopFlag = false;
    private AtomicBoolean workerPrintedProxyError = new AtomicBoolean(false);

    public MeekClient firstEntryMeekClient = null;
    public boolean firstEntryUsingHTTPProxy = false;
    public Socket firstEntrySocket = null;
    public String firstEntryIpAddress = null;
    
    ServerSelector(
            Tun2Socks.IProtectSocket protectSocket,
            ServerInterface serverInterface,
            Context context)
    {
        this.protectSocket = protectSocket;
        this.serverInterface = serverInterface;
        this.context = context;
    }
    
    // MeekClient.IAbortIndicator
    @Override
    public boolean shouldAbort() {
        return this.stopFlag;
    }

    private static class MeekRelay {
        public final String mHost;
        public final int mPort;
        public final String mObfuscationKeyword;

        MeekRelay(String host, int port, String obfuscationKeyword) {
            mHost = host;
            mPort = port;
            mObfuscationKeyword = obfuscationKeyword;
        }
    }; 

    // Embedded meek relay addresses (put your real values here)
    private final List<MeekRelay> mMeekRelays =
            Arrays.asList(
                    new MeekRelay("192.168.0.1", 8080, "secret1"),
                    new MeekRelay("172.16.0.1", 8080, "secret2"),
                    new MeekRelay("10.0.0.1", 8080, "secret3")
                    );
    
    private synchronized boolean hasMeekRelays() {
        return mMeekRelays.size() > 0;
    }
    
    private synchronized MeekRelay selectRandomMeekRelay() {
        Collections.shuffle(mMeekRelays);
        return mMeekRelays.get(0);
    }
    
    class CheckServerWorker implements Runnable
    {
        MeekClient meekClient = null;
        boolean usingHTTPProxy = false;
        ServerEntry entry = null;
        boolean responded = false;
        boolean completed = false;
        long responseTime = -1;
        SocketChannel channel = null;

        CheckServerWorker(ServerEntry entry)
        {
            this.entry = entry;
        }
        
        public void run()
        {
            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(context);
            long startTime = SystemClock.elapsedRealtime();
            Selector selector = null;
            
            try
            {
                this.channel = SocketChannel.open();
                
                if (protectSocketsRequired)
                {
                    // We may need to except this connection from the VpnService tun interface
                    protectSocket.doVpnProtect(this.channel.socket());
                }
                
                this.channel.configureBlocking(false);
                selector = Selector.open();
                
                // TODO: Add HTTP proxy support to MeekClient. Currently, since that support
                // is lacking, these cases are treated as mutually exclusive.

                // Meek cases:
                // 1. Create a new meek client with the selected meek configuration. The meek client
                //    for the selected connection will be managed by TunnelCore. All others will
                //    be shutdown by ServerSelector.
                // 2. Start the meek client, which is a localhost server listening on a OS assigned port
                // 3. The meek client is a static port forward to the selected Psiphon server, so call
                //    makeSocketChannelConnection with the meek client address in place of the Psiphon server
                // 4. The meek client accepts local connections instantly, so the meek protocol has been
                //    tweaked to immediately poll the meek server -- we wait for that round trip to
                //    complete, via awaitEstablishedFirstServerConnection, and take that to be the
                //    response time.
                //    Note that awaitEstablishedFirstServerConnection only works here because there's a new
                //    MeekClient instance per server candidate; if we started to reuse MeekClient instances
                //    then we need more sophisticated signaling.
                
                if (this.entry.meekFrontingDomain != null && this.entry.meekFrontingDomain.length() > 0)
                {
                    this.meekClient = new MeekClient(
                            ServerSelector.this.protectSocket,
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.meekServerPort),
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.getPreferredReachablityTestPort()),
                            this.entry.meekFrontingDomain);
                    this.meekClient.start();

                    makeSocketChannelConnection(selector, "127.0.0.1", this.meekClient.getLocalPort());
                    
                    this.meekClient.awaitEstablishedFirstServerConnection(ServerSelector.this);

                    this.responded = true;
                }
                else if (proxySettings != null)
                {
                    this.usingHTTPProxy = true;

                    makeSocketChannelConnection(selector, proxySettings.proxyHost, proxySettings.proxyPort);
                    this.channel.finishConnect();
                    selector.close();
                    this.channel.configureBlocking(true);
                
                    makeConnectionViaHTTPProxy(null, null);
                    this.responded = true;
                }
                // This meek code replaces the HTTP in-proxies and inherits the same "50%" invocation logic
                else if (this.entry.hasMeekServer && hasMeekRelays() && Math.random() >= 0.5)
                {
                    MyLog.g("EmbeddedMeekRelay", "forServer", this.entry.ipAddress);

                    MeekRelay meekRelay = selectRandomMeekRelay();                    
                    
                    this.meekClient = new MeekClient(
                            ServerSelector.this.protectSocket,
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.meekServerPort),
                            this.entry.ipAddress + ":" + Integer.toString(this.entry.getPreferredReachablityTestPort()),
                            meekRelay.mHost,
                            meekRelay.mPort,
                            meekRelay.mObfuscationKeyword);
                    this.meekClient.start();

                    makeSocketChannelConnection(selector, "127.0.0.1", this.meekClient.getLocalPort());
                    
                    this.meekClient.awaitEstablishedFirstServerConnection(ServerSelector.this);

                    this.responded = true;
                }
                else
                {
                    makeSocketChannelConnection(selector,
                            this.entry.ipAddress,
                            this.entry.getPreferredReachablityTestPort());
                    
                    this.responded = this.channel.finishConnect();
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
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                if (selector != null)
                {
                    try
                    {
                        selector.close();
                    }
                    catch (IOException e) {}
                }
                if (this.channel != null)
                {
                    if (!this.responded)
                    {
                        if (this.meekClient != null)
                        {
                            this.meekClient.stop();
                            this.meekClient = null;
                        }

                        try
                        {
                            this.channel.close();
                        }
                        catch (IOException e) {}
                        this.channel = null;
                    }
                    else
                    {
                        try
                        {
                            this.channel.configureBlocking(true);
                        }
                        catch (IOException e) {}
                    }
                }
            }
            
            this.responseTime = SystemClock.elapsedRealtime() - startTime;
            this.completed = true;
        }
        
        private void makeSocketChannelConnection(Selector selector, String ipAddress, int port) throws IOException
        {
            this.channel.connect(new InetSocketAddress(ipAddress, port));
            this.channel.register(selector, SelectionKey.OP_CONNECT);
            
            while (selector.select(SHUTDOWN_POLL_MILLISECONDS) == 0)
            {
                if (stopFlag)
                {
                    break;
                }
            }
        }
        
        private void makeConnectionViaHTTPProxy(String proxyUsername, String proxyPassword) throws IOException
        {
            Socket sock = this.channel.socket();
            
            // The following is mostly copied from ch.ethz.ssh2.transport.TransportManager.establishConnection()

            sock.setSoTimeout(0);
    
            /* OK, now tell the proxy where we actually want to connect to */
    
            StringBuffer sb = new StringBuffer();
    
            sb.append("CONNECT ");
            sb.append(this.entry.ipAddress);
            sb.append(':');
            sb.append(this.entry.getPreferredReachablityTestPort());
            sb.append(" HTTP/1.0\r\n");
            
            if ((proxyUsername != null) && (proxyPassword != null))
            {
                String credentials = proxyUsername + ":" + proxyPassword;
                char[] encoded = Base64.encode(StringEncoder.GetBytes(credentials));
                sb.append("Proxy-Authorization: Basic ");
                sb.append(encoded);
                sb.append("\r\n");
            }

            sb.append("\r\n");
    
            OutputStream out = sock.getOutputStream();
    
            out.write(StringEncoder.GetBytes(sb.toString()));
            out.flush();
    
            /* Now parse the HTTP response */
    
            byte[] buffer = new byte[1024];
            InputStream in = sock.getInputStream();
    
            int len = ClientServerHello.readLineRN(in, buffer);
    
            String httpResponse = StringEncoder.GetString(buffer, 0, len);
    
            if (httpResponse.startsWith("HTTP/") == false)
            {
                throw new IOException("The proxy did not send back a valid HTTP response.");
            }
    
            /* "HTTP/1.X XYZ X" => 14 characters minimum */
    
            if ((httpResponse.length() < 14) || (httpResponse.charAt(8) != ' ') || (httpResponse.charAt(12) != ' '))
            {
                throw new IOException("The proxy did not send back a valid HTTP response.");
            }
    
            int errorCode = 0;
    
            try
            {
                errorCode = Integer.parseInt(httpResponse.substring(9, 12));
            }
            catch (NumberFormatException ignore)
            {
                throw new IOException("The proxy did not send back a valid HTTP response.");
            }
    
            if ((errorCode < 0) || (errorCode > 999))
            {
                throw new IOException("The proxy did not send back a valid HTTP response.");
            }
    
            if (errorCode != 200)
            {
                throw new HTTPProxyException(httpResponse.substring(13), errorCode);
            }
    
            /* OK, read until empty line */
    
            while (true)
            {
                len = ClientServerHello.readLineRN(in, buffer);
                if (len == 0)
                {
                    break;
                }
            }
            return;
        }
    }
    
    class Coordinator implements Runnable
    {        
        public void run()
        {
            // Run until we have results (> 0) or abort requested.
            // Each run restarts from scratch: any pending responses
            // after MAX_WORK_TIME_MILLISECONDS are aborted and a new
            // queue of candidates is assembled.
            while (!stopFlag)
            {
                MyLog.v(R.string.selecting_server, MyLog.Sensitivity.NOT_SENSITIVE);
                
                if (runOnce())
                {
                    // We have a server
                    break;
                }

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
                MyLog.i(R.string.network_proxy_connect_information, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                        proxySettings.proxyHost + ":" + proxySettings.proxyPort);
            }

            // Reset this flag before running the workers.
            workerPrintedProxyError.set(false);
            
            for (ServerEntry entry : serverEntries)
            {
                if (-1 != entry.getPreferredReachablityTestPort() &&
                        entry.hasCapabilities(PsiphonConstants.REQUIRED_CAPABILITIES_FOR_TUNNEL) &&
                        entry.inRegion(egressRegion))
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
                MyLog.g(
                    "ServerResponseCheck",
                    "ipAddress", worker.entry.ipAddress,
                    "responded", worker.responded,
                    "responseTime", worker.responseTime,
                    "regionCode", worker.entry.regionCode);
                
                MyLog.d(
                    String.format("server: %s, responded: %s, response time: %d",
                            worker.entry.ipAddress, worker.responded ? "Yes" : "No", worker.responseTime));
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
                        assert(worker.channel != null);

                        if (worker.entry.ipAddress.equals(firstEntry.ipAddress))
                        {
                            // TODO: getters with mutex?
                            firstEntryMeekClient = worker.meekClient;
                            firstEntryUsingHTTPProxy = worker.usingHTTPProxy;
                            firstEntrySocket = worker.channel.socket();
                            firstEntryIpAddress = worker.entry.ipAddress;
                        }
                        else
                        {
                            if (worker.meekClient != null)
                            {
                                worker.meekClient.stop();
                                worker.meekClient = null;
                            }
                            try
                            {
                                worker.channel.close();
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

    public void Run(boolean protectSocketsRequired)
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
