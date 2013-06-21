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


public class Polipo
{
    // Singleton pattern
    
    private static Polipo m_polipo;

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }
    
    public static synchronized Polipo getPolipo()
    {
        if (m_polipo == null)
        {
            m_polipo = new Polipo();
        }
        
        return m_polipo;
    }

    static Thread m_polipoThread;

    public static synchronized boolean isPolipoThreadRunning()
    {
        if (m_polipoThread != null)
        {
            return true;
        }
        
        return false;
    }

    public void runForever() throws InterruptedException
    {
        // Polipo is chained to our local SOCKS proxy. We
        // run Polipo once and leave it running for the
        // lifetime of the main process. It should handle
        // the underlying SOCKS parent proxy going down
        // and coming back up. Leaving Polipo running
        // forever is compatible with the Polipo code
        // which is designed to run as a separate process
        // and which does not have any cleanup/restart
        // flow.
        
        // TODO: handle failed to start; e.g., port not available
        
        if (m_polipoThread != null)
        {
            return;
        }

        int port = Utils.findAvailablePort(PsiphonData.getPsiphonData().getDefaultHttpProxyPort(), 10);
        if(port == 0)
        {
            return;
        }

        PsiphonData.getPsiphonData().setHttpProxyPort(port);

        m_polipoThread = new Thread(
            new Runnable()
            {
                public void run() 
                {
                     runPolipo(
                             PsiphonData.getPsiphonData().getShareProxies() ? 1 : 0,
                             PsiphonData.getPsiphonData().getHttpProxyPort(),
                             PsiphonData.getPsiphonData().getSocksPort());
                }
            });

        m_polipoThread.start();
    }
    
    private native int runPolipo(int bindAll, int proxyPort, int localParentProxyPort);
    
    static
    {
        System.loadLibrary("polipo");
    }
}
