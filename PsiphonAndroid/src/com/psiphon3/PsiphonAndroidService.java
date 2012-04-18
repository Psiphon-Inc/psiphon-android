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
import android.support.v4.content.LocalBroadcastManager;

import ch.ethz.ssh2.*;

import com.psiphon3.PsiphonAndroidActivity;
import com.psiphon3.PsiphonAndroidStats;
import com.psiphon3.PsiphonServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.Utils.MyLog;

public class PsiphonAndroidService extends Service implements Utils.MyLog.ILogger
{
    private boolean firstStart = true;
    // TODO: use this? private Utils.CircularArrayList<Message> m_messages = new Utils.CircularArrayList<Message>(1000);
    private ArrayList<Message> m_messages = new ArrayList<Message>();
    private LocalBroadcastManager m_localBroadcastManager;
    private CountDownLatch m_stopSignal;
    private Thread m_tunnelThread;
    private PsiphonServerInterface m_interface;

    public class LocalBinder extends Binder
    {
        PsiphonAndroidService getService()
        {
            return PsiphonAndroidService.this;
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
        MyLog.d("PsiphonAndroidService.onStartCommand called, firstStart = " + (firstStart ? "true" : "false"));
        if (firstStart)
        {
            // TODO: put this stuff in onCreate instead?

            // NOTE: There must be no attempts to access the UI activity before this call.
            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);

            MyLog.logger = this;
            m_interface = new PsiphonServerInterface(this);
            doForeground();
            startTunnel();
            firstStart = false;
        }
        return android.app.Service.START_STICKY;
    }

    public class Message
    {
        public String m_message;
        public int m_messageClass;

        Message(String message, int messageClass) 
        {
            m_message = message;
            m_messageClass = messageClass;
        }
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
            return PsiphonAndroidActivity.MESSAGE_CLASS_ERROR;
        case Log.INFO:
            return PsiphonAndroidActivity.MESSAGE_CLASS_INFO;
        case Log.DEBUG:
            return PsiphonAndroidActivity.MESSAGE_CLASS_DEBUG;
        default:
            return PsiphonAndroidActivity.MESSAGE_CLASS_WARNING;
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
    
    private synchronized void sendMessage(String message)
    {
        sendMessage(message, PsiphonAndroidActivity.MESSAGE_CLASS_INFO);
    }
    
    private synchronized void sendMessage(
            String message,
            int messageClass)
    {
        // Record messages for playback in activity
        m_messages.add(new Message(message, messageClass));
        
        if (m_localBroadcastManager == null) return;

        Intent intent = new Intent(PsiphonAndroidActivity.ADD_MESSAGE);
        intent.putExtra(PsiphonAndroidActivity.ADD_MESSAGE_TEXT, message);
        intent.putExtra(PsiphonAndroidActivity.ADD_MESSAGE_CLASS, messageClass);
        m_localBroadcastManager.sendBroadcast(intent);
    }
    
    public ArrayList<Message> getMessages()
    {
        ArrayList<Message> messages = new ArrayList<Message>();
        messages.addAll(m_messages);
        return messages;
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
        // TODO: update notification icon when tunnel is connected

        Notification notification =
            new Notification(
                R.drawable.notification_icon,
                getText(R.string.app_name),
                System.currentTimeMillis());

        PendingIntent invokeActivityIntent = 
            PendingIntent.getActivity(
                this,
                0,
                new Intent(this, PsiphonAndroidActivity.class),
                0);

        notification.setLatestEventInfo(
            this,
            getText(R.string.psiphon_service_notification_message),
            getText(R.string.app_name),
            invokeActivityIntent);

        startForeground(R.string.psiphon_service_notification_id, notification);
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
    
    private void runTunnel()
    {
        PsiphonServerInterface.ServerEntry entry = m_interface.getCurrentServerEntry();

        Connection conn = null;
        DynamicPortForwarder socks = null;
        PsiphonNativeWrapper polipo = null;

        try
        {
            MyLog.i(R.string.ssh_connecting);
            conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
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
                return;
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
            polipo = new PsiphonNativeWrapper(
                            this,
                            PsiphonConstants.POLIPO_EXECUTABLE,
                            PsiphonConstants.POLIPO_ARGUMENTS);
            MyLog.i(R.string.http_proxy_running);
            
            try
            {
                m_interface.doHandshakeRequest();
                MyLog.d("TEMP: Handshake success");
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

            Intent i = new Intent(this, org.zirco.ui.activities.MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            //TODO: get real homepage URL
            i.setData(Uri.parse("http://vl7.net/ip"));
            startActivity(i);
            
            try
            {
                while (true)
                {
                    boolean stop = m_stopSignal.await(10, TimeUnit.SECONDS);

                    PsiphonAndroidStats.getStats().dumpReport();

                    if (!polipo.isRunning())
                    {
                        MyLog.e(R.string.http_proxy_stopped_unexpectedly);
                        break;
                    }

                    if (stop)
                    {
                        break;
                    }
                }
            }
            catch (InterruptedException e)
            {
            }            
        }
        catch (IOException e)
        {
            MyLog.e(R.string.error_message, e);
            return;
        }
        finally
        {
            if (polipo != null)
            {
                polipo.stop();
                MyLog.i(R.string.http_proxy_stopped);
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
                MyLog.i(R.string.socks_stopped);
            }
            if (conn != null)
            {
                conn.close();
                MyLog.i(R.string.ssh_stopped);
            }
        }
    }
    
    public boolean isTunnelStarted()
    {
        // TODO: states -- STARTING, CONNECTED, DISCONNECTED
        
        return m_tunnelThread != null;
    }
    
    public void startTunnel()
    {
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
        m_stopSignal.countDown();

        try
        {
            m_tunnelThread.join();
        }
        catch (InterruptedException e)
        {
        }
        
        m_stopSignal = null;
        m_tunnelThread = null;
    }
}
