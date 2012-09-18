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

import com.psiphon3.ServerInterface.ServerEntry;
import com.psiphon3.TunnelService.Signal;
import com.psiphon3.Utils.MyLog;

public class ServerListReorder
{
    static final int MAX_WORKER_THREADS = 30;
    static final int MAX_CHECK_TIME_MILLISECONDS = 5000;
    static final int RESPONSE_TIME_THRESHOLD_FACTOR = 2;
    
    private ServerInterface serverInterface;
    private Thread thread;
    
    //static const TCHAR* HTTP_CHECK_REQUEST_PATH = _T("/check");
    
    public ServerListReorder(ServerInterface serverInterface)
    {
        this.serverInterface = serverInterface;
    }

    class CheckServerWorker implements Runnable
    {
        ServerEntry entry = null;
        boolean responded = false;
        int responseTime = -1;
        //StopInfo m_stopInfo;

        CheckServerWorker(ServerEntry entry)
        {
            this.entry = entry;
        }
        
        public void run()
        {
            /*
            DWORD start_time = GetTickCount();
            
            
            //
            // TODO: replace HTTPS with SSH+?
            //
        
            tstring requestPath =
                tstring(HTTP_CHECK_REQUEST_PATH) + 
                _T("?server_secret=") + NarrowToTString(data->m_entry.webServerSecret);
        
            HTTPSRequest httpsRequest(true); // silentMode: don't print errors
            string response;
            bool requestSuccess = 
                httpsRequest.MakeRequest(
                    NarrowToTString(data->m_entry.serverAddress).c_str(),
                    data->m_entry.webServerPort,
                    data->m_entry.webServerCertificate,
                    requestPath.c_str(),
                    response,
                    data->m_stopInfo, 
                    false); // don't use local proxy
        
            DWORD end_time = GetTickCount(); // GetTickCount can wrap
        
            data->m_responseTime = (end_time >= start_time) ?
                                   (end_time - start_time) :
                                   (0xFFFFFFFF - start_time + end_time);
        
            data->m_responded = requestSuccess;            
            */
        }
    }
    
    class ReorderServerList implements Runnable
    {
        
        public void run()
        {
            /*
            ServerEntries serverEntries = serverList.GetList();
        
            // Check response time from each server (in parallel).
            // At most the first MAX_WORKER_THREADS servers in the
            // current server list will be checked. We select the
            // first MAX/2 server from the top of the list (they
            // may be better/fresher) and then MAX/2 random servers
            // from the rest of the list (they may be underused).
        
            // TODO: use a thread pool?
        
            vector<HANDLE> threadHandles;
            vector<WorkerThreadData*> threadData;
        
            if (serverEntries.size() > MAX_WORKER_THREADS)
            {
                random_shuffle(serverEntries.begin() + MAX_WORKER_THREADS/2, serverEntries.end());
            }
        
            for (ServerEntryIterator entry = serverEntries.begin(); entry != serverEntries.end(); ++entry)
            {
                WorkerThreadData* data = new WorkerThreadData(*entry, stopInfo);
        
                HANDLE threadHandle;
                if (!(threadHandle = CreateThread(0, 0, CheckServerThread, (void*)data, 0, 0)))
                {
                    continue;
                }
        
                threadHandles.push_back(threadHandle);
                threadData.push_back(data);
        
                if (threadHandles.size() >= MAX_WORKER_THREADS)
                {
                    break;
                }
            }
        
            // Wait for all threads to finish
        
            // TODO: stop waiting early if all threads finish?
        
            for (int waits = 0; waits < MAX_CHECK_TIME_MILLISECONDS/100; waits++)
            {
                Sleep(100);
        
                if (stopInfo.stopSignal->CheckSignal(stopInfo.stopReasons))
                {
                    // Stop waiting early if exiting the app, etc.
                    // NOTE: we still process results in this case
                    break;
                }
            }
            stopInfo.stopSignal->SignalStop(stopInfo.stopReasons);
        
            for (vector<HANDLE>::iterator handle = threadHandles.begin(); handle != threadHandles.end(); ++handle)
            {
                WaitForSingleObject(*handle, INFINITE);
                CloseHandle(*handle);
            }
        
            // Build a list of all servers that responded within the threshold
            // time (+100%) of the best server. Using the best server as a base
            // is intended to factor out local network conditions, local cpu
            // conditions (e.g., SSL overhead) etc. We randomly shuffle the
            // rsulting list for some client-side load balancing. Any server
            // that meets the threshold is considered equally qualified for
            // any position towards the top of the list.
        
            unsigned int fastestResponseTime = UINT_MAX;
        
            for (vector<WorkerThreadData*>::iterator data = threadData.begin(); data != threadData.end(); ++data)
            {
                my_print(
                    true,
                    _T("server: %s, responded: %s, response time: %d"),
                    NarrowToTString((*data)->m_entry.serverAddress).c_str(),
                    (*data)->m_responded ? L"yes" : L"no",
                    (*data)->m_responseTime);
        
                if ((*data)->m_responded && (*data)->m_responseTime < fastestResponseTime)
                {
                    fastestResponseTime = (*data)->m_responseTime;
                }
            }
        
            ServerEntries respondingServers;
        
            for (vector<WorkerThreadData*>::iterator data = threadData.begin(); data != threadData.end(); ++data)
            {
                if ((*data)->m_responded && (*data)->m_responseTime <=
                        fastestResponseTime*RESPONSE_TIME_THRESHOLD_FACTOR)
                {
                    respondingServers.push_back((*data)->m_entry);
                }
            }
        
            random_shuffle(respondingServers.begin(), respondingServers.end());
        
            // Merge back into server entry list. MoveEntriesToFront will move
            // these servers to the top of the list in the order submitted. Any
            // other servers, including non-responders and new servers discovered
            // while this process ran will remain in position after the move-to-front
            // list. By using the ConnectionManager's ServerList object we ensure
            // there's no conflict while reading/writing the persistent server list.
        
            if (respondingServers.size() > 0)
            {
                serverList.MoveEntriesToFront(respondingServers);
        
                my_print(false, _T("Preferred servers: %d"), respondingServers.size());
            }
        
            // Cleanup
        
            for (vector<WorkerThreadData*>::iterator data = threadData.begin(); data != threadData.end(); ++data)
            {
                delete *data;
            }
            */
        }
    }

    public void Start()
    {
        this.thread = new Thread(new ReorderServerList());
        this.thread.start();
    }
    
    public void Stop()
    {
        if (this.thread != null)
        {
            try
            {
                // signal                
                this.thread.join();
            }
            catch (InterruptedException e) {}
        }
        
        this.thread = null;
    }
}
