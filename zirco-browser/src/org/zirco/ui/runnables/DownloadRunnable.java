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

package org.zirco.ui.runnables;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

import org.zirco.model.items.DownloadItem;
import org.zirco.utils.IOUtils;

import android.os.Handler;
import android.os.Message;

/**
 * Background downloader.
 */
public class DownloadRunnable implements Runnable {
			
	private static final int BUFFER_SIZE = 4096;
	
	private DownloadItem mParent;
	
	private boolean mAborted;
	
	/**
	 * Contructor.
	 * @param parent The item to download.
	 */
	public DownloadRunnable(DownloadItem parent) {
		mParent = parent;
		mAborted = false;
	}
	
	private Handler mHandler = new Handler() {				
		
		public void handleMessage(Message msg) {
			mParent.onFinished();
		}
	};
	
	/**
	 * Compute the file name given the url.
	 * @return The file name.
	 */
	private String getFileNameFromUrl() {
		String fileName = mParent.getUrl().substring(mParent.getUrl().lastIndexOf("/") + 1);
		
		int queryParamStart = fileName.indexOf("?");
		if (queryParamStart > 0) {
		    fileName = fileName.substring(0, queryParamStart);
		}
		
		return fileName;
	}
	
	/**
	 * Get a file object representation of the file name, in th right folder of the SD card.
	 * @return A file object.
	 */
	private File getFile() {
		
		File downloadFolder = IOUtils.getDownloadFolder();
		
		if (downloadFolder != null) {
			
			return new File(downloadFolder, getFileNameFromUrl());
			
		} else {
			mParent.setErrorMessage("Unable to get download folder from SD Card.");			
			return null;
		}				
	}
	
	@Override
	public void run() {
		File downloadFile = getFile();
		
		if (downloadFile != null) {
			
			if (downloadFile.exists()) {
				downloadFile.delete();
			}
			
			BufferedInputStream bis = null;
			BufferedOutputStream bos = null;
			
			try {
				
				mParent.onStart();
				
				URL url = new URL(mParent.getUrl());
				URLConnection conn = url.openConnection();
				
				InputStream is = conn.getInputStream();
							
				int size = conn.getContentLength();
				
				double oldCompleted = 0;
				double completed = 0;
				
				bis = new BufferedInputStream(is);
				bos = new BufferedOutputStream(new FileOutputStream(downloadFile));
				
				boolean downLoading = true;
				byte[] buffer = new byte[BUFFER_SIZE];
				int downloaded = 0;
				int read;
				
				while ((downLoading) &&
						(!mAborted)) {

					if ((size - downloaded < BUFFER_SIZE) &&
							(size - downloaded > 0)) {
						buffer = new byte[size - downloaded];
					}

					read = bis.read(buffer);
					
					if (read > 0) {
						bos.write(buffer, 0, read);
						downloaded += read;
						
						completed = ((downloaded * 100f) / size);
						
					} else {
						downLoading = false;
					}
					
					// Notify each 5% or more.
					if (oldCompleted + 5 < completed) {
						mParent.onProgress((int) completed);
						oldCompleted = completed;
					}
				}

			} catch (MalformedURLException mue) {
				mParent.setErrorMessage(mue.getMessage());
			} catch (IOException ioe) {
				mParent.setErrorMessage(ioe.getMessage());
			} finally {
				
				if (bis != null) {
					try {
						bis.close();
					} catch (IOException ioe) {
						mParent.setErrorMessage(ioe.getMessage());
					}
				}
				if (bos != null) {
					try {
						bos.close();
					} catch (IOException ioe) {
						mParent.setErrorMessage(ioe.getMessage());
					}
				}							
			}
		
			if (mAborted) {
				if (downloadFile.exists()) {
					downloadFile.delete();
				}
			}
			
		} 
		
		mHandler.sendEmptyMessage(0);
	}
	
	/**
	 * Abort this download.
	 */
	public void abort() {
		mAborted = true;
	}

}
