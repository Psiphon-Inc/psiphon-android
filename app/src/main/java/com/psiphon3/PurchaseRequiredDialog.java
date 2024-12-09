/*
 * Copyright (c) 2022, Psiphon Inc.
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

package com.psiphon3;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseRequiredDialog implements DefaultLifecycleObserver {
    private final Dialog dialog;
    // Always shown
    private final CardView subscribeCard;
    private final CardView speedBoostCard;


    private final CardView openConduitCardView;
    private final ImageButton installConduitBtn;
    private final ImageButton updateConduitBtn;
    private final CardView updatePsiphonProCardView;

    private final View openConduitView;
    private final View installConduitView;
    private final View updateConduitView;
    private final View updatePsiphonProView;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Runnable disconnectTunnelRunnable;
    private Disposable updateStateDisposable;

    private PurchaseRequiredDialog(Context context) {
        View contentView = LayoutInflater.from(context).inflate(R.layout.purchase_required_prompt_layout, null);

        // Initialize all final fields
        subscribeCard = contentView.findViewById(R.id.subscribeCardView);
        speedBoostCard = contentView.findViewById(R.id.speedBoostCardView);
        openConduitCardView = contentView.findViewById(R.id.openConduitCardView);

        // Initialize the container views
        openConduitView = contentView.findViewById(R.id.openConduitView);
        installConduitView = contentView.findViewById(R.id.installConduitView);
        updateConduitView = contentView.findViewById(R.id.updateConduitView);
        updatePsiphonProView = contentView.findViewById(R.id.updatePsiphonProView);

        // Initialize the clickables
        installConduitBtn = contentView.findViewById(R.id.installConduitBtn);
        updateConduitBtn = contentView.findViewById(R.id.updateConduitBtn);
        updatePsiphonProCardView = contentView.findViewById(R.id.updatePsiphonProCardView);

        // Set click listeners
        openConduitCardView.setOnClickListener(v -> launchConduit());
        installConduitBtn.setOnClickListener(v -> openPlayStoreConduit());
        updateConduitBtn.setOnClickListener(v -> openPlayStoreConduit());
        updatePsiphonProCardView.setOnClickListener(v -> openPlayStorePsiphonPro());

        dialog = new Dialog(context, R.style.Theme_NoTitleDialog);
        dialog.setCancelable(false);
        dialog.setContentView(contentView);
        dialog.setOnShowListener(dialogInterface -> subscribeToConduitState());
    }

    private void registerLifecycleOwner(LifecycleOwner owner) {
        owner.getLifecycle().addObserver(this);
    }

    private void setSubscribeOnClickListener(View.OnClickListener listener) {
        subscribeCard.setOnClickListener(listener);
    }

    private void setSpeedBoostOnClickListener(View.OnClickListener listener) {
        speedBoostCard.setOnClickListener(listener);
    }

    private void setDisconnectTunnelRunnable(Runnable runnable) {
        this.disconnectTunnelRunnable = runnable;
    }

    private void show() {
        dialog.show();
        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);

    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        compositeDisposable.clear();
        if (dialog.getContext() instanceof LifecycleOwner) {
            ((LifecycleOwner) dialog.getContext()).getLifecycle().removeObserver(this);
        }
        dialog.dismiss();
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (dialog.isShowing()) {
            subscribeToConduitState();
        }
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        compositeDisposable.clear();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        if (dialog.isShowing()) {
            dismiss();
        }
    }

    private void subscribeToConduitState() {
        // If already subscribed, do nothing
        if (updateStateDisposable != null && !updateStateDisposable.isDisposed()) {
            return;
        }
        // Load trusted signatures from file
        PackageHelper.configureRuntimeTrustedSignatures(PackageHelper.readTrustedSignaturesFromFile(dialog.getContext()));

        updateStateDisposable =
                ConduitStateManager.newManager(dialog.getContext()).stateFlowable()
                        // Filter out unknown states
                        .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                        // Always observe on main thread to update UI
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(
                                this::updateConduitUI,
                                throwable -> {
                                    hideConduitUI();
                                }
                        );
        compositeDisposable.add(updateStateDisposable);
    }

    private void hideConduitUI() {
        // Hide all Conduit related containers
        openConduitView.setVisibility(View.GONE);
        installConduitView.setVisibility(View.GONE);
        updateConduitView.setVisibility(View.GONE);
        updatePsiphonProView.setVisibility(View.GONE);
    }

    private void updateConduitUI(ConduitState state) {
        switch (state.status()) {
            case NOT_INSTALLED:
                // Conduit is not installed, show install Conduit view, hide all others
                installConduitView.setVisibility(View.VISIBLE);
                updateConduitView.setVisibility(View.GONE);
                openConduitView.setVisibility(View.GONE);
                updatePsiphonProView.setVisibility(View.GONE);
                break;
            case INCOMPATIBLE_VERSION:
                // Incompatible version, show update Conduit view, hide all others
                updateConduitView.setVisibility(View.VISIBLE);
                installConduitView.setVisibility(View.GONE);
                openConduitView.setVisibility(View.GONE);
                updatePsiphonProView.setVisibility(View.GONE);
                break;
            case RUNNING:
                // Conduit is running, close the dialog
                dialog.dismiss();
                break;
            case STOPPED:
                // Conduit is stopped, show open Conduit view, hide all others
                openConduitView.setVisibility(View.VISIBLE);
                updatePsiphonProView.setVisibility(View.GONE);
                installConduitView.setVisibility(View.GONE);
                updateConduitView.setVisibility(View.GONE);
                break;
            case UNSUPPORTED_SCHEMA:
                // Unsupported schema, show update Psiphon Pro view, hide all others
                updatePsiphonProView.setVisibility(View.VISIBLE);
                installConduitView.setVisibility(View.GONE);
                updateConduitView.setVisibility(View.GONE);
                openConduitView.setVisibility(View.GONE);
                break;
            default:
                MyLog.w("PurchaseRequiredDialog: unhandled Conduit state: " + state.status());
                hideConduitUI();
                break;
        }
    }

    private void openPlayStoreConduit() {
        // Disconnect tunnel before opening Play Store
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=ca.psiphon.conduit"));
        intent.setPackage("com.android.vending");
        dialog.getContext().startActivity(intent);
        dialog.dismiss();
    }

    private void openPlayStorePsiphonPro() {
        // Disconnect tunnel before opening Play Store
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=com.psiphon3.subscription"));
        intent.setPackage("com.android.vending");
        dialog.getContext().startActivity(intent);
        dialog.dismiss();
    }

    private void launchConduit() {
        // Disconnect tunnel before launching Conduit
        if (disconnectTunnelRunnable != null) {
            disconnectTunnelRunnable.run();
        }
        Intent launchIntent = dialog.getContext().getPackageManager()
                .getLaunchIntentForPackage("ca.psiphon.conduit");
        if (launchIntent != null) {
            dialog.getContext().startActivity(launchIntent);
            dialog.dismiss();
        }
    }

    public static class Builder {
        private final PurchaseRequiredDialog dialog;
        private final LifecycleOwner lifecycleOwner;

        public Builder(Context context, LifecycleOwner lifecycleOwner) {
            this.dialog = new PurchaseRequiredDialog(context);
            this.lifecycleOwner = lifecycleOwner;
        }

        public Builder setSubscribeClickListener(View.OnClickListener listener) {
            dialog.setSubscribeOnClickListener(listener);
            return this;
        }

        public Builder setSpeedBoostClickListener(View.OnClickListener listener) {
            dialog.setSpeedBoostOnClickListener(listener);
            return this;
        }

        public Builder setDisconnectTunnelRunnable(Runnable runnable) {
            dialog.setDisconnectTunnelRunnable(runnable);
            return this;
        }

        public PurchaseRequiredDialog show() {
            dialog.registerLifecycleOwner(lifecycleOwner);
            dialog.show();
            return dialog;
        }
    }
}
