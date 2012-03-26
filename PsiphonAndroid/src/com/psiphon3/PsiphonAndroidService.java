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
import java.io.Serializable;
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
        public PsiphonAndroidActivity.MessageClass m_messageClass;

        Message(String message, PsiphonAndroidActivity.MessageClass messageClass) 
        {
            m_message = message;
            m_messageClass = messageClass;
        }
    }

    private synchronized void sendMessage(
            String message,
            PsiphonAndroidActivity.MessageClass messageClass)
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
        String hostname = "...";
        int port = 22;
        String username = "...";
        String password = "...";
        String obfuscationKeyword = "...";

        try
        {
            sendMessage("SSH connecting...", PsiphonAndroidActivity.MessageClass.GOOD);
            Connection conn = new Connection(hostname, obfuscationKeyword, port);
            conn.connect();
            sendMessage("SSH connected", PsiphonAndroidActivity.MessageClass.GOOD);

            sendMessage("SSH authenticating...", PsiphonAndroidActivity.MessageClass.GOOD);
            boolean isAuthenticated = conn.authenticateWithPassword(username, password);
            if (isAuthenticated == false)
            {
                sendMessage("SSH authentication failed", PsiphonAndroidActivity.MessageClass.BAD);
                return;
            }
            sendMessage("SSH authenticated", PsiphonAndroidActivity.MessageClass.GOOD);

            sendMessage("SOCKS starting...", PsiphonAndroidActivity.MessageClass.GOOD);
            DynamicPortForwarder socks = conn.createDynamicPortForwarder(1080);
            sendMessage("SOCKS running", PsiphonAndroidActivity.MessageClass.GOOD);

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
            sendMessage("SOCKS stopped", PsiphonAndroidActivity.MessageClass.GOOD);
            conn.close();
            sendMessage("SSH stopped", PsiphonAndroidActivity.MessageClass.GOOD);
        }
        catch (IOException e)
        {
            sendMessage("IOException: " + e, PsiphonAndroidActivity.MessageClass.BAD);
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
