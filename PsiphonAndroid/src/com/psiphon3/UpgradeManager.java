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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import com.psiphon3.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.Utils.MyLog;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;

/**
 * Contains logic relating to downloading and applying upgrades.
 */
public interface UpgradeManager
{
    static public class Upgrader
    {
        private Context context;
        
        public Upgrader(Context context)
        {
            this.context = context;
        }
        
        /**
         * Check if an upgrade can be applied at this time.
         * @return true if an upgrade is available to applied.
         */
        public boolean canUpgrade()
        {
            return isUpgradeFileAvailable();            
        }
        
        /**
         * Begin the upgrade process.
         * @return true if the upgrade has started, false if there is no upgrade
         *         available.
         */
        public boolean doUpgrade()
        {
            if (!canUpgrade())
            {
                return false;
            }
            
            return installUpgrade();
        }
        
        /**
         * Check if an upgrade file is available, and if it's actually a higher
         * version.
         * @return true if upgrade file is available to be applied.
         */
        protected boolean isUpgradeFileAvailable()
        {
            File file = this.context.getFileStreamPath(PsiphonConstants.UPGRADE_FILENAME);
            
            // Does the file exist?
            if (!file.exists())
            {
                return false;
            }
            
            // Is it a higher version than the current app?
            
            final PackageManager pm = this.context.getPackageManager();
            
            // Info about the potential upgrade file
            PackageInfo upgradePackageInfo = pm.getPackageArchiveInfo(
                                                    file.getAbsolutePath(), 
                                                    0);

            if (upgradePackageInfo == null)
            {
                // There's probably something wrong with the upgrade file.
                this.context.deleteFile(PsiphonConstants.UPGRADE_FILENAME);
                MyLog.e(R.string.UpgradeManager_CannotExtractUpgradePackageInfo);
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
                MyLog.e(R.string.UpgradeManager_CanNotRetrievePackageInfo, e);
                return false;
            }
            
            // Does the upgrade package have a higher version?
            return upgradePackageInfo.versionCode > currentPackageInfo.versionCode;
        }
        
        protected boolean installUpgrade()
        {
            File file = this.context.getFileStreamPath(PsiphonConstants.UPGRADE_FILENAME);
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            
            this.context.startActivity(intent);
            
            return true;
        }
    }

    static public class UpgradeDownloader
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
            // TODO: Actually stop
            if (this.thread != null)
            {
                try
                {
                    this.thread.join();
                } 
                catch (InterruptedException e)
                {
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
            this.context.deleteFile(PsiphonConstants.UPGRADE_FILENAME);
            
            byte[] upgradeFile = null;
            try
            {
                // Download the upgrade.
                upgradeFile = serverInterface.doUpgradeDownloadRequest();
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
                MyLog.w(R.string.UpgradeManager_UpgradeDownloadFailed, e);
                return;
            }
            
            try
            {
                FileOutputStream file;
                file = this.context.openFileOutput(PsiphonConstants.UPGRADE_FILENAME, Context.MODE_PRIVATE);
                file.write(upgradeFile);
                file.close();
            } 
            catch (IOException e)
            {
                // This is bad, but we'll let the user keep working.
                MyLog.e(R.string.UpgradeManager_UpgradeFileWriteFailed, e);
                return;
            }
            
            MyLog.i(R.string.UpgradeManager_UpgradeDownloaded);
        }
    }
}
