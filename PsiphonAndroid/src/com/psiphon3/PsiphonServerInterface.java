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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;


public class PsiphonServerInterface
{
    public class ServerEntry implements Cloneable
    {
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
    
    private ArrayList<ServerEntry> serverEntries;
    
    PsiphonServerInterface()
    {
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
                    // TODO: ...
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

    synchronized public void doUpgrade()
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
            Base64.decodeBase64(serverCertificate);
            this.expectedServerCertificate = (X509Certificate)factory.generateCertificate(
                    new ByteArrayInputStream(Base64.decodeBase64(serverCertificate)));
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
                   IOException
    {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(
                null,
                new TrustManager[] { new CustomTrustManager(serverCertificate) },
                new SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
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
            responseBody.write(buffer);
        }
        
        return responseBody.toByteArray();
    }
    
    private void addServerEntry(
            String encodedServerEntry,
            boolean isEmbedded)
    {
        try
        {
            JSONObject obj = new JSONObject(new String(Hex.decodeHex(encodedServerEntry.toCharArray())));
            ServerEntry newEntry = new ServerEntry();
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
        }
        catch (JSONException e)
        {
            // Ignore this server entry on parse error
            return;
        }
        catch (DecoderException e)
        {
            return;
        }
    }
}
