/*
 * Copyright (c) 2013, Psiphon Inc.
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

import com.psiphon3.FeedbackActivity;
import com.psiphon3.StatusActivity;
import com.psiphon3.psiphonlibrary.MainBase;
import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.TunnelService;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.LocalBroadcastManager;


public class Events implements com.psiphon3.psiphonlibrary.IEvents
{
    public void signalHandshakeSuccess(Context context, boolean isReconnect)
    {
        // Only send this intent if the StatusActivity is
        // in the foreground. If it isn't and we sent the
        // intent, the activity will interrupt the user in
        // some other app.        
        // It's too late to do this check in StatusActivity
        // onNewIntent.
        // NEW: now we would like to interrupt the user to
        // show the home tab when Psiphon gets initially
        // connected.
        
        if (PsiphonData.getPsiphonData().getStatusActivityForeground() || !isReconnect)
        {
            Intent intent = new Intent(
                    MainBase.TabbedActivityBase.HANDSHAKE_SUCCESS,
                    null,
                    context,
                    com.psiphon3.StatusActivity.class);
            intent.putExtra(MainBase.TabbedActivityBase.HANDSHAKE_SUCCESS_IS_RECONNECT, isReconnect);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    public void signalUnexpectedDisconnect(Context context)
    {
        /*
        // Only launch the intent if the browser is the current
        // task. We don't want to interrupt other apps; and in
        // the case of our app (currently), only the browser needs
        // to be interrupted.

        // TODO: track with onResume/onPause flag, as per:
        // http://stackoverflow.com/questions/3667022/android-is-application-running-in-background
        // In the meantime, the imprecision of the getRunningTasks method is acceptable in
        // our current case since it's is only used to not annoy the user.
        
        ActivityManager activityManager =
                (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningTaskInfo> runningTasks = activityManager.getRunningTasks(1);        
        
        if (runningTasks.size() > 0 &&
                runningTasks.get(0).baseActivity.flattenToString().compareTo(
                        "com.psiphon3/org.zirco.ui.activities.MainActivity") == 0)
        {
            Intent intent = new Intent(
                    StatusActivity.UNEXPECTED_DISCONNECT,
                    null,
                    context,
                    com.psiphon3.StatusActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
        */

        // Local broadcast to any existing status screen
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
        Intent intent = new Intent(StatusActivity.UNEXPECTED_DISCONNECT);
        localBroadcastManager.sendBroadcast(intent);
    }
    
    public void signalDisconnectRaiseActivityAutostart(Context context)
    {
        Intent intent = new Intent(
                MainBase.TabbedActivityBase.UNEXPECTED_DISCONNECT_RESTART,
                null,
                context,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    
    public void signalTunnelStarting(Context context)
    {
        // Local broadcast to any existing status screen
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
        Intent intent = new Intent(MainBase.TabbedActivityBase.TUNNEL_STARTING);
        localBroadcastManager.sendBroadcast(intent);
    }
    
    public void signalTunnelStopping(Context context)
    {
        // Local broadcast to any existing status screen
        LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
        Intent intent = new Intent(MainBase.TabbedActivityBase.TUNNEL_STOPPING);
        localBroadcastManager.sendBroadcast(intent);
    }

    public Intent pendingSignalNotification(Context context)
    {
        Intent intent = new Intent(
                "ACTION_VIEW",
                null,
                context,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
    
    public void displayBrowser(Context context)
    {
        displayBrowser(context, null);
    }

    public void displayBrowser(Context context, Uri uri)
    {
        try
        {
            if (PsiphonData.getPsiphonData().getTunnelWholeDevice())
            {
                // TODO: support multiple home pages in whole device mode. This is
                // disabled due to the case where users haven't set a default browser
                // and will get the prompt once per home page.
                
                if (uri == null)
                {
                    for (String homePage : PsiphonData.getPsiphonData().getHomePages())
                    {
                        uri = Uri.parse(homePage);
                        break;
                    }
                }
                
                if (uri != null)
                {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                    context.startActivity(browserIntent);
                }
            }
            else
            {
                Intent intent = new Intent(
                        "ACTION_VIEW",
                        uri,
                        context,
                        org.zirco.ui.activities.MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
                // This intent displays the Zirco browser.
                // We use "extras" to communicate Psiphon settings to Zirco.
                // When Zirco is first created, it will use the homePages
                // extras to open tabs for each home page, respectively. When the intent
                // triggers an existing Zirco instance (and it's a singleton) this extra
                // is ignored and the browser is displayed as-is.
                // When a uri is specified, it will open as a new tab. This is
                // independent of the home pages.
                // Note: Zirco now directly accesses PsiphonData to get the current
                // local HTTP proxy port for WebView tunneling.
                
                intent.putExtra("homePages", PsiphonData.getPsiphonData().getHomePages());
                intent.putExtra("serviceClassName", TunnelService.class.getName());        
                intent.putExtra("statusActivityClassName", StatusActivity.class.getName());
                intent.putExtra("feedbackActivityClassName", FeedbackActivity.class.getName());
                
                context.startActivity(intent);
            }
        }
        catch (ActivityNotFoundException e)
        {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }
}
