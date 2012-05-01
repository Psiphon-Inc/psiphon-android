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
    boolean m_signalStop;

    public void start() throws InterruptedException
    {
        stop();

        if (0 != initPolipo(
                    PsiphonConstants.HTTP_PROXY_PORT,
                    PsiphonConstants.SOCKS_PORT))
        {
            // TODO: throw custom exception?
            assert(false);
        }

        m_polipoThread = new Thread(
            new Runnable()
            {
                public void run()
                {
                    runPolipo();
                }
            });
        m_polipoThread.start();
    }
    
    public void stop() throws InterruptedException
    {
        if (m_polipoThread != null)
        {
            m_signalStop = true;
            Log.e("******", "set signal stop");
            m_polipoThread.join();
            Log.e("******", "joined");
            // TODO: force stop() after timeout?
        }
        m_polipoThread = null;
        m_signalStop = false;
    }

    public boolean isRunning()
    {
        return m_polipoThread != null && m_polipoThread.isAlive();
    }
    
    // NOTE port options are only set on first init() call and ignore on
    // subsequent calls; see note in native JNI wrapper.
    private native int initPolipo(int proxyPort, int localParentProxyPort);
    private native int runPolipo();
    
    static
    {
        System.loadLibrary("polipo");
    }
}
