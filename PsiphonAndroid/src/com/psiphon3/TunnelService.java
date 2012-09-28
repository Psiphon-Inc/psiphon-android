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

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import ch.ethz.ssh2.*;
import ch.ethz.ssh2.Connection.IStopSignalPending;

import com.psiphon3.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.TransparentProxyConfig.PsiphonTransparentProxyException;
import com.psiphon3.UpgradeManager;
import com.psiphon3.Utils.MyLog;
import com.stericson.RootTools.RootTools;

public class TunnelService extends Service implements Utils.MyLog.ILogger, IStopSignalPending
{
    enum State
    {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    private State m_state = State.DISCONNECTED;
    private boolean m_firstStart = true;
    private Thread m_tunnelThread;
    private ServerInterface m_interface;
    private UpgradeManager.UpgradeDownloader m_upgradeDownloader;
    private boolean m_destroyed = false;

    enum Signal
    {
        STOP_SERVICE,
        UNEXPECTED_DISCONNECT
    };
    private BlockingQueue<Signal> m_signalQueue;

    
    public class LocalBinder extends Binder
    {
        TunnelService getService()
        {
            return TunnelService.this;
        }
    }
    
    @Override
    public IBinder onBind(Intent intent)
    {
        return new LocalBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {        
        if (m_firstStart)
        {
            // TODO: put this stuff in onCreate instead?

            MyLog.logger = this;
            m_interface = new ServerInterface(this);
            m_upgradeDownloader = new UpgradeManager.UpgradeDownloader(this, m_interface);
            doForeground();
            MyLog.v(R.string.client_version, EmbeddedValues.CLIENT_VERSION);
            startTunnel();
            m_firstStart = false;
        }
        return android.app.Service.START_STICKY;
    }

    @Override
    public void onCreate()
    {
    }

    @Override
    public void onDestroy()
    {
    	m_destroyed = true;
        stopTunnel();
    }

    private void doForeground()
    {
        startForeground(R.string.psiphon_service_notification_id, this.createNotification());
    }
    
    private Notification createNotification()
    {
    	int contentTextID = -1;
    	int iconID = -1;
    	
    	switch (getState())
    	{
    	case CONNECTING:
    		contentTextID = R.string.psiphon_service_notification_message_connecting;
    		iconID = R.drawable.notification_icon_connecting;
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
    		
    		iconID = R.drawable.notification_icon_connected;
    		break;
    		
    	case DISCONNECTED:
    		contentTextID = R.string.psiphon_stopped;
    		iconID = R.drawable.notification_icon_disconnected;
    		break;
    	
		default:
			assert(false);    			
    	}
    	
        PendingIntent invokeActivityIntent = 
                PendingIntent.getActivity(
                    this,
                    0,
                    Events.pendingSignalNotification(this),
                    PendingIntent.FLAG_UPDATE_CURRENT);

	    Notification notification =
	            new Notification(
	            		iconID,
		                getText(R.string.app_name),
		                System.currentTimeMillis());
	
	    notification.setLatestEventInfo(
	        this,
	        getText(R.string.app_name),
	        getText(contentTextID),
	        invokeActivityIntent); 
    	
    	return notification;
    }

    /**
     * Utils.MyLog.ILogger implementation
     */
    @Override
    public void log(int priority, String message)
    {
        sendMessage(message, priority);
    }
    
    private synchronized void sendMessage(
            String message,
            int messageClass)
    {
        // Record messages for play back in activity
        PsiphonData.getPsiphonData().addStatusMessage(message, messageClass);
        
        Events.appendStatusMessage(this, message, messageClass);
    }
        
    class PsiphonServerHostKeyVerifier implements ServerHostKeyVerifier
    {
        private String m_expectedHostKey;
        
        PsiphonServerHostKeyVerifier(String expectedHostKey)
        {
            m_expectedHostKey = expectedHostKey;
        }

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
        m_state = newState;
        
        if (!this.m_destroyed)
        {
        	String ns = Context.NOTIFICATION_SERVICE;
        	NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);
            mNotificationManager.notify(
            		R.string.psiphon_service_notification_id, 
            		createNotification());
        }
    }
    
    class Monitor implements ConnectionMonitor
    {
        private BlockingQueue<Signal> m_signalQueue;

        public Monitor(BlockingQueue<Signal> signalQueue)
        {
            m_signalQueue = signalQueue;
        }

        public void connectionLost(Throwable reason)
        {
            MyLog.e(R.string.ssh_disconnected_unexpectedly);
    
            // 'Add' will do nothing if there's already a pending signal.
            // This is ok: the pending signal is either UNEXPECTED_DISCONNECT
            // or STOP_SERVICE, and both will result in a tear down.
            m_signalQueue.add(Signal.UNEXPECTED_DISCONNECT);
        }
    }

    private static class TunnelServiceUnexpectedDisconnect extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public TunnelServiceUnexpectedDisconnect()
        {
            super();
        }
    }
    
    
    private static class TunnelServiceStop extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public TunnelServiceStop()
        {
            super();
        }
    }
    
    private void checkSignals(int waitTimeSeconds)
            throws InterruptedException, TunnelServiceUnexpectedDisconnect, TunnelServiceStop
    {
        Signal signal = m_signalQueue.poll(waitTimeSeconds, TimeUnit.SECONDS);
        
        if (signal != null)
        {
            switch (signal)
            {
            case STOP_SERVICE:
                throw new TunnelServiceStop();
            case UNEXPECTED_DISCONNECT:
                throw new TunnelServiceUnexpectedDisconnect();
            }
        }
    }
    
    public boolean isStopSignalPending()
    {
        return m_signalQueue.peek() == Signal.STOP_SERVICE;
    }
    
    private boolean runTunnelOnce()
    {
    	setState(State.CONNECTING);
    	
        PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
        PsiphonData.getPsiphonData().setTunnelSessionID("");

        m_interface.start();
        
        // Generate a new client session ID to be included with all subsequent web requests
        // It's also included with the SSH login, for GeoIP region lookup on the server-side
        m_interface.generateNewCurrentClientSessionID();
        
        ServerInterface.ServerEntry entry = m_interface.getCurrentServerEntry();

        if (entry == null)
        {
            MyLog.e(R.string.no_server_entries);
            return false;
        }
        
        boolean runAgain = true;
        boolean unexpectedDisconnect = false;
        Connection conn = null;
        DynamicPortForwarder socks = null;
        TransparentProxyPortForwarder transparentProxy = null;
        DnsProxy dnsProxy = null;
        boolean cleanupTransparentProxyRouting = false;
        
        try
        {
            // Wait for network connectivity before proceeding

            MyLog.v(R.string.waiting_for_network_connectivity);

            while (!Utils.hasNetworkConnectivity(this))
            {
            	setState(State.DISCONNECTED);
                // Sleep 1 second before checking again
                checkSignals(1);
            }
            
            setState(State.CONNECTING);
            
            boolean tunnelWholeDevice = PsiphonData.getPsiphonData().getTunnelWholeDevice();
            
            if (tunnelWholeDevice)
            {
                // Check for required root access *before* establishing the SSH connection
                
                MyLog.v(R.string.checking_for_root_access);

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
                        MyLog.e(R.string.root_access_denied);
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

            MyLog.v(R.string.ssh_connecting);
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            Monitor monitor = new Monitor(m_signalQueue);
            conn.connect(
                    new PsiphonServerHostKeyVerifier(entry.sshHostKey),
                    0,
                    PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS,
                    this);
            MyLog.v(R.string.ssh_connected);

            checkSignals(0);

            // Client transmits its session ID prepended to the SSH password; the server
            // uses this to associate the tunnel with web requests -- for GeoIP region stats
            String sshPassword = m_interface.getCurrentClientSessionID() + entry.sshPassword;

            MyLog.v(R.string.ssh_authenticating);
            boolean isAuthenticated = conn.authenticateWithPassword(entry.sshUsername, sshPassword);
            if (isAuthenticated == false)
            {
                MyLog.e(R.string.ssh_authentication_failed);
                return runAgain;
            }
            MyLog.v(R.string.ssh_authenticated);

            MyLog.v(R.string.socks_starting);

            // If polipo is already running, we must use the same SOCKS port that polipo is
            // already using as it's parent proxy port.
            if (Polipo.isPolipoThreadRunning())
            {
                if (!Utils.isPortAvailable(PsiphonData.getPsiphonData().getSocksPort()))
                {
                    MyLog.e(R.string.socks_port_in_use, PsiphonData.getPsiphonData().getSocksPort());
                    runAgain = false;
                    return runAgain;
                }
            }
            else
            {
                int port = Utils.findAvailablePort(PsiphonConstants.SOCKS_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.socks_ports_failed);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setSocksPort(port);
            }

            socks = conn.createDynamicPortForwarder(PsiphonData.getPsiphonData().getSocksPort());
            MyLog.v(R.string.socks_running, PsiphonData.getPsiphonData().getSocksPort());

            // The HTTP proxy implementation is provided by Polipo,
            // a native application accessed via JNI. This proxy is
            // chained to our SOCKS proxy.

            // TODO: there's a security concern here - if the HTTP proxy
            // remains running after the main process dies, a malicious
            // app could plug in its own SOCKS proxy and capture all
            // Psiphon browser activity.
            
            Polipo.getPolipo().runForever();

            if (PsiphonData.getPsiphonData().getHttpProxyPort() == 0)
            {
                MyLog.e(R.string.http_proxy_ports_failed);
                runAgain = false;
                return runAgain;
            }

            MyLog.v(R.string.http_proxy_running, PsiphonData.getPsiphonData().getHttpProxyPort());
            
            // Start transparent proxy, DNS proxy, and iptables config
                        
            if (tunnelWholeDevice)
            {
                // TODO: findAvailablePort is only effective for TCP services
                int port = Utils.findAvailablePort(PsiphonConstants.DNS_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.dns_proxy_ports_failed);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setDnsProxyPort(port);
    
                dnsProxy = new DnsProxy(
                                "8.8.8.8", // TEMP. TODO: get remote address/port from Psiphon server
                                53,
                                PsiphonData.getPsiphonData().getDnsProxyPort());
    
                if (!dnsProxy.Start())
                {
                    // If we can't run the local DNS proxy, abort
                    runAgain = false;
                    return runAgain;             
                }
                
                MyLog.v(R.string.dns_proxy_running, PsiphonData.getPsiphonData().getDnsProxyPort());            
                
                port = Utils.findAvailablePort(PsiphonConstants.TRANSPARENT_PROXY_PORT, 10);
                if (port == 0)
                {
                    MyLog.e(R.string.transparent_proxy_ports_failed);
                    runAgain = false;
                    return runAgain;
                }
                PsiphonData.getPsiphonData().setTransparentProxyPort(port);
    
                transparentProxy = conn.createTransparentProxyForwarder(PsiphonData.getPsiphonData().getTransparentProxyPort());
    
                try
                {
                    TransparentProxyConfig.setupTransparentProxyRouting(this);
                    cleanupTransparentProxyRouting = true;
                }
                catch (PsiphonTransparentProxyException e)
                {
                    // If we can't configure the iptables routing, abort
                    MyLog.e(R.string.transparent_proxy_failed, e.getMessage());
                    runAgain = false;
                    return runAgain;
                }
                
                MyLog.v(R.string.transparent_proxy_running, PsiphonData.getPsiphonData().getTransparentProxyPort());
            }
            
            // Don't signal unexpected disconnect until we've started
            conn.addConnectionMonitor(monitor);
            
            setState(State.CONNECTED);
            PsiphonData.getPsiphonData().setTunnelRelayProtocol(PsiphonConstants.RELAY_PROTOCOL);
            
            checkSignals(0);

            try
            {
                m_interface.doHandshakeRequest();
                PsiphonData.getPsiphonData().setTunnelSessionID(m_interface.getCurrentServerSessionID());

                Events.signalHandshakeSuccess(this);
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.e(R.string.PsiphonAndroidService_HandshakeRequestFailed, requestException);

                // Treat this case like a tunnel failure -- we don't want to proceed without
                // a session ID, home page, etc. We don't expect it's likely that the handshake
                // will fail if the tunnel is successfully established.
                throw new IOException();
            }
            
            MyLog.i(tunnelWholeDevice ? R.string.psiphon_running_whole_device : R.string.psiphon_running_browser_only);

            checkSignals(0);

            try
            {
                m_interface.doConnectedRequest();
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.PsiphonAndroidService_ConnectedRequestFailed, requestException);
                // Allow the user to continue. Their session might still function correctly.
            }
            
            checkSignals(0);

            if (m_interface.isUpgradeAvailable())
            {
                m_upgradeDownloader.start();
            }
            
            try
            {
                // This busy-wait-ish loop is throttled by the `checkSignals(1)`
                // call. It will wait for 1 second before proceeding to the 
                // `doPeriodicWork()` call (which itself only takes action every
                // half-hour).
                while (true)
                {
                    checkSignals(1);
    
                    m_interface.doPeriodicWork(false);
                }
            }
            finally
            {
                m_interface.doPeriodicWork(true);
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
            MyLog.e(R.string.ssh_connection_failed);
        }
        catch (TunnelServiceUnexpectedDisconnect e)
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
        catch (TunnelServiceStop e)
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
            PsiphonData.getPsiphonData().setTunnelRelayProtocol("");
            PsiphonData.getPsiphonData().setTunnelSessionID("");

            // Abort any outstanding HTTP requests.
            // Currently this would only be the upgrade download request.
            // Otherwise the call below to m_upgradeDownloader.stop() would block.
            m_interface.stop();

            if (cleanupTransparentProxyRouting)
            {
                try
                {
                    TransparentProxyConfig.teardownTransparentProxyRouting(this);
                }
                catch (PsiphonTransparentProxyException e)
                {
                }
            }
            
            if (dnsProxy != null)
            {
                dnsProxy.Stop();
                MyLog.v(R.string.dns_proxy_stopped);                
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
                MyLog.v(R.string.transparent_proxy_stopped);                
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
                MyLog.v(R.string.socks_stopped);
            }

            if (conn != null)
            {
                conn.clearConnectionMonitors();
                conn.close();
                MyLog.v(R.string.ssh_stopped);
            }
            
            m_upgradeDownloader.stop();

            setState(State.DISCONNECTED);
            
            if (unexpectedDisconnect && !isStopSignalPending())
            {
                // This will invoke the status activity to show that
                // the tunnel is disconnected. Since that invocation
                // will also restart the tunnel, be sure not to do
                // it when a stop is signaled.
                
                Events.signalUnexpectedDisconnect(this);
            }
        }
        
        return runAgain;
    }
    
    private void runTunnel() throws InterruptedException
    {
        while (runTunnelOnce())
        {
            try
            {
                checkSignals(0);
            } 
            catch (TunnelServiceUnexpectedDisconnect e)
            {
                // Continue with the retry loop
            } 
            catch (TunnelServiceStop e)
            {
                // Stop has been requested, so get out of the retry loop.
                break;
            }
            
            try
            {
                // TODO: move to background thread...?
                m_interface.start();
                m_interface.fetchRemoteServerList();
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.TunnelService_FetchRemoteServerListFailed, requestException);
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
    }
    
    public void startTunnel()
    {
        stopTunnel();

        MyLog.v(R.string.starting_tunnel);

        setState(State.CONNECTING);
        
        // Only allow 1 signal at a time. A backlog of signals will break the retry loop.
        m_signalQueue = new ArrayBlockingQueue<Signal>(1);

        m_tunnelThread = new Thread(
            new Runnable()
            {
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
                }
            });

        m_tunnelThread.start();
    }
    
    public void stopTunnel()
    {
        if (m_tunnelThread != null)
        {
            try
            {
                MyLog.v(R.string.stopping_tunnel);
                
                // Override UNEXPECTED_DISCONNECT; TODO: race condition?
                m_signalQueue.clear();
                m_signalQueue.offer(Signal.STOP_SERVICE);

                // Tell the ServerInterface to stop (e.g., kill requests).

                // Currently, all requests are run in the context of the
                // tunnel thread; m_interface.outstandingRequests is not
                // a work queue, it's just a way for another thread to
                // reference the requests and invoke .abort(). Any
                // request that should not abort when the tunnel thread
                // should shut down should be omitted from the
                // outstandingRequests list.

                m_interface.stop();
                
                m_tunnelThread.join();

                MyLog.v(R.string.stopped_tunnel);
                MyLog.e(R.string.psiphon_stopped);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        
        m_signalQueue = null;
        m_tunnelThread = null;
    }
}
