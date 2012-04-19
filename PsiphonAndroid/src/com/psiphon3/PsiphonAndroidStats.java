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

import java.util.HashMap;
import java.util.Map;

import android.util.Log;

public class PsiphonAndroidStats
{
    // Singleton
    
    private static PsiphonAndroidStats m_stats;
    private HashMap<String, Integer> m_bytesSentByDomain;
    private HashMap<String, Integer> m_bytesReceivedByDomain;
        
    private PsiphonAndroidStats()
    {
        m_bytesSentByDomain = new HashMap<String, Integer>();
        m_bytesReceivedByDomain = new HashMap<String, Integer>();
    }

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }

    public static synchronized PsiphonAndroidStats getStats()
    {
        if (m_stats == null)
        {
            m_stats = new PsiphonAndroidStats();
        }
        
        return m_stats;
    }

    public synchronized void addBytesSent(String domain, int byteCount)
    {
        Integer total = m_bytesSentByDomain.get(domain);
        if (total == null)
        {
            total = 0;
        }
        total += byteCount;
        m_bytesSentByDomain.put(domain, total);
    }

    public synchronized void addBytesReceived(String domain, int byteCount)
    {
        Integer total = m_bytesReceivedByDomain.get(domain);
        if (total == null)
        {
            total = 0;
        }
        total += byteCount;
        m_bytesReceivedByDomain.put(domain, total);
    }
    
    public synchronized void dumpReport()
    {
    }
}
