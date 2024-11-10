/*
 * Copyright (c) 2024, Psiphon Inc.
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

package com.psiphon3.billing;

import android.content.Context;

import androidx.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.BillingClient.BillingResponseCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.CompletableEmitter;
import io.reactivex.Flowable;

public class BillingClientManager {
    private final Context context;
    private BillingClient billingClient;
    private boolean isConnecting = false;
    private boolean isConnected = false;
    private final PurchasesUpdatedListener purchasesUpdatedListener;

    private final List<CompletableEmitter> pendingOperations = new ArrayList<>();

    public BillingClientManager(Context context, PurchasesUpdatedListener listener) {
        this.context = context.getApplicationContext();
        this.purchasesUpdatedListener = listener;
    }

    public synchronized Completable ensureConnected() {
        return Completable.defer(this::connectIfNeeded)
                .retryWhen(errors -> errors
                        // Retry three times with delays of 500ms, 1000ms, and 3000ms
                        .zipWith(Flowable.just(500L, 1000L, 3000L), (err, delay) -> delay)
                        .flatMap(delay -> Flowable.timer(delay, TimeUnit.MILLISECONDS))
                )
                .onErrorResumeNext(error ->
                        Completable.error(new RuntimeException("Failed to connect after 3 attempts", error)));
    }

    private synchronized Completable connectIfNeeded() {
        return Completable.create(emitter -> {
            if (isConnected && billingClient.isReady()) {
                emitter.onComplete(); // Already connected, no action needed
                return;
            }

            pendingOperations.add(emitter);

            if (!isConnecting) {
                isConnecting = true;
                connect();
            }
        });
    }

    private void connect() {
        if (billingClient == null) {
            billingClient = BillingClient.newBuilder(context)
                    .enablePendingPurchases()
                    .setListener(purchasesUpdatedListener) // Attach the listener here
                    .build();
        }

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                synchronized (BillingClientManager.this) {
                    isConnecting = false;
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        isConnected = true;
                        for (CompletableEmitter emitter : pendingOperations) {
                            emitter.onComplete();
                        }
                    } else {
                        isConnected = false;
                        for (CompletableEmitter emitter : pendingOperations) {
                            emitter.onError(new GooglePlayBillingHelper.BillingException(billingResult.getResponseCode()));
                        }
                    }
                    pendingOperations.clear();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                synchronized (BillingClientManager.this) {
                    isConnected = false;
                    isConnecting = false;
                }
            }
        });
    }

    public synchronized void disconnect() {
        if (billingClient != null && billingClient.isReady()) {
            billingClient.endConnection();
        }
        isConnected = false;
    }

    public BillingClient getBillingClient() {
        if (!isConnected) {
            throw new IllegalStateException("BillingClient is not connected");
        }
        return billingClient;
    }

    public synchronized Flowable<BillingClient> freshBillingClient() {
        return Flowable.create(emitter -> {
            BillingClient newClient = BillingClient.newBuilder(context)
                    .enablePendingPurchases()
                    .setListener(purchasesUpdatedListener) // Attach the listener here
                    .build();

            newClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        emitter.onNext(newClient);
                        emitter.onComplete();
                    } else {
                        emitter.onError(new GooglePlayBillingHelper.BillingException(billingResult.getResponseCode()));
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    emitter.onError(new GooglePlayBillingHelper.BillingException(BillingResponseCode.SERVICE_DISCONNECTED));
                }
            });

            emitter.setCancellable(newClient::endConnection);
        }, BackpressureStrategy.LATEST);
    }
}
