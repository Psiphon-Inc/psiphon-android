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
import java.util.Date;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import ch.ethz.ssh2.*;
import ch.ethz.ssh2.Connection.IStopSignalPending;

import com.psiphon3.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.UpgradeManager;
import com.psiphon3.Utils.MyLog;

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
            MyLog.i(R.string.client_version, EmbeddedValues.CLIENT_VERSION);
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
        stopTunnel();
    }

    private void doForeground()
    {
        Notification notification =
            new Notification(
                R.drawable.notification_icon,
                getText(R.string.app_name),
                System.currentTimeMillis());

        PendingIntent invokeActivityIntent = 
            PendingIntent.getActivity(
                this,
                0,
                Events.pendingSignalNotification(this),
                PendingIntent.FLAG_UPDATE_CURRENT);

        notification.setLatestEventInfo(
            this,
            getText(R.string.psiphon_service_notification_message),
            getText(R.string.app_name),
            invokeActivityIntent);

        startForeground(R.string.psiphon_service_notification_id, notification);
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
    
    private boolean runTunnelOnce() throws InterruptedException
    {
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
        
        try
        {            
            checkSignals(0);

            MyLog.i(R.string.ssh_connecting);
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            Monitor monitor = new Monitor(m_signalQueue);
            conn.connect(
                    new PsiphonServerHostKeyVerifier(entry.sshHostKey),
                    0,
                    PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS,
                    this);
            MyLog.i(R.string.ssh_connected);

            checkSignals(0);

            MyLog.i(R.string.ssh_authenticating);
            boolean isAuthenticated = conn.authenticateWithPassword(entry.sshUsername, entry.sshPassword);
            if (isAuthenticated == false)
            {
                MyLog.e(R.string.ssh_authentication_failed);
                return runAgain;
            }
            MyLog.i(R.string.ssh_authenticated);

            MyLog.i(R.string.socks_starting);
            socks = conn.createDynamicPortForwarder(PsiphonConstants.SOCKS_PORT);
            MyLog.i(R.string.socks_running);

            // The HTTP proxy implementation is provided by Polipo,
            // a native application accessed via JNI. This proxy is
            // chained to our SOCKS proxy.

            // TODO: there's a security concern here - if the HTTP proxy
            // remains running after the main process dies, a malicious
            // app could plug in its own SOCKS proxy and capture all
            // Psiphon browser activity.
            
            Polipo.getPolipo().runForever();
            
            // Don't signal unexpected disconnect until we've started
            conn.addConnectionMonitor(monitor);
            
            setState(State.CONNECTED);
            
            checkSignals(0);

            try
            {
                m_interface.doHandshakeRequest();

                Events.signalHandshakeSuccess(this);
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.e(R.string.PsiphonAndroidService_HandshakeRequestFailed, requestException);
                throw requestException;
            }

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
        catch (PsiphonServerInterfaceException e)
        {
            // Drop into finally...
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
        finally
        {
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
                MyLog.w(R.string.socks_stopped);
            }
            if (conn != null)
            {
                conn.clearConnectionMonitors();
                conn.close();
                MyLog.w(R.string.ssh_stopped);
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
                m_interface.fetchRemoteServerList();
            }
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.TunnelService_FetchRemoteServerListFailed, requestException);
            }

            // 1-2 second delay before retrying
            // (same as Windows client, see comment in ConnectionManager.cpp)
            try
            {
                Thread.sleep(1000 + (long)(Math.random()*1000.0));
            }
            catch (InterruptedException ie) {}
        }
    }
    
    public void startTunnel()
    {
        stopTunnel();

        MyLog.w(R.string.starting_tunnel);

        setState(State.CONNECTING);
        
        m_interface.start();

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
                    catch (InterruptedException e) {}
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
                MyLog.w(R.string.stopping_tunnel);
                
                // Override UNEXPECTED_DISCONNECT
                // TODO: race condition?
                m_signalQueue.clear();
                m_signalQueue.offer(Signal.STOP_SERVICE);

                // Tell the ServerInterface to stop (e.g., kill requests).
                m_interface.stop();
                
                m_tunnelThread.join();

                MyLog.w(R.string.stopped_tunnel);
            }
            catch (InterruptedException e) {}
        }
        
        m_signalQueue = null;
        m_tunnelThread = null;
    }
}
