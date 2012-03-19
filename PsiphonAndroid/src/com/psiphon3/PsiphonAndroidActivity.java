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
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import ch.ethz.ssh2.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PsiphonAndroidActivity extends Activity implements OnClickListener
{
    private TableLayout m_messagesTableLayout;
    private ScrollView m_messagesScrollView;
    private Animation m_animRotate;
    private ImageView m_startImageView;
    private Thread tunnelThread;
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        m_messagesTableLayout = (TableLayout)findViewById(R.id.messagesTableLayout);
        m_messagesScrollView = (ScrollView)findViewById(R.id.messagesScrollView);
        m_startImageView = (ImageView)findViewById(R.id.startImageView);
        m_animRotate = AnimationUtils.loadAnimation(this, R.anim.rotate);

        tunnelThread = new Thread(new Runnable()
        {
            public void run()
            {
                testTunnel();
            }
        });

        tunnelThread.start();
        
        m_startImageView.setOnClickListener(this);
        
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
        int messageClassImageDesc = 0;
        int logPriority = 0;
        switch (messageClass)
        {
        case GOOD:
            messageClassImageRes = android.R.drawable.presence_online;
            messageClassImageDesc = R.string.message_image_good_desc;
            logPriority = Log.INFO;
            break;
        case BAD:
            messageClassImageRes = android.R.drawable.presence_busy;
            messageClassImageDesc = R.string.message_image_bad_desc;
            logPriority = Log.WARN;
            break;
        case DEBUG:
            messageClassImageRes = android.R.drawable.presence_away;
            messageClassImageDesc = R.string.message_image_debug_desc;
            logPriority = Log.DEBUG;
            break;
        default:
            messageClassImageRes = android.R.drawable.presence_invisible;
            messageClassImageDesc = R.string.message_image_neutral_desc;
            logPriority = Log.INFO;
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
        
        // Also log to LogCat
        Log.println(logPriority, PsiphonConstants.TAG, message);
        
        // Wait until the messages list is updated before attempting to scroll 
        // to the bottom.
        m_messagesScrollView.post(new Runnable() {
            @Override
            public void run() {
                m_messagesScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }
    
    private void spinImage()
    {
        m_startImageView.startAnimation(m_animRotate);
    }
    
    // OnClickListener implementation
    public void onClick(View view) 
    {
        if (view == m_startImageView)
        {
            AddMessage("start clicked", MessageClass.DEBUG);
            spinImage();
        }
    }

    public void testTunnel()
    {
        String hostname = "...";
        int port = 22;
        String username = "...";
        String password = "...";
        String obfuscationKeyword = "...";

        try
        {
            Connection conn = new Connection(hostname, obfuscationKeyword, port);
            conn.connect();
            Log.d(PsiphonConstants.TAG, "SSH connected");

            boolean isAuthenticated = conn.authenticateWithPassword(username, password);
            if (isAuthenticated == false)
            {
                Log.e(PsiphonConstants.TAG, "can't authenticate");
                return;
            }
            Log.d(PsiphonConstants.TAG, "SSH authenticated");

            // Test exec (sample code from ssh2 project)
            Session sess = conn.openSession();
            sess.execCommand("uname -a && date && uptime && who");
            Log.d("Psiphon", "Here is some information about the remote host:");
            InputStream stdout = new StreamGobbler(sess.getStdout());
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while (true)
            {
                String line = br.readLine();
                if (line == null)
                    break;
                Log.d("Psiphon", line);
            }
            Log.d("Psiphon", "ExitCode: " + sess.getExitStatus());
            sess.close();
            
            
            DynamicPortForwarder socks = conn.createDynamicPortForwarder(1080);
            Log.d(PsiphonConstants.TAG, "SOCKS running");

            try
            {
                Thread.sleep(60000);
            }
            catch (InterruptedException e)
            {
            }            

            socks.close();
            conn.close();
            Log.d(PsiphonConstants.TAG, "SSH/SOCKS closed");
        }
        catch (IOException e)
        {
            Log.e(PsiphonConstants.TAG, "IOException", e);
            return;
        }
    }
}
