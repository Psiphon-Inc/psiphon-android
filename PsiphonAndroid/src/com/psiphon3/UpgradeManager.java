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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import com.psiphon3.R;
import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.TunnelCore;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.view.KeyEvent;

/**
 * Contains logic relating to downloading and applying upgrades.
 */
public interface UpgradeManager
{
    /**
     * To be used by other UpgradeManager classes only.
     */
    static class UpgradeFile
    {
        private Context context;
        
        public UpgradeFile(Context context)
        {
            this.context = context;
        }
        
        public String getFullPath()
        {
            return this.context.getFileStreamPath(getFilename()).getAbsolutePath();
        }
        
        public String getFilename()
        {
            return "PsiphonAndroid.apk";
        }
        
        public boolean exists()
        {
            try
            {
                this.context.openFileInput(getFilename());
            } 
            catch (FileNotFoundException e)
            {
                return false;
            }
            
            return true;
        }
        
        public void delete()
        {
            this.context.deleteFile(getFilename());
        }
        
        public Uri getUri()
        {
            File file = new File(getFullPath());
            return Uri.fromFile(file);
        }
        
        @SuppressLint("WorldReadableFiles") // Making the APK world readable so Installer component can access it
        public boolean write(byte[] data)
        {
            FileOutputStream fos;
            try
            {
                fos = this.context.openFileOutput(
                                    getFilename(), 
                                    Context.MODE_WORLD_READABLE);
            } 
            catch (FileNotFoundException e)
            {
                // This absolutely should not happen. The documentation indicates:
                // "If the file exists but is a directory rather than a regular 
                //  file, does not exist but cannot be created, or cannot be 
                //  opened for any other reason then a FileNotFoundException is thrown."
                MyLog.w(R.string.UpgradeManager_UpgradeFileNotFound, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            
            try
            {
                fos.write(data);
                fos.close();
            } 
            catch (IOException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeFileWriteFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            
            return true;
        }
    }
    
    /**
     * Used for checking if an upgrade has been downloaded and installing it.
     */
    static public class UpgradeInstaller
    {
        private Context context;
        
        /**
         * Records whether an upgrade has already been attempted during this
         * session. We want to avoid repeated upgrade attempts during a single
         * session.
         */
        private static boolean s_upgradeAttempted = false;
        
        public UpgradeInstaller(Context context)
        {
            this.context = context;
        }
        
        /**
         * Check if an upgrade can be applied at this time.
         * @return true if an upgrade is available to applied, and if there has
         *         not already been an attempt to upgrade during this session.
         */
        public boolean canUpgrade()
        {
            return 
                !UpgradeInstaller.s_upgradeAttempted 
                && isUpgradeFileAvailable();            
        }
        
        /**
         * Check if an upgrade file is available, and if it's actually a higher
         * version.
         * @return true if upgrade file is available to be applied.
         */
        protected boolean isUpgradeFileAvailable()
        {
            UpgradeFile file = new UpgradeFile(context);

            // Does the file exist?
            if (!file.exists())
            {
                return false;
            }
            
            // Is it a higher version than the current app?
            
            final PackageManager pm = this.context.getPackageManager();
            
            // Info about the potential upgrade file
            PackageInfo upgradePackageInfo = pm.getPackageArchiveInfo(
                                                    file.getFullPath(), 
                                                    0);

            if (upgradePackageInfo == null)
            {
                // There's probably something wrong with the upgrade file.
                file.delete();
                MyLog.w(R.string.UpgradeManager_CannotExtractUpgradePackageInfo, MyLog.Sensitivity.NOT_SENSITIVE);
                return false;
            }
            
            // Info about the current app
            PackageInfo currentPackageInfo = null;
            try
            {
                currentPackageInfo = context.getPackageManager().getPackageInfo(
                                                context.getPackageName(), 
                                                0);
            } 
            catch (NameNotFoundException e)
            {
                // This really shouldn't happen -- we're getting info about the 
                // current package, which clearly exists.
                MyLog.w(R.string.UpgradeManager_CanNotRetrievePackageInfo, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            
            // Does the upgrade package have a higher version?
            if (upgradePackageInfo.versionCode <= currentPackageInfo.versionCode)
            {
                file.delete();
                return false;
            }
            
            return true;
        }
        
        /**
         * This interface must be implemented to provide callbacks for doUpgrade. 
         */
        public interface IUpgradeListener
        {
            public void upgradeNotStarted();
            public void upgradeStarted();
        }
        
        /**
         * Begin the upgrade process. Note that because there's a user prompt, 
         * this function is asynchronous. 
         * @param upgradeListener An implementation of IUpgradeListener that is
         *                        used as a callback for the various upgrade 
         *                        attempt outcomes.
         */
        public void doUpgrade(final IUpgradeListener upgradeListener)
        {
            if (!canUpgrade())
            {
                upgradeListener.upgradeNotStarted();
                return;
            }
            
            // Record that we have attempted to upgrade. We don't want to retry 
            // again this session.
            UpgradeInstaller.s_upgradeAttempted = true;
            
            // Create our user prompt.
            
            new AlertDialog.Builder(this.context)
                .setTitle(R.string.UpgradeManager_UpgradePromptTitle)
                .setMessage(R.string.UpgradeManager_UpgradePromptMessage)
                .setOnKeyListener(
                        new DialogInterface.OnKeyListener() {
                            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                return keyCode == KeyEvent.KEYCODE_SEARCH;
                            }})
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // User declined the prompt.
                        upgradeListener.upgradeNotStarted();
                    }})
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        // User cancelled the prompt (i.e., hit back button).
                        upgradeListener.upgradeNotStarted();
                    }})
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // User accepted the prompt. Begin the upgrade.
                        
                        UpgradeFile file = new UpgradeFile(context);
                        
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        
                        intent.setDataAndType(file.getUri(), "application/vnd.android.package-archive");
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        
                        context.startActivity(intent);

                        upgradeListener.upgradeStarted();
                    }})
                .show();                
        }
    }

    /**
     * Used to download upgrades from the server.
     */
    static public class UpgradeDownloader implements TunnelCore.UpgradeDownloader
    {
        private Context context;
        private ServerInterface serverInterface;
        private Thread thread;
        
        public UpgradeDownloader(Context context, ServerInterface serverInterface)
        {
            this.context = context;
            this.serverInterface = serverInterface;
        }

        /**
         * Begin downloading the upgrade from the server. Download is done in a
         * separate thread. 
         */
        public void start()
        {
            this.thread = new Thread(
                    new Runnable()
                    {
                        public void run()
                        {
                            downloadAndSaveUpgrade();
                        }
                    });

            this.thread.start();
        }

        /**
         * Stop an on-going upgrade download.
         */
        public void stop()
        {
            // The owner of the serverInterface must abort outstanding requests to ensure that
            // this function does not block.
            if (this.thread != null)
            {
                try
                {
                    this.thread.join();
                } 
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
            this.thread = null;
        }
        
        /**
         * Download the upgrade file and save it to private storage.
         */
        protected synchronized void downloadAndSaveUpgrade()
        {
            // Delete any existing upgrade file.
            UpgradeFile file = new UpgradeFile(context);
            file.delete();
            
            byte[] upgradeFileData = null;
            try
            {
                // Download the upgrade.
                upgradeFileData = serverInterface.doUpgradeDownloadRequest();
            }
            catch (PsiphonServerInterfaceException e)
            {
                // Comment from the Windows client:
                // If the download failed, we simply do nothing.
                // Rationale:
                // - The server is (and hopefully will remain) backwards compatible.
                // - The failure is likely a configuration one, as the handshake worked.
                // - A configuration failure could be common across all servers, so the
                //   client will never connect.
                // - Fail-over exposes new server IPs to hostile networks, so we don't
                //   like doing it in the case where we know the handshake already succeeded.
                MyLog.w(R.string.UpgradeManager_UpgradeDownloadFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return;
            }
            
            if (!file.write(upgradeFileData))
            {
                return;
            }
            
            MyLog.v(R.string.UpgradeManager_UpgradeDownloaded, MyLog.Sensitivity.NOT_SENSITIVE);
        }
    }
}
