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
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;


public class PsiphonServerInterface
{
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
    private ArrayList<String> homePages = new ArrayList<String>();
    private String upgradeClientVersion;
    private ArrayList<Pattern> pageViewRegexes = new ArrayList<Pattern>();
    private ArrayList<Pattern> httpsRequestRegexes = new ArrayList<Pattern>();
    private String speedTestURL;
    
    PsiphonServerInterface(Context context)
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
        catch (Exception e)
        {
            Log.e(PsiphonConstants.TAG, e.toString());
            // skip loading persistent server entries
        }
        
        for (String encodedEntry : PsiphonAndroidEmbeddedValues.EMBEDDED_SERVER_LIST.split("\n"))
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
    
    synchronized public boolean doHandshake()
    {
        try
        {
            ServerEntry entry = getCurrentServerEntry();
            String url = getCommonRequestURL("handshake", entry);
            byte[] response = makeRequest(url, null, entry.webServerCertificate);

            final String JSON_CONFIG_PREFIX = "Config: ";
            for (String line : new String(response).split("\n"))
            {
                if (line.indexOf(JSON_CONFIG_PREFIX) == 0)
                {
                    JSONObject obj = new JSONObject(line.substring(JSON_CONFIG_PREFIX.length()));

                    JSONArray homepages = obj.getJSONArray("homepages");
                    for (int i = 0; i < homepages.length(); i++)
                    {
                        this.homePages.add(homepages.getString(i));
                    }

                    this.upgradeClientVersion = obj.getString("upgrade_client_version");

                    JSONArray page_view_regexes = obj.getJSONArray("page_view_regexes");
                    for (int i = 0; i < page_view_regexes.length(); i++)
                    {
                        this.pageViewRegexes.add(
                            Pattern.compile(
                                page_view_regexes.getString(i),
                                java.util.regex.Pattern.CASE_INSENSITIVE));
                    }

                    JSONArray https_request_regexes = obj.getJSONArray("https_request_regexes");
                    for (int i = 0; i < https_request_regexes.length(); i++)
                    {
                        this.httpsRequestRegexes.add(
                                Pattern.compile(
                                        https_request_regexes.getString(i),
                                        java.util.regex.Pattern.CASE_INSENSITIVE));
                    }

                    this.speedTestURL = obj.getString("speed_test_url");

                    JSONArray encoded_server_list = obj.getJSONArray("encoded_server_list");
                    for (int i = 0; i < encoded_server_list.length(); i++)
                    {
                        addServerEntry(encoded_server_list.getString(i), false);
                    }
                }
            }
            
            return true;
        }
        catch (Exception e)
        {
            Log.e(PsiphonConstants.TAG, e.toString());
            return false;
        }
    }
    
    synchronized public void doConnected()
    {
    }

    synchronized public void doDownload()
    {
    }

    synchronized public void doStatus()
    {
    }

    synchronized public void doSpeed()
    {
    }

    synchronized public void doDisconnected()
    {
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

    private String getCommonRequestURL(String path, ServerEntry serverEntry)
    {
        return String.format(
                "https://%s:%d/%s?server_secret=%s&propagation_channel_id=%s&sponsor_id=%s&client_version=%s",
                serverEntry.ipAddress,
                serverEntry.webServerPort,
                path,
                serverEntry.webServerSecret,
                PsiphonAndroidEmbeddedValues.PROPAGATION_CHANNEL_ID,
                PsiphonAndroidEmbeddedValues.SPONSOR_ID,
                PsiphonAndroidEmbeddedValues.CLIENT_VERSION);
    }

    private byte[] makeRequest(String url, byte[] body, String serverCertificate)
            throws NoSuchAlgorithmException,
                   KeyManagementException,
                   CertificateException,
                   MalformedURLException,
                   IOException,
                   Exception
    {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                null,
                new TrustManager[] { new CustomTrustManager(serverCertificate) },
                new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new AllowAllHostnameVerifier());
        HttpsURLConnection conn = (HttpsURLConnection) new URL(url).openConnection();
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setUseCaches(false);
        // TODO: timeout?

        if (body == null)
        {
            conn.setRequestMethod("GET");                
        }
        else
        {
            conn.setRequestMethod("POST");
            conn.setRequestProperty(
                    "Content-Length", 
                    Integer.toString(body.length));
            DataOutputStream writer = new DataOutputStream(conn.getOutputStream());
            writer.write(body);
            writer.flush ();
            writer.close ();           
        }
        
        if (conn.getResponseCode() != HttpsURLConnection.HTTP_OK)
        {
            throw new Exception("HTTPS request failed");
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
        catch (Exception e)
        {
            Log.e(PsiphonConstants.TAG, e.toString());
            // Proceed, even if file saving fails
        }
    } 
}
