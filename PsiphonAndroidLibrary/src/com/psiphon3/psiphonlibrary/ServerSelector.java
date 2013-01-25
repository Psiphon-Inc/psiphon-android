/*
 * Copyright (c) 2012, Psiphon Inc.
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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.ServerInterface.ServerEntry;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class ServerSelector
{
    private final int NUM_THREADS = 10;
    private final int SHUTDOWN_POLL_MILLISECONDS = 50;
    private final int RESULTS_POLL_MILLISECONDS = 100;
    private final int SHUTDOWN_TIMEOUT_MILLISECONDS = 1000;
    private final int MAX_WORK_TIME_MILLISECONDS = 20000;

    private ServerInterface serverInterface = null;
    private Context context = null;
    private Thread thread = null;
    private boolean stopFlag = false;

    public Socket firstEntrySocket = null;
    public String firstEntryIpAddress = null;
    
    ServerSelector(ServerInterface serverInterface, Context context)
    {
        this.serverInterface = serverInterface;
        this.context = context;
    }
    
    class CheckServerWorker implements Runnable
    {
        ServerEntry entry = null;
        boolean responded = false;
        long responseTime = -1;
        SocketChannel channel = null;

        CheckServerWorker(ServerEntry entry)
        {
            this.entry = entry;
        }
        
        public void run()
        {
            long startTime = SystemClock.elapsedRealtime();

            try
            {
                this.channel = SocketChannel.open();
                this.channel.configureBlocking(false);
                this.channel.connect(new InetSocketAddress(
                                        this.entry.ipAddress,
                                        this.entry.getPreferredReachablityTestPort()));
                Selector selector = Selector.open();
                this.channel.register(selector, SelectionKey.OP_CONNECT);
                
                while (selector.select(SHUTDOWN_POLL_MILLISECONDS) == 0)
                {
                    if (stopFlag)
                    {
                        break;
                    }
                }
                
                this.responded = this.channel.finishConnect();
                
                selector.close();
                this.channel.configureBlocking(true);
                
                if (!this.responded)
                {
                    this.channel.close();
                    this.channel = null;
                }
            }
            catch (IOException e)
            {
            }
            
            this.responseTime = SystemClock.elapsedRealtime() - startTime;
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
                    ServerSelector.this.serverInterface.fetchRemoteServerList();
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
            
            if (serverEntries.size() > NUM_THREADS)
            {
                Collections.shuffle(serverEntries.subList(NUM_THREADS, serverEntries.size()));
            }
            
            ExecutorService threadPool = Executors.newFixedThreadPool(NUM_THREADS);
        
            for (ServerEntry entry : serverEntries)
            {
                if (-1 != entry.getPreferredReachablityTestPort() &&
                        entry.hasCapabilities(PsiphonConstants.REQUIRED_CAPABILITIES_FOR_TUNNEL))
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
                        for (CheckServerWorker worker : workers)
                        {
                            resultCount += worker.responded ? 1 : 0;
                        }
                        if (resultCount > 0)
                        {
                            // Use the results we have so far
                            stopFlag = true;
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
                Map<String, Object> diagnosticData = new HashMap<String, Object>();
                diagnosticData.put("ipAddress", worker.entry.ipAddress);
                diagnosticData.put("responded", worker.responded);
                diagnosticData.put("responseTime", worker.responseTime);
                MyLog.g("ServerResponseCheck", diagnosticData);
                
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
                            firstEntrySocket = worker.channel.socket();
                            firstEntryIpAddress = worker.entry.ipAddress;
                        }
                        else
                        {
                            try
                            {
                                worker.channel.close();
                            }
                            catch (IOException e)
                            {
                            }
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

    public void Run()
    {
        Abort();
        
        // Android 2.2 bug workaround
        // See http://code.google.com/p/android/issues/detail?id=9431
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.FROYO &&
            !CheckIPv6Support())
        {
            System.setProperty("java.net.preferIPv6Addresses", "false");
        }

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
