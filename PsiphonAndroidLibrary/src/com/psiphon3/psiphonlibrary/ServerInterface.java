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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.AbstractVerifier;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.SingleClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.ServerEntryAuth.ServerEntryAuthException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.content.Context;
import android.os.SystemClock;
import android.util.Pair;
import android.webkit.URLUtil;


public class ServerInterface
{
    /**
     * Exception type thrown by many members of the PsiphonServerInterface class.
     */
    public static class PsiphonServerInterfaceException extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public PsiphonServerInterfaceException()
        {
            super();
        }
        
        public PsiphonServerInterfaceException(String message)
        {
            super(message);
        }

        public PsiphonServerInterfaceException(String message, Throwable cause)
        {
            super(message, cause);
        }

        public PsiphonServerInterfaceException(Throwable cause)
        {
            super(cause);
        }
    }
    
    public class ServerEntry implements Cloneable
    {
        public String encodedEntry;
        public String ipAddress;
        public int webServerPort;
        public String webServerSecret;
        public String webServerCertificate;
        public int sshPort;
        public String sshUsername;
        public String sshPassword;
        public String sshHostKey;
        public int sshObfuscatedPort;
        public String sshObfuscatedKey;
        public ArrayList<String> capabilities;

        @Override
        public ServerEntry clone()
        {
            try
            {
                return (ServerEntry) super.clone();
            }
            catch (CloneNotSupportedException e)
            {
                throw new AssertionError();
            }
        }
        
        boolean hasCapability(String capability)
        {
            return this.capabilities.contains(capability);
        }

        boolean hasCapabilities(List<String> capabilities)
        {
            return this.capabilities.containsAll(capabilities);
        }

        int getPreferredReachablityTestPort()
        {
            if (hasCapability("OSSH"))
            {
                return this.sshObfuscatedPort;
            }
            else if (hasCapability("SSH"))
            {
                return this.sshPort;
            }
            else if (hasCapability("handshake"))
            {
                return this.webServerPort;
            }

            return -1;
        }
    }
    
    private Context ownerContext;
    private ArrayList<ServerEntry> serverEntries = new ArrayList<ServerEntry>();
    private String upgradeClientVersion;
    private String serverSessionID;
    private ServerEntry currentServerEntry;
    
    /** Array of all outstanding/ongoing requests. Anything in this array will
     * be aborted when {@link#stop()} is called. */
    private List<HttpRequestBase> outstandingRequests = new ArrayList<HttpRequestBase>();
    
    /** This flag is set to true when {@link#stop()} is called, and set back to 
     * false when {@link#start()} is called. */
    private boolean stopped = false;

    public ServerInterface(Context context)
    {
        this.ownerContext = context;

        // Load persistent server entries, then add embedded entries
        
        synchronized(PsiphonData.getPsiphonData().serverEntryFileLock)
        {
            try
            {
                FileInputStream file = context.openFileInput(
                        PsiphonConstants.SERVER_ENTRY_FILENAME);
                BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(file));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null)
                {
                    json.append(line);
                }
                file.close();
                JSONObject obj = new JSONObject(json.toString());
                JSONArray jsonServerEntries = obj.getJSONArray("serverEntries");
        
                for (int i = 0; i < jsonServerEntries.length(); i++)
                {
                    // NOTE: No shuffling, as we're restoring a previously arranged list
                    appendServerEntry(jsonServerEntries.getString(i));
                }
            }
            catch (FileNotFoundException e)
            {
                // pass
            }
            catch (IOException e)
            {
                MyLog.w(R.string.ServerInterface_FailedToReadStoredServerEntries, MyLog.Sensitivity.NOT_SENSITIVE, e);
                // skip loading persistent server entries
            } 
            catch (JSONException e)
            {
                MyLog.w(R.string.ServerInterface_FailedToParseStoredServerEntries, MyLog.Sensitivity.NOT_SENSITIVE, e);
                // skip loading persistent server entries
            }
        }
        
        try
        {
            shuffleAndAddServerEntries(
                EmbeddedValues.EMBEDDED_SERVER_LIST.split("\n"), true);
            saveServerEntries();
        } 
        catch (JSONException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseEmbeddedServerEntries, MyLog.Sensitivity.NOT_SENSITIVE, e);
        }
    }
    
    /**
     * Indicates that ServerInterface should transition back into an operational
     * state.
     */
    public void start()
    {
        this.stopped = false;
        this.currentServerEntry = null;
        resetPeriodicWork();
    }

    /**
     * Called when a halting of activity is required.
     */
    public void stop()
    {
        
        this.stopped = true;
        
        // NOTE: can't clear this here, as some requests are done after stop() is called in stopTunnel()
        //this.currentServerEntry = null;
        
        // This may be called from the app main thread, so it must not make 
        // network requests directly (because that's disallowed in Android).
        // request.abort() counts as "network activity", so it has to be done
        // in a separate thread.
        Thread thread = new Thread(
            new Runnable()
            {
                public void run()
                {
                    synchronized(outstandingRequests)
                    {
                        for (HttpRequestBase request : outstandingRequests) 
                        {
                            if (!request.isAborted()) request.abort();
                        }
                    }
                }
            });

        thread.start();
        try
        {
            thread.join();
        } 
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    } 

    public synchronized ServerEntry setCurrentServerEntry()
    {
        // Saves selected currentServerEntry for future reference (e.g., used by getRequestURL calls)
        
        if (this.serverEntries.size() > 0)
        {
            this.currentServerEntry = this.serverEntries.get(0).clone();
            return this.currentServerEntry;
        }

        return null;
    }
    
    synchronized ServerEntry getCurrentServerEntry()
    {
        if (this.currentServerEntry == null)
        {
            return null;
        }
        
        return this.currentServerEntry.clone();
    }
    
    synchronized ArrayList<ServerEntry> getServerEntries()
    {
        ArrayList<ServerEntry> serverEntries = new ArrayList<ServerEntry>();

        for (ServerEntry serverEntry : this.serverEntries)
        {
            serverEntries.add(serverEntry.clone());
        }

        return serverEntries;        
    }

    synchronized boolean serverWithCapabilitiesExists(List<String> capabilities)
    {
        for (ServerEntry entry: this.serverEntries)
        {
            if (entry.hasCapabilities(capabilities))
            {
                return true;
            }
        }
        
        return false;
    }
    
    synchronized void markCurrentServerFailed()
    {
        if (this.currentServerEntry != null)
        {
            // Move to end of list for last chance retry
            for (int i = 0; i < this.serverEntries.size(); i++)
            {
                if (this.serverEntries.get(i).ipAddress.equals(this.currentServerEntry.ipAddress))
                {
                    this.serverEntries.add(this.serverEntries.remove(i));
                    break;
                }
            }
            
            // Save the new server order
            saveServerEntries();
        }
    }
    
    synchronized public void generateNewCurrentClientSessionID()
    {
        byte[] clientSessionIdBytes = Utils.generateInsecureRandomBytes(PsiphonConstants.CLIENT_SESSION_ID_SIZE_IN_BYTES);
        PsiphonData.getPsiphonData().setClientSessionID(Utils.byteArrayToHexString(clientSessionIdBytes));
    }
    
    synchronized public String getCurrentClientSessionID()
    {
        String clientSessionID = PsiphonData.getPsiphonData().getClientSessionID();
        assert(clientSessionID != null);
        return clientSessionID;
    }
    
    synchronized public String getCurrentServerSessionID()
    {
        if (this.serverSessionID != null)
        {
            return this.serverSessionID;
        }
        
        return "";
    }
    
    /**
     * Makes the handshake request to the server. The client thereby obtains
     * session info from the server such as what homepages should be shown and
     * whether there is an upgrade available. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doHandshakeRequest() 
        throws PsiphonServerInterfaceException
    {
        boolean configProcessed = false;
        try
        {
            List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
            for (ServerEntry entry : this.serverEntries)
            {
                extraParams.add(Pair.create("known_server", entry.ipAddress));
            }
            
            String url = getRequestURL("handshake", extraParams);
            
            byte[] response = makeAbortableProxiedPsiphonRequest(url);

            final String JSON_CONFIG_PREFIX = "Config: ";
            for (String line : new String(response).split("\n"))
            {
                if (line.indexOf(JSON_CONFIG_PREFIX) == 0)
                {
                    configProcessed = true;
                    
                    JSONObject obj = new JSONObject(line.substring(JSON_CONFIG_PREFIX.length()));

                    JSONArray homepages = obj.getJSONArray("homepages");
                    
                    ArrayList<String> sessionHomePages = new ArrayList<String>();
                    
                    for (int i = 0; i < homepages.length(); i++)
                    {
                        sessionHomePages.add(homepages.getString(i));
                    }
                    PsiphonData.getPsiphonData().setHomePages(sessionHomePages);

                    this.upgradeClientVersion = obj.getString("upgrade_client_version");
                    
                    // Fix for null/"null" JSONLib issue
                    if (this.upgradeClientVersion.compareTo("null") == 0)
                    {
                        this.upgradeClientVersion = "";
                    }

                    List<Pair<Pattern, String>> pageViewRegexes = new ArrayList<Pair<Pattern, String>>();
                    JSONArray jsonPageViewRegexes = obj.getJSONArray("page_view_regexes");
                    for (int i = 0; i < jsonPageViewRegexes.length(); i++)
                    {
                        JSONObject regexReplace = jsonPageViewRegexes.getJSONObject(i);
                        
                        pageViewRegexes.add(Pair.create(
                                Pattern.compile(regexReplace.getString("regex"), Pattern.CASE_INSENSITIVE), 
                                regexReplace.getString("replace")));
                    }

                    List<Pair<Pattern, String>> httpsRequestRegexes = new ArrayList<Pair<Pattern, String>>();
                    JSONArray jsonHttpsRequestRegexes = obj.getJSONArray("https_request_regexes");
                    for (int i = 0; i < jsonHttpsRequestRegexes.length(); i++)
                    {
                        JSONObject regexReplace = jsonHttpsRequestRegexes.getJSONObject(i);
                        
                        httpsRequestRegexes.add(Pair.create(
                                Pattern.compile(regexReplace.getString("regex"), Pattern.CASE_INSENSITIVE), 
                                regexReplace.getString("replace")));
                    }
                    
                    // Set the regexes directly in the stats object rather than 
                    // storing them in this class.
                    PsiphonData.ReportedStats reportedStats = PsiphonData.getPsiphonData().getReportedStats();
                    if (reportedStats != null)
                    {
                        reportedStats.setRegexes(pageViewRegexes, httpsRequestRegexes);
                    }

                    JSONArray encoded_server_list = obj.getJSONArray("encoded_server_list");
                    String[] entries = new String[encoded_server_list.length()];
                    for (int i = 0; i < encoded_server_list.length(); i++)
                    {
                        entries[i] = encoded_server_list.getString(i);
                    }
                    if (encoded_server_list.length() > 0)
                    {
                        shuffleAndAddServerEntries(entries, false);
                        saveServerEntries();
                    }
                    
                    // We only support SSH, so this is our server session ID.
                    this.serverSessionID = obj.getString("ssh_session_id");
                }
            }
        }
        catch (JSONException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseHandshake, MyLog.Sensitivity.NOT_SENSITIVE, e);
            throw new PsiphonServerInterfaceException(e);
        }
        
        if (!configProcessed)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseHandshake, MyLog.Sensitivity.NOT_SENSITIVE);
            throw new PsiphonServerInterfaceException();
        }
    }
    
    /**
     * Make the 'connected' request to the server. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doConnectedRequest() 
        throws PsiphonServerInterfaceException
    {
        String lastConnected = "None";
        try
        {
            FileInputStream file = this.ownerContext.openFileInput(
                    PsiphonConstants.LAST_CONNECTED_FILENAME);
            BufferedReader reader =
                new BufferedReader(
                    new InputStreamReader(file));
            char[] buf = new char[30];
            reader.read(buf);
            file.close();
            lastConnected = new String(buf);
            lastConnected = lastConnected.trim();
        }
        catch (FileNotFoundException e)
        {
            // pass
        }
        catch (IOException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToReadLastConnected, MyLog.Sensitivity.NOT_SENSITIVE, e);
            // skip loading persistent server entries
        }         
        
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("session_id", this.serverSessionID));
        extraParams.add(Pair.create("last_connected", lastConnected));
        
        String url = getRequestURL("connected", extraParams);
        
        byte[] response = makeAbortableProxiedPsiphonRequest(url);

        try
        {
            JSONObject obj = new JSONObject(new String(response));
            String connected_timestamp = obj.getString("connected_timestamp");
            FileOutputStream file;
            file = this.ownerContext.openFileOutput(PsiphonConstants.LAST_CONNECTED_FILENAME, Context.MODE_PRIVATE);
            file.write(connected_timestamp.getBytes());
            file.close();
        }
        catch (IOException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToStoreLastConnected, MyLog.Sensitivity.NOT_SENSITIVE, e);
            // Proceed, even if file saving fails
        }
        catch(JSONException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseLastConnected, MyLog.Sensitivity.NOT_SENSITIVE, e);
            // Proceed if parsing response fails

        }
    }

    /**
     * Make a 'status' request to the server.
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doStatusRequest(
            boolean connected, 
            Map<String, Integer> pageViewEntries, 
            Map<String, Integer> httpsRequestEntries,
            Number bytesTransferred) 
        throws PsiphonServerInterfaceException
    {
        byte[] requestBody = null;
        
        try
        {
            JSONObject stats = new JSONObject();
            
            stats.put("bytes_transferred", bytesTransferred);
            MyLog.d("BYTES: " + bytesTransferred);
            
            JSONArray pageViews = new JSONArray();
            for (Map.Entry<String, Integer> pageViewEntry : pageViewEntries.entrySet())
            {
                JSONObject entry = new JSONObject();
                entry.put("page", pageViewEntry.getKey());
                entry.put("count", pageViewEntry.getValue());
    
                pageViews.put(entry);

                MyLog.d("PAGEVIEW: " + entry.toString());
            }
            stats.put("page_views", pageViews);

            JSONArray httpsRequests = new JSONArray();
            for (Map.Entry<String, Integer> httpsRequestEntry : httpsRequestEntries.entrySet())
            {
                JSONObject entry = new JSONObject();
                entry.put("domain", httpsRequestEntry.getKey());
                entry.put("count", httpsRequestEntry.getValue());
    
                httpsRequests.put(entry);

                MyLog.d("HTTPSREQUEST: " + entry.toString());
            }
            stats.put("https_requests", httpsRequests);

            requestBody = stats.toString().getBytes();
        } 
        catch (JSONException e)
        {
            // We will log and allow the request (and application) to continue.
            MyLog.d("Stats JSON failed", e);
        }        
        
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("session_id", this.serverSessionID));
        extraParams.add(Pair.create("connected", connected ? "1" : "0"));

        List<Pair<String,String>> additionalHeaders = new ArrayList<Pair<String,String>>();
        additionalHeaders.add(Pair.create("Content-Type", "application/json"));
        
        if (connected)
        {
            String url = getRequestURL("status", extraParams);
            makeAbortableProxiedPsiphonRequest(url, additionalHeaders, requestBody);            
        }
        else
        {
            // The final status request is not abortable and will fail over
            // to non-tunnel HTTPS and alternate ports. This is to maximize
            // our chance of getting stats for session duration.
            
            String urls[] = getRequestURLsWithFailover("status", extraParams);
            makePsiphonRequestWithFailover(urls, additionalHeaders, requestBody);
        }
    }

    synchronized public void doSpeedRequest(String operation, String info, Integer milliseconds, Integer size) 
        throws PsiphonServerInterfaceException
    {
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("session_id", this.serverSessionID));
        extraParams.add(Pair.create("operation", operation));
        extraParams.add(Pair.create("info", info));
        extraParams.add(Pair.create("milliseconds", milliseconds.toString()));
        extraParams.add(Pair.create("size", size.toString()));
        
        String url = getRequestURL("speed", extraParams);
        
        makeAbortableProxiedPsiphonRequest(url);
    }

    /**
     * Make the 'upgrade' request to the server. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public byte[] doUpgradeDownloadRequest() 
        throws PsiphonServerInterfaceException
    {
        String url = getRequestURL("download", null);
        
        return makeAbortableProxiedPsiphonRequest(url);
    }
    
    synchronized public void doFailedRequest(String error) 
        throws PsiphonServerInterfaceException
    {
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("error_code", error));
        
        String url = getRequestURL("failed", extraParams);
        
        makeAbortableProxiedPsiphonRequest(url);
    }

    /**
     * Make the 'feedback' request to the server. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doFeedbackRequest(String feedbackData) 
        throws PsiphonServerInterfaceException
    {
        if(getCurrentServerEntry() == null)
        {
            throw new PsiphonServerInterfaceException();
        }
        // NOTE: feedbackData is not being validated here
        byte[] requestBody = feedbackData.getBytes();

        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();

        extraParams.add(Pair.create("session_id", PsiphonData.getPsiphonData().getTunnelSessionID()));

        List<Pair<String,String>> additionalHeaders = new ArrayList<Pair<String,String>>();
        additionalHeaders.add(Pair.create("Content-Type", "application/json"));

        String urls[] = getRequestURLsWithFailover("feedback", extraParams);
        makePsiphonRequestWithFailover(urls, additionalHeaders, requestBody);
    }

    synchronized public void fetchRemoteServerList()
        throws PsiphonServerInterfaceException
    {
        // NOTE: Running this at the start of every session may be enabling
        // a monitor/probe attack that identifies outbound requests to S3,
        // checks that the (HTTPS) response is of a certain size, and then
        // monitors the host for connections to Psiphon nodes.

        // Get remote server entries from known S3 bucket; the bucket ID
        // is in the path component of the URL and the request is HTTPS,
        // so this request may be difficult to block without blocking all
        // of a valuable service.

        if (URLUtil.isValidUrl(EmbeddedValues.REMOTE_SERVER_LIST_URL))
        {
            // After at least one failed connection attempt, and no more than once
            // per few hours (if successful), or not more than once per few minutes
            // (if unsuccessful), check for a new remote server list.
            long nextFetchRemoteServerList = PsiphonData.getPsiphonData().getNextFetchRemoteServerList();
            if ((nextFetchRemoteServerList != -1) &&
                (nextFetchRemoteServerList > SystemClock.elapsedRealtime()))
            {
                return;
            }
            
            // TODO: move to makeDirectWebRequest? All non-tunneled requests
            // could/should check and wait for network connectivity. However, in this
            // case we want the network connectivity check to happen before the 
            // setNextFetchRemoteServerList value is calculated.
            boolean printedWaitingMessage = false;
            while (!Utils.hasNetworkConnectivity(ownerContext))
            {
                if (!printedWaitingMessage)
                {
                    MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);
                    printedWaitingMessage = true;
                }

                if (stopped)
                {
                    return;
                }
                try
                {
                    // Sleep 1 second before checking again
                    Thread.sleep(1000);
                }
                catch (InterruptedException e)
                {
                    return;
                }
            }
            
            PsiphonData.getPsiphonData().setNextFetchRemoteServerList(
                    SystemClock.elapsedRealtime() + 1000 * PsiphonConstants.SECONDS_BETWEEN_UNSUCCESSFUL_REMOTE_SERVER_LIST_FETCH);
            
            try
            {
                // TODO: failure is logged by the caller; right now the caller can't log
                // the attempt since the caller doesn't know that/when a fetch will happen.
                MyLog.v(R.string.fetch_remote_server_list, MyLog.Sensitivity.NOT_SENSITIVE);
                
                byte[] response = makeDirectWebRequest(EmbeddedValues.REMOTE_SERVER_LIST_URL);
    
                PsiphonData.getPsiphonData().setNextFetchRemoteServerList(
                        SystemClock.elapsedRealtime() + 1000 * PsiphonConstants.SECONDS_BETWEEN_SUCCESSFUL_REMOTE_SERVER_LIST_FETCH);
                
                String serverList = ServerEntryAuth.validateAndExtractServerList(new String(response));
    
                shuffleAndAddServerEntries(serverList.split("\n"), false);
                saveServerEntries();
            }
            catch (ServerEntryAuthException e)
            {
                MyLog.w(R.string.ServerInterface_InvalidRemoteServerList, MyLog.Sensitivity.NOT_SENSITIVE, e);
                throw new PsiphonServerInterfaceException(e);            
            } 
            catch (JSONException e)
            {
                MyLog.w(R.string.ServerInterface_InvalidRemoteServerList, MyLog.Sensitivity.NOT_SENSITIVE, e);
                throw new PsiphonServerInterfaceException(e);            
            }
        }
    }
    
    /**
     * Helper function for constructing request URLs. The request parameters it
     * supplies are: client_session_id, propagation_channel_id, sponsor_id, 
     * client_version, server_secret.
     * Any additional parameters must be provided in extraParams.
     * @param path  The path for the request; this is typically the name of the 
     *              request command; e.g. "connected". Do not use a leading slash.
     * @param extraParams  Additional parameters that should be included in the 
     *                     request. Can be null. The parameters values *must not*
     *                     be already URL-encoded. Note that this is a List of 
     *                     Pairs rather than a Map because there may be entries
     *                     with duplicate "keys".
     * @return  The full URL for the request.
     */
    private String getRequestURL(
            String path,
            List<Pair<String,String>> extraParams)
    {
        return getRequestURL(-1, path, extraParams);
    }

    /**
     * Helper function for constructing request URLs. The request parameters it
     * supplies are: client_session_id, propagation_channel_id, sponsor_id, 
     * client_version, server_secret.
     * Any additional parameters must be provided in extraParams.
     * @param webServerPort  -1: use server entry port 
     *              other value: use specified value
     * @param path  The path for the request; this is typically the name of the 
     *              request command; e.g. "connected". Do not use a leading slash.
     * @param extraParams  Additional parameters that should be included in the 
     *                     request. Can be null. The parameters values *must not*
     *                     be already URL-encoded. Note that this is a List of 
     *                     Pairs rather than a Map because there may be entries
     *                     with duplicate "keys".
     * @return  The full URL for the request.
     */
    private String getRequestURL(
                    int webServerPort,
                    String path,
                    List<Pair<String,String>> extraParams)
    {
        ServerEntry serverEntry = getCurrentServerEntry();
        String clientSessionID = getCurrentClientSessionID();
        
        StringBuilder url = new StringBuilder();
        String clientPlatform = PsiphonConstants.PLATFORM;
        
        // Detect if device is rooted and append to the client_platform string
        if (Utils.isRooted())
        {
            clientPlatform += PsiphonConstants.ROOTED;
        }
        
        url.append("https://").append(serverEntry.ipAddress)
           .append(":").append(webServerPort == -1 ? serverEntry.webServerPort : webServerPort)
           .append("/").append(path)
           .append("?client_session_id=").append(Utils.urlEncode(clientSessionID))
           .append("&server_secret=").append(Utils.urlEncode(serverEntry.webServerSecret))
           .append("&propagation_channel_id=").append(Utils.urlEncode(EmbeddedValues.PROPAGATION_CHANNEL_ID))
           .append("&sponsor_id=").append(Utils.urlEncode(EmbeddedValues.SPONSOR_ID))
           .append("&client_version=").append(Utils.urlEncode(EmbeddedValues.CLIENT_VERSION))
           .append("&relay_protocol=").append(Utils.urlEncode(PsiphonData.getPsiphonData().getTunnelRelayProtocol()))
           .append("&client_platform=").append(Utils.urlEncode(clientPlatform))
           .append("&tunnel_whole_device=").append(Utils.urlEncode(PsiphonData.getPsiphonData().getTunnelWholeDevice() ? "1" : "0"));

        if (extraParams != null)
        {
            for (Pair<String,String> param : extraParams) 
            {
                String paramKey = param.first;
                String paramValue = param.second;
                url.append("&").append(paramKey).append("=").append(Utils.urlEncode(paramValue));
            }
        }

        return url.toString();
    }

    private String[] getRequestURLsWithFailover(
            String path,
            List<Pair<String,String>> extraParams)
    {
        String urls[] = new String[2];
        urls[0] = getRequestURL(path, extraParams);
        urls[1] = getRequestURL(PsiphonConstants.DEFAULT_WEB_SERVER_PORT, path, extraParams);
        return urls;
    }

    private byte[] makeAbortableProxiedPsiphonRequest(String url)
            throws PsiphonServerInterfaceException
    {
        return makeAbortableProxiedPsiphonRequest(url, null, null);
    }
    
    private byte[] makePsiphonRequestWithFailover(
            String[] urls,
            List<Pair<String,String>> additionalHeaders,
            byte[] body) 
        throws PsiphonServerInterfaceException
    {
        // This request won't abort and will fail over to direct requests,
        // to multiple ports, when tunnel is down            

        PsiphonServerInterfaceException lastError;
        
        try
        {
            // Try tunneled request on first port (first url)
            
            return makeProxiedPsiphonRequest(false, urls[0], additionalHeaders, body);
        }
        catch (PsiphonServerInterfaceException e1)
        {
            lastError = e1;

            // NOTE: deliberately ignoring this.stopped and adding new non-abortable requests

            // Try direct non-tunnel request

            for (String url : urls)
            {
                try
                {
                    // Psiphon web request: authenticate the web server using the embedded certificate.
                    String psiphonServerCertificate = getCurrentServerEntry().webServerCertificate;

                    return makeRequest(false, false, psiphonServerCertificate, url, additionalHeaders, body);
                }
                catch (PsiphonServerInterfaceException e2)
                {
                    lastError = e2;

                    // Try next port/url...
                }
            }
            
            throw lastError;
        }
    }
    
    private byte[] makeProxiedPsiphonRequest(
            boolean canAbort,
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body) 
        throws PsiphonServerInterfaceException
    {
        // Psiphon web request: authenticate the web server using the embedded certificate.
        String psiphonServerCertificate = getCurrentServerEntry().webServerCertificate;
        
        // Psiphon web request: go through the tunnel to ensure this request is
        // transmitted even in the case where SSL protocol is blocked.
        boolean useLocalProxy = true;

        return makeRequest(
                canAbort,
                useLocalProxy,
                psiphonServerCertificate,
                url,
                additionalHeaders,
                body);
    }

    private byte[] makeAbortableProxiedPsiphonRequest(            
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body) 
        throws PsiphonServerInterfaceException
    {
        return makeProxiedPsiphonRequest(true, url, additionalHeaders, body);
    }

    private byte[] makeDirectWebRequest(String url)
            throws PsiphonServerInterfaceException
    {
        return makeRequest(true, false, null, url, null, null);
    }

    private class CustomTrustManager implements X509TrustManager
    {
        private X509Certificate expectedServerCertificate;
        
        // TODO: pre-validate cert in addServerEntry so this won't throw?
        CustomTrustManager(String serverCertificate) throws CertificateException
        {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            byte[] decodedServerCertificate = Utils.Base64.decode(serverCertificate);
            this.expectedServerCertificate = (X509Certificate)factory.generateCertificate(
                    new ByteArrayInputStream(decodedServerCertificate));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException
        {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException
        {
            if (chain.length != 1 ||
                !Arrays.equals(
                        chain[0].getTBSCertificate(),
                        this.expectedServerCertificate.getTBSCertificate()))
            {
                throw new CertificateException();
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }
    }

    private class CustomSSLSocketFactory extends SSLSocketFactory
    {
        SSLContext sslContext;
        TrustManager[] trustManager;
        AbstractVerifier hostnameVerifier;

        CustomSSLSocketFactory(String serverCertificate)
                throws KeyManagementException,
                       UnrecoverableKeyException,
                       NoSuchAlgorithmException,
                       KeyStoreException,
                       CertificateException
        {
            super(null);

            trustManager = new TrustManager[] { new CustomTrustManager(serverCertificate) };
            hostnameVerifier = new AllowAllHostnameVerifier();
            setHostnameVerifier(hostnameVerifier);

            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManager, new SecureRandom());            
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws IOException, UnknownHostException
        {
            return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket()
                throws IOException
        {
            return sslContext.getSocketFactory().createSocket();
        }
    }

    private byte[] makeRequest(
            boolean canAbort,
            boolean useLocalProxy,
            String serverCertificate,
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body) 
        throws PsiphonServerInterfaceException
    {    
        HttpRequestBase request = null;
        
        try
        {
            HttpParams params = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(params, PsiphonConstants.HTTPS_REQUEST_TIMEOUT);
            HttpConnectionParams.setSoTimeout(params, PsiphonConstants.HTTPS_REQUEST_TIMEOUT);
            
            HttpHost httpproxy;
            if (useLocalProxy)
            {
                httpproxy = new HttpHost("127.0.0.1", PsiphonData.getPsiphonData().getHttpProxyPort());
                params.setParameter(ConnRoutePNames.DEFAULT_PROXY, httpproxy);
            }

            DefaultHttpClient client = null;

            // If a specific web server certificate is provided, expect
            // exactly that certificate. Otherwise, accept a server
            // certificate signed by a CA in the default trust manager.

            CustomSSLSocketFactory sslSocketFactory = null;
            SchemeRegistry registry = null;
            ClientConnectionManager connManager = null;
            if (serverCertificate != null)
            {
                sslSocketFactory = new CustomSSLSocketFactory(serverCertificate);

                registry = new SchemeRegistry();
                registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
                registry.register(new Scheme("https", sslSocketFactory, 443));
    
                connManager = new SingleClientConnManager(params, registry);
                
                client = new DefaultHttpClient(connManager, params);
            }
            else
            {
                client = new DefaultHttpClient(params);
            }

            if (body != null)
            {
                HttpPost post = new HttpPost(url);
                post.setEntity(new ByteArrayEntity(body));
                request = post;
            }
            else
            {
                request = new HttpGet(url);
            }
            
            if (canAbort)
            {
                synchronized(this.outstandingRequests)
                {
                    this.outstandingRequests.add(request);
                }
            }
            
            if (additionalHeaders != null)
            {
                for (Pair<String,String> header : additionalHeaders)
                {
                    request.addHeader(header.first, header.second);
                }
            }

            HttpResponse response = client.execute(request);
            
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != HttpStatus.SC_OK)
            {
                throw new PsiphonServerInterfaceException(
                        this.ownerContext.getString(R.string.ServerInterface_HTTPSRequestFailed) + statusCode);
            }

            HttpEntity responseEntity = response.getEntity();
            
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            
            if (responseEntity != null)
            {
                InputStream instream = responseEntity.getContent();
                byte[] buffer = new byte[4096];
                int len = -1;
                while ((len = instream.read(buffer)) != -1)
                {
                    if (this.stopped) 
                    {
                        throw new PsiphonServerInterfaceException(
                                this.ownerContext.getString(R.string.ServerInterface_StopRequested));
                    }
                    
                    responseBody.write(buffer, 0, len);
                }
            }
            
            return responseBody.toByteArray();
        } 
        catch (ClientProtocolException e)
        {
            throw new PsiphonServerInterfaceException(e);
        } 
        catch (IOException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (KeyManagementException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (UnrecoverableKeyException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (KeyStoreException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (CertificateException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }
        catch (IllegalArgumentException e)
        {
            /* In some cases we have found the http client.execute method to throw a IllegalArgumentException after
               the tunnel has gone away: 
                E/AndroidRuntime(25874): FATAL EXCEPTION: Thread-2193
                E/AndroidRuntime(25874): java.lang.IllegalArgumentException: Socket is closed.
                E/AndroidRuntime(25874):        at org.apache.http.conn.ssl.SSLSocketFactory.isSecure(SSLSocketFactory.java:360)
                E/AndroidRuntime(25874):        at org.apache.http.impl.conn.DefaultClientConnectionOperator.updateSecureConnection(DefaultClientConnectionOperator.java:
                237)
                E/AndroidRuntime(25874):        at org.apache.http.impl.conn.AbstractPoolEntry.layerProtocol(AbstractPoolEntry.java:302)
                E/AndroidRuntime(25874):        at org.apache.http.impl.conn.AbstractPooledConnAdapter.layerProtocol(AbstractPooledConnAdapter.java:146)
                E/AndroidRuntime(25874):        at org.apache.http.impl.client.DefaultRequestDirector.establishRoute(DefaultRequestDirector.java:654)
                E/AndroidRuntime(25874):        at org.apache.http.impl.client.DefaultRequestDirector.execute(DefaultRequestDirector.java:370)
                E/AndroidRuntime(25874):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:555)
                E/AndroidRuntime(25874):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:487)
                E/AndroidRuntime(25874):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:465)
            */
            throw new PsiphonServerInterfaceException(e);
        }
        catch (NullPointerException e)
        {
            /* In some cases we have found the http client.execute method to throw a NullPointerException after
               the tunnel has gone away: 
                E/AndroidRuntime(23042): FATAL EXCEPTION: Thread-2246
                E/AndroidRuntime(23042): java.lang.NullPointerException
                E/AndroidRuntime(23042):        at org.apache.http.impl.conn.AbstractPoolEntry.layerProtocol(AbstractPoolEntry.java:305)
                E/AndroidRuntime(23042):        at org.apache.http.impl.conn.AbstractPooledConnAdapter.layerProtocol(AbstractPooledConnAdapter.java:146)
                E/AndroidRuntime(23042):        at org.apache.http.impl.client.DefaultRequestDirector.establishRoute(DefaultRequestDirector.java:654)
                E/AndroidRuntime(23042):        at org.apache.http.impl.client.DefaultRequestDirector.execute(DefaultRequestDirector.java:370)
                E/AndroidRuntime(23042):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:555)
                E/AndroidRuntime(23042):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:487)
                E/AndroidRuntime(23042):        at org.apache.http.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:465)
            */
            throw new PsiphonServerInterfaceException(e);
        }
        finally
        {
            if (request != null && canAbort)
            {
                synchronized(this.outstandingRequests)
                {
                    // Harmless if the request was successful. Necessary clean-up if
                    // the request was interrupted.
                    if (!request.isAborted()) request.abort();
                    
                    this.outstandingRequests.remove(request);
                }
            }
        }
    }

    private void shuffleAndAddServerEntries(
            String[] encodedServerEntries,
            boolean isEmbedded)
        throws JSONException
    {
        // Shuffling assists in load balancing when there
        // are multiple embedded/discovery servers at once
        
        List<String> encodedEntryList = Arrays.asList(encodedServerEntries);
        // NOTE: this changes the order of the input array, encodedServerEntries
        Collections.shuffle(encodedEntryList);
        for (String entry : encodedEntryList)
        {
            insertServerEntry(entry, isEmbedded);
        }
    }
    
    private ServerEntry decodeServerEntry(String encodedServerEntry)
            throws JSONException
    {
        String serverEntry = new String(Utils.hexStringToByteArray(encodedServerEntry));

        // Skip past legacy format (4 space delimited fields) and just parse the JSON config
        int jsonIndex = 0;
        for (int i = 0; i < 4; i++)
        {
            jsonIndex = serverEntry.indexOf(' ', jsonIndex) + 1;
        }

        JSONObject obj = new JSONObject(serverEntry.substring(jsonIndex));
        ServerEntry newEntry = new ServerEntry();
        newEntry.encodedEntry = encodedServerEntry;
        newEntry.ipAddress = obj.getString("ipAddress");
        newEntry.webServerPort = obj.getInt("webServerPort");
        newEntry.webServerSecret = obj.getString("webServerSecret");
        newEntry.webServerCertificate = obj.getString("webServerCertificate");
        newEntry.sshPort = obj.getInt("sshPort");
        newEntry.sshUsername = obj.getString("sshUsername");
        newEntry.sshPassword = obj.getString("sshPassword");
        newEntry.sshHostKey = obj.getString("sshHostKey");
        newEntry.sshObfuscatedPort = obj.getInt("sshObfuscatedPort");
        newEntry.sshObfuscatedKey = obj.getString("sshObfuscatedKey");
        
        newEntry.capabilities = new ArrayList<String>(); 
        if (obj.has("capabilities"))
        {
            JSONArray caps = obj.getJSONArray("capabilities");
            for (int i = 0; i < caps.length(); i++)
            {
                newEntry.capabilities.add(caps.getString(i));
            }
        }
        else
        {
            // At the time of introduction of the server capabilities feature
            // these are the default capabilities possessed by all servers.
            newEntry.capabilities.add("OSSH");
            newEntry.capabilities.add("SSH");
            newEntry.capabilities.add("VPN");
            newEntry.capabilities.add("handshake");
        }
        
        return newEntry;
    }
    
    private void appendServerEntry(String encodedServerEntry) 
        throws JSONException
    {
        // Simply append server entry at end
        
        ServerEntry newEntry = decodeServerEntry(encodedServerEntry);

        // Check if there's already an entry for this server
        for (int i = 0; i < this.serverEntries.size(); i++)
        {
            if (0 == newEntry.ipAddress.compareTo(serverEntries.get(i).ipAddress))
            {
                // This shouldn't be used on an existing list
                assert(false);
                break;
            }
        }
        
        this.serverEntries.add(newEntry);
    }

    private void insertServerEntry(String encodedServerEntry, boolean isEmbedded)
        throws JSONException
    {
        // Insert server entry according to embedded/discovery priority logic

        ServerEntry newEntry = decodeServerEntry(encodedServerEntry);

        // Check if there's already an entry for this server
        int existingIndex = -1;
        for (int i = 0; i < this.serverEntries.size(); i++)
        {
            if (0 == newEntry.ipAddress.compareTo(serverEntries.get(i).ipAddress))
            {
                existingIndex = i;
                break;
            }
        }
        
        if (existingIndex != -1)
        {
            // Only replace existing entries in the discovery case
            if (!isEmbedded)
            {
                serverEntries.remove(existingIndex);
                serverEntries.add(existingIndex, newEntry);
            }
        }
        else
        {
            // New entries are added in the second position, to preserve
            // the first position for the "current" working server
            int index = this.serverEntries.size() > 0 ? 1 : 0;
            this.serverEntries.add(index, newEntry);
        }
    }
    
    private void saveServerEntries()
    {
        synchronized(PsiphonData.getPsiphonData().serverEntryFileLock)
        {
            try
            {
                FileOutputStream file;
                file = this.ownerContext.openFileOutput(PsiphonConstants.SERVER_ENTRY_FILENAME, Context.MODE_PRIVATE);
                JSONObject obj = new JSONObject();
                JSONArray array = new JSONArray();
                for (ServerEntry serverEntry : this.serverEntries)
                {
                    array.put(serverEntry.encodedEntry);
                }
                obj.put("serverEntries", array);
                file.write(obj.toString().getBytes());
                file.close();
            }
            catch (JSONException e)
            {
                MyLog.w(R.string.ServerInterface_FailedToCreateServerEntries, MyLog.Sensitivity.NOT_SENSITIVE, e);
                // Proceed, even if file saving fails
            } 
            catch (IOException e)
            {
                MyLog.w(R.string.ServerInterface_FailedToStoreServerEntries, MyLog.Sensitivity.NOT_SENSITIVE, e);
                // Proceed, even if file saving fails
            }
        }
    }

    public synchronized void moveEntriesToFront(ArrayList<ServerEntry> reorderedServerEntries)
    {
        // Insert entries in input order

        for (int i = reorderedServerEntries.size() - 1; i >= 0; i--)
        {
            ServerEntry reorderedEntry = reorderedServerEntries.get(i);
            // Remove existing entry for server, if present. In the case where
            // the existing entry has different data, we must assume that a
            // discovery has happened that overwrote the data that's being
            // passed in. In that edge case, we just keep the existing entry
            // in its current position.

            boolean existingEntryChanged = false;

            for (int j = 0; j < this.serverEntries.size(); j++)
            {
                ServerEntry existingEntry = this.serverEntries.get(j);

                if (reorderedEntry.ipAddress.equals(existingEntry.ipAddress))
                {
                    // NOTE: depends on encodedEntry representing the entire object
                    if (0 != reorderedEntry.encodedEntry.compareTo(existingEntry.encodedEntry))
                    {
                        existingEntryChanged = true;
                    }
                    else
                    {
                        this.serverEntries.remove(j);
                    }
                    break;
                }
            }

            // Re-insert entry for server in new position

            if (!existingEntryChanged)
            {
                this.serverEntries.add(0, reorderedEntry);
            }
        }

        saveServerEntries();
    }
    
    private final long DEFAULT_STATS_SEND_INTERVAL_MS = 5*60*1000; // 5 mins
    private long statsSendInterval = DEFAULT_STATS_SEND_INTERVAL_MS;
    private long lastStatusSendTimeMS = 0;
    private final int DEFAULT_SEND_MAX_ENTRIES = 1000;
    private int sendMaxEntries = DEFAULT_SEND_MAX_ENTRIES;

    private synchronized void resetPeriodicWork()
    {
        this.statsSendInterval = this.DEFAULT_STATS_SEND_INTERVAL_MS;
        this.lastStatusSendTimeMS = 0;
        this.sendMaxEntries = this.DEFAULT_SEND_MAX_ENTRIES;
    }
    
    /**
     * Call to let the interface to any periodic work or checks that it needs to.
     * The primary example of this "work" is to send stats to the server when
     * a time or size threshold is reached.
     * @param finalCall Should be true if this is the last call -- i.e., if a
     *                  disconnect is about to occur.
     */
    public synchronized void doPeriodicWork(boolean finalCall)
    {
        long now = SystemClock.uptimeMillis();
        
        if (finalCall && PsiphonData.getPsiphonData().getDisplayDataTransferStats())
        {
            PsiphonData.DataTransferStats dataTransferStats = PsiphonData.getPsiphonData().getDataTransferStats();
            
            long totalBytesSent = dataTransferStats.getTotalBytesSent();
            double totalSentCompressionRatio = dataTransferStats.getTotalSentCompressionRatio();
            long totalBytesReceived = dataTransferStats.getTotalBytesReceived();
            double totalReceivedCompressionRatio = dataTransferStats.getTotalReceivedCompressionRatio();
            long elapsedTime = dataTransferStats.getElapsedTime();
                
            MyLog.v(
                    R.string.data_transfer_total_bytes_sent,
                    MyLog.Sensitivity.NOT_SENSITIVE,
                    Utils.byteCountToDisplaySize(totalBytesSent, false),
                    totalSentCompressionRatio);
        
            MyLog.v(
                    R.string.data_transfer_total_bytes_received,
                    MyLog.Sensitivity.NOT_SENSITIVE,
                    Utils.byteCountToDisplaySize(totalBytesReceived, false),
                    totalReceivedCompressionRatio);
        
            MyLog.v(
                    R.string.data_transfer_total_elapsed_time,
                    MyLog.Sensitivity.NOT_SENSITIVE,
                    Utils.elapsedTimeToDisplay(elapsedTime));        
        }
        
        PsiphonData.ReportedStats reportedStats = PsiphonData.getPsiphonData().getReportedStats();

        if (reportedStats != null)
        {
            // On the very first call, this.lastStatusSendTimeMS will be 0, but we
            // don't want to send immediately. So...
            if (this.lastStatusSendTimeMS == 0) this.lastStatusSendTimeMS = now; 
            
            // SystemClock.uptimeMillis() "may get reset occasionally (before it 
            // would otherwise wrap around)".
            if (now < this.lastStatusSendTimeMS) this.lastStatusSendTimeMS = 0;
            
            // If the time or size thresholds have been exceeded, or if we're being 
            // forced to, send the stats.
            if (finalCall
                || (this.lastStatusSendTimeMS + this.statsSendInterval) < now
                || reportedStats.getCount() >= this.sendMaxEntries)
            {
                MyLog.d("Sending stats"+(finalCall?" (final)":""));
                
                try
                {
                    doStatusRequest(
                            !finalCall, 
                            reportedStats.getPageViewEntries(), 
                            reportedStats.getHttpsRequestEntries(), 
                            reportedStats.getBytesTransferred());
                    
                    // Reset thresholds
                    this.lastStatusSendTimeMS = now;
                    this.statsSendInterval = DEFAULT_STATS_SEND_INTERVAL_MS;
                    this.sendMaxEntries = DEFAULT_SEND_MAX_ENTRIES;
                    
                    // Reset stats
                    reportedStats.clear();
                } 
                catch (PsiphonServerInterfaceException e)
                {
                    // Status request failed. This is fairly common. 
                    // We'll back off the thresholds and try again later.
                    this.statsSendInterval += DEFAULT_STATS_SEND_INTERVAL_MS;
                    this.sendMaxEntries += DEFAULT_SEND_MAX_ENTRIES;
                    
                    MyLog.d("Sending stats FAILED"+(finalCall?" (final)":""));
                }
            }
        }
    }

    public boolean isUpgradeAvailable()
    {
        return this.upgradeClientVersion.length() > 0;
    }
}
