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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import com.psiphon3.PsiphonAndroidService.Message;


public class PsiphonAndroidActivity extends Activity
{
    public static final String ADD_MESSAGE = "com.psiphon3.PsiphonAndroidActivity.ADD_MESSAGE";
    public static final String ADD_MESSAGE_TEXT = "com.psiphon3.PsiphonAndroidActivity.ADD_MESSAGE_TEXT";
    public static final String ADD_MESSAGE_CLASS = "com.psiphon3.PsiphonAndroidActivity.ADD_MESSAGE_CLASS";
    
    private TableLayout m_messagesTableLayout;
    private ScrollView m_messagesScrollView;
    private LocalBroadcastManager m_localBroadcastManager;
    private PsiphonAndroidService m_service;
    
    private static final int MENU_BROWSER = Menu.FIRST;
    private static final int MENU_EXIT = Menu.FIRST + 1;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        
        m_messagesTableLayout = (TableLayout)findViewById(R.id.messagesTableLayout);
        m_messagesScrollView = (ScrollView)findViewById(R.id.messagesScrollView);

        // Note that this must come before the above lines, or else the activity
        // will not be sufficiently initialized for isDebugMode to succeed. (Voodoo.)
        PsiphonConstants.DEBUG = Utils.isDebugMode(this);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);
        
        MenuItem item;
        
        item = menu.add(0, MENU_BROWSER, 0, "Open Browser");
        //item.setIcon(R.drawable.blah);

        
        item = menu.add(0, MENU_EXIT, 0, "Exit Psiphon");
        //item.setIcon(R.drawable.blah);
        
        return true;
    }
    
    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item)
    {
        switch(item.getItemId())
        {
        case MENU_BROWSER:         
            Events.openBrowser(this, "");
            return true;
        case MENU_EXIT:
            stopService(new Intent(this, PsiphonAndroidService.class));
            this.finish();
            return true;
        default:
            return super.onMenuItemSelected(featureId, item);
        }
    }

    // local service binding, as in http://developer.android.com/reference/android/app/Service.html
    
    private ServiceConnection m_connection = new ServiceConnection()
    {
        public void onServiceConnected(ComponentName className, IBinder service)
        {
            m_service = ((PsiphonAndroidService.LocalBinder)service).getService();

            // Restore messages previously posted by the service
            
            for (Message msg : m_service.getMessages())
            {
                addMessage(msg.m_message, msg.m_messageClass);
            }
            
            // Listen for new messages
            // Using local broad cast (http://developer.android.com/reference/android/support/v4/content/LocalBroadcastManager.html)
            
            m_localBroadcastManager = LocalBroadcastManager.getInstance(PsiphonAndroidActivity.this);

            m_localBroadcastManager.registerReceiver(
                    new AddMessageReceiver(),
                    new IntentFilter(ADD_MESSAGE));        
        }

        public void onServiceDisconnected(ComponentName className)
        {
            m_service = null;
        }
    };
 
    @Override
    protected void onStart()
    {
        super.onStart();

        // Remove previous messages as we'll re-populate with all messages
        // as part of binding to the service.
        m_messagesTableLayout.removeAllViews();
        
        // Using both "started" service and "bound" service interfaces:
        // - "started" to ensure tunnel service lives beyond this activity
        // - "bound" to interact with service (check connection status, start/stop, etc.)
        
        startService(new Intent(this, PsiphonAndroidService.class));
        bindService(new Intent(this, PsiphonAndroidService.class), m_connection, Context.BIND_AUTO_CREATE);

        // The next step is to restore messages and hook up the message receiver.
        // Since bind is asynchronous, hookUpMessages is called by onServiceConnected...
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        
        unbindService(m_connection);
    }
    
    public class AddMessageReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String message = intent.getStringExtra(ADD_MESSAGE_TEXT);
            int messageClass = intent.getIntExtra(ADD_MESSAGE_CLASS, MESSAGE_CLASS_INFO);
            addMessage(message, messageClass);
        }
    }
    
    public static final int MESSAGE_CLASS_INFO = 0;
    public static final int MESSAGE_CLASS_ERROR = 1;
    public static final int MESSAGE_CLASS_DEBUG = 2;
    public static final int MESSAGE_CLASS_WARNING = 3;
    
    public void addMessage(String message, int messageClass)
    {
        TableRow row = new TableRow(this);
        TextView messageTextView = new TextView(this);
        ImageView messageClassImageView = new ImageView(this);
        
        messageTextView.setText(message);
        
        int messageClassImageRes = 0;
        int messageClassImageDesc = 0;
        switch (messageClass)
        {
        case MESSAGE_CLASS_INFO:
            messageClassImageRes = android.R.drawable.presence_online;
            messageClassImageDesc = R.string.message_image_success_desc;
            break;
        case MESSAGE_CLASS_ERROR:
            messageClassImageRes = android.R.drawable.presence_busy;
            messageClassImageDesc = R.string.message_image_error_desc;
            break;
        case MESSAGE_CLASS_DEBUG:
            messageClassImageRes = android.R.drawable.presence_offline;
            messageClassImageDesc = R.string.message_image_debug_desc;
            break;
        case MESSAGE_CLASS_WARNING:
            messageClassImageRes = android.R.drawable.presence_invisible;
            messageClassImageDesc = R.string.message_image_warning_desc;
            break;
        }
        messageClassImageView.setImageResource(messageClassImageRes);
        messageClassImageView.setContentDescription(getResources().getText(messageClassImageDesc));
        
        // Make sure the class image is aligned to the right.
        TableRow.LayoutParams layoutParams = new TableRow.LayoutParams();
        layoutParams.gravity = android.view.Gravity.RIGHT; 
        messageClassImageView.setLayoutParams(layoutParams);
        
        row.addView(messageTextView);
        row.addView(messageClassImageView);
        
        m_messagesTableLayout.addView(row);
        
        // Wait until the messages list is updated before attempting to scroll 
        // to the bottom.
        m_messagesScrollView.post(
            new Runnable()
            {
                @Override
                public void run()
                {
                    m_messagesScrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
    }
}
