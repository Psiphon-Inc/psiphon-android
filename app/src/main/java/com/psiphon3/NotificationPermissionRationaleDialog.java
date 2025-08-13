/*
 * Copyright (c) 2025, Psiphon Inc.
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

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.Manifest;
import android.os.Build;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.psiphon3.subscription.R;

import java.lang.ref.WeakReference;

public final class NotificationPermissionRationaleDialog {
    private static WeakReference<Dialog> currentDialogRef;

    public static boolean isShowing() {
        Dialog dialog = currentDialogRef != null ? currentDialogRef.get() : null;
        return dialog != null && dialog.isShowing();
    }

    public static void show(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (isShowing()) return;

        View dialogView = LayoutInflater.from(context).inflate(R.layout.permission_rationale, null);

        String appName = context.getString(R.string.app_name);
        String alertTitle = String.format(context.getString(R.string.notifications_permission_rationale_title), appName);

        SpannableStringBuilder message = new SpannableStringBuilder();
        message.append(String.format(context.getString(R.string.notifications_permission_rationale_intro), appName));
        message.append("\n\n");

        SpannableString bp = new SpannableString(context.getString(R.string.notifications_permission_rationale_vpn_state_bp));
        bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.append(bp).append("\n\n");

        bp = new SpannableString(context.getString(R.string.notifications_permission_rationale_connection_problems_bp));
        bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.append(bp).append("\n\n");

        bp = new SpannableString(context.getString(R.string.notifications_permission_rationale_crash_reports_bp));
        bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.append(bp).append("\n\n");

        bp = new SpannableString(context.getString(R.string.notifications_permission_rationale_malaware_alerts_bp));
        bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.append(bp).append("\n\n");

        try {
            Class.forName("com.psiphon3.psiphonlibrary.UpgradeChecker");
            bp = new SpannableString(context.getString(R.string.notifications_permission_rationale_upgrade_available_bp));
            bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            message.append(bp).append("\n\n");
        } catch (ClassNotFoundException ignored) { }

        message.append(context.getString(R.string.notifications_permission_rationale_disable_any_time));
        ((TextView) dialogView.findViewById(R.id.textView)).setText(message);

        AlertDialog alertDialog = new AlertDialog.Builder(context)
                .setCancelable(false)
                .setTitle(alertTitle)
                .setView(dialogView)
                .setPositiveButton(R.string.label_ok, (dialog, which) -> {
                    if (context instanceof Activity) {
                        ((Activity) context).requestPermissions(
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                MainActivity.REQUEST_CODE_PERMISSIONS
                        );
                    }
                })
                .show();

        alertDialog.setOnDismissListener(d -> {
            currentDialogRef = null;
        });

        currentDialogRef = new WeakReference<>(alertDialog);
    }
}
