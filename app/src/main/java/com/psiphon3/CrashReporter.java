/*
 * Copyright (c) 2026, Psiphon Inc.
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import psi.Psi;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;

public final class CrashReporter {
    private static final String CRASH_REPORT_FILENAME = "go-crash-report";
    private static final String CRASH_REPORT_PENDING_SUFFIX = ".pending";
    private static final String CRASH_REPORT_CONTENT_SEPARATOR = "=== GO CRASH REPORT ===";
    private static final String NOTIFICATION_NATIVE_CRASH_CHANNEL_ID = "notificationNativeCrashChannelId";
    private static final Object crashReceiverLock = new Object();
    private static final Object sessionNotificationLock = new Object();

    public interface CrashReceiver {
        void onCrashSignal();
    }

    private static volatile WeakReference<CrashReceiver> crashReceiver = new WeakReference<>(null);
    private static boolean hasNotifiedPendingCrashReportThisSession = false;

    static {
        System.loadLibrary("crashreporter");
    }

    private CrashReporter() {
    }

    public static void registerCrashReceiver(CrashReceiver receiver) {
        synchronized (crashReceiverLock) {
            crashReceiver = new WeakReference<>(receiver);
        }
    }

    public static void unregisterCrashReceiver() {
        synchronized (crashReceiverLock) {
            crashReceiver = new WeakReference<>(null);
        }
    }

    public static String getActiveCrashReportPath(Context context) {
        return new File(context.getFilesDir(), CRASH_REPORT_FILENAME).getAbsolutePath();
    }

    public static String getActiveCrashReportPathForArming(Context context) {
        promoteOrDeleteActiveCrashReport(context);
        File activeReport = new File(getActiveCrashReportPath(context));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(activeReport, false))) {
            writer.write(getCrashReportHeader());
            writer.flush();
        } catch (IOException e) {
            MyLog.w("CrashReporter: failed to prepare active crash report: " + e);
        }
        return activeReport.getAbsolutePath();
    }

    public static void promoteActiveCrashReportIfPresent(Context context) {
        File pendingReport = new File(getPendingCrashReportPath(context));
        File activeReport = new File(getActiveCrashReportPath(context));
        if (containsCrashReport(activeReport)) {
            moveFile(activeReport, pendingReport);
        }
    }

    public static void promoteOrDeleteActiveCrashReport(Context context) {
        File pendingReport = new File(getPendingCrashReportPath(context));
        File activeReport = new File(getActiveCrashReportPath(context));
        if (containsCrashReport(activeReport)) {
            moveFile(activeReport, pendingReport);
        } else if (activeReport.exists()) {
            deleteFile(activeReport, "delete non-crash active file");
        }
    }

    public static boolean hasPendingCrashReport(Context context) {
        File pendingReport = new File(getPendingCrashReportPath(context));
        if (!containsCrashReport(pendingReport)) {
            if (pendingReport.exists()) {
                deleteFile(pendingReport, "delete non-crash pending file");
            }
            return false;
        }
        return true;
    }

    public static boolean markPendingCrashReportNotifiedThisSession() {
        synchronized (sessionNotificationLock) {
            if (hasNotifiedPendingCrashReportThisSession) {
                return false;
            }
            hasNotifiedPendingCrashReportThisSession = true;
            return true;
        }
    }

    public static String getPendingCrashReportPath(Context context) {
        return getActiveCrashReportPath(context) + CRASH_REPORT_PENDING_SUFFIX;
    }

    public static native boolean nativeInstallCrashSignalNotifier();

    public static native void nativeUninstallCrashSignalNotifier();

    public static void onNativeCrashSignal() {
        MyLog.e("CrashReporter: native crash signal callback reached Java");

        final CrashReceiver receiver = getRegisteredCrashReceiver();
        if (receiver != null) {
            try {
                receiver.onCrashSignal();
            } catch (RuntimeException e) {
                MyLog.w("CrashReporter: crash receiver callback failed: " + e);
            }
        }
    }

    private static CrashReceiver getRegisteredCrashReceiver() {
        synchronized (crashReceiverLock) {
            return crashReceiver.get();
        }
    }

    private static String getCrashReportHeader() {
        try {
            String buildInfo = Psi.getBuildInfo();
            if (buildInfo != null && !buildInfo.isEmpty()) {
                return "Build info: " + buildInfo + "\n"
                        + CRASH_REPORT_CONTENT_SEPARATOR + "\n\n";
            }
        } catch (RuntimeException e) {
            MyLog.w("CrashReporter: failed to read tunnel-core build info: " + e);
        }
        return "Build info unavailable\n"
                + CRASH_REPORT_CONTENT_SEPARATOR + "\n\n";
    }

    private static boolean containsCrashReport(File file) {
        if (!file.exists() || file.length() == 0) {
            return false;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (CRASH_REPORT_CONTENT_SEPARATOR.equals(line)) {
                    break;
                }
            }

            while ((line = reader.readLine()) != null) {
                // This is still a heuristic rather than a strict file format
                // check, but by only scanning content after the separator we
                // avoid false positives from the prewritten header.
                if (line.contains("panic:") ||
                        line.contains("fatal error:") ||
                        line.contains("goroutine ")) {
                    return true;
                }
            }
        } catch (IOException e) {
            MyLog.w("CrashReporter: failed to validate crash report: " + e);
        }

        return false;
    }

    private static void moveFile(File from, File to) {
        if (!from.exists()) {
            return;
        }

        if (to.exists() && !deleteFile(to, "replace existing crash report")) {
            MyLog.w("CrashReporter: failed to replace existing crash report");
            return;
        }

        if (from.renameTo(to)) {
            return;
        }

        try (BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(from));
             BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(to))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            deleteFile(from, "delete source crash report after copy");
        } catch (IOException e) {
            MyLog.w("CrashReporter: failed to move crash report: " + e);
        }
    }

    private static boolean deleteFile(File file, String description) {
        if (!file.exists()) {
            return true;
        }
        if (file.delete()) {
            return true;
        }
        MyLog.w("CrashReporter: failed to " + description + ": " + file.getAbsolutePath());
        return false;
    }

    public static void clearCrashNotification(Context context) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(R.id.notification_id_native_crash_report_available);
    }

    public static void showCrashNotification(Context context) {
        ensureNotificationChannel(context);

        Intent intent = new Intent(context, FeedbackActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_NATIVE_CRASH_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                .setGroup(context.getString(R.string.alert_notification_group))
                .setContentTitle(context.getString(R.string.psiphon_native_crash_notification_title))
                .setContentText(context.getString(R.string.psiphon_native_crash_notification_msg))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.psiphon_native_crash_notification_msg_long)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(R.id.notification_id_native_crash_report_available, notification);
    }

    private static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_NATIVE_CRASH_CHANNEL_ID,
                context.getText(R.string.psiphon_native_crash_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
    }
}
