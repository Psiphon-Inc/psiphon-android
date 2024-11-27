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
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseRequiredDialog implements DefaultLifecycleObserver {
    private final Dialog dialog;
    private final CardView cardSubscribe;
    private final CardView cardSpeedBoost;
    private final CardView cardConduit;
    private final CardView openConduitCard;
    private final CardView installConduitCard;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Runnable disconnectTunnelRunnable;
    private Disposable updateStateDisposable;

    private PurchaseRequiredDialog(Context context) {
        View contentView = LayoutInflater.from(context).inflate(R.layout.purchase_required_prompt_layout, null);

        // Initialize all final fields
        cardSubscribe = contentView.findViewById(R.id.cardSubscribe);
        cardSpeedBoost = contentView.findViewById(R.id.cardSpeedBoost);
        cardConduit = contentView.findViewById(R.id.cardConduit);
        openConduitCard = contentView.findViewById(R.id.openConduitCard);
        installConduitCard = contentView.findViewById(R.id.installConduitCard);

        dialog = new Dialog(context, R.style.Theme_NoTitleDialog);
        dialog.setCancelable(false);
        dialog.setContentView(contentView);
        dialog.setOnShowListener(dialogInterface -> subscribeToConduitState());
    }

    private void registerLifecycleOwner(LifecycleOwner owner) {
        owner.getLifecycle().addObserver(this);
    }

    private void setSubscribeOnClickListener(View.OnClickListener listener) {
        cardSubscribe.setOnClickListener(listener);
    }

    private void setSpeedBoostOnClickListener(View.OnClickListener listener) {
        cardSpeedBoost.setOnClickListener(listener);
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
        cardConduit.setVisibility(View.GONE);
    }

    private void updateConduitUI(ConduitState state) {
        cardConduit.setVisibility(View.VISIBLE);
        switch (state.status()) {
            case NOT_INSTALLED:
                setupInstallCard(false);
                break;
            case INCOMPATIBLE_VERSION:
                setupInstallCard(true);
                break;
            case RUNNING:
                dialog.dismiss();
                break;
            case STOPPED:
                setupOpenCard();
                break;
            default:
                MyLog.w("PurchaseRequiredDialog: unhandled Conduit state: " + state.status());
                hideConduitUI();
                break;
        }
    }

    private void setupInstallCard(boolean isConduitInstalled) {
        // Hide open card and show install card
        openConduitCard.setVisibility(View.GONE);
        installConduitCard.setVisibility(View.VISIBLE);

        // Switch between install and update text
        if (isConduitInstalled) {
            installConduitCard.findViewById(R.id.installConduitTextView).setVisibility(View.GONE);
            installConduitCard.findViewById(R.id.updateConduitTextView).setVisibility(View.VISIBLE);
        } else {
            installConduitCard.findViewById(R.id.installConduitTextView).setVisibility(View.VISIBLE);
            installConduitCard.findViewById(R.id.updateConduitTextView).setVisibility(View.GONE);
        }
        // Set click listener to disconnect tunnel and open Play Store for Conduit app
        cardConduit.setOnClickListener(v -> {
            if (disconnectTunnelRunnable != null) {
                disconnectTunnelRunnable.run();
            }
            openPlayStore();
        });
    }

    private void setupOpenCard() {
        // Hide install card and show open card
        openConduitCard.setVisibility(View.VISIBLE);
        installConduitCard.setVisibility(View.GONE);
        ImageView iconView = openConduitCard.findViewById(R.id.openConduitIconView);
        try {
            Context context = dialog.getContext();
            iconView.setImageDrawable(context.getPackageManager().getApplicationIcon("ca.psiphon.conduit"));
        } catch (Exception e) {
            MyLog.w("PurchaseRequiredDialog: failed to load Conduit app icon dynamically: " + e);
            iconView.setImageResource(R.drawable.ic_conduit_default);
        }
        // Set click listener to disconnect tunnel and launch Conduit app
        cardConduit.setOnClickListener(v -> {
            if (disconnectTunnelRunnable != null) {
                disconnectTunnelRunnable.run();
            }
            launchConduit();
        });
    }

    private void openPlayStore() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=ca.psiphon.conduit"));
        dialog.getContext().startActivity(intent);
        dialog.dismiss();
    }

    private void launchConduit() {
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
