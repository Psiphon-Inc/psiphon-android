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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import org.json.JSONException;

import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.TunnelCore;
import com.psiphon3.psiphonlibrary.AuthenticatedDataPackage.AuthenticatedDataPackageException;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Contains logic relating to downloading and applying upgrades.
 */
public interface UpgradeManager
{
    /**
     * To be used by other UpgradeManager classes only.
     */
    static abstract class UpgradeFile
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
        
        public abstract String getFilename();
        
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

    static class CompleteUpgradeFile extends UpgradeFile
    {
        public CompleteUpgradeFile(Context context)
        {
            super(context);
        }
        
        public String getFilename()
        {
            return "PsiphonAndroid.apk";
        }
    }    

    static class PartialUpgradeFile extends UpgradeFile implements ServerInterface.IResumableDownload
    {
        private int versionNumber;
        
        public PartialUpgradeFile(Context context, int versionNumber)
        {
            super(context);
            
            this.versionNumber = versionNumber;
        }
        
        public String getFilename()
        {
            return "PsiphonAndroid." + Integer.toString(this.versionNumber) + ".part";
        }
        
        public boolean isComplete()
        {
            ...complete JSON?
        }

        public boolean commit()
        {
            try
            {
                // NOTE: On Android, the OS performs its own upgrade authentication which
                // checks that the APK is signed with the same developer key. So the
                // additional signature check isn't strictly necessary. Although it
                // does reduce (not prevent) the chance that a malicious process writes
                // a fake "PsiphonAndroid.apk" -- with a different key -- which our
                // intent would start to install.
                
                String hexUpgradeAPK = AuthenticatedDataPackage.validateAndExtractServerList(
                                            EmbeddedValues.UPGRADE_SIGNATURE_PUBLIC_KEY,
                                            new String(read()));
                
                byte[] upgradeAPK = Utils.hexStringToByteArray(hexUpgradeAPK);
                
                CompleteUpgradeFile file = new CompleteUpgradeFile(super.context);
                file.write(upgradeAPK);

                return true;
            }
            catch (AuthenticatedDataPackageException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeVerificationFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            } 
            catch (JSONException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeVerificationFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
        }

        @Override
        public int getResumeOffset()
        {
            return getSize();
        }

        @Override
        public boolean appendData(byte[] buffer, int length)
        {
            return append(buffer, length);
        }
    }
    
    /**
     * Used for checking if an upgrade has been downloaded and installing it.
     */
    static public class UpgradeInstaller
    {
        /**
         * Check if an upgrade file is available, and if it's actually a higher
         * version.
         * @return true if upgrade file is available to be applied.
         */
        protected static CompleteUpgradeFile getAvailableCompleteUpgradeFile(Context context)
        {
            CompleteUpgradeFile file = new CompleteUpgradeFile(context);

            // Does the file exist?
            if (!file.exists())
            {
                return null;
            }
            
            // Is it a higher version than the current app?
            
            final PackageManager pm = context.getPackageManager();
            
            // Info about the potential upgrade file
            PackageInfo upgradePackageInfo = pm.getPackageArchiveInfo(file.getFullPath(), 0);

            if (upgradePackageInfo == null)
            {
                // There's probably something wrong with the upgrade file.
                file.delete();
                MyLog.w(R.string.UpgradeManager_CannotExtractUpgradePackageInfo, MyLog.Sensitivity.NOT_SENSITIVE);
                return null;
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
                return null;
            }
            
            // Does the upgrade package have a higher version?
            if (upgradePackageInfo.versionCode <= currentPackageInfo.versionCode)
            {
                file.delete();
                return null;
            }
            
            return file;
        }
        
        /**
         * Create an Android notification to launch the upgrade, if available
         */
        public static void notifyUpgrade(Context context)
        {
            CompleteUpgradeFile file = getAvailableCompleteUpgradeFile(context); 
            if (file == null)
            {
                return;
            }
            
            // This intent triggers the upgrade. It's launched if the user clicks the notification.

            Intent upgradeIntent = new Intent(Intent.ACTION_VIEW);
            
            upgradeIntent.setDataAndType(file.getUri(), "application/vnd.android.package-archive");
            upgradeIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        
            PendingIntent invokeUpgradeIntent = 
                    PendingIntent.getActivity(
                        context,
                        0,
                        upgradeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
        
            int iconID = PsiphonData.getPsiphonData().getNotificationIconUpgradeAvailable();
            if (iconID == 0)
            {
                iconID = R.drawable.notification_icon_upgrade_available;
            }
            
            Notification notification =
                    new Notification(
                            iconID,
                            context.getText(R.string.UpgradeManager_UpgradePromptTitle),
                            System.currentTimeMillis());

            notification.setLatestEventInfo(
                    context,
                    context.getText(R.string.UpgradeManager_UpgradePromptTitle),
                    context.getText(R.string.UpgradeManager_UpgradePromptMessage),
                    invokeUpgradeIntent); 
            
            NotificationManager notificationManager = (NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null)
            {
                notificationManager.notify(R.string.UpgradeManager_UpgradeAvailableNotificationId, notification);
            }
        }
    }

    /**
     * Used to download upgrades from the server.
     */
    static public class UpgradeDownloader
    {
        private Context context;
        private ServerInterface serverInterface;
        private Thread thread;
        private int versionNumber;
        
        public UpgradeDownloader(Context context, ServerInterface serverInterface)
        {
            this.context = context;
            this.serverInterface = serverInterface;
        }

        /**
         * Begin downloading the upgrade from the server. Download is done in a
         * separate thread. 
         */
        public void start(int versionNumber)
        {
            this.versionNumber = versionNumber;
            this.thread = new Thread(
                    new Runnable()
                    {
                        public void run()
                        {
                            if (downloadAndSaveUpgrade())
                            {
                                UpgradeManager.UpgradeInstaller.notifyUpgrade(context);
                            }
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
        protected boolean downloadAndSaveUpgrade()
        {
            PartialUpgradeFile file = new PartialUpgradeFile(context, this.versionNumber);
            
            // Check if we already have the complete file
            
            if (file.isComplete())
            {
                if (file.commit())
                {
                    return true;
                }
                else
                {
                    file.delete();
                    // Start over with a fresh download...
                }
            }
            
            // TODO: delete partial downloads of older versions
            
            try
            {
                // Download the upgrade. Partial chunks of data are written to
                // the file and the download may be resumed.
                serverInterface.doUpgradeDownloadRequest(file);
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
                return false;
            }
            
            MyLog.v(R.string.UpgradeManager_UpgradeDownloaded, MyLog.Sensitivity.NOT_SENSITIVE);
            
            // Commit results in a CompleteUpgradeFile.
            
            return file.commit();
        }
    }
}
