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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGetHC4;
import org.apache.http.client.methods.HttpPostHC4;
import org.apache.http.client.methods.HttpPutHC4;
import org.apache.http.client.methods.HttpRequestBaseHC4;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.BasicCredentialsProviderHC4;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.impl.conn.DefaultSchemePortResolver;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.protocol.HttpContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xbill.DNS.Address;
import org.xbill.DNS.PsiphonState;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Pair;
import android.webkit.URLUtil;

import com.psiphon3.psiphonlibrary.AuthenticatedDataPackage.AuthenticatedDataPackageException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.psiphonlibrary.Utils.RequestTimeoutAbort;


// We address this warning in Utils.initializeSecureRandom()
@SuppressLint("TrulyRandom")
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
        public String regionCode;
        public int meekServerPort;
        public String meekCookieEncryptionPublicKey;
        public String meekObfuscatedKey;
        public String meekFrontingDomain;
        public String meekFrontingHost;
        public String meekFrontingAddressesRegex;
        public ArrayList<String> meekFrontingAddresses;

        // These are determined while connecting.
        public String connType; // Set to one of PsiphonConstants.RELAY_PROTOCOL_*
        public String front;

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

        public static final String CAPABILITY_HANDSHAKE = "handshake";
        public static final String CAPABILITY_VPN = "VPN";
        public static final String CAPABILITY_OSSH = "OSSH";
        public static final String CAPABILITY_SSH = "SSH";
        public static final String CAPABILITY_FRONTED_MEEK = "FRONTED-MEEK";
        public static final String CAPABILITY_UNFRONTED_MEEK = "UNFRONTED-MEEK";
        public static final String CAPABILITY_UNFRONTED_MEEK_HTTPS = "UNFRONTED-MEEK-HTTPS";

        public static final String REGION_CODE_ANY = "";
        
        public boolean supportsProtocol(String protocol)
        {
            if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_OSSH))
            {
                return hasCapability(CAPABILITY_OSSH);
            }
            else if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_OSSH))
            {
                return hasCapability(CAPABILITY_UNFRONTED_MEEK);
            }
            else if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_UNFRONTED_MEEK_HTTPS_OSSH))
            {
                return hasCapability(CAPABILITY_UNFRONTED_MEEK_HTTPS);
            }
            else if (protocol.equals(PsiphonConstants.RELAY_PROTOCOL_FRONTED_MEEK_OSSH))
            {
                return hasCapability(CAPABILITY_FRONTED_MEEK);
            }
            return false;
        }

        public boolean hasCapability(String capability)
        {
            return this.capabilities.contains(capability);
        }

        public boolean hasOneOfTheseCapabilities(List<String> capabilities)
        {
            for (String capability : capabilities)
            {
                if (hasCapability(capability))
                {
                    return true;
                }
            }
            return false;
        }

        public int getPreferredReachablityTestPort()
        {
            if (hasCapability(CAPABILITY_OSSH) ||
                hasCapability(CAPABILITY_FRONTED_MEEK) ||
                hasCapability(CAPABILITY_UNFRONTED_MEEK) ||
                hasCapability(CAPABILITY_UNFRONTED_MEEK_HTTPS))
            {
                return this.sshObfuscatedPort;
            }
            else if (hasCapability(CAPABILITY_SSH))
            {
                return this.sshPort;
            }
            else if (hasCapability(CAPABILITY_HANDSHAKE))
            {
                return this.webServerPort;
            }

            return -1;
        }

        public boolean inRegion(String regionCode)
        {
            if (regionCode.equals(REGION_CODE_ANY))
            {
                return true;
            }

            return regionCode.equals(this.regionCode);
        }
    }

    private final Context ownerContext;
    private final ArrayList<ServerEntry> serverEntries = new ArrayList<ServerEntry>();
    private String upgradeClientVersion;
    private String serverSessionID;
    private int preemptiveReconnectLifetime = 0;
    private ServerEntry currentServerEntry;

    /** Array of all outstanding/ongoing requests. Anything in this array will
     * be aborted when {@link#stop()} is called. */
    private final List<HttpRequestBaseHC4> outstandingRequests = new ArrayList<HttpRequestBaseHC4>();

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
                    if (!EmbeddedValues.IGNORE_NON_EMBEDDED_SERVER_ENTRIES) {
                        appendServerEntry(jsonServerEntries.getString(i));
                    }
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
            catch (OutOfMemoryError e)
            {
                // Some mature client installs have so many server entries they cannot load them without
                // hitting out-of-memory, so they will not benefit from the MAX_SAVED_SERVER_ENTRIES_MEMORY_SIZE
                // limit added to saveServerEntries(). In this case, we simply ignore the saved list. The client
                // will proceed with the embedded list only, and going forward the MEMORY_SIZE limit will be
                // enforced.
            }
        }

        try
        {
            shuffleAndAddServerEntries(
                EmbeddedValues.EMBEDDED_SERVER_LIST, true);
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
                @Override
                public void run()
                {
                    synchronized(outstandingRequests)
                    {
                        for (HttpRequestBaseHC4 request : outstandingRequests)
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

    public boolean isStopped()
    {
        return this.stopped;
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

    synchronized boolean serverWithOneOfTheseCapabilitiesExists(List<String> capabilities)
    {
        for (ServerEntry entry: this.serverEntries)
        {
            if (entry.hasOneOfTheseCapabilities(capabilities))
            {
                return true;
            }
        }

        return false;
    }

    synchronized boolean serverInRegionExists(String regionCode)
    {
        for (ServerEntry entry: this.serverEntries)
        {
            if (entry.inRegion(regionCode))
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

    synchronized public int getPreemptiveReconnectLifetime()
    {
        return this.preemptiveReconnectLifetime;
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

            byte[] response = makeAbortableProxiedPsiphonRequest(PsiphonConstants.HTTPS_REQUEST_LONG_TIMEOUT, url);

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
                        if (!EmbeddedValues.IGNORE_NON_EMBEDDED_SERVER_ENTRIES) {
                            shuffleAndAddServerEntries(entries, false);
                            saveServerEntries();
                        }
                    }

                    // We only support SSH, so this is our server session ID.
                    this.serverSessionID = obj.getString("ssh_session_id");

                    this.preemptiveReconnectLifetime = 0;
                    if (obj.has("preemptive_reconnect_lifetime_milliseconds"))
                    {
                        this.preemptiveReconnectLifetime = obj.getInt("preemptive_reconnect_lifetime_milliseconds");
                    }
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
        String lastConnected = PsiphonConstants.LAST_CONNECTED_NO_VALUE;
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

        // We have observed blank last_connected values from some Android clients; we don't know what
        // exactly causes this; so, simply default to a valid value. This will now pass the validation
        //  in the connected request, and ultimately LAST_CONNECTED_FILENAME should be overwritten with
        // a new, valid value.
        if (lastConnected.length() == 0) {
            lastConnected = PsiphonConstants.LAST_CONNECTED_NO_VALUE;
        }

        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("session_id", this.serverSessionID));
        extraParams.add(Pair.create("last_connected", lastConnected));

        String url = getRequestURL("connected", extraParams);

        byte[] response = makeAbortableProxiedPsiphonRequest(PsiphonConstants.HTTPS_REQUEST_SHORT_TIMEOUT, url);

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
            Tun2Socks.IProtectSocket protectSocket,
            boolean hasTunnel,
            boolean finalCall,
            Map<String, Integer> pageViewEntries,
            Map<String, Integer> httpsRequestEntries,
            Number bytesTransferred)
        throws PsiphonServerInterfaceException
    {
        byte[] requestBody = null;

        try
        {
            JSONObject stats = new JSONObject();

            // Stats traffic analysis mitigation: [non-cryptographic] pseudorandom padding to ensure the size of status requests is not constant.
            // Padding size is JSON field overhead + 0-255 bytes + 33% base64 encoding overhead
            stats.put("padding", Utils.Base64.encode(Utils.generateInsecureRandomBytes(Utils.insecureRandRange(0, 255))));

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
        extraParams.add(Pair.create("connected", finalCall ? "0" : "1"));

        List<Pair<String,String>> additionalHeaders = new ArrayList<Pair<String,String>>();
        additionalHeaders.add(Pair.create("Content-Type", "application/json"));

        if (!finalCall)
        {
            assert(protectSocket == null);
            assert(hasTunnel == true);

            String url = getRequestURL("status", extraParams);
            makeAbortableProxiedPsiphonRequest(
                    PsiphonConstants.HTTPS_REQUEST_SHORT_TIMEOUT, url, additionalHeaders, requestBody, null);
        }
        else
        {
            // The final status request is not abortable and will fail over
            // to non-tunnel HTTPS and alternate ports. This is to maximize
            // our chance of getting stats for session duration.

            // When the final call is made, we may not have a tunnel. We may
            // also be holding VpnService tun routing open. When there's no
            // tunnel, don't make a tunneled request. When the routing is
            // in place, protect the (direct) request socket.

            String urls[] = getRequestURLsWithFailover("status", extraParams);
            makePsiphonRequestWithFailover(
                    protectSocket, PsiphonConstants.HTTPS_REQUEST_FINAL_REQUEST_TIMEOUT, hasTunnel, urls, additionalHeaders, requestBody, null);
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

        makeAbortableProxiedPsiphonRequest(PsiphonConstants.HTTPS_REQUEST_SHORT_TIMEOUT, url);
    }

    /**
     * Make the 'upgrade' request.
     * @throws PsiphonServerInterfaceException
     */
    public void doUpgradeDownloadRequest(IResumableDownload resumableDownload)
        throws PsiphonServerInterfaceException
    {
        // NOTE: This call is not 'synchronized'. This allows it to run in parallel
        // with other requests (doPeriodicWork/doStatusRequest) made by runTunnelOnce
        // while the UpgradeDownloader is working.

        // TODO: Other network requests should be changed to not hold a lock and/or
        // have fine-grained locking

        boolean canAbort = true;
        boolean useLocalProxy = true;

        makeRequest(
                null,
                PsiphonConstants.HTTPS_REQUEST_LONG_TIMEOUT,
                canAbort,
                useLocalProxy,
                RequestMethod.INFER,
                null,
                EmbeddedValues.UPGRADE_URL,
                null,
                null,
                resumableDownload,
                null);
    }

    synchronized public void doFailedRequest(String error)
        throws PsiphonServerInterfaceException
    {
        List<Pair<String,String>> extraParams = new ArrayList<Pair<String,String>>();
        extraParams.add(Pair.create("error_code", error));

        String url = getRequestURL("failed", extraParams);

        makeAbortableProxiedPsiphonRequest(PsiphonConstants.HTTPS_REQUEST_SHORT_TIMEOUT, url);
    }

    /**
     * Upload the encrypted feedback package
     * @throws PsiphonServerInterfaceException
     */
    synchronized public void doFeedbackUpload(
                                Tun2Socks.IProtectSocket protectSocket,
                                byte[] feedbackData)
        throws PsiphonServerInterfaceException
    {
        SecureRandom rnd = new SecureRandom();
        byte[] uploadId = new byte[8];
        rnd.nextBytes(uploadId);

        StringBuilder url = new StringBuilder();
        url.append("https://");
        url.append(EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_SERVER);
        url.append(EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_PATH);
        url.append(Utils.byteArrayToHexString(uploadId));

        String[] headerPieces = EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_SERVER_HEADERS.split(": ");

        List<Pair<String,String>> additionalHeaders = new ArrayList<Pair<String,String>>();
        additionalHeaders.add(Pair.create(headerPieces[0], headerPieces[1]));

        makeDirectWebRequest(
                protectSocket, PsiphonConstants.HTTPS_REQUEST_LONG_TIMEOUT,
                RequestMethod.PUT, url.toString(), additionalHeaders, feedbackData);
    }

    synchronized public void fetchRemoteServerList(Tun2Socks.IProtectSocket protectSocket)
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

            // Update resolvers to match underlying network interface
            Utils.updateDnsResolvers(ownerContext);

            PsiphonData.getPsiphonData().setNextFetchRemoteServerList(
                    SystemClock.elapsedRealtime() + 1000 * PsiphonConstants.SECONDS_BETWEEN_UNSUCCESSFUL_REMOTE_SERVER_LIST_FETCH);

            try
            {
                // TODO: failure is logged by the caller; right now the caller can't log
                // the attempt since the caller doesn't know that/when a fetch will happen.
                MyLog.v(R.string.fetch_remote_server_list, MyLog.Sensitivity.NOT_SENSITIVE);

                // We may need to except this connection from the VpnService tun interface
                byte[] response = makeDirectWebRequest(protectSocket, PsiphonConstants.HTTPS_REQUEST_LONG_TIMEOUT,
                        EmbeddedValues.REMOTE_SERVER_LIST_URL, PsiphonConstants.REMOTE_SERVER_LIST_ETAG_KEY);
                
                PsiphonData.getPsiphonData().setNextFetchRemoteServerList(
                        SystemClock.elapsedRealtime() + 1000 * PsiphonConstants.SECONDS_BETWEEN_SUCCESSFUL_REMOTE_SERVER_LIST_FETCH);

                if (response.length == 0)
                {
                    return;
                }

                String serverList = AuthenticatedDataPackage.extractAndVerifyData(
                                        EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY,
                                        false, // "data" is not Base64
                                        new String(response));

                if (!EmbeddedValues.IGNORE_NON_EMBEDDED_SERVER_ENTRIES) {
                    shuffleAndAddServerEntries(serverList.split("\n"), false);
                    saveServerEntries();
                }
            }
            catch (AuthenticatedDataPackageException e)
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

        // Detect if this is a Play Store build
        if (EmbeddedValues.IS_PLAY_STORE_BUILD)
        {
            clientPlatform += PsiphonConstants.PLAY_STORE_BUILD;
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
        if (getCurrentServerEntry().hasCapability(ServerEntry.CAPABILITY_HANDSHAKE) &&
                !getCurrentServerEntry().hasCapability(ServerEntry.CAPABILITY_FRONTED_MEEK))
        {
            // FRONTED_MEEK listens on port 443, so there is no port forward on 443 to the web server
            urls[1] = getRequestURL(PsiphonConstants.DEFAULT_WEB_SERVER_PORT, path, extraParams);
        }
        return urls;
    }

    private byte[] makePsiphonRequestWithFailover(
            Tun2Socks.IProtectSocket protectSocket,
            int timeout,
            boolean hasTunnel,
            String[] urls,
            List<Pair<String,String>> additionalHeaders,
            byte[] body,
            IResumableDownload resumableDownload)
        throws PsiphonServerInterfaceException
    {
        // This request won't abort and will fail over to direct requests,
        // to multiple ports, when tunnel is down

        PsiphonServerInterfaceException lastError = new PsiphonServerInterfaceException();

        if (hasTunnel)
        {
            try
            {
                // Try tunneled request on first port (first url)

                return makeProxiedPsiphonRequest(
                        timeout,
                        false,
                        urls[0],
                        additionalHeaders,
                        body,
                        resumableDownload);
            }
            catch (PsiphonServerInterfaceException e1)
            {
                lastError = e1;
            }
        }

        // NOTE: deliberately ignoring this.stopped and adding new non-abortable requests

        if (getCurrentServerEntry().hasCapability(ServerEntry.CAPABILITY_HANDSHAKE))
        {
            // Try direct non-tunnel requests

            for (String url : urls)
            {
                try
                {
                    // Psiphon web request: authenticate the web server using the embedded certificate.
                    String psiphonServerCertificate = getCurrentServerEntry().webServerCertificate;

                    return makeRequest(
                            protectSocket,
                            timeout,
                            false,
                            false,
                            RequestMethod.INFER,
                            psiphonServerCertificate,
                            url,
                            additionalHeaders,
                            body,
                            resumableDownload,
                            null);
                }
                catch (PsiphonServerInterfaceException e2)
                {
                    lastError = e2;

                    // Try next port/url...
                }
            }
        }

        throw lastError;
    }

    private byte[] makeAbortableProxiedPsiphonRequest(int timeout, String url)
            throws PsiphonServerInterfaceException
    {
        return makeAbortableProxiedPsiphonRequest(timeout, url, null, null, null);
    }

    private byte[] makeAbortableProxiedPsiphonRequest(
            int timeout,
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body,
            IResumableDownload resumableDownload)
        throws PsiphonServerInterfaceException
    {
        return makeProxiedPsiphonRequest(timeout, true, url, additionalHeaders, body, resumableDownload);
    }

    private byte[] makeProxiedPsiphonRequest(
            int timeout,
            boolean canAbort,
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body,
            IResumableDownload resumableDownload)
        throws PsiphonServerInterfaceException
    {
        // Psiphon web request: authenticate the web server using the embedded certificate.
        String psiphonServerCertificate = getCurrentServerEntry().webServerCertificate;

        // Psiphon web request: go through the tunnel to ensure this request is
        // transmitted even in the case where SSL protocol is blocked.
        boolean useLocalProxy = true;

        return makeRequest(
                null,
                timeout,
                canAbort,
                useLocalProxy,
                RequestMethod.INFER,
                psiphonServerCertificate,
                url,
                additionalHeaders,
                body,
                resumableDownload,
                null);
    }

    private byte[] makeDirectWebRequest(
                    Tun2Socks.IProtectSocket protectSocket,
                    int timeout,
                    String url,
                    String etagSharedPreferenceKey)
            throws PsiphonServerInterfaceException
    {
        return makeRequest(
                protectSocket, timeout, true, false, RequestMethod.INFER, null,
                url, null, null, null, etagSharedPreferenceKey);
    }

    private byte[] makeDirectWebRequest(
                    Tun2Socks.IProtectSocket protectSocket,
                    int timeout,
                    RequestMethod requestMethod,
                    String url,
                    List<Pair<String,String>> additionalHeaders,
                    byte[] body)
            throws PsiphonServerInterfaceException
    {
        return makeRequest(
                protectSocket, timeout, true, false, requestMethod, null,
                url, additionalHeaders, body, null, null);
    }

    private class FixedCertTrustManager implements X509TrustManager
    {
        private final X509Certificate expectedServerCertificate;

        // TODO: pre-validate cert in addServerEntry so this won't throw?
        FixedCertTrustManager(String serverCertificate) throws CertificateException
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

    public static class ProtectedDnsResolver implements DnsResolver
    {
        Tun2Socks.IProtectSocket protectSocket;
        ServerInterface serverInterface;

        ProtectedDnsResolver(Tun2Socks.IProtectSocket protectSocket, ServerInterface serverInterface)
        {
            this.protectSocket = protectSocket;
            this.serverInterface = serverInterface;
        }

        @Override
        public InetAddress[] resolve(String hostname) throws UnknownHostException
        {
            // NOTE:
            // - The purpose of this DnsResolver is to protect() sockets from the VPN tunnel routing.
            // - Using ch.boye.httpclientandroidlib instead of org.apache.http.client.HttpClient as the Android
            //   library is deprecated and doesn't have the DnsResolver interface.
            // - The Psiphon singleton state will be clobbered by simultaneous ServerInterface instances, which
            //   could result would be potential failed lookups. Currently only once instance uses the resolver.
            //   In addition, dnsjava uses also global state internally (shared Resolver object).
            // - dnsjava has been customized to call protect() on socket objects and to abort DNS lookups when
            //   a tunnel stop is commanded.
            // - DNS resolvers are updated in runTunnelOnce() and should be set to the correct resolvers for
            //   the active underlying (non-VPN) network.

            PsiphonState.getPsiphonState().setState(protectSocket, serverInterface);
            InetAddress[] result = Address.getAllByName(hostname);

            // HACK: now we're using ProtectedDnsResolver in meek, which may perform many
            // lookups in a short time period. To avoid clobbering, we're no longed clearing
            // the shared global state -- but this is only safe because the entire session has
            // a single IProtectSocket and [main] ServerInterface.

            // PsiphonState.getPsiphonState().setState(null, null);

            return result;
        }
    }

    public static class CustomDnsResolver implements DnsResolver
    {
        CustomDnsResolver()
        {
        }

        @Override
        public InetAddress[] resolve(String hostname) throws UnknownHostException
        {
            return Address.getAllByName(hostname);
        }
    }

    public static DnsResolver getDnsResolver(Tun2Socks.IProtectSocket protectSocket, ServerInterface serverInterface)
    {
        if (protectSocket != null)
        {
            return new ProtectedDnsResolver(protectSocket, serverInterface);
        }
        // NOTE: It's important to use the SystemDefaultDnsResolver in the other case not
        // simply because protect() is not required, but also because the hidden API
        // used in getActiveNetworkDnsResolvers() isn't available on Android < 4.0.
        // So we only use the custom resolver when protectSocket is called for, which
        // only happens on Android > 4.0 in VpnService mode, which is exactly the mode
        // where we need the custom resolver. Other modes (browser-only and root on
        // any version of Android) don't require a custom resolver (browser-only because
        // DNS isn't tunneled; root because Psiphon pid DNS isn't routed through the
        // tunnel).
        // NEW: It turns out that on Android > 4.0, using the SystemDefaultDnsResolver
        // results in DNS requests being made by a different process. So in rooted WDM
        // these requests aren't excluded by transparent proxy routing, which is required.
        // The CustomDnsResolver ensures that DNS requests are made by this process,
        // so that they can be excluded from transparent proxy routing,
        // so that they succeed in WDM when the tunnel is down.
        // NOTE that the CustomDnsResolver relies on a hidden API only available on
        // Android > 4.0.
        // NOTE also that the SystemDefaultDnsResolver makes requests from the Psiphon
        // process on Android < 4.0.
        else if (PsiphonData.getPsiphonData().getTunnelWholeDevice() &&
                Utils.hasVpnService())
        {
            return new CustomDnsResolver();
        }
        else
        {
            return new SystemDefaultDnsResolver();
        }
    }

    public static class ProtectedSSLConnectionSocketFactory extends SSLConnectionSocketFactory
    {
        public static String[] getSupportedProtocols() throws IOException
        {
            // Android 2.2.2 SSlSocket.getEnabledProtocols() crash workaround
            // in org.apache.http.conn.ssl.SSLConnectionSocketFactory
            // For more details on the bug see
            // https://code.google.com/p/android/issues/detail?id=21394
            // This doesn't affect older version of HttpClient,a new call to
            // SSlSocket.getEnabledProtocols() was introduced by this changeset 
            // http://markmail.org/message/jvzl5fatgj747fcx in 4.3.5.1

            javax.net.ssl.SSLSocketFactory sf = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket sslsock;
            sslsock = (javax.net.ssl.SSLSocket) sf.createSocket();
            String[] allProtocols = sslsock.getSupportedProtocols();
            final List<String> safeProtocolsList = new ArrayList<String>(allProtocols.length);
            for (String protocol : allProtocols) {
                if (!protocol.startsWith("SSL")) {
                    safeProtocolsList.add(protocol);
                }
            }
            if(safeProtocolsList.isEmpty()) {
                return null;
            }

            String[] safeProtocols = safeProtocolsList.toArray(new String[safeProtocolsList.size()]);
            
            // We've seen at least one reported error case where setEnabledProtocols
            // would throw IllegalArgument exception when passed a protocol name
            // reported by getSupportedProtocols. In that case fallback to TLSv1
            try {
                sslsock.setEnabledProtocols(safeProtocols);
            } catch (IllegalArgumentException e) {
                safeProtocols = new String[] {"TLSv1"};
            }
            return safeProtocols;
        }

        Tun2Socks.IProtectSocket protectSocket;

        ProtectedSSLConnectionSocketFactory(
                Tun2Socks.IProtectSocket protectSocket,
                SSLContext sslContext,
                X509HostnameVerifier verifier) throws IOException
        {
            super(sslContext, ProtectedSSLConnectionSocketFactory.getSupportedProtocols(), null, verifier);
            this.protectSocket = protectSocket;
        }

        // NOTE:
        // - The purpose of this custom socket factory is to protect() sockets from the
        //   VPN tunnel routing.
        // - The SSLSocketFactory.prepareSocket() hook is invoked too late for us to make use of it.
        // - Not well understood yet: the protect() function fails with straight Socket objects, but
        //   succeeds with DatagramSockets and Sockets from SocketChannel. So we're constructing
        //   channel Sockets here.
        // - The underlying implementation of ch.boye.httpclientandroidlib.conn.ssl.SSLSocketFactory.createSocket(HttpParams) v. 1.2.2
        //   (a) ignores HttpParams; (b) makes an SSLSocket but casts that down to a Socket when returning. As long
        //   as these conditions hold, our implementation which doesn't call super() should be sound.
        // - Socket.close() closes the channel: http://docs.oracle.com/javase/6/docs/api/java/net/Socket.html#close%28%29
        // - CreateLayeredSocket not overridden: in our usage of it, this will be invoked with
        //   the proxy set to localhost and protect() is not required.

        @Override
        public Socket createSocket(HttpContext context)
                throws IOException
        {
            Socket socket = SocketChannel.open().socket();
            if (this.protectSocket != null)
            {
                this.protectSocket.doVpnProtect(socket);
            }

            return socket;
        }
    }
    
    // Disables SNI, no hostname verification
    public static class FrontingSSLConnectionSocketFactory extends ProtectedSSLConnectionSocketFactory
    {
        private javax.net.ssl.SSLSocketFactory socketFactory;
        
        FrontingSSLConnectionSocketFactory(
                Tun2Socks.IProtectSocket protectSocket,
                SSLContext sslContext,
                X509HostnameVerifier verifier) throws IOException
        {
            super(protectSocket, sslContext, verifier);
            this.socketFactory = sslContext.getSocketFactory();
        }

        @Override
        public Socket createLayeredSocket(
                final Socket socket,
                final String target,
                final int port,
                final HttpContext context) throws IOException {
            
            final SSLSocket sslsock = (SSLSocket) this.socketFactory.createSocket(
                    socket,
                    target,
                    port,
                    true);
            String supportedProtocols[] = getSupportedProtocols();
            if (supportedProtocols != null) {
                sslsock.setEnabledProtocols(supportedProtocols);
            }
            prepareSocket(sslsock);
            sslsock.startHandshake();
            return sslsock;
        }
    }

    public static class ProtectedPlainConnectionSocketFactory extends PlainConnectionSocketFactory
    {
        Tun2Socks.IProtectSocket protectSocket;

        ProtectedPlainConnectionSocketFactory(Tun2Socks.IProtectSocket protectSocket)
        {
            super();

            this.protectSocket = protectSocket;
        }

        // NOTE:
        // See comment block in ProtectedSSLSocketFactory
        @Override
        public Socket createSocket(HttpContext context)
        {
            Socket socket = null;
            try
            {
                socket = SocketChannel.open().socket();
                if (this.protectSocket != null)
                {
                    this.protectSocket.doVpnProtect(socket);
                }
            } catch (IOException e)
            {
                // TODO: log?
            }

            return socket;
        }
    }

    public interface IResumableDownload
    {
        long getResumeOffset();
        boolean appendData(byte[] buffer, int length);
    }

    enum RequestMethod {INFER, GET, POST, PUT};

    private byte[] makeRequest(
            Tun2Socks.IProtectSocket protectSocket,
            int timeout,
            boolean canAbort,
            boolean useLocalProxy,
            RequestMethod requestMethod,
            String serverCertificate,
            String url,
            List<Pair<String,String>> additionalHeaders,
            byte[] body,
            IResumableDownload resumableDownload,
            String etagSharedPreferenceKey)
        throws PsiphonServerInterfaceException
    {
        HttpRequestBaseHC4 request = null;
        CloseableHttpResponse response = null;
        CloseableHttpClient client = null;
        HttpClientContext httpClientContext = null;

        try
        {
            RequestConfig.Builder requestBuilder = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setSocketTimeout(timeout);
            
            httpClientContext = HttpClientContext.create();

            
            HttpHost httpproxy;
            if (useLocalProxy)
            {
                httpproxy = new HttpHost("127.0.0.1", PsiphonData.getPsiphonData().getHttpProxyPort());
            	requestBuilder.setProxy(httpproxy);
            }
            else
            {
                PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(this.ownerContext);
                if (proxySettings != null)
                {
                    httpproxy = new HttpHost(proxySettings.proxyHost, proxySettings.proxyPort);
                	requestBuilder.setProxy(httpproxy);
                	Credentials proxyCredentials = PsiphonData.getPsiphonData().getProxyCredentials();
                	if(proxyCredentials != null)
                	{
                		CredentialsProvider credentialsProvider = new BasicCredentialsProviderHC4();
                		credentialsProvider.setCredentials(AuthScope.ANY, proxyCredentials);
                		httpClientContext.setCredentialsProvider(credentialsProvider);
                	}
                }
            }

            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManager[] trustManager = null;
            ProtectedSSLConnectionSocketFactory sslSocketFactory = null;

            if (serverCertificate != null)
            {
                // If a specific web server certificate is provided, expect
                // exactly that certificate.

                trustManager = new TrustManager[] { new FixedCertTrustManager(serverCertificate) };
                sslContext.init(null, trustManager, new SecureRandom());
                sslSocketFactory = new ProtectedSSLConnectionSocketFactory(
                                        protectSocket,
                                        sslContext,
                                        SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            }
            else
            {
                // Otherwise, accept a server certificate signed by a CA in
                // the default trust manager.

                sslContext.init(null,  null,  null);
                sslSocketFactory = new ProtectedSSLConnectionSocketFactory(
                                        protectSocket,
                                        sslContext,
                                        SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
            }
            
            DnsResolver dnsResolver = getDnsResolver(protectSocket, this);
            
            ProtectedPlainConnectionSocketFactory plainSocketFactory = new ProtectedPlainConnectionSocketFactory(protectSocket);

            Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder
                .<ConnectionSocketFactory> create()
                .register("https", sslSocketFactory)
                // See
                // http://mail-archives.apache.org/mod_mbox/hc-dev/201311.mbox/%3C528E1219.8010003@oracle.com%3E
                // Plain 'http' scheme must be used to establish an
                // intermediate connection
                // to the proxy itself before 'https' tunneling could be
                // employed.
                //
                // TODO: investigate if plain socket _may_ need to be
                // protected if external HTTP proxy is used
                .register("http", plainSocketFactory)
                .build();

            HttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(
                    socketFactoryRegistry, ManagedHttpClientConnectionFactory.INSTANCE, 
                    DefaultSchemePortResolver.INSTANCE , dnsResolver);
			
            client = HttpClientBuilder.create()
                    .setConnectionManager(connectionManager)
                    .build();
            

            if (requestMethod == RequestMethod.POST ||
                (requestMethod == RequestMethod.INFER && body != null))
            {
                HttpPostHC4 post = new HttpPostHC4(url);
                post.setEntity(new ByteArrayEntity(body));
                request = post;
            }
            else if (requestMethod == RequestMethod.PUT)
            {
                HttpPutHC4 put = new HttpPutHC4(url);
                put.setEntity(new ByteArrayEntity(body));
                request = put;
            }
            else
            {
                request = new HttpGetHC4(url);
            }
            
            request.setConfig(requestBuilder.build());

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

            if (resumableDownload != null)
            {
                // Add a Range header to request the resumable download starting offset
                // E.g., header "Range:bytes=123-" requests the download to start at byte 123 of the resource
                request.addHeader("Range", "bytes="+Long.toString(resumableDownload.getResumeOffset()) + "-");
            }
            // NOTE: for now resumableDownload and etag support are mutually exclusive
            else if (etagSharedPreferenceKey != null)
            {
                SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.ownerContext);
                String etag = preferences.getString(etagSharedPreferenceKey, "");
                if (etag.length() > 0)
                {
                    request.addHeader("If-None-Match", etag);
                }
            }

            RequestTimeoutAbort timeoutAbort = new RequestTimeoutAbort(request);
            new Timer(true).schedule(timeoutAbort, timeout);
            try
            {
                response = client.execute(request, httpClientContext);
            }
            finally
            {
                timeoutAbort.cancel();
            }

            int statusCode = response.getStatusLine().getStatusCode();

            if (resumableDownload != null)
            {
                // Special case: the resumeable download may ask for bytes past the resource
                // range since it doesn't store the "completed download" state. In this case,
                // the HTTP server returns 416. Otherwise, we expect 206.
                if (statusCode != HttpStatus.SC_PARTIAL_CONTENT &&
                        statusCode != HttpStatus.SC_REQUESTED_RANGE_NOT_SATISFIABLE)
                {
                    throw new PsiphonServerInterfaceException(
                            this.ownerContext.getString(R.string.ServerInterface_HTTPSRequestFailed) + statusCode);
                }
            }
            else
            {
                if (statusCode != HttpStatus.SC_NOT_MODIFIED &&
                        statusCode != HttpStatus.SC_OK)
                {
                    throw new PsiphonServerInterfaceException(
                            this.ownerContext.getString(R.string.ServerInterface_HTTPSRequestFailed) + statusCode);
                }
            }
            
            if (etagSharedPreferenceKey != null)
            {
                Header responseHeaders[] = response.getHeaders("Etag");
                if (responseHeaders.length > 0)
                {
                    String etagValue = responseHeaders[0].getValue();
                    if (etagValue.length() > 0)
                    {
                        Editor editor = PreferenceManager.getDefaultSharedPreferences(this.ownerContext).edit();
                        editor.putString(etagSharedPreferenceKey, etagValue);
                        // Ignore failure
                        editor.commit();
                    }
                }
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

                    if (resumableDownload == null)
                    {
                        responseBody.write(buffer, 0, len);
                    }
                    else
                    {
                        resumableDownload.appendData(buffer, len);
                    }
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
        catch (NoSuchAlgorithmException e)
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
        catch (IllegalStateException e)
        {
            /* In some cases we have found the http client.execute method to throw a IllegalStateException after
               the tunnel has gone away:
                E/AndroidRuntime( 7013): FATAL EXCEPTION: Thread-35792
                E/AndroidRuntime( 7013): java.lang.IllegalStateException: Connection is not open
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.SocketHttpClientConnection.assertOpen(SocketHttpClientConnection.java:84)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.AbstractHttpClientConnection.flush(AbstractHttpClientConnection.java:282)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.conn.ManagedClientConnectionImpl.flush(ManagedClientConnectionImpl.java:175)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.protocol.HttpRequestExecutor.doSendRequest(HttpRequestExecutor.java:260)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.protocol.HttpRequestExecutor.execute(HttpRequestExecutor.java:125)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.client.DefaultRequestDirector.tryExecute(DefaultRequestDirector.java:717)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.client.DefaultRequestDirector.execute(DefaultRequestDirector.java:522)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:902)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:801)
                E/AndroidRuntime( 7013):        at ch.boye.httpclientandroidlib.impl.client.AbstractHttpClient.execute(AbstractHttpClient.java:780)
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
            if (response != null) {
                try
                {
                    response.close();
                }
                catch (IOException e)
                {
                    MyLog.w(R.string.make_request_close_http_response, MyLog.Sensitivity.NOT_SENSITIVE, e);
                }
            }
            if (request != null && canAbort)
            {
                synchronized(this.outstandingRequests)
                {
                    // Harmless if the request was successful. Necessary clean-up if
                    // the request was interrupted.
                    if (!request.isAborted()){
                        request.abort();
                    }

                    request.releaseConnection();
                    
                    this.outstandingRequests.remove(request);
                }
            }
            if (client != null) {
                try
                {
                    client.close();
                }
                catch (IOException e)
                {
                    MyLog.w(R.string.make_request_close_http_client, MyLog.Sensitivity.NOT_SENSITIVE, e);
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
        String serverEntry;
        try
        {
            serverEntry = new String(Utils.hexStringToByteArray(encodedServerEntry));
        }
        catch (IllegalArgumentException e)
        {
            throw new JSONException("invalid encoded server entry");
        }

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
            newEntry.capabilities.add(ServerEntry.CAPABILITY_OSSH);
            newEntry.capabilities.add(ServerEntry.CAPABILITY_SSH);
            newEntry.capabilities.add(ServerEntry.CAPABILITY_VPN);
            newEntry.capabilities.add(ServerEntry.CAPABILITY_HANDSHAKE);
        }

        if (obj.has("region"))
        {
            newEntry.regionCode = obj.getString("region");
        }
        else
        {
            newEntry.regionCode = "";
        }

        if (newEntry.hasCapability(ServerEntry.CAPABILITY_UNFRONTED_MEEK) ||
                newEntry.hasCapability(ServerEntry.CAPABILITY_UNFRONTED_MEEK_HTTPS) ||
                newEntry.hasCapability(ServerEntry.CAPABILITY_FRONTED_MEEK))
        {
            newEntry.meekServerPort = obj.getInt("meekServerPort");
            newEntry.meekCookieEncryptionPublicKey = obj.getString("meekCookieEncryptionPublicKey");
            newEntry.meekObfuscatedKey = obj.getString("meekObfuscatedKey");
        }
        else
        {
            newEntry.meekServerPort = -1;
            newEntry.meekObfuscatedKey = "";
        }

        newEntry.meekFrontingDomain = "";
        newEntry.meekFrontingHost = "";
        newEntry.meekFrontingAddressesRegex = "";
        newEntry.meekFrontingAddresses = new ArrayList<String>();
        if (newEntry.hasCapability(ServerEntry.CAPABILITY_FRONTED_MEEK))
        {
            newEntry.meekFrontingDomain = obj.getString("meekFrontingDomain");
            newEntry.meekFrontingHost = obj.getString("meekFrontingHost");
            
            if (obj.has("meekFrontingAddressesRegex"))
            {
                newEntry.meekFrontingAddressesRegex = obj.getString("meekFrontingAddressesRegex");
            }

            /* WARNING: don't do this Generex random() here.
             * This is called from the ServerInterface constructor.
             * This can be processing intensive, especially with a large embedded server list.
            String meekFrontingAddressesRegex = "";
            if (obj.has("meekFrontingAddressesRegex"))
            {
                meekFrontingAddressesRegex = obj.getString("meekFrontingAddressesRegex");
            }
            
            if (meekFrontingAddressesRegex.length() > 0)
            {
                newEntry.meekFrontingAddresses.add(new Generex(meekFrontingAddressesRegex).random());
            }
            else*/
            if (obj.has("meekFrontingAddresses"))
            {
                JSONArray meekFrontingAddressesJSON = obj.getJSONArray("meekFrontingAddresses");
                for (int i = 0; i < meekFrontingAddressesJSON.length(); i++)
                {
                    newEntry.meekFrontingAddresses.add(meekFrontingAddressesJSON.getString(i));
                }
            }
            // We will always use meekFrontingAddresses from now on (unless there is a meekFrontingAddressesRegex),
            // so copy meekFrontingDomain in if no other meekFrontingAddresses are specified
            if (newEntry.meekFrontingAddresses.size() == 0)
            {
                newEntry.meekFrontingAddresses.add(newEntry.meekFrontingDomain);
            }
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

        RegionAdapter.setServerExists(this.ownerContext, newEntry.regionCode, false);
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

            // Special case: replace if supposedly newer entry specifies
            // region and existing does not -- in this case we do know that
            // the new candidate is actually newer.
            boolean overwriteEmbedded =
                    this.serverEntries.get(existingIndex).regionCode.length() == 0
                    && newEntry.regionCode.length() > 0;

            if (!isEmbedded || overwriteEmbedded)
            {
                this.serverEntries.remove(existingIndex);
                this.serverEntries.add(existingIndex, newEntry);

                RegionAdapter.setServerExists(this.ownerContext, newEntry.regionCode, false);
                // TODO: remove region if old entry was last server for region?
            }
        }
        else
        {
            // New entries are added in the second position, to preserve
            // the first position for the "current" working server
            int index = this.serverEntries.size() > 0 ? 1 : 0;
            this.serverEntries.add(index, newEntry);

            RegionAdapter.setServerExists(this.ownerContext, newEntry.regionCode, false);
        }
    }

    private static final long MAX_SAVED_SERVER_ENTRIES_LIMIT_MEMORY_SIZE = 4*1024*1024; // 4MB

    private synchronized void saveServerEntries()
    {
        synchronized(PsiphonData.getPsiphonData().serverEntryFileLock)
        {
            FileOutputStream file = null;
            try
            {
                byte[] fileContents = null;
                long savedServerEntriesLimitMemorySize = MAX_SAVED_SERVER_ENTRIES_LIMIT_MEMORY_SIZE;
                while (this.serverEntries.size() > 0 &&
                        savedServerEntriesLimitMemorySize >= this.serverEntries.get(0).encodedEntry.length())
                {
                    try
                    {
                        JSONObject obj = new JSONObject();
                        JSONArray array = new JSONArray();
                        long serializedServerEntrySize = 0;
                        for (int i = 0; i < this.serverEntries.size(); i++)
                        {
                            ServerEntry serverEntry = this.serverEntries.get(i);
                            if (serializedServerEntrySize > 0 &&
                                    serializedServerEntrySize + serverEntry.encodedEntry.length() > savedServerEntriesLimitMemorySize)
                            {
                                // Enforce MAX_SAVED_SERVER_ENTRIES_MEMORY_SIZE:
                                // Don't add this entry when there's already at least one entry (serializedServerEntrySize > 0) and
                                // adding this one will exceed the memory size limit (serializedServerEntrySize + serverEntry.encodedEntry.length() > MAX_SAVED_SERVER_ENTRIES_MEMORY_SIZE)
                                //
                                // NOTE: side-effect! we truncate this.serverEntries to match what's serialized
                                this.serverEntries.subList(i, this.serverEntries.size()).clear();
                                break;
                            }
                            array.put(serverEntry.encodedEntry);
                            serializedServerEntrySize += serverEntry.encodedEntry.length();
                        }
                        obj.put("serverEntries", array);
                        fileContents = obj.toString().getBytes();
                        break;
                    }
                    catch (OutOfMemoryError e)
                    {
                        // Try again, with half the memory limit. This is to mitigate crashes we've seen
                        // reported in production where the MAX_SAVED_SERVER_ENTRIES_LIMIT_MEMORY_SIZE limit
                        // is not low enough.
                        fileContents = null;
                        savedServerEntriesLimitMemorySize /= 2;
                    }
                }
                if (fileContents != null)
                {
                    file = this.ownerContext.openFileOutput(PsiphonConstants.SERVER_ENTRY_FILENAME, Context.MODE_PRIVATE);
                    file.write(fileContents);
                }
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
            finally
            {
                if (file != null)
                {
                    try
                    {
                        file.close();
                    } catch (IOException e) {}
                }
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
    public synchronized void doPeriodicWork(
            Tun2Socks.IProtectSocket protectSocket,
            boolean hasTunnel,
            boolean finalCall)
    {
        long now = SystemClock.uptimeMillis();

        if (finalCall && PsiphonData.getPsiphonData().getDisplayDataTransferStats())
        {
            PsiphonData.DataTransferStats dataTransferStats = PsiphonData.getPsiphonData().getDataTransferStats();

            long bytesSent = dataTransferStats.getSessionBytesSent();
            double sentCompressionRatio = dataTransferStats.getSessionSentCompressionRatio();
            long bytesReceived = dataTransferStats.getSessionBytesReceived();
            double receivedCompressionRatio = dataTransferStats.getSessionReceivedCompressionRatio();
            long elapsedTime = dataTransferStats.getElapsedTime();

            MyLog.v(
                    R.string.data_transfer_bytes_sent,
                    MyLog.Sensitivity.NOT_SENSITIVE,
                    Utils.byteCountToDisplaySize(bytesSent, false),
                    sentCompressionRatio);

            MyLog.v(
                    R.string.data_transfer_bytes_received,
                    MyLog.Sensitivity.NOT_SENSITIVE,
                    Utils.byteCountToDisplaySize(bytesReceived, false),
                    receivedCompressionRatio);

            MyLog.v(
                    R.string.data_transfer_elapsed_time,
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
                            protectSocket,
                            hasTunnel,
                            finalCall,
                            reportedStats.getPageViewEntries(),
                            reportedStats.getHttpsRequestEntries(),
                            reportedStats.getBytesTransferred());

                    // Reset thresholds
                    this.lastStatusSendTimeMS = now;
                    this.statsSendInterval = DEFAULT_STATS_SEND_INTERVAL_MS;
                    this.sendMaxEntries = DEFAULT_SEND_MAX_ENTRIES;

                    // Stats traffic analysis mitigation: add some [non-cryptographic] pseudorandom jitter to the time interval
                    this.statsSendInterval += Utils.insecureRandRange(0, (int)DEFAULT_STATS_SEND_INTERVAL_MS);

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

                    if (finalCall)
                    {
                        MyLog.w(R.string.final_status_request_failed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                    }
                }
            }
        }
    }

    public boolean isUpgradeAvailable()
    {
        return this.upgradeClientVersion.length() > 0;
    }

    public int getUpgradeVersion()
    {
        try
        {
            return Integer.parseInt(this.upgradeClientVersion);
        }
        catch (NumberFormatException e)
        {
        }
        return 0;
    }
}
