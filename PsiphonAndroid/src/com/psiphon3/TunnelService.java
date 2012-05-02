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
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
    private Thread m_tunnelThread;
    private ServerInterface m_interface;

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

    private boolean runTunnelOnce() throws InterruptedException
    {
        ServerInterface.ServerEntry entry = m_interface.getCurrentServerEntry();

        boolean runAgain = true;
        Connection conn = null;
        DynamicPortForwarder socks = null;
        boolean unexpectedDisconnect = false;
        
        try
        {
            MyLog.i(R.string.ssh_connecting);
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            Monitor monitor = new Monitor(m_signalQueue);
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
            // and will consume the signal queue (assumes this code
            // will fall through to the while loop below...)
            conn.addConnectionMonitor(monitor);
            
            setState(State.CONNECTED);
            
            try
            {
                m_interface.doHandshakeRequest();
                MyLog.d("TEMP: Handshake success");

                Events.signalHandshakeSuccess(this);
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
            
            boolean closeTunnel = false;
            
            while (!closeTunnel)
            {
                Signal signal = m_signalQueue.poll(10, TimeUnit.SECONDS);
                
                if (signal != null)
                {
                    switch (signal)
                    {
                    case STOP_SERVICE:
                        MyLog.d("TEMP: runTunnelOnce: stop signal");
                        unexpectedDisconnect = false;
                        runAgain = false;
                        closeTunnel = true;
                        break;
                    case UNEXPECTED_DISCONNECT:
                        MyLog.d("TEMP: runTunnelOnce: unexpected disconnect signal");
                        // TODO: need to call MarkCurrentServerFailed()?
                        unexpectedDisconnect = true;
                        runAgain = true;
                        closeTunnel = true;
                        break;
                    }
                }

                m_interface.doPeriodicWork(closeTunnel);
            }
        }
        catch (IOException e)
        {
        	unexpectedDisconnect = true;

        	// SSH errors -- tunnel problems -- result in IOException
            // Make sure we try a different server (if any) next time
            // Note: we're not marking the server failed if handshake/connected requests failed
            
            m_interface.markCurrentServerFailed();

            // This prints too much info -- the stack trace, but also IP
            // address (not sure if we want to obscure that or not...) 
            // MyLog.e(R.string.error_message, e);
            MyLog.e(R.string.ssh_connection_failed);
        }
        finally
        {
            MyLog.d("TEMP: runTunnelOnce: finally");

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

            setState(State.DISCONNECTED);
            
            if (unexpectedDisconnect)
            {
                Events.signalUnexpectedDisconnect(this);
            }
        }
        
        return runAgain;
    }
    
    private void runTunnel() throws InterruptedException
    {
        while (runTunnelOnce())
        {
            // 2-4 second delay before retrying
            // (same as Windows client, see comment in ConnectionManager.cpp)
            try
            {
                Thread.sleep(2000 + (long)(Math.random()*4000.0));
            }
            catch (InterruptedException ie) {}            
        }
    }
    
    public void startTunnel()
    {
        stopTunnel();

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
                m_signalQueue.put(Signal.STOP_SERVICE);                
                m_tunnelThread.join();
            }
            catch (InterruptedException e) {}
        }

        m_signalQueue = null;
        m_tunnelThread = null;
    }
}
