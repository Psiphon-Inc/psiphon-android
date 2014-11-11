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

package org.xbill.DNS;

import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.Tun2Socks;

public class PsiphonState
{
    // Singleton pattern
    
    private static PsiphonState PsiphonState;

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }
    
    public static synchronized PsiphonState getPsiphonState()
    {
        if (PsiphonState == null)
        {
            PsiphonState = new PsiphonState();
        }
        
        return PsiphonState;
    }
    
    private Tun2Socks.IProtectSocket m_protectSocket;
    private ServerInterface m_serverInterface;

    private PsiphonState()
    {        
    }
    
    public synchronized Tun2Socks.IProtectSocket getProtectSocket()
    {
        return m_protectSocket;
    }

    public synchronized ServerInterface getServerInterface()
    {
        return m_serverInterface;
    }
    
    public synchronized void setState(Tun2Socks.IProtectSocket protectSocket, ServerInterface serverInterface)
    {
        m_protectSocket = protectSocket;
        m_serverInterface = serverInterface;
    }
}
