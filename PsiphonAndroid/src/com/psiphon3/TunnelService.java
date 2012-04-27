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
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import ch.ethz.ssh2.*;

import com.psiphon3.StatusActivity;
import com.psiphon3.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.Utils.MyLog;

public class TunnelService extends Service implements Utils.MyLog.ILogger
{
    enum State
    {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }
    private State m_state = State.DISCONNECTED;
    private boolean m_firstStart = true;
    private CountDownLatch m_stopSignal;
    private Thread m_tunnelThread;
    private ServerInterface m_interface;

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
            doForeground();
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

        Intent intent = new Intent(
                "ACTION_VIEW",
                Uri.EMPTY,
                this,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent invokeActivityIntent = 
            PendingIntent.getActivity(
                this,
                0,
                intent,
                0);

        notification.setLatestEventInfo(
            this,
            getText(R.string.psiphon_service_notification_message),
            getText(R.string.app_name),
            invokeActivityIntent);

        startForeground(R.string.psiphon_service_notification_id, notification);
    }    

    /**
     * Utils.MyLog.ILogger implementation
     * For Android priority values, see <a href="http://developer.android.com/reference/android/util/Log.html">http://developer.android.com/reference/android/util/Log.html</a>
     */
    @Override
    public int getAndroidLogPriorityEquivalent(int priority)
    {
        switch (priority)
        {
        case Log.ERROR:
            return StatusActivity.MESSAGE_CLASS_ERROR;
        case Log.INFO:
            return StatusActivity.MESSAGE_CLASS_INFO;
        case Log.DEBUG:
            return StatusActivity.MESSAGE_CLASS_DEBUG;
        default:
            return StatusActivity.MESSAGE_CLASS_WARNING;
        }
    }

    @Override
    public String getResString(int stringResID, Object... formatArgs)
    {
        if (formatArgs == null || formatArgs.length == 0)
        {
            return getString(stringResID);
        }
        
        return getString(stringResID, formatArgs);
    }
    
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
        private String expectedHostKey;
        
        PsiphonServerHostKeyVerifier(String expectedHostKey)
        {
            this.expectedHostKey = expectedHostKey;
        }

        public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey)
        {
            return 0 == this.expectedHostKey.compareTo(Utils.Base64.encode(serverHostKey));
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
        public void connectionLost(Throwable reason)
        {
            m_stopSignal.countDown();
            MyLog.e(R.string.ssh_disconnected_unexpectedly);
        }
    }

    private boolean runTunnelOnce()
    {
        boolean stopped = false;

        ServerInterface.ServerEntry entry = m_interface.getCurrentServerEntry();

        Connection conn = null;
        Monitor  monitor = new Monitor();
        DynamicPortForwarder socks = null;
        NativeWrapper polipo = null;
        boolean unexpectedDisconnect = false;
        
        try
        {
            MyLog.i(R.string.ssh_connecting);
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            conn.addConnectionMonitor(monitor);
            conn.connect(
                    new PsiphonServerHostKeyVerifier(entry.sshHostKey),
                    PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS,
                    PsiphonConstants.SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS);
            MyLog.i(R.string.ssh_connected);

            MyLog.i(R.string.ssh_authenticating);
            boolean isAuthenticated = conn.authenticateWithPassword(entry.sshUsername, entry.sshPassword);
            if (isAuthenticated == false)
            {
                MyLog.e(R.string.ssh_authentication_failed);
                return stopped;
            }
            MyLog.i(R.string.ssh_authenticated);

            MyLog.i(R.string.socks_starting);
            socks = conn.createDynamicPortForwarder(PsiphonConstants.SOCKS_PORT);
            MyLog.i(R.string.socks_running);

            // The HTTP proxy implementation is provided by Polipo,
            // a native app that we spawn as a separate process. This
            // proxy is chained to our SOCKS proxy.

            // TODO: is this native process subject to shutdown, similar
            // to regular Services (which we prevent with startForeground)?

            // TODO: there's a security concern here - if the HTTP proxy
            // remains running after the main process dies, a malicious
            // app could plug in its own SOCKS proxy and capture all
            // Psiphon browser activity.
            
            MyLog.i(R.string.http_proxy_starting);
            polipo = new NativeWrapper(
                            this,
                            PsiphonConstants.POLIPO_EXECUTABLE,
                            PsiphonConstants.POLIPO_ARGUMENTS);
            MyLog.i(R.string.http_proxy_running);
            
            setState(State.CONNECTED);
            
            try
            {
                m_interface.doHandshakeRequest();
                MyLog.d("TEMP: Handshake success");

                // Open home pages
                //TODO: get real homepage URL
                Events.displayBrowser(this);
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.PsiphonAndroidService_HandshakeRequestFailed, requestException);
                // Allow the user to continue. Their session might still function correctly.
            }

            try
            {
                m_interface.doConnectedRequest();
                MyLog.d("TEMP: Connected request success");
            } 
            catch (PsiphonServerInterfaceException requestException)
            {
                MyLog.w(R.string.PsiphonAndroidService_ConnectedRequestFailed, requestException);
                // Allow the user to continue. Their session might still function correctly.
            }
            
            try
            {
                while (true)
                {
                    boolean stop = m_stopSignal.await(10, TimeUnit.SECONDS);

                    m_interface.doPeriodicWork(stop);

                    if (!polipo.isRunning())
                    {
                        MyLog.e(R.string.http_proxy_stopped_unexpectedly);
                        unexpectedDisconnect = true;
                        break;
                    }

                    if (stop)
                    {
                        stopped = true;
                        break;
                    }
                }
            }
            catch (InterruptedException e) {}            
        }
        catch (IOException e)
        {
        	unexpectedDisconnect = true;

        	// SSH and Polipo errors -- tunnel problems -- result in IOException
            // Make sure we try a different server (if any) next time
            // Note: we're not marking the server failed if handshake/connected requests failed
            
            m_interface.markCurrentServerFailed();

            // 1-2 second delay before retrying
            // (same as Windows client, see comment in ConnectionManager.cpp)
            try
            {
                Thread.sleep(1000 + (long)(Math.random()*1000.0));
            }
            catch (InterruptedException ie) {}
            
            MyLog.e(R.string.error_message, e);

            return stopped;
        }
        finally
        {
            if (polipo != null)
            {
                polipo.stop();
                MyLog.w(R.string.http_proxy_stopped);
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
                MyLog.w(R.string.socks_stopped);
            }
            if (conn != null)
            {
                conn.close();
                MyLog.w(R.string.ssh_stopped);
            }

            setState(State.DISCONNECTED);
            
            if (unexpectedDisconnect)
            {
                Events.displayStatus(this);
            }
        }
        
        return stopped;
    }
    
    private void runTunnel()
    {
        boolean stopped = false;
        while (!stopped)
        {
            stopped = runTunnelOnce();
        }
    }
    
    public void startTunnel()
    {
        stopTunnel();
        setState(State.CONNECTING);
        m_stopSignal = new CountDownLatch(1);
        m_tunnelThread = new Thread(
            new Runnable()
            {
                public void run()
                {
                    runTunnel();
                }
            });

        m_tunnelThread.start();
    }
    
    public void stopTunnel()
    {
        if (m_tunnelThread != null)
        {
            m_stopSignal.countDown();
    
            try
            {
                m_tunnelThread.join();
            }
            catch (InterruptedException e)
            {
            }
        }

        m_stopSignal = null;
        m_tunnelThread = null;
    }
}
