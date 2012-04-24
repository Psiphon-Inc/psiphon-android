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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.LocalBroadcastManager;


public class Events
{
    static public void addMessage(Context context, String message, int messageClass)
    {
    	// Local broadcast to any existing status screen
    	LocalBroadcastManager localBroadcastManager = LocalBroadcastManager.getInstance(context);
        Intent intent = new Intent(StatusActivity.ADD_MESSAGE);
        intent.putExtra(StatusActivity.ADD_MESSAGE_TEXT, message);
        intent.putExtra(StatusActivity.ADD_MESSAGE_CLASS, messageClass);
        localBroadcastManager.sendBroadcast(intent);
    }

    static public void showDisconnected(Context context)
    {
    	// Simply display the status screen
    	Intent intent = new Intent(
    			"ACTION_VIEW",
    			Uri.EMPTY,
    			context,
    			com.psiphon3.StatusActivity.class);
    	intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);    	
    }
    
    static public void openBrowser(Context context, String uri)
    {
    	Intent intent = new Intent(
    			"ACTION_VIEW",
    			Uri.parse(uri),
    			context,
    			org.zirco.ui.activities.MainActivity.class);
        intent.putExtra("localProxyPort", PsiphonConstants.HTTP_PROXY_PORT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }
}
