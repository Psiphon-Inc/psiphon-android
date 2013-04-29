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

package com.psiphon3.psiphonlibrary;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

import android.os.SystemClock;
import android.util.Pair;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

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

    private ArrayList<String> m_homePages;
    private long m_nextFetchRemoteServerList;
    private boolean m_statusActivityForeground;
    private String m_clientSessionID;
    private String m_tunnelSessionID;
    private String m_tunnelRelayProtocol;
    private int m_defaultSocksPort = PsiphonConstants.SOCKS_PORT;
    private int m_defaultHttpProxyPort = PsiphonConstants.HTTP_PROXY_PORT;
    private int m_socksPort;
    private int m_httpProxyPort;
    private int m_dnsProxyPort;
    private int m_transparentProxyPort;
    private boolean m_tunnelWholeDevice;
    private boolean m_vpnServiceUnavailable;
    private TunnelCore m_currentTunnelCore;
    private ReportedStats m_reportedStats;
    private boolean m_enableReportedStats;
    private DataTransferStats m_dataTransferStats;
    private boolean m_displayDataTransferStats;
    
    public int m_notificationIconConnecting = 0;
    public int m_notificationIconConnected = 0;
    public int m_notificationIconDisconnected = 0;

    public Object serverEntryFileLock = new Object(); // Used as an intrinsic lock
        
    private PsiphonData()
    {
        m_homePages = new ArrayList<String>();
        m_nextFetchRemoteServerList = -1;
        m_statusActivityForeground = false;
        m_tunnelWholeDevice = false;
        m_vpnServiceUnavailable = false;
        m_reportedStats = new ReportedStats();
        m_enableReportedStats = true;
        m_dataTransferStats = new DataTransferStats();
        m_displayDataTransferStats = false;
    }

    public synchronized void setHomePages(ArrayList<String> homePages)
    {
        m_homePages.clear();
        for (int i = 0; i < homePages.size(); i++)
        {
            m_homePages.add(homePages.get(i));
        }
    }

    public synchronized ArrayList<String> getHomePages()
    {
        ArrayList<String> homePages = new ArrayList<String>();
        homePages.addAll(m_homePages);
        return homePages;
    }

    public synchronized long getNextFetchRemoteServerList()
    {
        return m_nextFetchRemoteServerList;
    }

    public synchronized void setNextFetchRemoteServerList(long nextFetchRemoteServerList)
    {
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
    
    public synchronized void setDefaultHttpProxyPort(int defaultHttpProxyPort)
    {
        m_defaultHttpProxyPort = defaultHttpProxyPort;
    }

    public synchronized int getDefaultHttpProxyPort()
    {
        return m_defaultHttpProxyPort;
    }

    public synchronized void setDefaultSocksPort(int defaultSocksPort)
    {
        m_defaultSocksPort = defaultSocksPort;
    }

    public synchronized int getDefaultSocksPort()
    {
        return m_defaultSocksPort;
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

    public synchronized void setDnsProxyPort(int dnsProxyPort)
    {
        m_dnsProxyPort = dnsProxyPort;
    }

    public synchronized int getDnsProxyPort()
    {
        return m_dnsProxyPort;
    }

    public synchronized void setTransparentProxyPort(int transparentProxyPort)
    {
        m_transparentProxyPort = transparentProxyPort;
    }

    public synchronized int getTransparentProxyPort()
    {
        return m_transparentProxyPort;
    }

    public synchronized void setTunnelWholeDevice(boolean tunnelWholeDevice)
    {
        m_tunnelWholeDevice = tunnelWholeDevice;
    }

    public synchronized boolean getTunnelWholeDevice()
    {
        return m_tunnelWholeDevice;
    }

    public synchronized void setVpnServiceUnavailable(boolean vpnServiceUnavailable)
    {
        m_vpnServiceUnavailable = vpnServiceUnavailable;
    }

    public synchronized boolean getVpnServiceUnavailable()
    {
        return m_vpnServiceUnavailable;
    }

    public synchronized void setCurrentTunnelCore(TunnelCore tunnelCore)
    {
        // TODO: make TunnelCore a singleton; then get rid of this hack.
        
        m_currentTunnelCore = tunnelCore;
    }

    public synchronized TunnelCore getCurrentTunnelCore()
    {
        return m_currentTunnelCore;
    }

    public synchronized void setNotificationIcons(
            int connecting, 
            int connected, 
            int disconnected)
    {
        m_notificationIconConnecting = connecting;
        m_notificationIconConnected = connected;
        m_notificationIconDisconnected = disconnected;
    }
    
    public synchronized int getNotificationIconConnecting()
    {
        return m_notificationIconConnecting;
    }

    public synchronized int getNotificationIconConnected()
    {
        return m_notificationIconConnected;
    }

    public synchronized int getNotificationIconDisconnected()
    {
        return m_notificationIconDisconnected;
    }

    public synchronized void setEnableReportedStats(boolean enableReportedStats)
    {
        m_enableReportedStats = enableReportedStats;
    }

    public synchronized boolean getEnableReportedStats()
    {
        return m_enableReportedStats;
    }

    public synchronized ReportedStats getReportedStats()
    {
        if (!m_enableReportedStats)
        {
            return null;
        }
        return m_reportedStats;
    }

    public class ReportedStats
    {
        private Integer m_bytesTransferred = 0;
        private Map<String, Integer> m_pageViewEntries;
        private Map<String, Integer> m_httpsRequestEntries;
        private List<Pair<Pattern, String>> m_pageViewRegexes;
        private List<Pair<Pattern, String>> m_httpsRequestRegexes;
            
        ReportedStats()
        {
            this.m_pageViewEntries = new HashMap<String, Integer>();
            this.m_httpsRequestEntries = new HashMap<String, Integer>();
        }
    
        public synchronized void setRegexes(
                List<Pair<Pattern, String>> pageViewRegexes,
                List<Pair<Pattern, String>> httpsRequestRegexes)
        {
            this.m_pageViewRegexes = pageViewRegexes;
            this.m_httpsRequestRegexes = httpsRequestRegexes;
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
    
    public synchronized void setDisplayDataTransferStats(boolean displayDataTransferStats)
    {
        m_displayDataTransferStats = displayDataTransferStats;
    }

    public synchronized boolean getDisplayDataTransferStats()
    {
        return m_displayDataTransferStats;
    }

    public synchronized DataTransferStats getDataTransferStats()
    {
        return m_dataTransferStats;
    }

    public class DataTransferStats
    {
        private boolean m_isConnected;
        private long m_startTime;

        private long m_totalBytesSent;
        private long m_totalUncompressedBytesSent;
        private long m_totalOverheadBytesSent;
        private long m_totalBytesReceived;
        private long m_totalUncompressedBytesReceived;
        private long m_totalOverheadBytesReceived;

        private long m_sessionBytesSent;
        private long m_sessionUncompressedBytesSent;
        private long m_sessionOverheadBytesSent;
        private long m_sessionBytesReceived;
        private long m_sessionUncompressedBytesReceived;
        private long m_sessionOverheadBytesReceived;

        public final static long SLOW_BUCKET_PERIOD_MILLISECONDS = 5*60*1000; 
        public final static long FAST_BUCKET_PERIOD_MILLISECONDS = 1000;
        public final static int MAX_BUCKETS = 24*60/5;
        
        private class Bucket
        {
            public long m_bytesSent = 0;
            public long m_bytesReceived = 0;
        }
        
        private ArrayList<Bucket> m_slowBuckets;
        private long m_slowBucketsLastStartTime;
        private ArrayList<Bucket> m_fastBuckets;
        private long m_fastBucketsLastStartTime;
        
        DataTransferStats()
        {
            m_totalBytesSent = 0;
            m_totalUncompressedBytesSent = 0;
            m_totalOverheadBytesSent = 0;
            m_totalBytesReceived = 0;
            m_totalUncompressedBytesReceived = 0;
            m_totalOverheadBytesReceived = 0;

            stop();
        }
        
        public synchronized void start()
        {
            this.m_isConnected = true;
            reset();
        }

        public synchronized void stop()
        {
            this.m_isConnected = false;
            reset();
        }
        
        private void reset()
        {
            long now = SystemClock.elapsedRealtime();
            this.m_startTime = now;
            this.m_sessionBytesSent = 0;
            this.m_sessionUncompressedBytesSent = 0;
            this.m_sessionOverheadBytesSent = 0;
            this.m_sessionBytesReceived = 0;
            this.m_sessionUncompressedBytesReceived = 0;
            this.m_sessionOverheadBytesReceived = 0;
            this.m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            this.m_slowBuckets = newBuckets();
            this.m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            this.m_fastBuckets = newBuckets();
        }

        public synchronized void addBytesSent(int bytes, int uncompressedBytes, int overheadBytes)
        {
            this.m_totalBytesSent += bytes;
            this.m_totalUncompressedBytesSent += uncompressedBytes;
            this.m_totalOverheadBytesSent += overheadBytes;
            
            this.m_sessionBytesSent += bytes;
            this.m_sessionUncompressedBytesSent += uncompressedBytes;
            this.m_sessionOverheadBytesSent += overheadBytes;
            
            manageBuckets();
            addSentToBuckets(bytes);
        }
    
        public synchronized void addBytesReceived(int bytes, int uncompressedBytes, int overheadBytes)
        {
            this.m_totalBytesReceived += bytes;
            this.m_totalUncompressedBytesReceived += uncompressedBytes;
            this.m_totalOverheadBytesReceived += overheadBytes;

            this.m_sessionBytesReceived += bytes;
            this.m_sessionUncompressedBytesReceived += uncompressedBytes;
            this.m_sessionOverheadBytesReceived += overheadBytes;

            manageBuckets();
            addReceivedToBuckets(bytes);
        }
        
        private long bucketStartTime(long now, long period)
        {
            return period*(now/period);
        }
        
        private ArrayList<Bucket> newBuckets()
        {
            ArrayList<Bucket> buckets = new ArrayList<Bucket>();
            for (int i = 0; i < MAX_BUCKETS; i++)
            {
                buckets.add(new Bucket());
            }
            return buckets;
        }
        
        private void shiftBuckets(long diff, long period, ArrayList<Bucket> buckets)
        {
            for (int i = 0; i < diff/period + 1; i++)
            {
                buckets.add(buckets.size(), new Bucket());
                if (buckets.size() >= MAX_BUCKETS)
                {
                    buckets.remove(0);
                }
            }            
        }
        
        private void manageBuckets()
        {
            long now = SystemClock.elapsedRealtime();

            long diff = now - this.m_slowBucketsLastStartTime;            
            if (diff >= SLOW_BUCKET_PERIOD_MILLISECONDS)
            {
                shiftBuckets(diff, SLOW_BUCKET_PERIOD_MILLISECONDS, m_slowBuckets);
                this.m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            }

            diff = now - this.m_fastBucketsLastStartTime;            
            if (diff >= FAST_BUCKET_PERIOD_MILLISECONDS)
            {
                shiftBuckets(diff, FAST_BUCKET_PERIOD_MILLISECONDS, m_fastBuckets);
                this.m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            }
        }
        
        private ArrayList<Long> getSentSeries(ArrayList<Bucket> buckets)
        {
            ArrayList<Long> series = new ArrayList<Long>();
            for (int i = 0; i < buckets.size(); i++)
            {
                series.add(buckets.get(i).m_bytesSent);
            }
            return series;
        }
        
        private ArrayList<Long> getReceivedSeries(ArrayList<Bucket> buckets)
        {
            ArrayList<Long> series = new ArrayList<Long>();
            for (int i = 0; i < buckets.size(); i++)
            {
                series.add(buckets.get(i).m_bytesReceived);
            }
            return series;
        }
        
        private void addSentToBuckets(int bytes)
        {
            this.m_slowBuckets.get(this.m_slowBuckets.size()-1).m_bytesReceived += bytes;
            this.m_fastBuckets.get(this.m_fastBuckets.size()-1).m_bytesReceived += bytes;
        }
        
        private void addReceivedToBuckets(int bytes)
        {
            this.m_slowBuckets.get(this.m_slowBuckets.size()-1).m_bytesSent += bytes;
            this.m_fastBuckets.get(this.m_fastBuckets.size()-1).m_bytesSent += bytes;
        }
        
        public synchronized boolean isConnected()
        {
            return this.m_isConnected;
        }
        
        public synchronized long getElapsedTime()
        {
            long now = SystemClock.elapsedRealtime();
            
            return now - this.m_startTime;
        }
    
        public synchronized long getTotalBytesSent()
        {
            return this.m_totalBytesSent;
        }
        
        public synchronized long getTotalUncompressedBytesSent()
        {
            return this.m_totalUncompressedBytesSent;
        }

        public synchronized long getTotalOverheadBytesSent()
        {
            return this.m_totalOverheadBytesSent;
        }

        public synchronized double getTotalSentCompressionRatio()
        {
            if (this.m_totalUncompressedBytesSent == 0) return 0.0;
            double ratio = 100.0*(1.0-(double)(this.m_totalBytesSent + this.m_totalOverheadBytesSent)/(double)this.m_totalUncompressedBytesSent);
            return ratio > 0.0 ? ratio : 0.0;
        }
        
        public synchronized long getTotalSentSaved()
        {
            long savings = this.m_totalUncompressedBytesSent - (this.m_totalBytesSent + this.m_totalOverheadBytesSent);
            return savings > 0 ? savings : 0;
        }
        
        public synchronized long getTotalBytesReceived()
        {
            return this.m_totalBytesReceived;
        }
        
        public synchronized long getTotalOverheadBytesReceived()
        {
            return this.m_totalOverheadBytesReceived;
        }
        
        public synchronized long getTotalUncompressedBytesReceived()
        {
            return this.m_totalUncompressedBytesReceived;
        }

        public synchronized double getTotalReceivedCompressionRatio()
        {
            if (this.m_totalUncompressedBytesReceived == 0) return 0.0;
            double ratio = 100.0*(1.0-(double)(this.m_totalBytesReceived + this.m_totalOverheadBytesReceived)/(double)this.m_totalUncompressedBytesReceived);
            return ratio > 0.0 ? ratio : 0.0;
        }
        
        public synchronized long getTotalReceivedSaved()
        {
            long savings = this.m_totalUncompressedBytesReceived - (this.m_totalBytesReceived + this.m_totalOverheadBytesReceived);
            return savings > 0 ? savings : 0;
        }
        
        public synchronized long getSessionBytesSent()
        {
            return this.m_sessionBytesSent;
        }
        
        public synchronized long getSessionUncompressedBytesSent()
        {
            return this.m_sessionUncompressedBytesSent;
        }

        public synchronized long getSessionOverheadBytesSent()
        {
            return this.m_sessionOverheadBytesSent;
        }

        public synchronized double getSessionSentCompressionRatio()
        {
            if (this.m_sessionUncompressedBytesSent == 0) return 0.0;
            double ratio = 100.0*(1.0-(double)(this.m_sessionBytesSent + this.m_sessionOverheadBytesSent)/(double)this.m_sessionUncompressedBytesSent);
            return ratio > 0.0 ? ratio : 0.0;
        }
        
        public synchronized long getSessionSentSaved()
        {
            long savings = this.m_sessionUncompressedBytesSent - (this.m_sessionBytesSent + this.m_sessionOverheadBytesSent);
            return savings > 0 ? savings : 0;
        }
        
        public synchronized long getSessionBytesReceived()
        {
            return this.m_sessionBytesReceived;
        }
        
        public synchronized long getSessionlOverheadBytesReceived()
        {
            return this.m_sessionOverheadBytesReceived;
        }
        
        public synchronized long getSessionUncompressedBytesReceived()
        {
            return this.m_sessionUncompressedBytesReceived;
        }

        public synchronized double getSessionReceivedCompressionRatio()
        {
            if (this.m_sessionUncompressedBytesReceived == 0) return 0.0;
            double ratio = 100.0*(1.0-(double)(this.m_sessionBytesReceived + this.m_sessionOverheadBytesReceived)/(double)this.m_sessionUncompressedBytesReceived);
            return ratio > 0.0 ? ratio : 0.0;
        }
        
        public synchronized long getSessionReceivedSaved()
        {
            long savings = this.m_sessionUncompressedBytesReceived - (this.m_sessionBytesReceived + this.m_sessionOverheadBytesReceived);
            return savings > 0 ? savings : 0;
        }

        public synchronized ArrayList<Long> getSlowSentSeries()
        {
            manageBuckets();
            return getSentSeries(this.m_slowBuckets);
        }
        
        public synchronized ArrayList<Long> getSlowReceivedSeries()
        {
            manageBuckets();
            return getReceivedSeries(this.m_slowBuckets);
        }
        
        public synchronized ArrayList<Long> getFastSentSeries()
        {
            manageBuckets();
            return getSentSeries(this.m_fastBuckets);
        }
        
        public synchronized ArrayList<Long> getFastReceivedSeries()
        {
            manageBuckets();
            return getReceivedSeries(this.m_fastBuckets);
        }
    }
    
    /*
     * Status Message History support
     */

    static public class StatusEntry
    {
        private Date timestamp;
        private int id;
        private Object[] formatArgs;
        private Throwable throwable;
        private int priority;
        private MyLog.Sensitivity sensitivity;
        
        public Date timestamp()
        {
            return timestamp;
        }
        
        public int id()
        {
            return id;
        }
        
        public Object[] formatArgs()
        {
            return formatArgs;
        }
        
        public Throwable throwable()
        {
            return throwable;
        }
        
        public int priority()
        {
            return priority;
        }
        
        public MyLog.Sensitivity sensitivity()
        {
            return sensitivity;
        }
    }
    
    private ArrayList<StatusEntry> m_statusHistory = new ArrayList<StatusEntry>();
    
    public void addStatusEntry(
            Date timestamp,
            int id, 
            MyLog.Sensitivity sensitivity, 
            Object[] formatArgs, 
            Throwable throwable, 
            int priority)
    {
        StatusEntry entry = new StatusEntry();
        entry.timestamp = timestamp;
        entry.id = id;
        entry.sensitivity = sensitivity;
        entry.formatArgs = formatArgs;
        entry.throwable = throwable;
        entry.priority = priority;
        
        synchronized(m_statusHistory) 
        {
            m_statusHistory.add(entry);
        }
    }
    
    public ArrayList<StatusEntry> cloneStatusHistory()
    {
        ArrayList<StatusEntry> copy;
        synchronized(m_statusHistory) 
        {
            copy = new ArrayList<StatusEntry>(m_statusHistory);
        }
        return copy;
    }
    
    public void clearStatusHistory()
    {
        synchronized(m_statusHistory) 
        {        
            m_statusHistory.clear();
        }
    }
    
    /** 
     * @param index
     * @return Returns item at `index`. Negative indexes count from the end of 
     * the array. If `index` is out of bounds, null is returned.
     */
    public StatusEntry getStatusEntry(int index) 
    {
        synchronized(m_statusHistory) 
        {   
            if (index < 0) 
            {
                // index is negative, so this is subtracting...
                index = m_statusHistory.size() + index;
                // Note that index is still negative if the array is empty or if
                // the negative value was too large.
            }
            
            if (index >= m_statusHistory.size() || index < 0)
            {
                return null;
            }
            
            return m_statusHistory.get(index);
        }
    }
    
    /*
     * Diagnostic history support
     */
    
    static public class DiagnosticEntry extends Object
    {
        private Date timestamp;
        private String msg;
        private JSONObject data;

        public Date timestamp()
        {
            return timestamp;
        }
        
        public String msg()
        {
            return msg;
        }
        
        public JSONObject data()
        {
            return data;
        }
    }
        
    static private List<DiagnosticEntry> m_diagnosticHistory = new ArrayList<DiagnosticEntry>();

    static public void addDiagnosticEntry(Date timestamp, String msg, JSONObject data)
    {
        DiagnosticEntry entry = new DiagnosticEntry();
        entry.timestamp = timestamp;
        entry.msg = msg;
        entry.data = data;
        m_diagnosticHistory.add(entry);
    }
    
    static public List<DiagnosticEntry> cloneDiagnosticHistory()
    {
        List<DiagnosticEntry> copy;
        synchronized(m_diagnosticHistory) 
        {
            copy = new ArrayList<DiagnosticEntry>(m_diagnosticHistory);
        }
        return copy;
    }
}
