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

package org.zirco.model.items;

import java.io.File;
import java.util.Random;

import org.zirco.R;
import org.zirco.events.EventConstants;
import org.zirco.events.EventController;
import org.zirco.ui.activities.DownloadsListActivity;
import org.zirco.ui.runnables.DownloadRunnable;
import org.zirco.utils.IOUtils;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Represent a download item.
 */
public class DownloadItem {
	
	private Context mContext;
	
	private String mUrl;
	private String mFileName;
	
	private int mProgress;
	
	private String mErrorMessage;
	
	private DownloadRunnable mRunnable;
	
	private boolean mIsFinished;
	private boolean mIsAborted;
	
	private NotificationManager mNotificationManager;
	private Notification mNotification;
	private int mNotificationId;
	
	/**
	 * Constructor.
	 * @param context The current context.
	 * @param url The download url.
	 */
	public DownloadItem(Context context, String url) {
		
		mContext = context;
		
		mUrl = url;
		mFileName = mUrl.substring(mUrl.lastIndexOf("/") + 1);
		
		int queryParamStart = mFileName.indexOf("?");
		if (queryParamStart > 0) {
		    mFileName = mFileName.substring(0, queryParamStart);
		}
		
		mProgress = 0;
	
		mRunnable = null;
		mErrorMessage = null;
		
		mIsFinished = false;
		mIsAborted = false;
		
		Random r = new Random();
		mNotificationId = r.nextInt();
		mNotification = null;
		mNotificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
	}
	
	/**
	 * Gets the download url.
	 * @return The download url.
	 */
	public String getUrl() {
		return mUrl;
	}
	
	/**
	 * Gets the filename on disk.
	 * @return The filename on disk.
	 */
	public String getFileName() {
		return mFileName;
	}
	
	public String getFilePath() {
		return IOUtils.getDownloadFolder().getAbsolutePath() + File.separator + mFileName;
	}
	
	/**
	 * Gets the download progress.
	 * @return The download progress.
	 */
	public int getProgress() {
		return mProgress;
	}
	
	/**
	 * Set the current error message for this download.
	 * @param errorMessage The error message.
	 */
	public void setErrorMessage(String errorMessage) {
		mErrorMessage = errorMessage;
	}
	
	/**
	 * Gets the error message for this download.
	 * @return The error message.
	 */
	public String getErrorMessage() {
		return mErrorMessage;
	}
	
	/**
	 * Trigger a start download event.
	 */
	public void onStart() {
		createNotification();
		
		EventController.getInstance().fireDownloadEvent(EventConstants.EVT_DOWNLOAD_ON_START, this);
	}
	
	/**
	 * Set this item is download finished state. Trigger a finished download event.
	 */
	public void onFinished() {
		mProgress = 100;
		mRunnable = null;
		
		mIsFinished = true;
		
		updateNotificationOnEnd();
		
		EventController.getInstance().fireDownloadEvent(EventConstants.EVT_DOWNLOAD_ON_FINISHED, this);
	}
	
	/**
	 * Set the current progress. Trigger a progress download event.
	 * @param progress The current progress.
	 */
	public void onProgress(int progress) {
		mProgress = progress;
		
		EventController.getInstance().fireDownloadEvent(EventConstants.EVT_DOWNLOAD_ON_PROGRESS, this);
	}
	
	/**
	 * Start the current download.
	 */
	public void startDownload() {
		if (mRunnable != null) {
			mRunnable.abort();
		}
		mRunnable = new DownloadRunnable(this);
		new Thread(mRunnable).start();
	}
	
	/**
	 * Abort the current download.
	 */
	public void abortDownload() {
		if (mRunnable != null) {
			mRunnable.abort();
		}
		mIsAborted = true;
	}
	
	/**
	 * Check if the download is finished.
	 * @return True if the download is finished.
	 */
	public boolean isFinished() {
		return mIsFinished;
	}
	
	/**
	 * Check if the download is aborted.
	 * @return True if the download is aborted.
	 */
	public boolean isAborted() {
		return mIsAborted;
	}
	
	/**
	 * Create the download notification.
	 */
	private void createNotification() {
		mNotification = new Notification(R.drawable.download_anim, mContext.getString(R.string.DownloadNotification_DownloadStart), System.currentTimeMillis());		

		Intent notificationIntent = new Intent(mContext.getApplicationContext(), DownloadsListActivity.class);
		PendingIntent contentIntent = PendingIntent.getActivity(mContext.getApplicationContext(), 0, notificationIntent, 0);

		mNotification.setLatestEventInfo(mContext.getApplicationContext(), mContext.getString(R.string.DownloadNotification_DownloadInProgress), mFileName, contentIntent);

		mNotificationManager.notify(mNotificationId, mNotification);
	}
	
	/**
	 * Update the download notification at the end of download.
	 */
	private void updateNotificationOnEnd() {
		if (mNotification != null) {
			mNotificationManager.cancel(mNotificationId);			
		}
		
		String message;
		if (mIsAborted) {
			message = mContext.getString(R.string.DownloadNotification_DownloadCanceled);
		} else {
			message = mContext.getString(R.string.DownloadNotification_DownloadComplete);
		}

		mNotification = new Notification(R.drawable.stat_sys_download, mContext.getString(R.string.DownloadNotification_DownloadComplete), System.currentTimeMillis());
		mNotification.flags |= Notification.FLAG_AUTO_CANCEL;

		Intent notificationIntent = new Intent(mContext.getApplicationContext(), DownloadsListActivity.class);
		PendingIntent contentIntent = PendingIntent.getActivity(mContext.getApplicationContext(), 0, notificationIntent, 0);

		mNotification.setLatestEventInfo(mContext.getApplicationContext(), mFileName, message, contentIntent);

		mNotificationManager.notify(mNotificationId, mNotification);		
	}

}
