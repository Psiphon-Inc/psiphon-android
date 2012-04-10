/*
 * Zirco Browser for Android
 * 
 * Copyright (C) 2010 J. Devauchelle and contributors.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package org.zirco.ui.activities;

import org.zirco.R;
import org.zirco.controllers.Controller;
import org.zirco.events.EventConstants;
import org.zirco.events.EventController;
import org.zirco.events.IDownloadEventsListener;
import org.zirco.model.adapters.DownloadListAdapter;
import org.zirco.model.items.DownloadItem;

import android.app.ListActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Download list activity.
 */
public class DownloadsListActivity extends ListActivity implements IDownloadEventsListener {
	
	private static final int MENU_CLEAR_DOWNLOADS = Menu.FIRST;

	private DownloadListAdapter mAdapter;
	
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.downloads_list_activity);
        
        setTitle(R.string.DownloadListActivity_Title);
        
        EventController.getInstance().addDownloadListener(this);
        
        fillData();
	}
	
	@Override
	protected void onDestroy() {
		EventController.getInstance().removeDownloadListener(this);
		super.onDestroy();
	}
	
	@Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	super.onCreateOptionsMenu(menu);
    	
    	MenuItem item = menu.add(0, MENU_CLEAR_DOWNLOADS, 0, R.string.DownloadListActivity_RemoveCompletedDownloads);
        item.setIcon(R.drawable.ic_menu_delete);
        
        return true;
	}
	
	@Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
		
		switch(item.getItemId()) {
		case MENU_CLEAR_DOWNLOADS:
			Controller.getInstance().clearCompletedDownloads();
			fillData();
			return true;
		default: return super.onMenuItemSelected(featureId, item);
		}
	}

	/**
	 * Fill the download list.
	 */
	private void fillData() {
		mAdapter = new DownloadListAdapter(this, Controller.getInstance().getDownloadList());
		setListAdapter(mAdapter);
	}

	@Override
	public void onDownloadEvent(String event, Object data) {
		if (event.equals(EventConstants.EVT_DOWNLOAD_ON_START)) {
			fillData();
		} else if (event.equals(EventConstants.EVT_DOWNLOAD_ON_PROGRESS)) {				
			if (data != null) {
				DownloadItem item = (DownloadItem) data;
				ProgressBar bar = mAdapter.getBarMap().get(item);
				if (bar != null) {
					bar.setMax(100);
					bar.setProgress(item.getProgress());
				}				
			}
		} else if (event.equals(EventConstants.EVT_DOWNLOAD_ON_FINISHED)) {
			if (data != null) {
				DownloadItem item = (DownloadItem) data;
				
				TextView title = mAdapter.getTitleMap().get(item);
				if (title != null) {
					if (item.isAborted()) {
						title.setText(String.format(getResources().getString(R.string.DownloadListActivity_Aborted), item.getFileName()));
					} else {
						title.setText(String.format(getResources().getString(R.string.DownloadListActivity_Finished), item.getFileName()));
					}
				}
				
				ProgressBar bar = mAdapter.getBarMap().get(item);
				if (bar != null) {					
					bar.setProgress(bar.getMax());
				}
				
				ImageButton button = mAdapter.getButtonMap().get(item);
				if (button != null) {
					button.setEnabled(false);
				}
			}
		}
		
	}
	
}
