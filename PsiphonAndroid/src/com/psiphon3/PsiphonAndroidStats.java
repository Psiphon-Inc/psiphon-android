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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PsiphonAndroidStats
{
    // Singleton
    
    private static PsiphonAndroidStats m_stats;
    private Map<String, Integer> m_bytesSentByDomain;
    private Map<String, Integer> m_bytesReceivedByDomain;
    private Map<String, Integer> m_pageViewEntries;
    private Map<String, Integer> m_httpsRequestEntries;
    private List<Utils.Pair<Pattern, String>> m_pageViewRegexes;
    private List<Utils.Pair<Pattern, String>> m_httpsRequestRegexes;
        
    private PsiphonAndroidStats()
    {
        m_bytesSentByDomain = new HashMap<String, Integer>();
        m_bytesReceivedByDomain = new HashMap<String, Integer>();
        m_pageViewEntries = new HashMap<String, Integer>();
        m_httpsRequestEntries = new HashMap<String, Integer>();
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

    public synchronized void setRegexes(
            List<Utils.Pair<Pattern, String>> pageViewRegexes,
            List<Utils.Pair<Pattern, String>> httpsRequestRegexes)
    {
        m_stats.m_pageViewRegexes = pageViewRegexes;
        m_stats.m_httpsRequestRegexes = httpsRequestRegexes;
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
    
    public synchronized void upsertPageView(String entry)
    {
        String storeEntry = "(OTHER)";
        
        for (Utils.Pair<Pattern, String> regexReplace : this.m_pageViewRegexes)
        {
            Matcher matcher = regexReplace.left.matcher(entry);
            if (matcher.find())
            {
                storeEntry = matcher.replaceFirst(regexReplace.right);
                break;
            }
        }
        
        if (storeEntry.length() == 0) return;
        
        // Add/increment the entry.
        Integer prevCount = this.m_pageViewEntries.get(storeEntry);
        if (prevCount == null) prevCount = 0;
        this.m_pageViewEntries.put(storeEntry, prevCount+1);
    }
    
    public synchronized void upsertHttpsRequest(String entry)
    {
        // TODO: This is identical code to the function above, because we don't
        // yet know what a HTTPS "entry" looks like, because we haven't implemented
        // HTTPS response parsing yet.
        
        String storeEntry = "(OTHER)";
        
        for (Utils.Pair<Pattern, String> regexReplace : this.m_httpsRequestRegexes)
        {
            Matcher matcher = regexReplace.left.matcher(entry);
            if (matcher.find())
            {
                storeEntry = matcher.replaceFirst(regexReplace.right);
                break;
            }
        }
        
        if (storeEntry.length() == 0) return;
        
        // Add/increment the entry.
        Integer prevCount = this.m_httpsRequestEntries.get(storeEntry);
        if (prevCount == null) prevCount = 0;
        this.m_httpsRequestEntries.put(storeEntry, prevCount+1);
    }
    
    public synchronized void dumpReport()
    {
    }
}
