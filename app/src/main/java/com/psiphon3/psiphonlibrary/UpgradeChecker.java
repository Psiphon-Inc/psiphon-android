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

/**
 * Created by Adam on 2016-03-09.
 */
public class UpgradeChecker extends WakefulBroadcastReceiver {
    private final int ALARM_FREQUENCY_MS = 3000; // TODO: more like 7*60*60*1000 -- use an odd number of hours so it's not the same time every day
    private final int ALARM_INTENT_REQUEST_CODE = 0;
    private final String ALARM_INTENT_ACTION = UpgradeChecker.class.getName()+":ALARM";
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
     * TODO: Is the notification showing too aggressive? What if it has been swiped away?
     * @param context
     * @return true if upgrade check is needed.
     */
    public static boolean upgradeCheckNeeded(Context context) {
        Context appContext = context.getApplicationContext();

        // The main process will call this when it tries to connect, so we will use this opportunity
        // to make sure our alarm is created.
        Intent createAlarmIntent = new Intent(appContext, UpgradeChecker.class);
        createAlarmIntent.setAction(CREATE_ALARM_INTENT_ACTION);
        appContext.sendBroadcast(createAlarmIntent);

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
            EmbeddedValues.hasEverBeenSideLoaded(appContext)) {
            log(context, R.string.upgrade_checker_no_upgrading, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);
            return false;
        }

        if (new UpgradeManager.VerifiedUpgradeFile(appContext).exists()) {
            log(context, R.string.upgrade_checker_upgrade_file_exists, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);
            // We know there's an upgrade file available, so send an intent about it.
            Intent intent = new Intent(appContext, UpgradeChecker.class);
            intent.setAction(UPGRADE_FILE_AVAILABLE_INTENT_ACTION);
            appContext.sendBroadcast(intent);
        }

        log(appContext, R.string.upgrade_checker_check_needed, MyLog.Sensitivity.NOT_SENSITIVE, Log.INFO);

        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
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
    private void createAlarm(Context appContext) {
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

        Intent service = new Intent(context, UpgradeChecker.class);
        startWakefulService(context, service);
    }



    public static class UpgradeCheckerService extends IntentService implements PsiphonTunnel.HostService {
        public UpgradeCheckerService() {
            super("UpgradeCheckerService");
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            UpgradeChecker.completeWakefulIntent(intent);
        }

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
            return null;
        }

        @Override
        public void onDiagnosticMessage(String message) {

        }

        @Override
        public void onClientUpgradeDownloaded(String filename) {

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
        public void onAvailableEgressRegions(List<String> regions) {

        }

        @Override
        public void onSocksProxyPortInUse(int port) {

        }

        @Override
        public void onHttpProxyPortInUse(int port) {

        }

        @Override
        public void onListeningSocksProxyPort(int port) {

        }

        @Override
        public void onListeningHttpProxyPort(int port) {

        }

        @Override
        public void onUpstreamProxyError(String message) {

        }

        @Override
        public void onConnecting() {

        }

        @Override
        public void onConnected() {

        }

        @Override
        public void onHomepage(String url) {

        }

        @Override
        public void onClientRegion(String region) {

        }

        @Override
        public void onSplitTunnelRegion(String region) {

        }

        @Override
        public void onUntunneledAddress(String address) {

        }

        @Override
        public void onBytesTransferred(long sent, long received) {

        }

        @Override
        public void onStartedWaitingForNetworkConnectivity() {

        }
    }
}
