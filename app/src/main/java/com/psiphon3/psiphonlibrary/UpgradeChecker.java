/*
 * Copyright (c) 2016, Psiphon Inc.
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

import com.psiphon3.R;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Debug;
import android.os.IBinder;
import android.provider.UserDictionary;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;

import java.util.List;

import ca.psiphon.PsiphonTunnel;

/*
 * Self-upgrading notes.
 * - UpgradeChecker is responsible for processing downloaded upgrade files (authenticate package,
 *   check APK version), notifying users of upgrades, and invoking the OS installer. Only
 *   UpgradeChecker will do these things, so we ensure there’s only one upgrade notification, etc.
 * - Every X hours, an alarm will wake up UpgradeChecker and it will launch its own tunnel-core and
 *   download an upgrade if no upgrade is pending. This achieves the Google Play-like
 *   upgrade-when-not-running.
 * - The Psiphon app tunnel-core will also download upgrades, if no upgrade is pending. It will make
 *   an untunneled check when it can’t connect. Or it will download when handshake indicates an
 *   upgrade is available.
 * - An upgrade is pending if a valid upgrade has been downloaded and is awaiting install. Both
 *   tunnel-cores need to be configured to skip upgrades when there’s a pending upgrade. In fact,
 *   the UpgradeChecker tunnel-core need not be started at all in this case.
 * - When the Psiphon app tunnel-core downloads an upgrade, it notifies UpgradeChecker with an
 *   intent. UpgradeChecker takes ownership of the downloaded file and proceeds as if it downloaded
 *   the file.
 *
 *
 *
 *
 *
We accept an edge condition that the upgrade checker process tunnel-core and the Psiphon app tunnel-core may both download the upgrade file at the exact same time. Only the upgrade monitor will process the file and notify the user.
I don’t think we should skip an upgrade monitor 24 hourly run if the Psiphon app is running. The Psiphon app won’t check for upgrades if it’s happily connected, so it gives us more coverage to let the daily check always run.
We will add a persistent last-check-timestamp to tunnel-core so that the 6 hour throttle persists across toggles. For that matter, we can do the same with fetch remote server list. Aside from that enhancement, the logic remains: only do an untunneled upgrade check after 30 seconds of failing to connect; once a successful check has been made, don’t check again for 6 hours. For the tunnel-core in the upgrade monitor process, the 6 hour throttle will be a no-op.



- set EstablishTunnelTimeoutSeconds in tunnel-core options to limit connection attempt time. (How long?)


 */

public class UpgradeChecker extends WakefulBroadcastReceiver {
    private static final int ALARM_FREQUENCY_MS = 3000; // TODO: more like 7*60*60*1000 -- use an odd number of hours so it's not the same time every day
    private static final int ALARM_INTENT_REQUEST_CODE = 0;
    private static final String ALARM_INTENT_ACTION = UpgradeChecker.class.getName()+":ALARM";
    private static final String CREATE_ALARM_INTENT_ACTION = UpgradeChecker.class.getName()+":CREATE_ALARM";

    public static final String UPGRADE_FILE_AVAILABLE_INTENT_ACTION = UpgradeChecker.class.getName()+":UPGRADE_AVAILABLE";

    private static void log(Context context, int stringResID, MyLog.Sensitivity sensitivity, int priority, Object... formatArgs) {
        String logJSON = LoggingProvider.makeLogJSON(stringResID, sensitivity, priority, formatArgs);
        if (logJSON == null) {
            // Fail silently
            return;
        }

        ContentValues values = new ContentValues();
        values.put(LoggingProvider.LOG_JSON_KEY, logJSON);

        context.getContentResolver().insert(
                LoggingProvider.INSERT_URI,
                values
        );
    }

    /**
     * Checks whether an upgrade check should be performed. False will be returned if there's already
     * an upgrade file downloaded.
     * Side-effect: If an existing upgrade file is detected, the upgrade notification will be displayed.
     * Side-effect: Creates the UpgradeChecker alarm.
     * TODO: Is the notification showing too aggressive? What if it has been swiped away?
     * @param context
     * @return true if upgrade check is needed.
     */
    public static boolean upgradeCheckNeeded(Context context) {
        Context appContext = context.getApplicationContext();

        // The main process will call this when it tries to connect, so we will use this opportunity
        // to make sure our alarm is created.
        createAlarm(appContext);

        // Don't re-download the upgrade package when a verified upgrade file is
        // awaiting application by the user. A previous upgrade download will have
        // completed and have been extracted to this verified upgrade file.
        // Without this check, tunnel-core won't know that the upgrade is already
        // downloaded, as the file name differs from UpgradeDownloadFilename, and
        // so the entire upgrade will be re-downloaded on each tunnel connect until
        // the user actually applies the upgrade.
        // As a result of this check, a user that delays applying an upgrade until
        // after a subsequent upgrade is released will first apply a stale upgrade
        // and then download the next upgrade.
        // Note: depends on getAvailableCompleteUpgradeFile deleting VerifiedUpgradeFile
        // after upgrade is complete. Otherwise, no further upgrades would download.
        // TODO: implement version tracking for the verified upgrade file so that
        // we can proceed with downloading a newer upgrade when an outdated upgrade exists
        // on disk.

        if (EmbeddedValues.UPGRADE_URL.length() == 0 ||
            !EmbeddedValues.hasEverBeenSideLoaded(appContext)) {  // Play Store Build instances must not use custom auto-upgrade
            log(context, R.string.upgrade_checker_no_upgrading, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);
            return false;
        }

        if (UpgradeManager.UpgradeInstaller.upgradeFileAvailable(appContext)) {
            log(context, R.string.upgrade_checker_upgrade_file_exists, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);
            // We know there's an upgrade file available, so send an intent about it.
            Intent intent = new Intent(appContext, UpgradeChecker.class);
            intent.setAction(UPGRADE_FILE_AVAILABLE_INTENT_ACTION);
            appContext.sendBroadcast(intent);
            return false;
        }

        log(appContext, R.string.upgrade_checker_check_needed, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);

        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Debug.waitForDebugger(); // DEBUG
        // Make sure the alarm is created, regardless of which intent we received.
        createAlarm(context.getApplicationContext());

        String action = intent.getAction();

        if (action.equals(ALARM_INTENT_ACTION)) {
            log(context, R.string.upgrade_checker_alarm_intent_received, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            if (!upgradeCheckNeeded(context)) {
                return;
            }
            checkForUpgrade(context);
        }
        else if (action.equals(UPGRADE_FILE_AVAILABLE_INTENT_ACTION)) {
            log(context, R.string.upgrade_checker_upgrade_file_available_intent_received, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            // Create upgrade notification. User clicking the notification will trigger the install.
            UpgradeManager.UpgradeInstaller.notifyUpgrade(context.getApplicationContext());
        }
        else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            log(context, R.string.upgrade_checker_boot_completed_intent_received, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            // Pass. We created the alarm above, so nothing else to do (until the alarm runs).
        }
        else if (action.equals(CREATE_ALARM_INTENT_ACTION)) {
            log(context, R.string.upgrade_checker_create_alarm_intent_received, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            // Pass. We created the alarm above, so nothing else to do (until the alarm runs).
        }
    }

    /**
     * Creates the periodic alarm used to check for updates. Can be called unconditionally; it
     * handles cases when the alarm is already created.
     * @param appContext The application context.
     */
    private static void createAlarm(Context appContext) {
        Intent intent = new Intent(appContext, UpgradeChecker.class);
        intent.setAction(ALARM_INTENT_ACTION);

        boolean alarmExists = (PendingIntent.getBroadcast(appContext, ALARM_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE) != null);

        if (alarmExists) {
            log(appContext, R.string.upgrade_checker_alarm_exists, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            return;
        }

        log(appContext, R.string.upgrade_checker_creating_alarm, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);

        PendingIntent alarmIntent = PendingIntent.getBroadcast(
                appContext,
                ALARM_INTENT_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        AlarmManager alarmMgr = (AlarmManager)appContext.getSystemService(Context.ALARM_SERVICE);
        alarmMgr.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                ALARM_FREQUENCY_MS, ALARM_FREQUENCY_MS,
                alarmIntent);
    }

    private void checkForUpgrade(Context context) {
        log(context, R.string.upgrade_checker_start_service, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);

        Intent service = new Intent(context, UpgradeCheckerService.class);
        startWakefulService(context, service);
    }


    public static class UpgradeCheckerService extends IntentService implements PsiphonTunnel.HostService {
        private PsiphonTunnel mTunnel;
        private Intent mWakefulIntent;

        public UpgradeCheckerService() {
            super("UpgradeCheckerService");

            mTunnel = PsiphonTunnel.newPsiphonTunnel(this);
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            log(this, R.string.upgrade_checker_check_start, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);

            if (mWakefulIntent != null) {
                // Already processing an intent.
                log(this, R.string.upgrade_checker_already_in_progress, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
                UpgradeChecker.completeWakefulIntent(intent);
                return;
            }

            mWakefulIntent = intent;

            Utils.initializeSecureRandom();

            try {
                mTunnel.startTunneling(TunnelManager.getServerEntries(this));
            } catch (PsiphonTunnel.Exception e) {
                log(this, R.string.upgrade_checker_start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, Log.ERROR, e.getMessage());
                done();
                return;
            }
        }

        protected void done() {
            log(this, R.string.upgrade_checker_done, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            UpgradeChecker.completeWakefulIntent(mWakefulIntent);
            mWakefulIntent = null;
        }

        /*
         * PsiphonTunnel.HostService implementation
         */

        @Override
        public String getAppName() {
            return getString(R.string.app_name);
        }

        @Override
        public Context getContext() {
            return this;
        }

        @Override
        public String getPsiphonConfig() {
            String config = TunnelManager.buildTunnelCoreConfig(
                    this,                       // context
                    "upgradechecker",           // tempTunnelName
                    "Psiphon_UpgradeChecker_"); // clientPlatformPrefix
            return config == null ? "" : config;
        }

        @Override
        public void onClientIsLatestVersion() {
            log(this, R.string.upgrade_checker_client_is_latest_version, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);

            mTunnel.stop();
            done();
        }

        @Override
        public void onClientUpgradeDownloaded(String filename) {
            log(this, R.string.upgrade_checker_client_upgrade_downloaded, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);

            mTunnel.stop();

            Intent intent = new Intent(this, UpgradeChecker.class);
            intent.setAction(UPGRADE_FILE_AVAILABLE_INTENT_ACTION);
            this.sendBroadcast(intent);

            done();
        }

        @Override
        public void onExiting() {
            // Likely due to connection timeout
            log(this, R.string.upgrade_checker_tunnel_exiting, MyLog.Sensitivity.NOT_SENSITIVE, Log.VERBOSE);
            done();
        }

        @Override
        public void onConnected() {}

        @Override
        public void onDiagnosticMessage(String message) {
            // DEBUG
            Log.d("PsiphonUpgradeChecker", message);
        }

        @Override
        public Object getVpnService() {
            return null;
        }

        @Override
        public Object newVpnServiceBuilder() {
            return null;
        }

        @Override
        public void onAvailableEgressRegions(List<String> regions) {}

        @Override
        public void onSocksProxyPortInUse(int port) {}

        @Override
        public void onHttpProxyPortInUse(int port) {}

        @Override
        public void onListeningSocksProxyPort(int port) {}

        @Override
        public void onListeningHttpProxyPort(int port) {}

        @Override
        public void onUpstreamProxyError(String message) {}

        @Override
        public void onConnecting() {}

        @Override
        public void onHomepage(String url) {}

        @Override
        public void onClientRegion(String region) {}

        @Override
        public void onSplitTunnelRegion(String region) {}

        @Override
        public void onUntunneledAddress(String address) {}

        @Override
        public void onBytesTransferred(long sent, long received) {}

        @Override
        public void onStartedWaitingForNetworkConnectivity() {}
    }
}
