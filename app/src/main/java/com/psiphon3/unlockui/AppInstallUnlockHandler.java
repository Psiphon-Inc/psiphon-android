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
 */

package com.psiphon3.unlockui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.psiphon3.PackageHelper;
import com.psiphon3.UnlockOptions;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

public class AppInstallUnlockHandler extends UnlockOptionHandler {
    private final UnlockOptions.AppInstallUnlockEntry appEntry;
    private final Runnable disconnectTunnelRunnable;

    public AppInstallUnlockHandler(String key, UnlockOptions.AppInstallUnlockEntry appEntry,
                                   Runnable disconnectTunnelRunnable, Runnable dismissDialogRunnable) {
        super(key, appEntry, dismissDialogRunnable);
        this.appEntry = appEntry;
        this.disconnectTunnelRunnable = disconnectTunnelRunnable;
    }

    @Override
    protected View createView(ViewGroup parent) {
        View view = getLayoutInflater(parent).inflate(R.layout.unlock_option_app_install_layout, parent, false);

        TextView appNameText = view.findViewById(R.id.appNameText);
        TextView descriptionText = view.findViewById(R.id.appDescriptionText);
        ImageButton installBtn = view.findViewById(R.id.installAppBtn);

        appNameText.setText(parent.getContext().getString(R.string.install_app_to_unlock_title, appEntry.appName));
        descriptionText.setText(parent.getContext().getString(R.string.install_app_to_unlock_description, appEntry.appName));

        installBtn.setOnClickListener(v -> openPlayStore(v.getContext()));

        return view;
    }

    @Override
    public void onShowDialog() {
        checkIfAppInstalled();
    }

    @Override
    public void onResume() {
        checkIfAppInstalled();
    }

    private void checkIfAppInstalled() {
        if (inflatedView != null && isAppInstalled(inflatedView.getContext())) {
            // App is now installed, dismiss dialog
            MyLog.i("AppInstallUnlockHandler: app " + appEntry.appName + " is installed, dismissing dialog.");
            dismissDialogRunnable.run();
        }
    }

    private boolean isAppInstalled(Context context) {
        return PackageHelper.isPackageInstalled(context.getPackageManager(), appEntry.appId);
    }

    private void openPlayStore(Context context) {
        // Disconnect tunnel before opening Play Store
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(appEntry.playStoreUrl));
        intent.setPackage("com.android.vending");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        try {
            MyLog.i("AppInstallUnlockHandler: opening Play Store for app: " + appEntry.appName);
            context.startActivity(intent);
            dismissDialogRunnable.run();
        } catch (ActivityNotFoundException ignored) {
            // Do nothing if Play Store is not available, just log the error
            MyLog.w("AppInstallUnlockHandler: Play Store not found for: " + appEntry.playStoreUrl);
        }
    }
}