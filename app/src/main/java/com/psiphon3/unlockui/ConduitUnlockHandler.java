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
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.cardview.widget.CardView;

import com.psiphon3.ConduitState;
import com.psiphon3.ConduitStateManager;
import com.psiphon3.PackageHelper;
import com.psiphon3.UnlockOptions;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class ConduitUnlockHandler extends UnlockOptionHandler {
    private static final String PLAYSTORE_CONDUIT_URL =
            "https://play.google.com/store/apps/details?id=ca.psiphon.conduit";
    private static final String PLAYSTORE_PSIPHON_PRO_URL =
            "https://play.google.com/store/apps/details?id=com.psiphon3.subscription";

    private final Runnable disconnectTunnelRunnable;
    private Disposable conduitStateDisposable;

    // Views for different conduit states
    private View updatePsiphonProView;
    private View installConduitView;
    private View updateConduitView;
    private View openConduitView;

    public ConduitUnlockHandler(UnlockOptions.UnlockEntry entry, Runnable disconnectTunnelRunnable,
                                Runnable dismissDialogRunnable) {
        super(UnlockOptions.UNLOCK_ENTRY_CONDUIT, entry, dismissDialogRunnable);
        this.disconnectTunnelRunnable = disconnectTunnelRunnable;
    }

    @Override
    protected View createView(ViewGroup parent) {
        View view = getLayoutInflater(parent).inflate(R.layout.unlock_option_conduit_layout, parent, false);

        // Initialize state views
        updatePsiphonProView = view.findViewById(R.id.updatePsiphonProView);
        installConduitView = view.findViewById(R.id.installConduitView);
        updateConduitView = view.findViewById(R.id.updateConduitView);
        openConduitView = view.findViewById(R.id.openConduitView);

        // Set up click listeners
        CardView updatePsiphonProCard = view.findViewById(R.id.updatePsiphonProCardView);
        ImageButton installConduitBtn = view.findViewById(R.id.installConduitBtn);
        ImageButton updateConduitBtn = view.findViewById(R.id.updateConduitBtn);
        CardView openConduitCard = view.findViewById(R.id.openConduitCardView);

        updatePsiphonProCard.setOnClickListener(v -> openPlayStorePsiphonPro(v.getContext()));
        installConduitBtn.setOnClickListener(v -> openPlayStoreConduit(v.getContext()));
        updateConduitBtn.setOnClickListener(v -> openPlayStoreConduit(v.getContext()));
        openConduitCard.setOnClickListener(v -> launchConduit(v.getContext()));

        return view;
    }

    @Override
    public void onShowDialog() {
        subscribeToConduitState();
    }

    @Override
    public void onResume() {
        subscribeToConduitState();
    }

    @Override
    public void onPause() {
        if (conduitStateDisposable != null && !conduitStateDisposable.isDisposed()) {
            conduitStateDisposable.dispose();
        }
        conduitStateDisposable = null;
    }

    @Override
    public void onDismissDialog() {
        inflatedView = null;
        if (conduitStateDisposable != null && !conduitStateDisposable.isDisposed()) {
            conduitStateDisposable.dispose();
        }
    }

    private void subscribeToConduitState() {
        if (inflatedView == null) {
            return;
        }
        if (conduitStateDisposable != null && !conduitStateDisposable.isDisposed()) {
            return; // Already subscribed
        }


        // Load trusted signatures from file
        PackageHelper.configureRuntimeTrustedSignatures(
                PackageHelper.readTrustedSignaturesFromFile(inflatedView.getContext()));

        conduitStateDisposable =
                ConduitStateManager.newManager(inflatedView.getContext()).stateFlowable()
                        .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                this::updateConduitUI,
                                throwable -> {
                                    MyLog.e("ConduitUnlockHandler: error observing conduit state: " + throwable);
                                    hideAllConduitViews();
                                }
                        );
    }

    private void updateConduitUI(ConduitState state) {
        if (inflatedView == null) {
            return;
        }

        switch (state.status()) {
            case NOT_INSTALLED:
                showView(installConduitView);
                break;
            case INCOMPATIBLE_VERSION:
                showView(updateConduitView);
                break;
            case RUNNING:
                // Conduit is running, dismiss dialog
                MyLog.i("ConduitUnlockHandler: Conduit is running, dismissing dialog.");
                dismissDialogRunnable.run();
                break;
            case STOPPED:
                showView(openConduitView);
                break;
            case UNSUPPORTED_SCHEMA:
                showView(updatePsiphonProView);
                break;
            default:
                MyLog.w("ConduitUnlockHandler: unhandled Conduit state: " + state.status());
                hideAllConduitViews();
                break;
        }
    }

    private void showView(View viewToShow) {
        hideAllConduitViews();
        if (viewToShow != null) {
            viewToShow.setVisibility(View.VISIBLE);
        }
    }

    private void hideAllConduitViews() {
        if (updatePsiphonProView != null) {
            updatePsiphonProView.setVisibility(View.GONE);
        }
        if (installConduitView != null) {
            installConduitView.setVisibility(View.GONE);
        }
        if (updateConduitView != null) {
            updateConduitView.setVisibility(View.GONE);
        }
        if (openConduitView != null) {
            openConduitView.setVisibility(View.GONE);
        }
    }

    private void openPlayStoreConduit(android.content.Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(PLAYSTORE_CONDUIT_URL));
        intent.setPackage("com.android.vending");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (disconnectTunnelRunnable != null) {
                disconnectTunnelRunnable.run();
            }
            MyLog.i("ConduitUnlockHandler: opening Play Store for Conduit");
            context.startActivity(intent);
            dismissDialogRunnable.run();
        } catch (ActivityNotFoundException e) {
            MyLog.w("ConduitUnlockHandler: Play Store not found for: " + PLAYSTORE_CONDUIT_URL);
        }
    }

    private void openPlayStorePsiphonPro(android.content.Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(PLAYSTORE_PSIPHON_PRO_URL));
        intent.setPackage("com.android.vending");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (disconnectTunnelRunnable != null) {
                disconnectTunnelRunnable.run();
            }
            MyLog.i("ConduitUnlockHandler: opening Play Store for Psiphon Pro");
            context.startActivity(intent);
            dismissDialogRunnable.run();
        } catch (ActivityNotFoundException e) {
            MyLog.w("ConduitUnlockHandler: Play Store not found for: " + PLAYSTORE_PSIPHON_PRO_URL);
        }
    }

    private void launchConduit(android.content.Context context) {
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }
        Intent launchIntent = context.getPackageManager()
                .getLaunchIntentForPackage("ca.psiphon.conduit");
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            MyLog.i("ConduitUnlockHandler: launching Conduit app");
            context.startActivity(launchIntent);
            dismissDialogRunnable.run();
        }
    }
}