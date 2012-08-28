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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.util.Pair;

import com.psiphon3.Utils.MyLog;

public class PsiphonData
{
    // Singleton pattern
    
	private static PsiphonData m_psiphonData;

    public Object clone() throws CloneNotSupportedException
    {
        throw new CloneNotSupportedException();
    }
    
    public static synchronized PsiphonData getPsiphonData()
    {
        if (m_psiphonData == null)
        {
        	m_psiphonData = new PsiphonData();
        }
        
        return m_psiphonData;
    }

	private ArrayList<StatusMessage> m_statusMessages;
    private ArrayList<String> m_homePages;
    private Stats m_stats;
    private Date m_nextFetchRemoteServerList;
    private boolean m_statusActivityForeground;
    private String m_clientSessionID;
    private String m_tunnelSessionID;
    private String m_tunnelRelayProtocol;
    private int m_socksPort;
    private int m_httpProxyPort;
    private int m_transparentProxyPort;

    public Object serverEntryFileLock = new Object(); // Used as an intrinsic lock
        
    private PsiphonData()
    {
    	m_statusMessages = new ArrayList<StatusMessage>();
    	m_homePages = new ArrayList<String>();
    	m_stats = new Stats();
    	m_nextFetchRemoteServerList = null;
    	m_statusActivityForeground = false;
    }

    public synchronized void addStatusMessage(String message, int messageClass)
    {
    	m_statusMessages.add(new StatusMessage(message, messageClass));
    }

    public synchronized ArrayList<StatusMessage> getStatusMessages()
    {
        ArrayList<StatusMessage> messages = new ArrayList<StatusMessage>();
        messages.addAll(m_statusMessages);
        return messages;
    }

    public synchronized void addHomePage(String uri)
    {
    	m_homePages.add(uri);
    }

    public synchronized ArrayList<String> getHomePages()
    {
        ArrayList<String> homePages = new ArrayList<String>();
        homePages.addAll(m_homePages);
        return homePages;
    }

    public synchronized Stats getStats()
    {
    	return m_stats;
    }

    public synchronized Date getNextFetchRemoteServerList() {
        return m_nextFetchRemoteServerList;
    }

    public synchronized void setNextFetchRemoteServerList(Date nextFetchRemoteServerList) {
        m_nextFetchRemoteServerList = nextFetchRemoteServerList;
    }

    public synchronized void setStatusActivityForeground(boolean visible)
    {
        m_statusActivityForeground = visible;
    }
    
    public synchronized boolean getStatusActivityForeground()
    {
        return m_statusActivityForeground;
    }
    
    public synchronized void setClientSessionID(String clientSessionID)
    {
        m_clientSessionID = clientSessionID;
    }
    
    public synchronized String getClientSessionID()
    {
        return m_clientSessionID;
    }
    
    public synchronized void setTunnelSessionID(String sessionID)
    {
        m_tunnelSessionID = sessionID;
    }
    
    public synchronized String getTunnelSessionID()
    {
        return m_tunnelSessionID;
    }
    
    public synchronized void setTunnelRelayProtocol(String relayProtocol)
    {
        m_tunnelRelayProtocol = relayProtocol;
    }
    
    public synchronized String getTunnelRelayProtocol()
    {
        return m_tunnelRelayProtocol;
    }
    
    public synchronized void setHttpProxyPort(int httpProxyPort)
    {
        m_httpProxyPort = httpProxyPort;
    }

    public synchronized int getHttpProxyPort()
    {
        return m_httpProxyPort;
    }

    public synchronized void setSocksPort(int socksPort)
    {
        m_socksPort = socksPort;
    }

    public synchronized int getSocksPort()
    {
        return m_socksPort;
    }

    public synchronized void setTransparentProxyPort(int transparentProxyPort)
    {
        m_transparentProxyPort = transparentProxyPort;
    }

    public synchronized int getTransparentProxyPort()
    {
        return m_transparentProxyPort;
    }

    public class StatusMessage
    {
        public String m_message;
        public int m_messageClass;

        StatusMessage(String message, int messageClass) 
        {
            m_message = message;
            m_messageClass = messageClass;
        }
    }

	public class Stats
	{
	    private Integer m_bytesTransferred = 0;
	    private Map<String, Integer> m_pageViewEntries;
	    private Map<String, Integer> m_httpsRequestEntries;
	    private List<Pair<Pattern, String>> m_pageViewRegexes;
	    private List<Pair<Pattern, String>> m_httpsRequestRegexes;
	        
	    Stats()
	    {
	        m_pageViewEntries = new HashMap<String, Integer>();
	        m_httpsRequestEntries = new HashMap<String, Integer>();
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
	
	    public synchronized Integer getBytesTransferred()
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
}
