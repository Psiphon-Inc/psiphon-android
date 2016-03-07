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

package com.psiphon3.psiphonlibrary;

import java.util.ArrayList;
import java.util.List;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

/*
 * Adapted from the sample code here: http://developer.android.com/reference/android/content/AsyncTaskLoader.html
 */

public class StatusList {
    
    public static class StatusListAdapter extends ArrayAdapter<PsiphonData.StatusEntry> {
        private final LayoutInflater m_inflater;
        private final int m_resourceID;
        private final int m_textViewResourceId;
        private final int m_imageViewResourceId;
        private final int m_timestampViewResourceId;
        private final Drawable m_imageInfo;
        private final Drawable m_imageError;

        public StatusListAdapter(
                Context context, 
                int resource, 
                int textViewResourceId,
                int imageViewResourceId,
                int timestampViewResourceId) {
            super(context, resource, textViewResourceId);
            m_resourceID = resource;
            m_textViewResourceId = textViewResourceId;
            m_imageViewResourceId = imageViewResourceId;
            m_timestampViewResourceId = timestampViewResourceId;
            
            m_inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

            Resources res = context.getResources();
            m_imageInfo = res.getDrawable(android.R.drawable.presence_online);  
            m_imageError = res.getDrawable(android.R.drawable.presence_busy);  
        }

        @Override 
        public View getView(int position, View convertView, ViewGroup parent) {
            View rowView = null;

            if (convertView == null) {
                rowView = m_inflater.inflate(m_resourceID, null);
            } else {
                rowView = convertView;
            }

            PsiphonData.StatusEntry item = getItem(position);
            
            Drawable messageClassImage = null;
            boolean boldText = true;

            switch (item.priority())
            {
            case Log.INFO:
                messageClassImage = m_imageInfo;
                break;
            case Log.ERROR:
                messageClassImage = m_imageError;
                break;
            default:
                // No image
                boldText = false;
                break;
            }
            
            String msg = getContext().getString(item.id(), item.formatArgs());
            
            if (item.throwable() != null)
            {
                // Just report the first line of the stack trace
                String[] stackTraceLines = Log.getStackTraceString(item.throwable()).split("\n");
                msg = msg + (stackTraceLines.length > 0 ? "\n" + stackTraceLines[0] : ""); 
            }
            
            TextView textView = (TextView)rowView.findViewById(m_textViewResourceId);
            if (textView != null) {
                textView.setText(msg);
                textView.setTypeface(boldText ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            
            ImageView imageView = (ImageView)rowView.findViewById(m_imageViewResourceId);
            if (imageView != null) {
                imageView.setImageDrawable(messageClassImage);
            }

            TextView timestampView = (TextView)rowView.findViewById(m_timestampViewResourceId);
            if (timestampView != null) {
                timestampView.setText(Utils.getLocalTimeString(item.timestamp()));
            }
            
            return rowView;
        }
        
        public void addEntries(List<PsiphonData.StatusEntry> entries) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                addEntriesFast(entries);
            }
            else {
                addEntriesSlow(entries);
            }
        }
        
        @TargetApi(Build.VERSION_CODES.HONEYCOMB) 
        private void addEntriesFast(List<PsiphonData.StatusEntry> entries) {
            addAll(entries);
        }

        private void addEntriesSlow(List<PsiphonData.StatusEntry> entries) {
            for (PsiphonData.StatusEntry entry : entries) {
                add(entry);
            }
        }
    }
    
    public static class StatusListIntentReceiver extends BroadcastReceiver {
        public static interface NotificationRecipient {
            public void statusAddedNotificationReceived();
        }
        
        public static final String STATUS_ADDED = "com.psiphon3.PsiphonAndroidActivity.STATUS_ADDED";
        
        final Context m_context;
        final LocalBroadcastManager m_localBroadcastManager;
        final NotificationRecipient m_recipient;
        
        public StatusListIntentReceiver(Context context, NotificationRecipient recipient) {
            m_context = context;
            m_localBroadcastManager = LocalBroadcastManager.getInstance(m_context);
            m_recipient = recipient;
            
            IntentFilter filter = new IntentFilter(STATUS_ADDED);
            m_localBroadcastManager.registerReceiver(this, filter);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            m_recipient.statusAddedNotificationReceived();
        }
        
        // Sends an intent to itself.
        public void notifyStatusAdded() {
            m_localBroadcastManager.sendBroadcast(new Intent(STATUS_ADDED));
        }        
    }
    
    public static class StatusListViewManager 
        implements StatusListIntentReceiver.NotificationRecipient {
        
        final StatusListAdapter m_adapter;
        final ListView m_listview;
        final StatusListIntentReceiver m_intentReceiver;
        int m_nextStatusEntryIndex = 0;

        public StatusListViewManager(ListView listview) {
            Context context = listview.getContext();
            
            m_adapter = new StatusListAdapter(
                    context, 
                    context.getResources().getIdentifier("message_row", "layout", context.getPackageName()),
                    context.getResources().getIdentifier("MessageRow.Text", "id", context.getPackageName()),
                    context.getResources().getIdentifier("MessageRow.Image", "id", context.getPackageName()),
                    context.getResources().getIdentifier("MessageRow.Timestamp", "id", context.getPackageName()));
            
            m_listview = listview;
            m_listview.setTranscriptMode(ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);
            m_listview.setAdapter(m_adapter);
            
            m_intentReceiver = new StatusListIntentReceiver(context, this);
            
            scrollListViewToBottom();
        }
        
        public void notifyStatusAdded() {
            m_intentReceiver.notifyStatusAdded();
        }
        
        /**
         * Should only be called from StatusListIntentReceiver.
         * @see com.psiphon3.psiphonlibrary.StatusList.StatusListIntentReceiver.NotificationRecipient#statusAddedNotificationReceived()
         */
        @Override
        public void statusAddedNotificationReceived() {
            // It might only be one item that's been added, but proceed as if 
            // there are a bunch to bulk-load.
            
            List<PsiphonData.StatusEntry> newEntries = new ArrayList<PsiphonData.StatusEntry>(); 
            while (true) {
                PsiphonData.StatusEntry entry = PsiphonData.getPsiphonData().getStatusEntry(m_nextStatusEntryIndex);
                if (entry == null) {
                    // No more entries to add
                    break;
                }
                
                m_nextStatusEntryIndex += 1;
                
                // Never show debug messages
                // Also, don't show warnings
                if (entry.priority() == Log.DEBUG ||
                        entry.priority() == Log.WARN) {
                    continue;
                }
                
                newEntries.add(entry);
            }
            
            m_adapter.addEntries(newEntries);
        }
        
        private void scrollListViewToBottom() {
            m_listview.post(new Runnable() {
                @Override
                public void run() {
                    // Select the last row so it will scroll into view...
                    m_listview.setSelection(m_adapter.getCount() - 1);
                }
            });
        }
    }
}
