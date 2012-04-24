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

import android.util.Pair;

import com.psiphon3.Utils.MyLog;

public class Stats
{
    // Singleton
    
    private static Stats m_stats;
    private Integer m_bytesTransferred = 0;
    private Map<String, Integer> m_pageViewEntries;
    private Map<String, Integer> m_httpsRequestEntries;
    private List<Pair<Pattern, String>> m_pageViewRegexes;
    private List<Pair<Pattern, String>> m_httpsRequestRegexes;
        
    private Stats()
    {
        m_pageViewEntries = new HashMap<String, Integer>();
        m_httpsRequestEntries = new HashMap<String, Integer>();
    }

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }
    
    public static synchronized Stats getStats()
    {
        if (m_stats == null)
        {
            m_stats = new Stats();
        }
        
        return m_stats;
    }

    public synchronized void setRegexes(
            List<Pair<Pattern, String>> pageViewRegexes,
            List<Pair<Pattern, String>> httpsRequestRegexes)
    {
        m_stats.m_pageViewRegexes = pageViewRegexes;
        m_stats.m_httpsRequestRegexes = httpsRequestRegexes;
    }

    public synchronized void addBytesSent(int byteCount)
    {
        this.m_bytesTransferred += byteCount;
    }

    public synchronized void addBytesReceived(int byteCount)
    {
        this.m_bytesTransferred += byteCount;
    }
    
    public synchronized void upsertPageView(String entry)
    {
        String storeEntry = "(OTHER)";
        
        if (this.m_pageViewRegexes != null)
        {
            for (Pair<Pattern, String> regexReplace : this.m_pageViewRegexes)
            {
                Matcher matcher = regexReplace.first.matcher(entry);
                if (matcher.find())
                {
                    storeEntry = matcher.replaceFirst(regexReplace.second);
                    break;
                }
            }
        }
            
        if (storeEntry.length() == 0) return;
        
        // Add/increment the entry.
        Integer prevCount = this.m_pageViewEntries.get(storeEntry);
        if (prevCount == null) prevCount = 0;
        this.m_pageViewEntries.put(storeEntry, prevCount+1);
        
        MyLog.d("upsertPageView: ("+(prevCount+1)+") "+storeEntry);
    }
    
    public synchronized void upsertHttpsRequest(String entry)
    {
        // TODO: This is identical code to the function above, because we don't
        // yet know what a HTTPS "entry" looks like, because we haven't implemented
        // HTTPS response parsing yet.
        
        String storeEntry = "(OTHER)";
        
        if (this.m_httpsRequestRegexes != null)
        {
            for (Pair<Pattern, String> regexReplace : this.m_httpsRequestRegexes)
            {
                Matcher matcher = regexReplace.first.matcher(entry);
                if (matcher.find())
                {
                    storeEntry = matcher.replaceFirst(regexReplace.second);
                    break;
                }
            }
        }
        
        if (storeEntry.length() == 0) return;
        
        // Add/increment the entry.
        Integer prevCount = this.m_httpsRequestEntries.get(storeEntry);
        if (prevCount == null) prevCount = 0;
        this.m_httpsRequestEntries.put(storeEntry, prevCount+1);
    }
    
    public synchronized int getCount()
    {
        return this.m_pageViewEntries.size() + this.m_httpsRequestEntries.size();
    }

    public synchronized Map<String, Integer> getPageViewEntries()
    {
        return this.m_pageViewEntries;
    }

    public synchronized Map<String, Integer> getHttpsRequestEntries()
    {
        return this.m_httpsRequestEntries;
    }

    public Integer getBytesTransferred()
    {
        return this.m_bytesTransferred;
    }

    public synchronized void clear()
    {
        this.m_bytesTransferred = 0;
        this.m_pageViewEntries.clear();
        this.m_httpsRequestEntries.clear();
    }
}
