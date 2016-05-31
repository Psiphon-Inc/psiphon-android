/*
 * Copyright (c) 2016, Psiphon Inc.
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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import com.psiphon3.R;

import org.apache.http.auth.Credentials;
import org.apache.http.auth.NTCredentials;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class UpstreamProxySettings {
    // Singleton pattern

    private static UpstreamProxySettings m_upstreamProxySettings;

    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }

    public static synchronized UpstreamProxySettings getUpstreamProxySettings() {
        if (m_upstreamProxySettings == null) {
            m_upstreamProxySettings = new UpstreamProxySettings();
        }
        return m_upstreamProxySettings;
    }

    public static synchronized String getUpstreamProxyUrlFromCurrentPreferences(Context context) {
        getUpstreamProxySettings().updateProxySettingsFromPreferences(context);
        return getUpstreamProxySettings().getUpstreamProxyUrl(context);
    }

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

    private UpstreamProxySettings() {
        m_useHTTPProxy = false;
        m_useSystemProxySettings = false;
        m_useCustomProxySettings = false;
        m_useProxyAuthentication = false;
    }

    public synchronized void updateProxySettingsFromPreferences(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);

        m_useSystemProxySettings = sharedPreferences.getBoolean(
                context.getString(R.string.useSystemProxySettingsPreference), false);

        // Backwards compatibility: if m_useSystemProxySettings is
        // set and (the new) useProxySettingsPreference is not,
        // then set it
        if (m_useSystemProxySettings
                && !sharedPreferences.contains(context.getString(R.string.useProxySettingsPreference))) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(context.getString(R.string.useProxySettingsPreference), true);
            editor.commit();
        }

        m_useHTTPProxy = sharedPreferences.getBoolean(
                context.getString(R.string.useProxySettingsPreference), false);

        m_useCustomProxySettings = sharedPreferences.getBoolean(
                context.getString(R.string.useCustomProxySettingsPreference), false);

        m_customProxyHost = sharedPreferences.getString(
                context.getString(R.string.useCustomProxySettingsHostPreference), "");

        m_customProxyPort = sharedPreferences.getString(
                context.getString(R.string.useCustomProxySettingsPortPreference), "");

        m_useProxyAuthentication = sharedPreferences.getBoolean(
                context.getString(R.string.useProxyAuthenticationPreference), false);

        m_proxyUsername = sharedPreferences.getString(
                context.getString(R.string.useProxyUsernamePreference), "");

        m_proxyPassword = sharedPreferences.getString(
                context.getString(R.string.useProxyPasswordPreference), "");

        m_proxyDomain = sharedPreferences.getString(
                context.getString(R.string.useProxyDomainPreference), "");
    }

    public synchronized boolean getUseHTTPProxy()
    {
        return m_useHTTPProxy;
    }

    public synchronized boolean getUseSystemProxySettings()
    {
        return m_useSystemProxySettings;
    }

    public synchronized boolean getUseCustomProxySettings()
    {
        return m_useCustomProxySettings;
    }

    public synchronized String getCustomProxyHost()
    {
        return m_customProxyHost;
    }

    public synchronized String getCustomProxyPort()
    {
        return m_customProxyPort;
    }

    public synchronized boolean getUseProxyAuthentication()
    {
        return m_useProxyAuthentication;
    }

    public synchronized String getProxyUsername()
    {
        return m_proxyUsername;
    }

    public synchronized String getProxyPassword()
    {
        return m_proxyPassword;
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
            if (!credentials.getDomain().equals(""))
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
}
