/*
 * Copyright (c) 2015, Psiphon Inc.
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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.ListIterator;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;
import org.json.JSONObject;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

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

    private boolean m_showAds;
    private boolean m_skipHomePage;
    private ArrayList<String> m_homePages;
    private long m_nextFetchRemoteServerList;
    private boolean m_statusActivityForeground;
    private int m_configuredLocalSocksProxyPort = 0;
    private int m_configuredLocalHttpProxyPort = 0;
    private int m_listeningLocalSocksProxyPort = 0;
    private int m_listeningLocalHttpProxyPort = 0;
    private boolean m_tunnelWholeDevice;
    private boolean m_useHTTPProxy;
    private boolean m_useSystemProxySettings;
    private boolean m_useCustomProxySettings;
    private String m_customProxyHost;
    private String m_customProxyPort;
    private boolean m_useProxyAuthentication;
    private String m_proxyUsername;
    private String m_proxyPassword;
    private String m_proxyDomain;
    private ProxySettings m_savedSystemProxySettings;
    private boolean m_startingTunnelManager = false;
    private TunnelManager m_currentTunnelManager = null;
    private IEvents m_currentEventsInterface = null;
    private DataTransferStats m_dataTransferStats;
    private boolean m_displayDataTransferStats;
    private boolean m_downloadUpgrades;
    private String m_egressRegion;
    private String m_clientRegion;
    
    public int m_notificationIconConnecting = 0;
    public int m_notificationIconConnected = 0;
    public int m_notificationIconDisconnected = 0;
    public int m_notificationIconUpgradeAvailable = 0;

    public Object serverEntryFileLock = new Object(); // Used as an intrinsic lock
        
    private PsiphonData()
    {
        m_showAds = false;
        m_skipHomePage = false;
        m_homePages = new ArrayList<String>();
        m_nextFetchRemoteServerList = -1;
        m_statusActivityForeground = false;
        m_tunnelWholeDevice = false;
        m_useHTTPProxy = false;
        m_useSystemProxySettings = false;
        m_useCustomProxySettings = false;
        m_useProxyAuthentication = false;
        m_dataTransferStats = new DataTransferStats();
        m_displayDataTransferStats = false;
        m_downloadUpgrades = false;
        m_egressRegion = PsiphonConstants.REGION_CODE_ANY;
    }

    public synchronized void setShowAds()
    {
        m_showAds = true;
    }
    
    public synchronized boolean getShowAds()
    {
        return m_showAds;
    }
    
    public synchronized void setSkipHomePage()
    {
        m_skipHomePage = true;
    }
    
    public synchronized boolean getSkipHomePage()
    {
        return m_skipHomePage;
    }

    public synchronized void clearHomePages()
    {
        m_homePages.clear();
    }

    public synchronized void addHomePage(String url)
    {
        if (url.contains("psiphon_show_ads"))
        {
            setShowAds();
        }
        if (url.contains("psiphon_skip_homepage"))
        {
            setSkipHomePage();
        }
        for (int i = 0; i < m_homePages.size(); i++)
        {
            if (m_homePages.get(i).equals(url))
            {
                return;
            }
        }
        m_homePages.add(url);
    }

    public synchronized ArrayList<String> getHomePages()
    {
        ArrayList<String> homePages = new ArrayList<String>();
        homePages.addAll(m_homePages);
        return homePages;
    }
    
    public synchronized boolean showFirstHomePageInApp()
    {
        boolean showHomePage = false;
        ArrayList<String> homepages = getHomePages();
        if (!getSkipHomePage() && homepages.size() > 0) {
            showHomePage = true;
            for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
                if (homepages.get(0).contains(homeTabUrlExclusion))
                {
                    showHomePage = false;
                    break;
                }
            }
        }
        return showHomePage;
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
    
    public synchronized void setConfiguredLocalSocksProxyPort(int configuredLocalSocksProxyPort)
    {
        m_configuredLocalSocksProxyPort = configuredLocalSocksProxyPort;
    }

    public synchronized int getConfiguredLocalSocksProxyPort()
    {
        return m_configuredLocalSocksProxyPort;
    }

    public synchronized void setConfiguredLocalHttpProxyPort(int configuredLocalHttpProxyPort)
    {
        m_configuredLocalHttpProxyPort = configuredLocalHttpProxyPort;
    }

    public synchronized int getConfiguredLocalHttpProxyPort()
    {
        return m_configuredLocalHttpProxyPort;
    }
    
    public synchronized void setListeningLocalSocksProxyPort(int listeningLocalSocksProxyPort)
    {
        m_listeningLocalSocksProxyPort = listeningLocalSocksProxyPort;
    }

    public synchronized int getListeningLocalSocksProxyPort()
    {
        return m_listeningLocalSocksProxyPort;
    }

    public synchronized void setListeningLocalHttpProxyPort(int listeningLocalHttpProxyPort)
    {
        m_listeningLocalHttpProxyPort = listeningLocalHttpProxyPort;
    }

    public synchronized int getListeningLocalHttpProxyPort()
    {
        return m_listeningLocalHttpProxyPort;
    }

    public synchronized void setTunnelWholeDevice(boolean tunnelWholeDevice)
    {
        m_tunnelWholeDevice = tunnelWholeDevice;
    }

    public synchronized boolean getTunnelWholeDevice()
    {
        return m_tunnelWholeDevice;
    }

    public synchronized void setEgressRegion(String egressRegion)
    {
        m_egressRegion = egressRegion;
    }

    public synchronized String getEgressRegion()
    {
        return m_egressRegion;
    }

    public synchronized void setClientRegion(String clientRegion)
    {
        m_clientRegion = clientRegion;
    }

    public synchronized String getClientRegion()
    {
        return m_clientRegion;
    }

    public synchronized void setUseHTTPProxy(boolean useHTTPProxy)
    {
    	m_useHTTPProxy = useHTTPProxy;
    }

    public synchronized boolean getUseHTTPProxy()
    {
    	return m_useHTTPProxy;
    }
    
    public synchronized void setUseSystemProxySettings(boolean useSystemProxySettings)
    {
        m_useSystemProxySettings = useSystemProxySettings;
    }

    public synchronized boolean getUseSystemProxySettings()
    {
        return m_useSystemProxySettings;
    }
    
    public synchronized void setUseCustomProxySettings(boolean useCustomProxySettings)
    {
        m_useCustomProxySettings = useCustomProxySettings;
    }

    public synchronized boolean getUseCustomProxySettings()
    {
        return m_useCustomProxySettings;
    }
    
    public synchronized void setCustomProxyHost(String host)
    {
    	m_customProxyHost = host;
    }
    
    public synchronized String getCustomProxyHost()
    {
    	return m_customProxyHost;
    }

    public synchronized void setCustomProxyPort(String port)
    {
    	m_customProxyPort = port;
    }
    
    public synchronized String getCustomProxyPort()
    {
    	return m_customProxyPort;
    }
    
    public synchronized void setUseProxyAuthentication(boolean useProxyAuthentication)
    {
        m_useProxyAuthentication = useProxyAuthentication;
    }

    public synchronized boolean getUseProxyAuthentication()
    {
        return m_useProxyAuthentication;
    }

    public synchronized void setProxyUsername(String username)
    {
    	m_proxyUsername = username;
    }
    
    public synchronized String getProxyUsername()
    {
    	return m_proxyUsername;
    }

    public synchronized void setProxyPassword(String password)
    {
    	m_proxyPassword = password;
    }
    
    public synchronized String getProxyPassword()
    {
    	return m_proxyPassword;
    }
    
    public synchronized void setProxyDomain(String domain)
    {
    	m_proxyDomain = domain;
    }
    
    public synchronized String getProxyDomain()
    {
    	return m_proxyDomain;
    }

    public class ProxySettings
    {
        public String proxyHost;
        public int proxyPort;
    }
    
    // Call this before doing anything that could change the system proxy settings
    // (such as setting a WebView's proxy)
    public synchronized void saveSystemProxySettings(Context context)
    {
        if (m_savedSystemProxySettings == null)
        {
            m_savedSystemProxySettings = getSystemProxySettings(context);
        }
    }
    
    // Checks if we are supposed to use proxy settings, custom or system,
    // and if system, if any system proxy settings are configured.
    // Returns the user-requested proxy settings.
    public synchronized ProxySettings getProxySettings(Context context)
    {
        if (!getUseHTTPProxy())
        {
            return null;
        }
        
        ProxySettings settings = null;
        
        if (getUseCustomProxySettings())
        {
            settings = new ProxySettings();
            
            settings.proxyHost = getCustomProxyHost();
            String port = getCustomProxyPort();
            try
            {
                settings.proxyPort = Integer.parseInt(port);
            }
            catch (NumberFormatException e)
            {
                settings.proxyPort = -1;
            }
        }
        		
        if (getUseSystemProxySettings())
        {
            settings = getSystemProxySettings(context);
            
            if (settings.proxyHost == null || 
                settings.proxyHost.length() == 0 || 
                settings.proxyPort <= 0)
            {
                settings = null;
            }
        }
        
        return settings;
    }
    
	public synchronized Credentials getProxyCredentials() {
		if (!getUseProxyAuthentication()) {
			return null;
		}

		String username = getProxyUsername();
		String password = getProxyPassword();
		String domain = getProxyDomain();
		
		if (username == null || username.trim().equals("")) {
			return null;
		}
		if (password == null || password.trim().equals("")) {
			return null;
		}
		if (domain == null || domain.trim().equals("")) {
			return new NTCredentials(username, password, "", "");
		}
		
		String localHost;
		try {
			localHost = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			localHost = "localhost";
		}

		return new NTCredentials(username, password, localHost, domain);
	}
    
    private ProxySettings getSystemProxySettings(Context context)
    {
        ProxySettings settings = m_savedSystemProxySettings;
        
        if (settings == null)
        {
            settings = new ProxySettings();
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
            {
                settings.proxyHost = System.getProperty("http.proxyHost");
                String port = System.getProperty("http.proxyPort");
                settings.proxyPort = Integer.parseInt(port != null ? port : "-1");
            }
            else
            {
                settings.proxyHost = android.net.Proxy.getHost(context);
                settings.proxyPort = android.net.Proxy.getPort(context);
            }
        }
        
        return settings;
    }
    
    // Returns a tunnel-core compatible proxy URL for the
    // current user configured proxy settings.
    // e.g., http://NTDOMAIN\NTUser:password@proxyhost:3375,
    //       http://user:password@proxyhost:8080", etc.
    public synchronized String getUpstreamProxyUrl(Context context)
    {
        ProxySettings proxySettings = getProxySettings(context);

        if (proxySettings == null)
        {
            return "";
        }

        StringBuilder url = new StringBuilder();
        url.append("http://");

        NTCredentials credentials = (NTCredentials) getProxyCredentials();

        if (credentials != null)
        {
            if (credentials.getDomain() != "")
            {
                url.append(credentials.getDomain());
                url.append("\\");
            }
            url.append(credentials.getUserName());
            url.append(":");
            url.append(credentials.getPassword());
            url.append("@");
        }

        url.append(proxySettings.proxyHost);
        url.append(":");
        url.append(proxySettings.proxyPort);

        return url.toString();
    }

    public synchronized void setStartingTunnelManager()
    {
        m_startingTunnelManager = true;
    }

    public synchronized boolean getStartingTunnelManager()
    {
        return m_startingTunnelManager;
    }

    public synchronized void setCurrentTunnelManager(TunnelManager tunnelManager)
    {
        m_startingTunnelManager = false;
        m_currentTunnelManager = tunnelManager;
    }

    public synchronized TunnelManager getCurrentTunnelManager()
    {
        return m_currentTunnelManager;
    }

    public synchronized void setCurrentEventsInterface(IEvents eventsInterface)
    {
        m_currentEventsInterface = eventsInterface;
    }

    public synchronized IEvents getCurrentEventsInterface()
    {
        return m_currentEventsInterface;
    }

    public synchronized void setNotificationIcons(
            int connecting, 
            int connected, 
            int disconnected,
            int upgradeAvailable)
    {
        m_notificationIconConnecting = connecting;
        m_notificationIconConnected = connected;
        m_notificationIconDisconnected = disconnected;
        m_notificationIconUpgradeAvailable = upgradeAvailable;
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

    public synchronized int getNotificationIconUpgradeAvailable()
    {
        return m_notificationIconUpgradeAvailable;
    }

    public synchronized void setDownloadUpgrades(boolean downloadUpgrades)
    {
        m_downloadUpgrades = downloadUpgrades;
    }

    public synchronized boolean getDownloadUpgrades()
    {
        return m_downloadUpgrades;
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
        private long m_connectedTime;

        private long m_totalBytesSent;
        private long m_totalBytesReceived;

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
            m_totalBytesReceived = 0;

            stop();
        }
        
        public synchronized void startSession()
        {
            resetBytesTransferred();
        }

        public synchronized void startConnected()
        {
            this.m_isConnected = true;
            this.m_connectedTime = SystemClock.elapsedRealtime();
        }

        public synchronized void stop()
        {
            this.m_isConnected = false;
            this.m_connectedTime = 0;
            resetBytesTransferred();
        }
        
        private void resetBytesTransferred()
        {
            long now = SystemClock.elapsedRealtime();
            this.m_slowBucketsLastStartTime = bucketStartTime(now, SLOW_BUCKET_PERIOD_MILLISECONDS);
            this.m_slowBuckets = newBuckets();
            this.m_fastBucketsLastStartTime = bucketStartTime(now, FAST_BUCKET_PERIOD_MILLISECONDS);
            this.m_fastBuckets = newBuckets();
        }

        public synchronized void addBytesSent(long bytes)
        {
            this.m_totalBytesSent += bytes;
            
            manageBuckets();
            addSentToBuckets(bytes);
        }
    
        public synchronized void addBytesReceived(long bytes)
        {
            this.m_totalBytesReceived += bytes;

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
        
        private void addSentToBuckets(long bytes)
        {
            this.m_slowBuckets.get(this.m_slowBuckets.size()-1).m_bytesSent += bytes;
            this.m_fastBuckets.get(this.m_fastBuckets.size()-1).m_bytesSent += bytes;
        }
        
        private void addReceivedToBuckets(long bytes)
        {
            this.m_slowBuckets.get(this.m_slowBuckets.size()-1).m_bytesReceived += bytes;
            this.m_fastBuckets.get(this.m_fastBuckets.size()-1).m_bytesReceived += bytes;
        }
        
        public synchronized boolean isConnected()
        {
            return this.m_isConnected;
        }
        
        public synchronized long getElapsedTime()
        {
            long now = SystemClock.elapsedRealtime();
            
            return now - this.m_connectedTime;
        }
    
        public synchronized long getTotalBytesSent()
        {
            return this.m_totalBytesSent;
        }
        
        public synchronized long getTotalBytesReceived()
        {
            return this.m_totalBytesReceived;
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
    
    /** 
     * @return Returns the last non-DEBUG, non-WARN(ing) item, or null if there is none.
     */
    public StatusEntry getLastStatusEntryForDisplay() 
    {
        synchronized(m_statusHistory) 
        {   
            ListIterator<StatusEntry> iterator = m_statusHistory.listIterator(m_statusHistory.size());
            
            while (iterator.hasPrevious())
            {
                StatusEntry current_item = iterator.previous();
                if (current_item.priority() != Log.DEBUG &&
                    current_item.priority() != Log.WARN)
                {
                    return current_item;
                }
            }
            
            return null;
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
        synchronized(m_diagnosticHistory) 
        {
            m_diagnosticHistory.add(entry);
        }
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
