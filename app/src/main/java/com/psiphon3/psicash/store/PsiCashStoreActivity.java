/*
 * Copyright (c) 2021, Psiphon Inc.
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

package com.psiphon3.psicash.store;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.MainActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.subscription.R;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashStoreActivity extends LocalizedActivities.AppCompatActivity {
    public static final int ACTIVITY_REQUEST_CODE = 201;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private BroadcastReceiver broadcastReceiver;
    private TunnelServiceInteractor tunnelServiceInteractor;
    private View progressOverlay;
    private final PublishRelay<PsiCashStoreIntent> intentsPublishRelay = PublishRelay.create();
    private Disposable psiCashUpdatesDisposable;

    private enum SceneState {
        NOT_AVAILABLE_WHILE_CONNECTING,
        NOT_AVAILABLE_WHILE_SUBSCRIBED,
        PSICASH_INVALID_STATE,
        PSICASH_NOT_ACCOUNT,
        PSICASH_LOGGED_IN,
        PSICASH_LOGGED_OUT
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.psicash_store_activity);
        progressOverlay = findViewById(R.id.progress_overlay);

        tunnelServiceInteractor = new TunnelServiceInteractor(this, true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.TUNNEL_RESTART);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (BroadcastIntent.TUNNEL_RESTART.equals(action)) {
                        tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), false);
                        finish();
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

        PsiCashStoreViewModel psiCashStoreViewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(PsiCashStoreViewModel.class);

        psiCashStoreViewModel.processIntents(intentsPublishRelay);

        final Flowable<SceneState> psiCashAccountStateFlowable =
                psiCashStoreViewModel.states()
                        .toFlowable(BackpressureStrategy.LATEST)
                        .filter(psiCashStoreViewState -> !psiCashStoreViewState.psiCashTransactionInFlight())
                        .map(state -> {
                            if (state.isAccount()) {
                                if (state.hasTokens()) {
                                    return SceneState.PSICASH_LOGGED_IN;
                                }
                                return SceneState.PSICASH_LOGGED_OUT;
                            } else {
                                if (state.hasTokens()) {
                                    return SceneState.PSICASH_NOT_ACCOUNT;
                                }
                                return SceneState.PSICASH_INVALID_STATE;
                            }
                        })
                        .distinctUntilChanged();

        compositeDisposable.add(GooglePlayBillingHelper.getInstance(getApplicationContext())
                .subscriptionStateFlowable()
                .distinctUntilChanged()
                .switchMap(subscriptionState -> {
                    if (subscriptionState.hasValidPurchase()) {
                        return Flowable.just(SceneState.NOT_AVAILABLE_WHILE_SUBSCRIBED);
                    }
                    return tunnelServiceInteractor.tunnelStateFlowable()
                            .filter(state -> !state.isUnknown())
                            .distinctUntilChanged()
                            .switchMap(state -> {
                                if (state.isRunning() && !state.connectionData().isConnected()) {
                                    return Flowable.just(SceneState.NOT_AVAILABLE_WHILE_CONNECTING);
                                }
                                return psiCashAccountStateFlowable;
                            });
                })
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .doOnNext(sceneState -> {
                    if (isFinishing()) {
                        return;
                    }

                    FragmentTransaction transaction = getSupportFragmentManager()
                            .beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);

                    switch (sceneState) {
                        case NOT_AVAILABLE_WHILE_CONNECTING:
                            transaction.replace(R.id.psicash_store_main, new PsiphonConnectingFragment());
                            break;
                        case NOT_AVAILABLE_WHILE_SUBSCRIBED:
                            transaction.replace(R.id.psicash_store_main, new UserSubscribedFragment());
                            break;
                        case PSICASH_INVALID_STATE:
                            transaction.replace(R.id.psicash_store_main, new InvalidPsiCashStateFragment());
                            break;
                        case PSICASH_NOT_ACCOUNT:
                        case PSICASH_LOGGED_IN:
                            transaction.replace(R.id.psicash_store_main, new StoreTabHostFragment());
                            break;
                        case PSICASH_LOGGED_OUT:
                            transaction.replace(R.id.psicash_store_main, new LoginRequiredFragment());
                            break;
                        default:
                            throw new IllegalStateException(new IllegalStateException("PsiCashStoreActivity: unknown scene state " + sceneState));
                    }
                    transaction.commitAllowingStateLoss();
                })
                .subscribe());
    }

    @Override
    protected void onStart() {
        super.onStart();
        tunnelServiceInteractor.onStart(getApplicationContext());
    }

    @Override
    protected void onStop() {
        super.onStop();
        tunnelServiceInteractor.onStop(getApplicationContext());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (psiCashUpdatesDisposable != null) {
            psiCashUpdatesDisposable.dispose();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        tunnelServiceInteractor.onResume();
        // Get PsiCash updates when foregrounded and on tunnel state changes after
        psiCashUpdatesDisposable = tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .distinctUntilChanged()
                .doOnNext(__ -> intentsPublishRelay.accept(PsiCashStoreIntent.GetPsiCash.create(
                        tunnelStateFlowable())))
                .subscribe();
        compositeDisposable.add(psiCashUpdatesDisposable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        tunnelServiceInteractor.onDestroy(getApplicationContext());
        compositeDisposable.dispose();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        // Call through to main activity if tunnel connect is requested
        if (data != null && MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION.equals(data.getAction())) {
            try {
                setResult(Activity.RESULT_OK, data);
                finish();
            } catch (RuntimeException ignored) {
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }
}
