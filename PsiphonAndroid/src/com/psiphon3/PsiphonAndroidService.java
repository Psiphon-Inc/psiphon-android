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
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import android.support.v4.content.LocalBroadcastManager;

import ch.ethz.ssh2.*;

import com.psiphon3.PsiphonAndroidActivity;
import com.psiphon3.PsiphonAndroidStats;

public class PsiphonAndroidService extends Service
{
    private boolean firstStart = true;
    // TODO: use this? private Utils.CircularArrayList<Message> m_messages = new Utils.CircularArrayList<Message>(1000);
    private ArrayList<Message> m_messages = new ArrayList<Message>();
    private LocalBroadcastManager m_localBroadcastManager;
    private CountDownLatch m_stopSignal;
    private Thread m_tunnelThread;
    private PsiphonServerInterface m_interface = new PsiphonServerInterface();

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
        Log.d(PsiphonConstants.TAG, "PsiphonAndroidService.onStartCommand called, firstStart = " + (firstStart ? "true" : "false"));
        if (firstStart)
        {
            // TODO: put this stuff in onCreate instead?
            
            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);
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
    
    private void runTunnel()
    {
        PsiphonServerInterface.ServerEntry entry = m_interface.getCurrentServerEntry();

        try
        {
            sendMessage(getText(R.string.ssh_connecting).toString());
            Connection conn = new Connection(entry.ipAddress, entry.sshObfuscatedKey, entry.sshObfuscatedPort);
            conn.connect();
            sendMessage(getText(R.string.ssh_connected).toString());

            sendMessage(getText(R.string.ssh_authenticating).toString());
            boolean isAuthenticated = conn.authenticateWithPassword(entry.sshUsername, entry.sshPassword);
            if (isAuthenticated == false)
            {
                sendMessage(
                    getText(R.string.ssh_authentication_failed).toString(),
                    PsiphonAndroidActivity.MESSAGE_CLASS_ERROR);
                return;
            }
            sendMessage(getText(R.string.ssh_authenticated).toString());

            sendMessage(getText(R.string.socks_starting).toString());
            DynamicPortForwarder socks = conn.createDynamicPortForwarder(1080);
            sendMessage(getText(R.string.socks_running).toString());

            if (m_interface.doHandshake())
            {
                sendMessage("TEMP: Handshake success");
            }
            else
            {
                sendMessage("TEMP: Handshake failed", PsiphonAndroidActivity.MESSAGE_CLASS_ERROR);
            }

            try
            {
                while (true)
                {
                    boolean stop = m_stopSignal.await(10, TimeUnit.SECONDS);
                    PsiphonAndroidStats.getStats().dumpReport();
                    if (stop) break;
                }
            }
            catch (InterruptedException e)
            {
            }            

            socks.close();
            sendMessage(getText(R.string.socks_stopped).toString());
            conn.close();
            sendMessage(getText(R.string.ssh_stopped).toString());
        }
        catch (IOException e)
        {
            sendMessage(
                    String.format(getText(R.string.error_message).toString(), e.toString()),
                    PsiphonAndroidActivity.MESSAGE_CLASS_ERROR);
            return;
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
