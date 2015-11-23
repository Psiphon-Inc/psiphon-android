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

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.preference.PreferenceManager;
import android.util.Pair;

import ca.psiphon.PsiphonTunnel;

import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class TunnelManager implements PsiphonTunnel.HostService
{
    public enum ConnectionState
    {
        CONNECTING,
        CONNECTED
    }

    private ConnectionState m_state = ConnectionState.CONNECTING;
    private Context m_parentContext = null;
    private Service m_parentService = null;
    private boolean m_firstStart = true;
    private boolean m_isReconnect = false;
    private boolean m_serviceDestroyed = false;
    private IEvents m_eventsInterface = null;
    private final List<Pair<String,String>> m_extraAuthParams = new ArrayList<Pair<String,String>>();
    private PsiphonTunnel m_tunnel = null;
    private boolean m_isTunnelStarted = false;
    private String m_lastUpstreamProxyErrorMessage;


    public TunnelManager(Context parentContext, Service parentService)
    {
        m_parentContext = parentContext;
        m_parentService = parentService;
    }

    public void setEventsInterface(IEvents eventsInterface)
    {
        m_eventsInterface = eventsInterface;
    }

    // NOTE: currently unused
    public void setExtraAuthParams(List<Pair<String,String>> extraAuthParams)
    {
        m_extraAuthParams.clear();

        for (Pair<String,String> extraAuthParam : extraAuthParams)
        {
            m_extraAuthParams.add(Pair.create(extraAuthParam.first, extraAuthParam.second));
        }
    }

    // Implementation of android.app.Service.onStartCommand
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (m_firstStart)
        {
            doForeground();
            MyLog.v(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            startTunnel();
            m_firstStart = false;
        }
        return android.app.Service.START_NOT_STICKY;
    }

    // Implementation of android.app.Service.onCreate
    public void onCreate()
    {
        m_tunnel = PsiphonTunnel.newPsiphonTunnel(this);
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy()
    {
        m_serviceDestroyed = true;

        stopTunnel();
    }

    private void doForeground()
    {
        if (m_parentService == null)
        {
            // Only works with a Service
            return;
        }

        m_parentService.startForeground(R.string.psiphon_service_notification_id, this.createNotification(false));
    }

    private Notification createNotification(boolean alert)
    {
        if (m_parentService == null)
        {
            // Only works with a Service
            return null;
        }

        int contentTextID = -1;
        int iconID = -1;
        CharSequence ticker = null;

        switch (getState())
        {
        case CONNECTING:
            contentTextID = R.string.psiphon_service_notification_message_connecting;
            ticker = m_parentService.getText(R.string.psiphon_service_notification_message_connecting);
            iconID = PsiphonData.getPsiphonData().getNotificationIconConnecting();
            if (iconID == 0) {
                iconID = R.drawable.notification_icon_connecting_animation;
            }
            break;

        case CONNECTED:
            if (PsiphonData.getPsiphonData().getTunnelWholeDevice())
            {
                contentTextID = R.string.psiphon_running_whole_device;
            }
            else
            {
                contentTextID = R.string.psiphon_running_browser_only;
            }

            iconID = PsiphonData.getPsiphonData().getNotificationIconConnected();
            if (iconID == 0) {
                iconID = R.drawable.notification_icon_connected;
            }
            break;

        default:
            assert(false);
        }

        Intent activityIntent = null;
        if (m_eventsInterface != null)
        {
            activityIntent = m_eventsInterface.pendingSignalNotification(m_parentService);
        }

        if (activityIntent == null)
        {
            // Default intent if m_eventsInterface is null or returns a null pendingSignalNotification Intent.
            // This intent will launch nothing.
            // NOTE that setLatestEventInfo requires a PendingIntent.  And that calls to notify (ie from setState below)
            // require a contentView which is set by setLatestEventInfo.
            activityIntent = new Intent();
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        PendingIntent invokeActivityIntent =
                PendingIntent.getActivity(
                    m_parentService,
                    0,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification =
                new Notification(
                        iconID,
                        ticker,
                        System.currentTimeMillis());

        if (alert)
        {
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithSound), false))
            {
                notification.defaults |= Notification.DEFAULT_SOUND;
            }
            if (PreferenceManager.getDefaultSharedPreferences(m_parentService).getBoolean(
                    m_parentService.getString(R.string.preferenceNotificationsWithVibrate), false))
            {
                notification.defaults |= Notification.DEFAULT_VIBRATE;
            }
        }

        notification.setLatestEventInfo(
            m_parentService,
            m_parentService.getText(R.string.app_name),
            m_parentService.getText(contentTextID),
            invokeActivityIntent);

        return notification;
    }

    private synchronized ConnectionState getState()
    {
        return m_state;
    }

    private synchronized void setState(ConnectionState newState)
    {
        boolean alert = (newState != m_state);

        m_state = newState;

        if (!m_serviceDestroyed && m_parentService != null)
        {
            String ns = Context.NOTIFICATION_SERVICE;
            NotificationManager notificationManager =
                    (NotificationManager)m_parentService.getSystemService(ns);
            if (notificationManager != null)
            {
                notificationManager.notify(
                        R.string.psiphon_service_notification_id,
                        createNotification(alert));
            }
        }
    }
    
    private final static String LEGACY_SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";
    private final static int MAX_LEGACY_SERVER_ENTRIES = 100;

    private String getServerEntries()
    {
        StringBuilder list = new StringBuilder();
        
        for (String encodedServerEntry : EmbeddedValues.EMBEDDED_SERVER_LIST)
        {
            list.append(encodedServerEntry);
            list.append("\n");
        }
        
        // Import legacy server entries
        try
        {
            FileInputStream file = m_parentContext.openFileInput(LEGACY_SERVER_ENTRY_FILENAME);
            BufferedReader reader = new BufferedReader(new InputStreamReader(file));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
            {
                json.append(line);
            }
            file.close();
            JSONObject obj = new JSONObject(json.toString());
            JSONArray jsonServerEntries = obj.getJSONArray("serverEntries");

            // MAX_LEGACY_SERVER_ENTRIES ensures the list we pass through to tunnel-core
            // is unlikely to trigger an OutOfMemoryError
            for (int i = 0; i < jsonServerEntries.length() && i < MAX_LEGACY_SERVER_ENTRIES; i++)
            {
                list.append(jsonServerEntries.getString(i));
                list.append("\n");
            }
            
            // Don't need to repeat the import again
            m_parentContext.deleteFile(LEGACY_SERVER_ENTRY_FILENAME);
        }
        catch (FileNotFoundException e)
        {
            // pass
        }
        catch (IOException e)
        {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());
        }
        catch (JSONException e)
        {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());
        }
        catch (OutOfMemoryError e)
        {
            MyLog.g("prepareServerEntries failed: %s", e.getMessage());

            // Comment from legacy code:
            // Some mature client installs have so many server entries they cannot load them without
            // hitting out-of-memory, so they will not benefit from the MAX_SAVED_SERVER_ENTRIES_MEMORY_SIZE
            // limit added to saveServerEntries(). In this case, we simply ignore the saved list. The client
            // will proceed with the embedded list only, and going forward the MEMORY_SIZE limit will be
            // enforced.
        }
        
        return list.toString();
    }
    
    private void startTunnel()
    {
        Utils.checkSecureRandom();

        stopTunnel();
        
        m_isTunnelStarted = true;
        
        // Notify if an upgrade has already been downloaded and is waiting for install
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentContext);
        
        if (m_eventsInterface != null)
        {
            m_eventsInterface.signalTunnelStarting(m_parentContext);
        }

        MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentContext));

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
        
        PsiphonData.getPsiphonData().clearHomePages();
        
        PsiphonData.getPsiphonData().getDataTransferStats().startSession();
        
        m_isReconnect = false;
        
        boolean runVpn =
            PsiphonData.getPsiphonData().getTunnelWholeDevice() &&
            Utils.hasVpnService() &&
            // Guard against trying to start WDM mode when the global option flips while starting a TunnelService
            (m_parentService instanceof TunnelVpnService);

        try
        {
            if (runVpn)
            {
                if (!m_tunnel.startRouting())
                {
                    throw new PsiphonTunnel.Exception("application is not prepared or revoked");
                }
                
                MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            
            m_tunnel.startTunneling(getServerEntries());
        }
        catch (PsiphonTunnel.Exception e)
        {
            MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
            
            // Cleanup routing/tunnel
            stopTunnel();

            // Stop service
            m_parentService.stopForeground(true);
            m_parentService.stopSelf();
        }
    }

    private void stopTunnel()
    {
        if (!m_isTunnelStarted)
        {
            return;
        }

        MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        if (m_eventsInterface != null)
        {
            m_eventsInterface.signalTunnelStopping(m_parentContext);
        }

        m_tunnel.stop();
        
        PsiphonData.getPsiphonData().getDataTransferStats().stop();
        
        setState(ConnectionState.CONNECTING);

        MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
        
        m_isTunnelStarted = false;
    }

    // A hack to stop the VpnService, which doesn't respond to normal
    // stopService() calls.
    public void stopVpnServiceHelper()
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);
        
        stopTunnel();

        m_parentService.stopForeground(true);
        m_parentService.stopSelf();
    }

    @Override
    public String getAppName() {
        return m_parentContext.getString(R.string.app_name);
    }

    @Override
    public Context getContext() {
        return m_parentContext;
    }

    @Override
    public VpnService getVpnService() {
        return ((TunnelVpnService)m_parentService);
    }

    @Override
    public Builder newVpnServiceBuilder() {
        return ((TunnelVpnService)m_parentService).newBuilder();
    }

    @Override
    public String getPsiphonConfig() {        
        try {            
            JSONObject json = new JSONObject();
            
            if (0 > EmbeddedValues.UPGRADE_URL.length() && 
                    EmbeddedValues.hasEverBeenSideLoaded(m_parentContext) &&
                    PsiphonData.getPsiphonData().getDownloadUpgrades())
            {
                json.put("UpgradeDownloadUrl", EmbeddedValues.UPGRADE_URL);
                
                json.put("UpgradeDownloadFilename",
                        new UpgradeManager.DownloadedUpgradeFile(m_parentContext).getFilename());                
            }
            
            json.put("ClientPlatform", PsiphonConstants.PLATFORM);
            
            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);
            
            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);
            
            json.put("SponsorId", EmbeddedValues.SPONSOR_ID);

            json.put("RemoteServerListUrl", EmbeddedValues.REMOTE_SERVER_LIST_URL);

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            json.put("LocalHttpProxyPort", PsiphonData.getPsiphonData().getConfiguredLocalHttpProxyPort());

            json.put("LocalSocksProxyPort", PsiphonData.getPsiphonData().getConfiguredLocalSocksProxyPort());

            json.put("UpstreamProxyUrl", PsiphonData.getPsiphonData().getUpstreamProxyUrl(m_parentService));            
            
            String egressRegion = PsiphonData.getPsiphonData().getEgressRegion();
            MyLog.g("EgressRegion", "regionCode", egressRegion);
            json.put("EgressRegion", egressRegion);
            
            return json.toString();

        } catch (JSONException e) {
            
            return "";
        }
    }

    @Override
    public void onDiagnosticMessage(String message) {
        MyLog.g("diagnostic", "msg", message);
    }

    @Override
    public void onAvailableEgressRegions(List<String> regions) {
        for (String region : regions)
        {
            RegionAdapter.setServerExists(m_parentContext, region, false);
        }
    }

    @Override
    public void onSocksProxyPortInUse(int port) {
        MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        stopVpnServiceHelper();
    }

    @Override
    public void onHttpProxyPortInUse(int port) {
        MyLog.e(R.string.http_proxy_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
        stopVpnServiceHelper();
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        PsiphonData.getPsiphonData().setListeningLocalSocksProxyPort(port);
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
        MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
        PsiphonData.getPsiphonData().setListeningLocalHttpProxyPort(port);
    }

    @Override
    public void onUpstreamProxyError(String message) {
        // Display the error message only once, and continue trying to connect in
        // case the issue is temporary.
        if (m_lastUpstreamProxyErrorMessage != null && !m_lastUpstreamProxyErrorMessage.equals(message))
        {
            MyLog.v(R.string.upstream_proxy_error, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, message);
            m_lastUpstreamProxyErrorMessage = message;
        }        
    }

    @Override
    public void onConnecting() {
        setState(ConnectionState.CONNECTING);
        
        MyLog.v(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);
        
        if (m_isReconnect)
        {
            if (m_eventsInterface != null)
            {
                m_eventsInterface.signalUnexpectedDisconnect(m_parentContext);
            }
        }
    }

    @Override
    public void onConnected() {
        setState(ConnectionState.CONNECTED);
        
        MyLog.v(R.string.tunnel_connected, MyLog.Sensitivity.NOT_SENSITIVE);
        
        PsiphonData.getPsiphonData().getDataTransferStats().startConnected();
        
        if (m_eventsInterface != null)
        {
            m_eventsInterface.signalHandshakeSuccess(m_parentContext, m_isReconnect);
        }

        m_isReconnect = true;
    }

    @Override
    public void onHomepage(String url) {
        PsiphonData.getPsiphonData().addHomePage(url);        
    }

    @Override
    public void onClientRegion(String region) {
        PsiphonData.getPsiphonData().setClientRegion(region);
    }

    @Override
    public void onClientUpgradeDownloaded(String filename) {
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentContext);      
    }

    @Override
    public void onSplitTunnelRegion(String region) {
        MyLog.v(R.string.split_tunnel_region, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, region);
    }

    @Override
    public void onUntunneledAddress(String address) {
        MyLog.v(R.string.untunneled_address, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, address);
    }

    @Override
    public void onBytesTransferred(long sent, long received) {
        PsiphonData.DataTransferStats stats = PsiphonData.getPsiphonData().getDataTransferStats();
        stats.addBytesSent(sent);
        stats.addBytesReceived(received);
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        MyLog.v(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);      
    }
}
