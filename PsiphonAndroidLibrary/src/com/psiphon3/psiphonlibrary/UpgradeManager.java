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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.AuthenticatedDataPackage.AuthenticatedDataPackageException;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
        
        public String getFullPath(String filename)
        {
            return this.context.getFileStreamPath(filename).getAbsolutePath();
        }
        
        public String getFullPath()
        {
            return getFullPath(getFilename());
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
        
        public boolean delete()
        {
            return this.context.deleteFile(getFilename());
        }
        
        public boolean rename(String newFilename)
        {
            File file = new File(getFullPath());
            return file.renameTo(new File(getFullPath(newFilename)));
        }
        
        public Uri getUri()
        {
            File file = new File(getFullPath());
            return Uri.fromFile(file);
        }
        
        public long getSize()
        {
            File file = new File(getFullPath());
            return file.length();
        }

        public abstract boolean isWorldReadable();
        
        @SuppressLint("WorldReadableFiles")
        public FileOutputStream createForWriting() throws FileNotFoundException
        {
            int mode = 0;
            if (isWorldReadable()) mode |= Context.MODE_WORLD_READABLE;

            return this.context.openFileOutput(getFilename(), mode);             
        }

        @SuppressLint("WorldReadableFiles")
        public boolean write(byte[] data, int length, boolean append)
        {
            FileOutputStream fos = null;
            try
            {
                int mode = 0;
                if (isWorldReadable()) mode |= Context.MODE_WORLD_READABLE;
                if (append) mode |= Context.MODE_APPEND;

                fos = this.context.openFileOutput(getFilename(), mode); 

                fos.write(data, 0, length);
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
            catch (IOException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeFileWriteFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            finally
            {
                if (fos != null)
                {
                    try { fos.close(); } catch (IOException e) {}
                }
            }
            
            return true;
        }
    }

    static class UnverifiedUpgradeFile extends UpgradeFile
    {
        public UnverifiedUpgradeFile(Context context)
        {
            super(context);
        }
        
        public String getFilename()
        {
            return "PsiphonAndroid.apk.unverified";
        }
        
        public boolean isWorldReadable()
        {
            // Making the APK world readable so Installer component can access it
            return true;
        }
    }    

    static class VerifiedUpgradeFile extends UpgradeFile
    {
        public VerifiedUpgradeFile(Context context)
        {
            super(context);
        }
        
        public String getFilename()
        {
            return "PsiphonAndroid.apk";
        }
        
        public boolean isWorldReadable()
        {
            // Making the APK world readable so Installer component can access it
            return true;
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
        
        
        public boolean isWorldReadable()
        {
            return false;
        }

        private InputStream openUnzipStream() throws IOException, FileNotFoundException
        {
            return new GZIPInputStream(new BufferedInputStream(super.context.openFileInput(getFilename())));
        }

        public boolean extract()
        {
            InputStream unzipStream = null;

            try
            {
                // NOTE: On Android, the OS also performs its own upgrade authentication which
                // checks that the APK is signed with the same developer key. Our own
                // additional signature check mitigates against a malicious MiM which supplies
                // a malicious, unsigned, upgrade payload which our intent would start to install.
                
                unzipStream = openUnzipStream();
                
                UnverifiedUpgradeFile unverifiedFile = new UnverifiedUpgradeFile(super.context);
                OutputStream dataDestination = unverifiedFile.createForWriting();
                
                AuthenticatedDataPackage.extractAndVerifyData(
                        EmbeddedValues.UPGRADE_SIGNATURE_PUBLIC_KEY,
                        unzipStream,
                        true, // "data" is Base64 (and is a large value to be streamed)
                        dataDestination);

                unverifiedFile.rename(new VerifiedUpgradeFile(super.context).getFilename());
                
                return true;
            }
            catch (FileNotFoundException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeFileNotFound, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            catch (IOException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeFileReadFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            catch (AuthenticatedDataPackageException e)
            {
                MyLog.w(R.string.UpgradeManager_UpgradeFileAuthenticateFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                return false;
            }
            finally
            {
                if (unzipStream != null)
                {
                    try { unzipStream.close(); } catch (IOException e) {}
                }
            }
        }

        @Override
        public long getResumeOffset()
        {
            return getSize();
        }

        @Override
        public boolean appendData(byte[] buffer, int length)
        {
            return write(buffer, length, true);
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
        protected static VerifiedUpgradeFile getAvailableCompleteUpgradeFile(Context context)
        {
            VerifiedUpgradeFile file = new VerifiedUpgradeFile(context);

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
            // Play Store Build instances must not use custom auto-upgrade
            if (!EmbeddedValues.hasEverBeenSideLoaded(context))
            {
                return;
            }
            
            VerifiedUpgradeFile file = getAvailableCompleteUpgradeFile(context); 
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
        private boolean stopFlag;

        private final int MAX_RETRY_ATTEMPTS = 10;
        private final int RETRY_DELAY_MILLISECONDS = 30*1000;
        private final int RETRY_WAIT_MILLISECONDS = 100;
        
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
            // Play Store Build instances must not use custom auto-upgrade
            if (0 == EmbeddedValues.UPGRADE_URL.length() || !EmbeddedValues.hasEverBeenSideLoaded(context))
            {
                return;
            }
            
            this.versionNumber = versionNumber;
            this.stopFlag = false;
            this.thread = new Thread(
                    new Runnable()
                    {
                        public void run()
                        {
                            for (int attempt = 0; !stopFlag && attempt < MAX_RETRY_ATTEMPTS; attempt++)
                            {
                                // NOTE: depends on ServerInterface.stop(), not stopFlag, to interrupt requests in progress

                                if (downloadAndExtractUpgrade())
                                {
                                    UpgradeManager.UpgradeInstaller.notifyUpgrade(context);
                                    break;
                                }
                                
                                // After a failure, delay a minute before trying again
                                // TODO: synchronize with preemptive reconnect? 

                                for (int wait = 0; wait < RETRY_DELAY_MILLISECONDS; wait += RETRY_WAIT_MILLISECONDS)
                                {
                                    try
                                    {
                                        Thread.sleep(RETRY_WAIT_MILLISECONDS);
                                    }
                                    catch (InterruptedException e)
                                    {
                                        Thread.currentThread().interrupt();
                                    }
                                    if (stopFlag)
                                    {
                                        break;
                                    }
                                }
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
                    this.stopFlag = true;
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
        protected boolean downloadAndExtractUpgrade()
        {
            PartialUpgradeFile file = new PartialUpgradeFile(context, this.versionNumber);
            
            // TODO: delete/cleanup partial downloads of older versions
            
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
            
            // Commit results in a CompleteUpgradeFile.
            // NOTE: if we fail at this point, there will be at least one more HTTP
            // request which may return status code 416 since we already have the complete
            // file but haven't stored the "completed download" state. We're not checking
            // for completeness by attempting an extract, since that could result in
            // false error messages.
            
            if (!file.extract())
            {
                // If the file isn't working and we think we have the complete file,
                // there may be corrupt bytes. So delete it and next time we'll start over.
                // NOTE: this means if the failure was due to not enough free space
                // to write the extracted file... we still re-download.
                file.delete();
                return false;
            }
            
            MyLog.v(R.string.UpgradeManager_UpgradeDownloaded, MyLog.Sensitivity.NOT_SENSITIVE);
            
            return true;
        }
    }
}
