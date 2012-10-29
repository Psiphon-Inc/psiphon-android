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

package com.psiphon3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import android.os.SystemClock;

import com.psiphon3.ServerInterface.ServerEntry;
import com.psiphon3.TunnelService.Signal;
import com.psiphon3.Utils.MyLog;

public class ServerListReorder
{
    private final int NUM_THREADS = 10;
    private final int SHUTDOWN_POLL_MILLISECONDS = 100;
    private final int SHUTDOWN_TIMEOUT_MILLISECONDS = 1000;
    private final int MAX_WORK_TIME_MILLISECONDS = 5000;
    private final int RESPONSE_TIME_THRESHOLD_FACTOR = 2;

    private ServerInterface serverInterface = null;
    private Thread thread = null;
    boolean stopFlag = false;
    
    ServerListReorder(ServerInterface serverInterface)
    {
        this.serverInterface = serverInterface;
    }
    
    class CheckServerWorker implements Runnable
    {
        ServerEntry entry = null;
        boolean responded = false;
        long responseTime = -1;

        CheckServerWorker(ServerEntry entry)
        {
            this.entry = entry;
        }
        
        public void run()
        {
            long startTime = SystemClock.elapsedRealtime();

            try
            {
                SocketChannel channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.connect(new InetSocketAddress(
                                        this.entry.ipAddress,
                                        this.entry.getPreferredReachablityTestPort()));
                Selector selector = Selector.open();
                channel.register(selector, SelectionKey.OP_CONNECT);
                
                while (selector.select(SHUTDOWN_POLL_MILLISECONDS) == 0)
                {
                    if (stopFlag)
                    {
                        break;
                    }
                }
                
                this.responded = channel.finishConnect();
            }
            catch (IOException e)
            {
            }
            
            this.responseTime = SystemClock.elapsedRealtime() - startTime;
        }
    }
    
    class ReorderServerList implements Runnable
    {        
        public void run()
        {
            // Adapted from Psiphon Windows client module server_list_reordering.cpp; see comments there.
            // Revision: https://bitbucket.org/psiphon/psiphon-circumvention-system/src/881d32d09e3a/Client/psiclient/server_list_reordering.cpp

            ArrayList<ServerEntry> serverEntries = serverInterface.getServerEntries();
            ArrayList<CheckServerWorker> workers = new ArrayList<CheckServerWorker>();

            // Unlike the Windows implementation, we're using a proper thread pool.
            // We still prioritize the first few servers (first enqueued into the
            // work queue) along with a randomly prioritized sampling some servers
            // from deeper in the list. Assumes the default Executors.newFixedThreadPool
            // priority is FIFO.
            
            if (serverEntries.size() > NUM_THREADS)
            {
                Collections.shuffle(serverEntries.subList(NUM_THREADS, serverEntries.size()));
            }
            
            ExecutorService threadPool = Executors.newFixedThreadPool(NUM_THREADS);
        
            for (ServerEntry entry : serverEntries)
            {
                if (-1 != entry.getPreferredReachablityTestPort())
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
                
                for (int wait = 0;
                     !threadPool.awaitTermination(0, TimeUnit.MILLISECONDS) &&
                     !stopFlag &&
                     wait <= MAX_WORK_TIME_MILLISECONDS;
                     wait += SHUTDOWN_POLL_MILLISECONDS)
                {
                    Thread.sleep(SHUTDOWN_POLL_MILLISECONDS);
                }

                threadPool.shutdownNow();
                threadPool.awaitTermination(SHUTDOWN_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
            
            // Build a list of all servers that responded within the threshold
            // time (+100%) of the best server. Using the best server as a base
            // is intended to factor out local network conditions, local cpu
            // conditions (e.g., SSL overhead) etc. We randomly shuffle the
            // resulting list for some client-side load balancing. Any server
            // that meets the threshold is considered equally qualified for
            // any position towards the top of the list.
        
            long fastestResponseTime = Long.MAX_VALUE;
        
            for (CheckServerWorker worker : workers)
            {
                PsiphonData.addServerResponseCheck(worker.entry.ipAddress, worker.responded, worker.responseTime);
                MyLog.d(
                    String.format("server: %s, responded: %s, response time: %d",
                            worker.entry.ipAddress, worker.responded ? "Yes" : "No", worker.responseTime));
        
                if (worker.responded && worker.responseTime < fastestResponseTime)
                {
                    fastestResponseTime = worker.responseTime;
                }
            }
        
            ArrayList<ServerEntry> respondingServers = new ArrayList<ServerEntry>();
        
            for (CheckServerWorker worker : workers)
            {
                if (worker.responded && worker.responseTime <= fastestResponseTime*RESPONSE_TIME_THRESHOLD_FACTOR)
                {
                    respondingServers.add(worker.entry);
                }
            }
        
            Collections.shuffle(respondingServers);
        
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
            }
        }
    }

    public void Start()
    {
        Stop();
        this.thread = new Thread(new ReorderServerList());
        this.thread.start();
    }
    
    public void Stop()
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
