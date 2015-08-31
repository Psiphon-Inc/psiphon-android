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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Pair;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.ConnectionMonitor;
import ch.ethz.ssh2.DynamicPortForwarder;
import ch.ethz.ssh2.ServerHostKeyVerifier;
import ch.ethz.ssh2.TransparentProxyPortForwarder;

import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.TransparentProxyConfig.PsiphonTransparentProxyException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.stericson.RootTools.RootTools;

public class TunnelCore implements Connection.IStopSignalPending, Tun2Socks.IProtectSocket
{
    public enum State
    {
        CONNECTING,
        CONNECTED
    }

    enum Signal
    {
        STOP_TUNNEL,
        UNEXPECTED_DISCONNECT
    }

    private State m_state = State.CONNECTING;
    private Context m_parentContext = null;
    private Service m_parentService = null;
    private boolean m_firstStart = true;
    private Thread m_tunnelThread = null;
    private ServerInterface m_interface = null;
    private UpgradeManager.UpgradeDownloader m_upgradeDownloader = null;
    private ServerSelector.TargetProtocolState m_targetProtocolState = null;
    private ServerSelector m_serverSelector = null;
    private boolean m_destroyed = false;
    private IEvents m_eventsInterface = null;
    private boolean m_useGenericLogMessages = false;
    private final List<Pair<String,String>> m_extraAuthParams = new ArrayList<Pair<String,String>>();
    private BlockingQueue<Signal> m_signalQueue;


    public TunnelCore(Context parentContext, Service parentService)
    {
        m_parentContext = parentContext;
        m_parentService = parentService;
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
        m_interface = new ServerInterface(m_parentContext);
        m_targetProtocolState = new ServerSelector.TargetProtocolState();
        m_serverSelector = new ServerSelector(m_targetProtocolState, this, this, m_interface, m_parentContext);
        m_upgradeDownloader = new UpgradeManager.UpgradeDownloader(m_parentContext, m_interface);
    }

    // Implementation of android.app.Service.onDestroy
    public void onDestroy()
    {
        m_destroyed = true;

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

    public static class PsiphonServerHostKeyVerifier implements ServerHostKeyVerifier
    {
        private final String m_expectedHostKey;

        PsiphonServerHostKeyVerifier(String expectedHostKey)
        {
            m_expectedHostKey = expectedHostKey;
        }

        @Override
        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
        {
            return 0 == m_expectedHostKey.compareTo(Utils.Base64.encode(serverHostKey));
        }
    }

    public synchronized State getState()
    {
        return m_state;
    }

    private synchronized void setState(State newState)
    {
        boolean alert = (newState != m_state);

        m_state = newState;

        if (!this.m_destroyed && m_parentService != null)
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

    class Monitor implements ConnectionMonitor
    {
        private final BlockingQueue<Signal> m_signalQueue;

        public Monitor(BlockingQueue<Signal> signalQueue)
        {
            m_signalQueue = signalQueue;
        }

        @Override
        public void connectionLost(Throwable reason)
        {
            MyLog.e(R.string.ssh_disconnected_unexpectedly, MyLog.Sensitivity.NOT_SENSITIVE);

            // 'Add' will do nothing if there's already a pending signal.
            // This is ok: the pending signal is either UNEXPECTED_DISCONNECT
            // or STOP_TUNNEL, and both will result in a tear down.
            m_signalQueue.add(Signal.UNEXPECTED_DISCONNECT);
        }
    }

    private static class TunnelVpnServiceUnexpectedDisconnect extends Exception
    {
        private static final long serialVersionUID = 1L;

        public TunnelVpnServiceUnexpectedDisconnect()
        {
            super();
        }
    }

    private static class TunnelVpnTunnelStop extends Exception
    {
        private static final long serialVersionUID = 1L;

        public TunnelVpnTunnelStop()
        {
            super();
        }
    }

    private void checkSignals(int waitTimeSeconds)
            throws InterruptedException, TunnelVpnServiceUnexpectedDisconnect, TunnelVpnTunnelStop
    {
        Signal signal = m_signalQueue.poll(waitTimeSeconds, TimeUnit.SECONDS);

        if (signal != null)
        {
            switch (signal)
            {
            case STOP_TUNNEL:
                throw new TunnelVpnTunnelStop();
            case UNEXPECTED_DISCONNECT:
                throw new TunnelVpnServiceUnexpectedDisconnect();
            }
        }
    }

    @Override
    public boolean isStopSignalPending()
    {
        return m_signalQueue == null || m_signalQueue.peek() == Signal.STOP_TUNNEL;
    }

    public static Connection establishSshConnection(
            Connection.IStopSignalPending stopSignalPending,
            Socket socket,
            ServerInterface.ServerEntry entry,
            String clientSessionId,
            List<Pair<String,String>> extraAuthParams) throws IOException
    {
        Connection sshConnection = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
        sshConnection.connect(
                socket,
                new PsiphonServerHostKeyVerifier(entry.sshHostKey),
                0,
                PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS,
                stopSignalPending);

        if (stopSignalPending.isStopSignalPending())
        {
            return null;
        }

        // Send auth params as JSON-encoded string in SSH password field
        // Client session ID is used to associate the tunnel with web requests -- for GeoIP region stats

        JSONObject authParams = new JSONObject();
        try
        {
            authParams.put("SessionId", clientSessionId);
            authParams.put("SshPassword", entry.sshPassword);
            for (Pair<String,String> extraAuthParam : extraAuthParams)
            {
                authParams.put(extraAuthParam.first, extraAuthParam.second);
            }
        }
        catch (JSONException e)
        {
            return null;
        }

        if (!sshConnection.authenticateWithPassword(entry.sshUsername, authParams.toString()))
        {
            MyLog.e(R.string.ssh_authentication_failed, MyLog.Sensitivity.NOT_SENSITIVE);
            return null;
        }

        return sshConnection;
    }

    private void cleanupSshConnection(Socket socket, Connection sshConnection)
    {
        if (sshConnection != null)
        {
            sshConnection.clearConnectionMonitors();
            sshConnection.close();
        }

        if (socket != null)
        {
            try
            {
                socket.close();
            }
            catch (IOException e)
            {
            }
        }
    }

    private Socket connectSocket(boolean protectSocketsRequired, long timeout, String ipAddress, int port)
            throws IOException, InterruptedException, TunnelVpnServiceUnexpectedDisconnect, TunnelVpnTunnelStop
    {
        SocketChannel channel = SocketChannel.open();

        if (protectSocketsRequired)
        {
            // We may need to except this connection from the VpnService tun interface
            doVpnProtect(channel.socket());
        }

        channel.configureBlocking(false);
        channel.connect(new InetSocketAddress(ipAddress, port));
        Selector selector = Selector.open();
        channel.register(selector, SelectionKey.OP_CONNECT);

        long startTime = SystemClock.elapsedRealtime();

        boolean success = true;
        while (selector.select(100) == 0)
        {
            checkSignals(0);

            if (startTime + timeout <= SystemClock.elapsedRealtime())
            {
                success = false;
                break;
            }
        }

        if (success)
        {
            success = channel.finishConnect();
        }

        selector.close();

        if (success)
        {
            channel.configureBlocking(true);
            return channel.socket();
        }
        else
        {
            channel.close();
            return null;
        }
    }

    private boolean runTunnelOnce(boolean[] activeServices)
    {
        setState(State.CONNECTING);

        MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentContext));

        PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
        PsiphonData.getPsiphonData().setTunnelSessionID("");

        m_interface.start();

        // Generate a new client session ID to be included with all subsequent web requests
        // It's also included with the SSH login, and Meek cookie, for GeoIP region lookup on the server-side
        m_interface.generateNewCurrentClientSessionID();

        boolean runAgain = true;
        boolean unexpectedDisconnect = false;
        long preemptiveReconnectWaitUntil = 0;
        long preemptiveReconnectTimePeriod = 0;

        MeekClient meekClient = null;
        boolean usingHTTPProxy = false;
        Socket socket = null;
        Connection sshConnection = null;
        DynamicPortForwarder socks = null;
        TransparentProxyPortForwarder transparentProxy = null;
        DnsProxy dnsProxy = null;
        boolean cleanupTransparentProxyRouting = false;
        boolean cleanupTun2Socks = false;

        try
        {
            if (m_interface.setCurrentServerEntry() == null)
            {
                MyLog.e(R.string.no_server_entries, MyLog.Sensitivity.NOT_SENSITIVE);
                runAgain = false;
                return runAgain;
            }

            boolean tunnelWholeDevice = PsiphonData.getPsiphonData().getTunnelWholeDevice();
            boolean runVpnService = tunnelWholeDevice &&
                    Utils.hasVpnService() &&
                    !PsiphonData.getPsiphonData().getVpnServiceUnavailable() &&
                    !PsiphonData.getPsiphonData().getWdmForceIptables();

            // Guard against trying to start WDM mode when the global option flips while starting a TunnelService
            if (runVpnService && (m_parentService instanceof TunnelService))
            {
                tunnelWholeDevice = false;
                runVpnService = false;
            }

            // TODO: get remote address/port from Psiphon server
            String tunnelWholeDeviceDNSServer = PsiphonConstants.TUNNEL_WHOLE_DEVICE_DNS_RESOLVER_ADDRESS;

            if (tunnelWholeDevice && !runVpnService)
            {
                // Root mode not currently supported in Android Lollipop
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                {
                    MyLog.e(R.string.root_mode_not_supported, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }

                // Check for required root access *before* establishing the SSH connection

                MyLog.v(R.string.checking_for_root_access, MyLog.Sensitivity.NOT_SENSITIVE);

                // Check root access
                //
                // Some known Superuser/RootTools/Psiphon limitations:
                // - The root-check timeout will block tunnel shutdown. It's now 10 seconds instead of 5 seconds because it's best
                //   when the user responds within the first time period (see race condition note below). This is mitigated by the
                //   fact that you can't click Quit with the Superuser prompt up.
                // - Clicking Home and presumably other app switch methods loses the Superuser prompt -- but doesn't stop the root-check
                //   waiting on it. The timeout loop will cause the prompt to re-appear (even over the home screen).
                // - There's a frequently exhibiting race condition between clicking the prompt and the timeout, so often you can click
                //   Deny or Allow and get asked again right away. The "remember" option mitigates this. And the increase to 10 seconds
                //   also mitigates this.
                // - Could probably make the root-check timeout not block the tunnel shutdown and so lengthen the timeout, but there's
                //   another limiting factor that keeps that timeout short-ish: the Runtime.getRuntime.exec() hang bug. This code *needs*
                //   to timeout and kill the proc and retry without waiting forever.

                while (true)
                {
                    // The getTunnelWholeDevice option will only be on when the device
                    // is rooted, but our app may still be denied su privileges
                    int result = RootTools.isAccessGiven();
                    if (result == 0)
                    {
                        // Root access denied
                        MyLog.e(R.string.root_access_denied, MyLog.Sensitivity.NOT_SENSITIVE);
                        runAgain = false;
                        return runAgain;
                    }
                    else if (result == 1)
                    {
                        // Root access granted
                        break;
                    }
                    else
                    {
                        // Timeout/unknown (user hasn't responded to prompt)
                        // ...fall through to checkSignals and then try again
                    }

                    checkSignals(0);
                }
            }

            checkSignals(0);

            m_serverSelector.Run(
                    tunnelWholeDevice && runVpnService, // protect sockets in whole device mode
                    m_interface.getCurrentClientSessionID(),
                    m_extraAuthParams);

            checkSignals(0);

            // At this point we'll start counting bytes transferred for SSH traffic
            PsiphonData.getPsiphonData().getDataTransferStats().startSession();

            MyLog.v(R.string.ssh_connected, MyLog.Sensitivity.NOT_SENSITIVE);

            // The preemptive reconnect should be started "preemptiveReconnectTimePeriod" after
            // the last socket connection completed. But we don't know preemptiveReconnectTimePeriod yet.
            // It will be added after the handshake.
            preemptiveReconnectWaitUntil = SystemClock.elapsedRealtime(); // + preemptiveReconnectTimePeriod

            meekClient = m_serverSelector.firstEntryMeekClient;
            usingHTTPProxy = m_serverSelector.firstEntryUsingHTTPProxy;
            socket = m_serverSelector.firstEntrySocket;
            sshConnection = m_serverSelector.firstEntrySshConnection;
            String ipAddress = m_serverSelector.firstEntryIpAddress;
            if (socket == null ||  sshConnection == null)
            {
                return runAgain;
            }
            ServerInterface.ServerEntry entry = m_interface.setCurrentServerEntry();
            // TODO: can this happen? handle gracefully
            assert(entry.ipAddress.equals(ipAddress));

            checkSignals(0);

            // Update resolvers (again) to match underlying network interface used for SSH tunnel
            Utils.updateDnsResolvers(m_parentContext);

            MyLog.v(R.string.socks_starting, MyLog.Sensitivity.NOT_SENSITIVE);

            // If polipo is already running, we must use the same SOCKS port that polipo is
            // already using as it's parent proxy port.
            if (Polipo.isPolipoThreadRunning())
            {
                if (!Utils.isPortAvailable(PsiphonData.getPsiphonData().getSocksPort()))
                {
                    MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getSocksPort());
                    runAgain = false;
                    return runAgain;
                }
            }
            else
            {
                int port = Utils.findAvailablePort(PsiphonData.getPsiphonData().getDefaultSocksPort(), 10);
                if (port == 0)
                {
                    MyLog.e(R.string.socks_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setSocksPort(port);
            }

            if (PsiphonData.getPsiphonData().getShareProxies())
            {
                socks = sshConnection.createDynamicPortForwarder(PsiphonData.getPsiphonData().getSocksPort());
            }
            else
            {
                socks = sshConnection.createDynamicPortForwarder(
                        new InetSocketAddress("127.0.0.1", PsiphonData.getPsiphonData().getSocksPort()));
            }
            MyLog.v(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getSocksPort());

            // The Psiphon localhost HTTP proxy implementation is provided by Polipo,
            // a native application accessed via JNI. This proxy is
            // chained to our SOCKS proxy.

            // TODO: there's a security concern here - if the HTTP proxy
            // remains running after the main process dies, a malicious
            // app could plug in its own SOCKS proxy and capture all
            // Psiphon browser activity.

            try
            {
                Polipo.getPolipo().runForever();
            }
            catch (java.lang.UnsatisfiedLinkError e)
            {
                MyLog.e(R.string.run_polipo_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                runAgain = false;
                return runAgain;
            }

            if (PsiphonData.getPsiphonData().getHttpProxyPort() == 0)
            {
                MyLog.e(R.string.http_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                runAgain = false;
                return runAgain;
            }

            MyLog.v(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getHttpProxyPort());

            // Start transparent proxy, DNS proxy, and iptables config

            if (tunnelWholeDevice && !runVpnService)
            {
                // TODO: findAvailablePort is only effective for TCP services
                int port = Utils.findAvailablePort(PsiphonConstants.DNS_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.dns_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setDnsProxyPort(port);

                dnsProxy = new DnsProxy(
                                tunnelWholeDeviceDNSServer,
                                53,
                                PsiphonData.getPsiphonData().getDnsProxyPort());

                if (!dnsProxy.Start())
                {
                    // If we can't run the local DNS proxy, abort
                    runAgain = false;
                    return runAgain;
                }

                MyLog.v(R.string.dns_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getDnsProxyPort());

                port = Utils.findAvailablePort(PsiphonConstants.TRANSPARENT_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.transparent_proxy_ports_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setTransparentProxyPort(port);

                transparentProxy = sshConnection.createTransparentProxyForwarder(PsiphonData.getPsiphonData().getTransparentProxyPort());
                MyLog.v(R.string.transparent_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, PsiphonData.getPsiphonData().getTransparentProxyPort());

                if (!activeServices[ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING])
                {
                    try
                    {
                        TransparentProxyConfig.setupTransparentProxyRouting(m_parentContext);
                        cleanupTransparentProxyRouting = true;
                    }
                    catch (PsiphonTransparentProxyException e)
                    {
                        // If we can't configure the iptables routing, abort
                        MyLog.e(R.string.transparent_proxy_routing_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                        runAgain = false;
                        return runAgain;
                    }

                    MyLog.v(R.string.transparent_proxy_routing_running, MyLog.Sensitivity.NOT_SENSITIVE);
                }
            }

            // Run as Android OS VPN

            if (tunnelWholeDevice && runVpnService)
            {
                if (!activeServices[ACTIVE_SERVICE_TUN2SOCKS])
                {
                    // VpnService backwards compatibility: doVpnProtect/doVpnBuilder are wrapper
                    // functions so we don't reference the undefined VpnServer class when this function
                    // is loaded.

                    String privateIpAddress = Utils.selectPrivateAddress();

                    if (privateIpAddress == null)
                    {
                        MyLog.v(R.string.vpn_service_no_private_address_available, MyLog.Sensitivity.NOT_SENSITIVE);
                        runAgain = false;
                        return runAgain;
                    }

                    ParcelFileDescriptor vpnInterfaceFileDescriptor = null;

                    // NOTE: relying on ServerSelector to have called doVpnProtect on the connection socket, as required
                    if (null == (vpnInterfaceFileDescriptor = doVpnBuilder(privateIpAddress)))
                    {
                        // TODO: don't fail over to root mode in the not-really-broken revoked edge condition case (e.g., establish() returns null)?
                        runAgain = failOverToRootWholeDeviceMode();
                        return runAgain;
                    }

                    MyLog.v(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);

                    String socksServerAddress = "127.0.0.1:" + Integer.toString(PsiphonData.getPsiphonData().getSocksPort());
                    String udpgwServerAddress = "127.0.0.1:" + Integer.toString(PsiphonConstants.UDPGW_SERVER_PORT);

                    cleanupTun2Socks = true;

                    Tun2Socks.Start(
                            this,
                            vpnInterfaceFileDescriptor,
                            PsiphonConstants.VPN_INTERFACE_MTU,
                            Utils.getPrivateAddressRouter(privateIpAddress),
                            PsiphonConstants.VPN_INTERFACE_NETMASK,
                            socksServerAddress,
                            udpgwServerAddress,
                            true);

                    // TODO: detect and report: tun2Socks.Start failed; tun2socks run() unexpected exit

                    MyLog.v(R.string.tun2socks_running, MyLog.Sensitivity.NOT_SENSITIVE);
                }
            }

            // Don't signal unexpected disconnect until we've started
            sshConnection.addConnectionMonitor(new Monitor(m_signalQueue));

            checkSignals(0);

            // Certain Android devices silently fail to route through the VpnService tun device.
            // Test connecting to a service available only through the tunnel. Stop when the check fails.

            // NOTE: this test succeeds due to the tun2socks accept on localhost, which confirms that
            // the connection was tunneled, and fails due to direct connect because the selected
            // service is firewalled.
            // TODO: A more advanced implementation would have tun2socks recognize this test and (a)
            // not attempt a SOCKS port forward; (b) respond with a verifiable byte stream. This byte
            // stream must be a random nonce known to TunnelCore and tun2socks but not known to any
            // external party that could respond, yielding a false positive.

            // NEW: don't do this check on Lollipop any more, since we have not observed any Lollipop
            // devices that silently fail to route through the VpnService tun device (but we have
            // seen the test fail on Lollipop when routing is successful).
            
            if (tunnelWholeDevice && runVpnService && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            {
                boolean success = false;

                // A retry is made due to failure of the first attempt observed
                // in Android Lollipop. A second attempt appears to succeed (when it should).
                // TODO: analyze problem -- Is the first attempt failure due to
                // something like tun2socks not running? Tun2Socks.Start is asynchronous:
                // it starts a thread which runs tun2socks; but even so, when channel.connect
                // is called won't the VPN route packets to the VPN fd which will be
                // queued and read when tun2socks eventually starts?
                for (int retry = 0; !success && retry < 10; retry++)
                {
                    SocketChannel channel = null;
                    Selector selector = null;
                    try
                    {
                        channel = SocketChannel.open();
                        channel.configureBlocking(false);
                        // Select a random port to be slightly less fingerprintable in the untunneled (failure) case.
                        int port = Utils.insecureRandRange(PsiphonConstants.CHECK_TUNNEL_SERVER_FIRST_PORT, PsiphonConstants.CHECK_TUNNEL_SERVER_LAST_PORT);
                        channel.connect(new InetSocketAddress(entry.ipAddress, port));
                        selector = Selector.open();
                        channel.register(selector, SelectionKey.OP_CONNECT);
                        for (int i = 0;
                             i < PsiphonConstants.CHECK_TUNNEL_TIMEOUT_MILLISECONDS && selector.select(100) == 0;
                             i += 100)
                        {
                            checkSignals(0);
                        }
                        success = channel.finishConnect();
                    }
                    catch (IOException e) {}
                    finally
                    {
                        if (selector != null)
                        {
                            try
                            {
                                selector.close();
                            }
                            catch (IOException e) {}
                        }
                        if (channel != null)
                        {
                            try
                            {
                                channel.close();
                            }
                            catch (IOException e) {}
                        }
                    }
                }

                if (!success)
                {
                    MyLog.e(R.string.check_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE);

                    // If this test fails, there's something wrong with routing. Fail over to
                    // the other whole device mode if possible or stop the tunnel.
                    runAgain = failOverToRootWholeDeviceMode();
                    return runAgain;
                }
            }

            checkSignals(0);

            // Start connection elapsed time
            PsiphonData.getPsiphonData().getDataTransferStats().startConnected();

            setState(State.CONNECTED);
            PsiphonData.getPsiphonData().setTunnelRelayProtocol(entry.connType);

            try
            {
                m_interface.doHandshakeRequest();
                PsiphonData.getPsiphonData().setTunnelSessionID(m_interface.getCurrentServerSessionID());

                // Handshake indicates whether to use preemptive reconnect mode. Based on client region.
                // The returned value is expected maximum connection lifetime. From that we calculate
                // our connection cycle period.

                long preemptiveReconnectLifetime = m_interface.getPreemptiveReconnectLifetime();
                if (preemptiveReconnectLifetime > 0)
                {
                    preemptiveReconnectTimePeriod = preemptiveReconnectLifetime/2 + PsiphonConstants.PREEMPTIVE_RECONNECT_LIFETIME_ADJUSTMENT_MILLISECONDS;
                    if (preemptiveReconnectTimePeriod < 0)
                    {
                        preemptiveReconnectTimePeriod = 0;
                    }
                    MyLog.g("preemptiveReconnectTimePeriod " + Long.toString(preemptiveReconnectTimePeriod));

                    preemptiveReconnectWaitUntil += preemptiveReconnectTimePeriod;
                }

                if (m_eventsInterface != null)
                {
                    m_eventsInterface.signalHandshakeSuccess(m_parentContext, m_isReconnect);
                }

                m_isReconnect = true;
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.e(R.string.PsiphonAndroidService_HandshakeRequestFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);

                // Treat this case like a tunnel failure -- we don't want to proceed without
                // a session ID, home page, etc. We don't expect it's likely that the handshake
                // will fail if the tunnel is successfully established.
                throw new IOException();
            }

            if (m_useGenericLogMessages)
            {
                MyLog.i(R.string.psiphon_running_generic, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            else
            {
                MyLog.i(tunnelWholeDevice ? R.string.psiphon_running_whole_device : R.string.psiphon_running_browser_only, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            checkSignals(0);

            try
            {
                m_interface.doConnectedRequest();
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.PsiphonAndroidService_ConnectedRequestFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);
                // Allow the user to continue. Their session might still function correctly.
            }

            checkSignals(0);

            if (m_interface.isUpgradeAvailable() && PsiphonData.getPsiphonData().getDownloadUpgrades())
            {
                m_upgradeDownloader.start(m_interface.getUpgradeVersion());
            }

            boolean hasTunnel = true;

            Socket oldSocket = null;
            Connection oldSshConnection = null;
            Socket newSocket = null;
            Connection newSshConnection = null;

            try
            {
                // This busy-wait-ish loop is throttled by the `checkSignals(1)`
                // call. It will wait for 1 second before proceeding to the
                // `doPeriodicWork()` call (which itself only takes action every
                // half-hour).
                while (true)
                {
                    checkSignals(1);

                    m_interface.doPeriodicWork(null, true, false);

                    // NOTE: this preemptive reconnect code isn't compatible with HTTP proxy or
                    // meek modes (yet)... it only makes direct connections

                    if (preemptiveReconnectTimePeriod != 0
                            && !usingHTTPProxy
                            && meekClient == null)
                    {
                        long now = SystemClock.elapsedRealtime();
                        if (now >= preemptiveReconnectWaitUntil)
                        {
                            // Retire the old connection

                            if (oldSocket != null || oldSshConnection != null)
                            {
                                cleanupSshConnection(oldSocket, oldSshConnection);
                                oldSocket = null;
                                oldSshConnection = null;
                                MyLog.g("preemptive ssh stopped");
                            }

                            checkSignals(0);

                            // Connect directly to the same server

                            try
                            {
                                newSocket = connectSocket(
                                                tunnelWholeDevice && runVpnService,
                                                PsiphonConstants.PREEMPTIVE_RECONNECT_SOCKET_TIMEOUT_MILLISECONDS,
                                                entry.ipAddress,
                                                entry.sshObfuscatedPort);
                            }
                            catch (IOException e)
                            {
                                // Jump to retry next server if too much time has elapsed; else just retry within this loop
                                if (SystemClock.elapsedRealtime() > preemptiveReconnectWaitUntil + preemptiveReconnectTimePeriod)
                                {
                                    MyLog.w(R.string.preemptive_socket_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                                    throw e;
                                }
                                newSocket = null;
                            }

                            if (newSocket == null)
                            {
                                MyLog.w(R.string.preemptive_socket_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                                // If we can't make a second connection, retry
                                continue;
                            }

                            long nextPreemptiveReconnectWaitUntil = SystemClock.elapsedRealtime() + preemptiveReconnectTimePeriod;

                            checkSignals(0);

                            try
                            {
                                newSshConnection = establishSshConnection(
                                                        this,
                                                        newSocket,
                                                        entry,
                                                        m_interface.getCurrentClientSessionID(),
                                                        m_extraAuthParams);
                            }
                            catch (IOException e)
                            {
                                // Jump to retry next server if too much time has elapsed; else just retry within this loop
                                if (SystemClock.elapsedRealtime() > preemptiveReconnectWaitUntil + preemptiveReconnectTimePeriod)
                                {
                                    MyLog.w(R.string.preemptive_ssh_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                                    throw e;
                                }
                                newSshConnection = null;
                            }

                            if (newSshConnection == null)
                            {
                                newSocket.close();

                                MyLog.w(R.string.preemptive_ssh_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                                // If we can't make a second connection, retry
                                continue;
                            }

                            preemptiveReconnectWaitUntil = nextPreemptiveReconnectWaitUntil;

                            // ...also jump out to retry next server if either connection is unexpectedly disconnected
                            newSshConnection.addConnectionMonitor(new Monitor(m_signalQueue));

                            // Switch SOCKS/transparent proxy servers over to newer SSH connection
                            // Existing proxied connections are owned by the old SSH connection object and will stay open
                            // NOTE: dnsProxy isn't restarted -- it uses the current socks proxy via the transparent proxy

                            // SO_REUSEADDR is used, but we still get EADDRINUSE if the switch over it too fast
                            // See e.g., http://hea-www.harvard.edu/~fine/Tech/addrinuse.html
                            // So if we get BindException, we sleep a short while and retry.

                            if (socks != null)
                            {
                                try
                                {
                                    socks.close();
                                    socks = null;
                                }
                                catch (IOException e)
                                {
                                }
                            }

                            while (SystemClock.elapsedRealtime() < preemptiveReconnectWaitUntil)
                            {
                                checkSignals(0);
                                try
                                {
                                    socks = newSshConnection.createDynamicPortForwarder(PsiphonData.getPsiphonData().getSocksPort());
                                    break;
                                }
                                catch (IOException e)
                                {
                                    socks = null;
                                    if (e instanceof java.net.BindException)
                                    {
                                        MyLog.g("preemptive restart socks: BindException");
                                        Thread.sleep(PsiphonConstants.PREEMPTIVE_RECONNECT_BIND_WAIT_MILLISECONDS);
                                    }
                                    else
                                    {
                                        throw e;
                                    }
                                }
                            }
                            if (socks == null)
                            {
                                MyLog.w(R.string.preemptive_bind_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                            }

                            if (tunnelWholeDevice && !runVpnService)
                            {
                                if (transparentProxy != null)
                                {
                                    try
                                    {
                                        transparentProxy.close();
                                        transparentProxy = null;
                                    }
                                    catch (IOException e)
                                    {
                                    }
                                }

                                // TODO: refactor common code
                                while (SystemClock.elapsedRealtime() < preemptiveReconnectWaitUntil)
                                {
                                    checkSignals(0);
                                    try
                                    {
                                        transparentProxy = newSshConnection.createTransparentProxyForwarder(PsiphonData.getPsiphonData().getTransparentProxyPort());
                                        break;
                                    }
                                    catch (IOException e)
                                    {
                                        transparentProxy = null;
                                        if (e instanceof java.net.BindException)
                                        {
                                            MyLog.g("preemptive restart transparentProxy: BindException");
                                            Thread.sleep(PsiphonConstants.PREEMPTIVE_RECONNECT_BIND_WAIT_MILLISECONDS);
                                        }
                                        else
                                        {
                                            throw e;
                                        }
                                    }
                                }
                                if (transparentProxy == null)
                                {
                                    MyLog.v(R.string.preemptive_bind_failed, MyLog.Sensitivity.NOT_SENSITIVE);
                                }
                            }

                            oldSocket = socket;
                            oldSshConnection = sshConnection;

                            socket = newSocket;
                            sshConnection = newSshConnection;

                            newSocket = null;
                            newSshConnection = null;
                        }
                    }
                }
            }
            catch (TunnelVpnServiceUnexpectedDisconnect e)
            {
                // NOTE: this it re-caught in the outer try. Here we're just
                // setting a flag which determines doPeriodicWork behavior when
                // there's no tunnel.
                hasTunnel = false;
                throw e;
            }
            finally
            {
                cleanupSshConnection(newSocket, newSshConnection);
                cleanupSshConnection(oldSocket, oldSshConnection);

                // At this point, there may be no tunnel and we may need to protect
                // the request socket.
                m_interface.doPeriodicWork(cleanupTun2Socks ? this : null, hasTunnel, true);
            }
        }
        catch (IOException e)
        {
            unexpectedDisconnect = true;

            // SSH errors -- tunnel problems -- result in IOException
            // Make sure we try a different server (if any) next time
            // Note: we're not marking the server failed if handshake/connected requests failed

            m_interface.markCurrentServerFailed();

            // TODO: This prints too much info -- the stack trace, but also IP
            // address (not sure if we want to obscure that or not...)
            //MyLog.e(R.string.error_message, e);
            MyLog.e(R.string.ssh_connection_failed, MyLog.Sensitivity.NOT_SENSITIVE);
        }
        catch (TunnelVpnServiceUnexpectedDisconnect e)
        {
            // NOTE: Not calling MarkCurrentServerFailed(), although there
            // may be a problem with the server. This exception is thrown
            // in the case where the tunnel was successfully established
            // and the connection monitor detected a disconnect. We'll
            // retry the current server at least once. If it still is
            // down -- i.e., not an intermittent problem, we'll expect
            // an IOException on the connection attempt, which will call
            // calling MarkCurrentServerFailed().

            unexpectedDisconnect = true;
            runAgain = true;
        }
        catch (TunnelVpnTunnelStop e)
        {
            unexpectedDisconnect = false;
            runAgain = false;
        }
        catch (InterruptedException e)
        {
            runAgain = false;
        }
        finally
        {
            // Provide visual feedback (notification icon) that we are no longer connected
            setState(State.CONNECTING);

            if (unexpectedDisconnect)
            {
                MyLog.v(R.string.current_network_type, MyLog.Sensitivity.NOT_SENSITIVE, Utils.getNetworkTypeName(m_parentContext));

                if (!isStopSignalPending())
                {
                    // This will invoke the status activity to show that
                    // the tunnel is disconnected. Since that invocation
                    // will also restart the tunnel, be sure not to do
                    // it when a stop is signaled.

                    if (m_eventsInterface != null)
                    {
                        m_eventsInterface.signalUnexpectedDisconnect(m_parentContext);
                    }
                }

                if (PsiphonConstants.TARGET_PROTOCOL_ROTATION_SESSION_DURATION_THRESHOLD_MILLISECONDS >=
                        PsiphonData.getPsiphonData().getDataTransferStats().getElapsedTime())
                {
                    m_targetProtocolState.rotateTarget();
                }
            }

            PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
            PsiphonData.getPsiphonData().setTunnelSessionID("");

            // Abort any outstanding HTTP requests.
            // Currently this would only be the upgrade download request.
            // Otherwise the call below to m_upgradeDownloader.stop() would block.
            m_interface.stop();

            if (cleanupTransparentProxyRouting)
            {
                if (!runAgain)
                {
                    // TODO: refactor: combine with cleanup in runTunnel()
                    try
                    {
                        TransparentProxyConfig.teardownTransparentProxyRouting(m_parentContext);
                        MyLog.v(R.string.transparent_proxy_routing_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
                        activeServices[ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING] = false;
                    }
                    catch (PsiphonTransparentProxyException e)
                    {
                    }
                }
                else
                {
                    activeServices[ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING] = true;
                }
            }

            if (dnsProxy != null)
            {
                dnsProxy.Stop();
                MyLog.v(R.string.dns_proxy_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            if (transparentProxy != null)
            {
                try
                {
                    transparentProxy.close();
                }
                catch (IOException e)
                {
                    // Ignore
                }
                MyLog.v(R.string.transparent_proxy_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            if (cleanupTun2Socks)
            {
                if (!runAgain || PsiphonData.getPsiphonData().getVpnServiceUnavailable() ||
                        PsiphonData.getPsiphonData().getWdmForceIptables())
                {
                    // NOTE: getVpnServiceUnavailable() becomes true when failing over to
                    // iptables mode and in that case we don't leave the tun routing up.

                    // TODO: refactor: combine with cleanup in runTunnel()
                    Tun2Socks.Stop();
                    MyLog.v(R.string.tun2socks_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
                    activeServices[ACTIVE_SERVICE_TUN2SOCKS] = false;
                }
                else
                {
                    // When running again (e.g., unexpected disconnect, or retry connect) we
                    // leave the VpnService tun routing up to avoid leaking traffic outside
                    // the VPN in this case.
                    activeServices[ACTIVE_SERVICE_TUN2SOCKS] = true;
                }
            }

            if (socks != null)
            {
                try
                {
                    socks.close();
                }
                catch (IOException e)
                {
                    // Ignore
                }
                MyLog.v(R.string.socks_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            m_upgradeDownloader.stop();

            if (socket != null || sshConnection != null)
            {
                cleanupSshConnection(socket, sshConnection);
                sshConnection = null;
                socket = null;
                MyLog.v(R.string.ssh_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            if (meekClient != null)
            {
                meekClient.stop();
                meekClient = null;
            }

            PsiphonData.getPsiphonData().getDataTransferStats().stop();
        }

        return runAgain;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public boolean doVpnProtect(Socket socket)
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        if (!((TunnelVpnService)m_parentService).protect(socket))
        {
            String networkTypeName = Utils.getNetworkTypeName(m_parentService);
            MyLog.e(R.string.vpn_service_failed, MyLog.Sensitivity.NOT_SENSITIVE,
                    "protect socket failed (" + networkTypeName + ")");
            return false;
        }
        return true;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public boolean doVpnProtect(DatagramSocket socket)
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        if (!((TunnelVpnService)m_parentService).protect(socket))
        {
            String networkTypeName = Utils.getNetworkTypeName(m_parentService);
            MyLog.e(R.string.vpn_service_failed, MyLog.Sensitivity.NOT_SENSITIVE,
                    "protect datagram socket failed (" + networkTypeName + ")");
            return false;
        }
        return true;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private ParcelFileDescriptor doVpnBuilder(String privateIpAddress)
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        Locale prevLocale = Locale.getDefault();

        ParcelFileDescriptor vpnInterfaceFileDescriptor = null;
        String builderErrorMessage = null;
        try
        {
            String subnet = Utils.getPrivateAddressSubnet(privateIpAddress);
            int prefixLength = Utils.getPrivateAddressPrefixLength(privateIpAddress);
            String router = Utils.getPrivateAddressRouter(privateIpAddress);

            // Set the locale to English (or probably any other language that
            // uses Hindu-Arabic (aka Latin) numerals).
            // We have found that VpnService.Builder does something locale-dependent
            // internally that causes errors when the locale uses its own numerals
            // (i.e., Farsi and Arabic).
            Locale.setDefault(new Locale("en"));

            VpnService.Builder builder = ((TunnelVpnService)m_parentService).newBuilder();
            vpnInterfaceFileDescriptor = builder
                    .setSession(m_parentService.getString(R.string.app_name))
                    .setMtu(PsiphonConstants.VPN_INTERFACE_MTU)
                    .addAddress(privateIpAddress, prefixLength)
                    .addRoute("0.0.0.0", 0)
                    .addRoute(subnet, prefixLength)
                    .addDnsServer(router)
                    .establish();

            if (vpnInterfaceFileDescriptor == null)
            {
                // as per http://developer.android.com/reference/android/net/VpnService.Builder.html#establish%28%29
                builderErrorMessage = "application is not prepared or revoked";
            }
        }
        catch(IllegalArgumentException e)
        {
            builderErrorMessage = e.getMessage();
        }
        catch(IllegalStateException e)
        {
            builderErrorMessage = e.getMessage();
        }
        catch(SecurityException e)
        {
            builderErrorMessage = e.getMessage();
        }
        finally
        {
            // Restore the original locale.
            Locale.setDefault(prevLocale);
        }

        if (vpnInterfaceFileDescriptor == null)
        {
            // If we can't configure the Android OS VPN, abort
            MyLog.e(R.string.vpn_service_failed, MyLog.Sensitivity.NOT_SENSITIVE, builderErrorMessage);
        }

        return vpnInterfaceFileDescriptor;
    }

    private boolean failOverToRootWholeDeviceMode()
    {
        if (Utils.isRooted())
        {
            // VpnService appears to be broken, but we can try root mode instead.
            PsiphonData.getPsiphonData().setVpnServiceUnavailable(true);
            return true;
        }

        return false;
    }

    private static int ACTIVE_SERVICE_TUN2SOCKS = 0;
    private static int ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING = 1;

    private boolean m_isReconnect = false;

    private void runTunnel() throws InterruptedException
    {
        // Check if an upgrade has already been downloaded and is ready for install
        UpgradeManager.UpgradeInstaller.notifyUpgrade(m_parentContext);

        if (!m_interface.serverWithOneOfTheseCapabilitiesExists(PsiphonConstants.SUFFICIENT_CAPABILITIES_FOR_TUNNEL)
                || !m_interface.serverInRegionExists(PsiphonData.getPsiphonData().getEgressRegion()))
        {
            MyLog.e(R.string.no_server_entries, MyLog.Sensitivity.NOT_SENSITIVE);
            return;
        }

        m_isReconnect = false;

        // Active services are components runTunnelOnce leaves active on exit.
        // We use this to keep the routing in place in whole device modes to
        // avoid traffic leakage when failing over to another server.
        boolean[] activeServices = new boolean[]{false, false}; // ACTIVE_SERVICE_TUN2SOCKS, ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING

        while (runTunnelOnce(activeServices))
        {
            try
            {
                checkSignals(0);
            }
            catch (TunnelVpnServiceUnexpectedDisconnect e)
            {
                // Continue with the retry loop
            }
            catch (TunnelVpnTunnelStop e)
            {
                // Stop has been requested, so get out of the retry loop.
                break;
            }

            try
            {
                m_interface.start();
                m_interface.fetchRemoteServerList(activeServices[ACTIVE_SERVICE_TUN2SOCKS] ? this : null);
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.TunnelService_FetchRemoteServerListFailed, MyLog.Sensitivity.NOT_SENSITIVE, requestException);
            }
            finally
            {
                m_interface.stop();
            }

            // 1-2 second delay before retrying
            // (same as Windows client, see comment in ConnectionManager.cpp)
            try
            {
                Thread.sleep(1000 + (long)(Math.random()*1000.0));
            }
            catch (InterruptedException ie)
            {
                break;
            }
        }

        // Clean up active services.

        if (activeServices[ACTIVE_SERVICE_TUN2SOCKS])
        {
            Tun2Socks.Stop();
            MyLog.v(R.string.tun2socks_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
        }

        if (activeServices[ACTIVE_SERVICE_TRANSPARENT_PROXY_ROUTING])
        {
            try
            {
                TransparentProxyConfig.teardownTransparentProxyRouting(m_parentContext);
                MyLog.v(R.string.transparent_proxy_routing_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
            }
            catch (PsiphonTransparentProxyException e)
            {
            }
        }
    }

    public void startTunnel()
    {
        Utils.checkSecureRandom();

        stopTunnel();

        if (m_eventsInterface != null)
        {
            m_eventsInterface.signalTunnelStarting(m_parentContext);
        }

        MyLog.v(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        // Only allow 1 signal at a time. A backlog of signals will break the retry loop.
        m_signalQueue = new ArrayBlockingQueue<Signal>(1);

        m_tunnelThread = new Thread(
            new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        runTunnel();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }

                    if (m_eventsInterface != null)
                    {
                        m_eventsInterface.signalTunnelStopping(m_parentContext);
                    }

                    if (m_parentService != null)
                    {
                        // If the tunnel is stopping itself (e.g., due to a fatal error
                        // where we don't try-next-server), then the service should stop itself.
                        m_parentService.stopForeground(true);
                        m_parentService.stopSelf();
                    }

                    MyLog.v(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
                    MyLog.e(R.string.psiphon_stopped, MyLog.Sensitivity.NOT_SENSITIVE);
                }
            });

        m_tunnelThread.start();
    }

    public void signalUnexpectedDisconnect()
    {
        // Override STOP_TUNNEL; TODO: race condition?
        if (m_signalQueue != null)
        {
            m_signalQueue.clear();
            m_signalQueue.offer(Signal.UNEXPECTED_DISCONNECT);
        }
    }

    public void stopVpnServiceHelper()
    {
        // *Must* have a parent service for this mode
        assert (m_parentService != null);

        // A hack to stop the VpnService, which doesn't respond to normal
        // stopService() calls.

        // Stopping tun2socks will close the VPN interface fd, which
        // in turn stops the VpnService. Without closing the fd, the
        // stopService call has no effect and the only way to stop
        // the VPN is via the OS notification UI.
        Tun2Socks.Stop();

        // Sometimes we're in the state where there's no fd, and the
        // service still isn't responding to external stopService() calls.
        // For example, when stuck in the waiting-for-connectivity check
        // in ServerSelector.
        m_parentService.stopForeground(true);
        m_parentService.stopSelf();
    }

    public void stopTunnel()
    {
        if (m_tunnelThread != null)
        {
            if (m_tunnelThread.isAlive())
            {
                MyLog.v(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);
            }

            // Wake up/interrupt the tunnel thread

            // Override UNEXPECTED_DISCONNECT; TODO: race condition?
            if (m_signalQueue != null)
            {
                m_signalQueue.clear();
                m_signalQueue.offer(Signal.STOP_TUNNEL);
            }

            // Tell the ServerInterface to stop (e.g., kill requests).

            // Currently, all requests are run in the context of the
            // tunnel thread; m_interface.outstandingRequests is not
            // a work queue, it's just a way for another thread to
            // reference the requests and invoke .abort(). Any
            // request that should not abort when the tunnel thread
            // should shut down should be omitted from the
            // outstandingRequests list.

            m_interface.stop();

            // Note: calling m_serverSelector.Abort() only after m_interface.stop()
            // has been called is important as the server selector may be waiting for
            // a fetch remote server list request. Only m_interface.stop() will abort
            // this request. In the case where ServerInterface.fetchRemoteServerList
            // is awaiting network connectivity, it could block forever is not
            // properly cancelled.
            if (m_serverSelector != null)
            {
                m_serverSelector.Abort();
            }

            try
            {
                m_tunnelThread.join();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }

            m_tunnelThread = null;
            m_signalQueue = null;
        }
    }

    public void setEventsInterface(IEvents eventsInterface)
    {
        m_eventsInterface = eventsInterface;
    }

    public ServerInterface getServerInterface()
    {
        return m_interface;
    }

    public void setUseGenericLogMessages(boolean useGenericLogMessages)
    {
        m_useGenericLogMessages = useGenericLogMessages;
    }

    public void setExtraAuthParams(List<Pair<String,String>> extraAuthParams)
    {
        m_extraAuthParams.clear();

        for (Pair<String,String> extraAuthParam : extraAuthParams)
        {
            m_extraAuthParams.add(Pair.create(extraAuthParam.first, extraAuthParam.second));
        }
    }
}
