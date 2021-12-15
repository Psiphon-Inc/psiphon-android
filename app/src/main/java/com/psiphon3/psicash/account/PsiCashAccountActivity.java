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

package com.psiphon3.psicash.account;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.psiphon3.TunnelState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class PsiCashAccountActivity extends LocalizedActivities.AppCompatActivity {
    public static final int ACTIVITY_REQUEST_CODE = 202;
    private TunnelServiceInteractor tunnelServiceInteractor;
    private BroadcastReceiver broadcastReceiver;
    private View progressOverlay;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }

    private enum SceneState {
        NOT_AVAILABLE_WHILE_NOT_CONNECTED, NOT_AVAILABLE_WHILE_CONNECTING, PSICASH_SIGN_IN
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.psicash_account_activity);

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
                        tunnelServiceInteractor.commandTunnelRestart(false);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

        compositeDisposable.add(tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .distinctUntilChanged()
                .map(tunnelState -> {
                    if (tunnelState.isRunning()) {
                        if (tunnelState.connectionData().isConnected()) {
                            return SceneState.PSICASH_SIGN_IN;
                        }
                        return SceneState.NOT_AVAILABLE_WHILE_CONNECTING;
                    }
                    return SceneState.NOT_AVAILABLE_WHILE_NOT_CONNECTED;
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
                        case NOT_AVAILABLE_WHILE_NOT_CONNECTED:
                            transaction.replace(R.id.psicash_account_main, new PsiphonNotConnectedFragment());
                            break;
                        case NOT_AVAILABLE_WHILE_CONNECTING:
                            transaction.replace(R.id.psicash_account_main, new PsiphonConnectingFragment());
                            break;
                        case PSICASH_SIGN_IN:
                            transaction.replace(R.id.psicash_account_main, new PsiCashSignInFragment());
                            break;
                    }
                    transaction.commitAllowingStateLoss();
                })
                .subscribe());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        tunnelServiceInteractor.onDestroy(getApplicationContext());
        compositeDisposable.dispose();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            setResult(Activity.RESULT_OK);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tunnelServiceInteractor.onResume();
        Intent intent = getIntent();
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

    void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }
}
