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

public class Polipo
{
    Thread m_polipoThread;

    public void start()
    {
        stop();
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
    
    public void stop()
    {
        if (m_polipoThread != null)
        {
            // TODO: pass in a "stop" object and have polipo gracefully shutdown when the stop flag is set
            m_polipoThread.stop();
        }
        m_polipoThread = null;
    }
    
    private native void runPolipo();
    
    static
    {
        System.loadLibrary("polipo");
    }
}
