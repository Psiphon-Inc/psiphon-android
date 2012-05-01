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

import android.util.Log;

public class Polipo
{
    Thread m_polipoThread;
    boolean m_polipoListening;
    boolean m_signalStop;

    public boolean start() throws InterruptedException
    {
        stop();
        m_polipoThread = new Thread(
            new Runnable()
            {
                public void run()
                {
                    runPolipo(
                        PsiphonConstants.HTTP_PROXY_PORT,
                        PsiphonConstants.SOCKS_PORT);
                }
            });
        m_polipoThread.start();

        // Allow up to a second for Polipo to start listening
        for (int i = 0; i < 100; i += 10)
        {
            if (m_polipoListening) break;
            Thread.sleep(10);
        }
        
        if (!m_polipoListening)
        {
            stop();
        }
        
        return m_polipoListening;
    }
    
    public void stop() throws InterruptedException
    {
        if (m_polipoThread != null)
        {
            m_signalStop = true;
            m_polipoThread.join();
            // TODO: force stop() after timeout?
        }
        m_polipoThread = null;
        m_polipoListening = false;
        m_signalStop = false;
    }

    public boolean isRunning()
    {
        return m_polipoThread != null && m_polipoThread.isAlive();
    }
    
    private native int runPolipo(int proxyPort, int localParentProxyPort);
    
    static
    {
        System.loadLibrary("polipo");
    }
}
