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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.psiphon3.Utils.MyLog;

import android.content.Context;
import android.os.SystemClock;
import android.util.Pair;


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
    }
    
    private Context ownerContext;
    private ArrayList<ServerEntry> serverEntries = new ArrayList<ServerEntry>();
    private String upgradeClientVersion;
    private String speedTestURL;
    private String clientSessionID; // access via getCurrentClientSessionID -- even internally
    private String serverSessionID;

    ServerInterface(Context context)
    {
        this.ownerContext = context;

        // Load persistent server entries, then add embedded entries
        
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
            JSONArray serverEntries = obj.getJSONArray("serverEntries");
    
            for (int i = 0; i < serverEntries.length(); i++)
            {
                addServerEntry(serverEntries.getString(i), false);
            }
        }
        catch (FileNotFoundException e)
        {
            // pass
        }
        catch (IOException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToReadStoredServerEntries, e);
            // skip loading persistent server entries
        } 
        catch (JSONException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseStoredServerEntries, e);
            // skip loading persistent server entries
        }
        
        for (String encodedEntry : EmbeddedValues.EMBEDDED_SERVER_LIST.split("\n"))
        {
            addServerEntry(encodedEntry, true);
        }
    }
    
    synchronized ServerEntry getCurrentServerEntry()
    {
        if (this.serverEntries.size() > 0)
        {
            return this.serverEntries.get(0).clone();
        }

        return null;
    }
    
    synchronized void markCurrentServerFailed()
    {
        if (this.serverEntries.size() > 0)
        {
            // Move to end of list for last chance retry
            this.serverEntries.add(this.serverEntries.remove(0));
            
            // Save the new server order
            saveServerEntries();
        }
    }
    
    synchronized private void generateNewCurrentClientSessionID()
    {
        byte[] clientSessionIdBytes = Utils.generateInsecureRandomBytes(PsiphonConstants.CLIENT_SESSION_ID_SIZE_IN_BYTES);
        this.clientSessionID = Utils.byteArrayToHexString(clientSessionIdBytes);
        MyLog.d("generated new current client session ID");
    }
    
    synchronized private String getCurrentClientSessionID()
    {
        if (this.clientSessionID == null)
        {
            generateNewCurrentClientSessionID();
        }
        
        return this.clientSessionID;
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
        try
        {
            List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
            for (ServerEntry entry : this.serverEntries)
            {
                extraParams.add(Pair.create("known_server", entry.ipAddress));
            }
            
            String url = getRequestURL("handshake", extraParams);
            
            byte[] response = makeRequest(url);

            final String JSON_CONFIG_PREFIX = "Config: ";
            for (String line : new String(response).split("\n"))
            {
                if (line.indexOf(JSON_CONFIG_PREFIX) == 0)
                {
                    JSONObject obj = new JSONObject(line.substring(JSON_CONFIG_PREFIX.length()));

                    JSONArray homepages = obj.getJSONArray("homepages");
                    for (int i = 0; i < homepages.length(); i++)
                    {
                    	PsiphonData.getPsiphonData().addHomePage(homepages.getString(i));
                    }

                    this.upgradeClientVersion = obj.getString("upgrade_client_version");

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
                    PsiphonData.getPsiphonData().getStats().setRegexes(pageViewRegexes, httpsRequestRegexes);

                    this.speedTestURL = obj.getString("speed_test_url");

                    JSONArray encoded_server_list = obj.getJSONArray("encoded_server_list");
                    for (int i = 0; i < encoded_server_list.length(); i++)
                    {
                        addServerEntry(encoded_server_list.getString(i), false);
                    }
                    
                    // We only support SSH, so this is our server session ID.
                    this.serverSessionID = obj.getString("ssh_session_id");
                }
            }
        }
        catch (JSONException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToParseHandshake, e);
            throw new PsiphonServerInterfaceException(e);
        }
    }
    
    /**
     * Make the 'connected' request to the server. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doConnectedRequest() 
        throws PsiphonServerInterfaceException
    {
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("session_id", this.serverSessionID));
        
        String url = getRequestURL("connected", extraParams);
        
        makeRequest(url);
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

        String url = getRequestURL("status", extraParams);
        
        List<Pair<String,String>> additionalHeaders = new ArrayList<Pair<String,String>>();
        additionalHeaders.add(Pair.create("Content-Type", "application/json"));
        
        makeRequest(url, additionalHeaders, requestBody);
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
        
        makeRequest(url);
    }

    /**
     * Make the 'upgrade' request to the server. 
     * @throws PsiphonServerInterfaceException
     */
    synchronized public byte[] doUpgradeDownloadRequest() 
        throws PsiphonServerInterfaceException
    {
        String url = getRequestURL("download", null);
        
        return makeRequest(url);
    }
    
    synchronized public void doFailedRequest(String error) 
        throws PsiphonServerInterfaceException
    {
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("error_code", error));
        
        String url = getRequestURL("failed", extraParams);
        
        makeRequest(url);
    }

    private class CustomTrustManager implements X509TrustManager
    {
        private X509Certificate expectedServerCertificate;
        
        // TODO: parse cert in addServerEntry?
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
        ServerEntry serverEntry = getCurrentServerEntry();
        String clientSessionID = getCurrentClientSessionID();
        
        StringBuilder url = new StringBuilder();
        
        url.append("https://").append(serverEntry.ipAddress)
           .append(":").append(serverEntry.webServerPort)
           .append("/").append(path)
           .append("?client_session_id=").append(Utils.urlEncode(clientSessionID))
           .append("&server_secret=").append(Utils.urlEncode(serverEntry.webServerSecret))
           .append("&propagation_channel_id=").append(Utils.urlEncode(EmbeddedValues.PROPAGATION_CHANNEL_ID))
           .append("&sponsor_id=").append(Utils.urlEncode(EmbeddedValues.SPONSOR_ID))
           .append("&client_version=").append(Utils.urlEncode(EmbeddedValues.CLIENT_VERSION))
           .append("&relay_protocol=").append(Utils.urlEncode(PsiphonConstants.RELAY_PROTOCOL))
           .append("&client_platform=").append(Utils.urlEncode(PsiphonConstants.PLATFORM));
        
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

    private byte[] makeRequest(String url) 
            throws PsiphonServerInterfaceException
    {
        return makeRequest(url, null, null);
    }
    
    private byte[] makeRequest(String url, List<Pair<String,String>> additionalHeaders, byte[] body) 
        throws PsiphonServerInterfaceException
    {
        String serverCertificate = getCurrentServerEntry().webServerCertificate;
        
        SSLContext context;
        try
        {
            context = SSLContext.getInstance("TLS");
            context.init(
                    null,
                    new TrustManager[] { new CustomTrustManager(serverCertificate) },
                    new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier(new AllowAllHostnameVerifier());
            HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(PsiphonConstants.HTTPS_REQUEST_TIMEOUT);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            
            if (additionalHeaders != null)
            {
                for (Pair<String,String> header : additionalHeaders)
                {
                    conn.addRequestProperty(header.first, header.second);
                }
            }
    
            if (body == null)
            {
                conn.setRequestMethod("GET");                
            }
            else
            {
                conn.setRequestMethod("POST");
                conn.addRequestProperty(
                        "Content-Length", 
                        Integer.toString(body.length));
                DataOutputStream writer = new DataOutputStream(conn.getOutputStream());
                writer.write(body);
                writer.flush();
                writer.close();           
            }
            
            if (conn.getResponseCode() != HttpsURLConnection.HTTP_OK)
            {
                throw new PsiphonServerInterfaceException("HTTPS request failed with error: " + conn.getResponseCode());
            }
    
            ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
            
            byte[] buffer = new byte[4096];
            int len = -1;
            while ((len = conn.getInputStream().read(buffer)) != -1)
            {
                responseBody.write(buffer, 0, len);
            }
            
            return responseBody.toByteArray();
        } 
        catch (NoSuchAlgorithmException e)
        {
            throw new PsiphonServerInterfaceException(e);
        } 
        catch (GeneralSecurityException e)
        {
            throw new PsiphonServerInterfaceException(e);
        } 
        catch (IOException e)
        {
            throw new PsiphonServerInterfaceException(e);
        }        
    }
    
    private void addServerEntry(
            String encodedServerEntry,
            boolean isEmbedded)
    {
        try
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

            // Save the updated server entries
            saveServerEntries();
        }
        catch (JSONException e)
        {
            // Ignore this server entry on parse error
            MyLog.w(R.string.ServerInterface_HandshakeJSONParseFailed, e);
            return;
        }
    }
    
    private void saveServerEntries()
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
            MyLog.w(R.string.ServerInterface_FailedToCreateServerEntries, e);
            // Proceed, even if file saving fails
        } 
        catch (IOException e)
        {
            MyLog.w(R.string.ServerInterface_FailedToStoreServerEntries, e);
            // Proceed, even if file saving fails
        }
    }

    private final long DEFAULT_STATS_SEND_INTERVAL_MS = 3*60*1000; // 30 mins
    private long statsSendInterval = DEFAULT_STATS_SEND_INTERVAL_MS;
    private long lastStatusSendTimeMS = 0;
    private final int DEFAULT_SEND_MAX_ENTRIES = 1000;
    private int sendMaxEntries = DEFAULT_SEND_MAX_ENTRIES;
    
    /**
     * Call to let the interface to any periodic work or checks that it needs to.
     * The primary example of this "work" is to send stats to the server when
     * a time or size threshold is reached.
     * @param finalCall Should be true if this is the last call -- i.e., if a
     *                  disconnect is about to occur.
     */
    public synchronized void doPeriodicWork(boolean finalCall)
    {
    	PsiphonData.Stats stats = PsiphonData.getPsiphonData().getStats();
    	
        long now = SystemClock.uptimeMillis();
        
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
            || stats.getCount() >= this.sendMaxEntries)
        {
            MyLog.d("Sending stats"+(finalCall?" (final)":""));
            
            try
            {
                doStatusRequest(
                        !finalCall, 
                        stats.getPageViewEntries(), 
                        stats.getHttpsRequestEntries(), 
                        stats.getBytesTransferred());
                
                // Reset thresholds
                this.lastStatusSendTimeMS = now;
                this.statsSendInterval = DEFAULT_STATS_SEND_INTERVAL_MS;
                this.sendMaxEntries = DEFAULT_SEND_MAX_ENTRIES;
                
                // Reset stats
                stats.clear();
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

    public boolean isUpgradeAvailable()
    {
        return !this.upgradeClientVersion.isEmpty();
    } 
}
