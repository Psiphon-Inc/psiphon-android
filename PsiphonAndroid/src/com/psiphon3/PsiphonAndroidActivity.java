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
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import ch.ethz.ssh2.*;
import java.io.IOException;

public class PsiphonAndroidActivity extends Activity 
{
    private TableLayout messagesTableLayout;
    private ScrollView messagesScrollView;
    private Thread tunnelThread;
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        this.messagesTableLayout = (TableLayout)findViewById(R.id.messagesTableLayout);
        this.messagesScrollView = (ScrollView)findViewById(R.id.messagesScrollView);

        /*
        tunnelThread = new Thread(new Runnable()
        {
            public void run()
            {
                testTunnel();
            }
        });

        tunnelThread.start();
        */
        
        AddMessage("onCreate finished", MessageClass.DEBUG);
    }
    
    public enum MessageClass { GOOD, BAD, NEUTRAL, DEBUG };
    
    public void AddMessage(String message, MessageClass messageClass)
    {
        TableRow row = new TableRow(this);
        TextView messageTextView = new TextView(this);
        ImageView messageClassImageView = new ImageView(this);
        
        messageTextView.setText(message);
        
        int messageClassImageRes = 0;
        switch (messageClass)
        {
        case GOOD:
            messageClassImageRes = android.R.drawable.presence_online;
            break;
        case BAD:
            messageClassImageRes = android.R.drawable.presence_busy;
            break;
        case DEBUG:
            messageClassImageRes = android.R.drawable.presence_away;
            break;
        default:
            messageClassImageRes = android.R.drawable.presence_invisible;
            break;
        }
        messageClassImageView.setImageResource(messageClassImageRes);
        
        row.addView(messageTextView);
        row.addView(messageClassImageView);
        
        this.messagesTableLayout.addView(row);
        
        this.messagesScrollView.post(new Runnable() {
            @Override
            public void run() {
                messagesScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    public void testTunnel()
    {
        String hostname = "...";
        String username = "...";
        String password = "...";

        try
        {
            Connection conn = new Connection(hostname);
            conn.connect();
            Log.d("Psiphon", "SSH connected");

            boolean isAuthenticated = conn.authenticateWithPassword(username, password);
            if (isAuthenticated == false)
            {
                Log.e("Psiphon", "can't authenticate");
                return;
            }
            Log.d("Psiphon", "SSH authenticated");

            DynamicPortForwarder socks = conn.createDynamicPortForwarder(1080);
            Log.d("Psiphon", "SOCKS running");

            try
            {
                Thread.sleep(60000);
            }
            catch (InterruptedException e)
            {
            }            

            socks.close();
            conn.close();
            Log.d("Psiphon", "SSH/SOCKS closed");
        }
        catch (IOException e)
        {
            Log.e("Psiphon", "IOException", e);
            return;
        }
    }
}
