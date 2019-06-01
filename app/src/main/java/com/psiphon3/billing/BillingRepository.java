/*
 *
 * Copyright (c) 2019, Psiphon Inc.
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

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.jakewharton.rxrelay2.PublishRelay;

import java.util.Collections;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;

class BillingRepository {
    private static BillingRepository INSTANCE = null;
    private final Flowable<BillingClient> connectionFlowable;
    private PublishRelay<PurchasesUpdate> purchasesUpdatedRelay;

    private BillingRepository(final Context ctx) {
        purchasesUpdatedRelay = PublishRelay.create();

        PurchasesUpdatedListener listener = (billingResult, purchases) -> {
            @BillingResponseCode int responseCode = billingResult.getResponseCode();
            PurchasesUpdate purchasesUpdate = PurchasesUpdate.create(responseCode, purchases);
            purchasesUpdatedRelay.accept(purchasesUpdate);
        };

        Flowable<BillingClient> billingClientFlowable = Flowable.<BillingClient>create(emitter -> {
            BillingClient billingClient = BillingClient.newBuilder(ctx)
                    .enablePendingPurchases()
                    .setListener(listener)
                    .build();
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult billingResult) {
                    if (!emitter.isCancelled()) {
                        @BillingResponseCode int responseCode = billingResult.getResponseCode();
                        if (responseCode == BillingResponseCode.OK) {
                            emitter.onNext(billingClient);
                        } else {
                            emitter.onError(new RuntimeException(billingResult.getDebugMessage()));
                        }
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    if (!emitter.isCancelled()) {
                        emitter.onComplete();
                    }
                }
            });

            emitter.setCancellable(() -> {
                if (billingClient.isReady()) {
                    billingClient.endConnection();
                }
            });

        }, BackpressureStrategy.LATEST);

        this.connectionFlowable =
                Completable.complete()
                        .observeOn(AndroidSchedulers.mainThread()) // just to be sure billing client is called from main thread
                        .andThen(billingClientFlowable)
                        .share() //all observers will wait connection
                        .repeat() //repeat when billing client disconnected
                        .replay() //return same instance for all observers
                        .refCount(); //keep connection if at least one observer exists
    }

    public static BillingRepository getInstance(final Context context) {
        if (INSTANCE == null) {
            INSTANCE = new BillingRepository(context);
        }
        return INSTANCE;
    }

    Flowable<PurchasesUpdate> observeUpdates() {
        return connectionFlowable.flatMap(s ->
                purchasesUpdatedRelay.toFlowable(BackpressureStrategy.LATEST));
    }

    Single<List<Purchase>> getPurchases() {
        return getBoughtItems(BillingClient.SkuType.INAPP);
    }

    Single<List<Purchase>> getSubscriptions() {
        return getBoughtItems(BillingClient.SkuType.INAPP);
    }

    private Single<List<Purchase>> getBoughtItems(String type) {
        return connectionFlowable
                .flatMap(client -> {
                    Purchase.PurchasesResult purchasesResult = client.queryPurchases(type);
                    if (purchasesResult.getResponseCode() == BillingResponseCode.OK) {
                        List<Purchase> purchaseList = purchasesResult.getPurchasesList();
                        if (purchaseList == null) {
                            purchaseList = Collections.emptyList();
                        }
                        return Flowable.just(purchaseList);
                    } else {
                        return Flowable.error(new RuntimeException("Error: " + purchasesResult.getResponseCode()));
                    }
                })
                .firstOrError();
    }

    Single<List<SkuDetails>> getSkuDetails(List<String> ids, String type) {
        SkuDetailsParams params = SkuDetailsParams
                .newBuilder()
                .setSkusList(ids)
                .setType(type)
                .build();
        return connectionFlowable
                .flatMap(client ->
                        Flowable.<List<SkuDetails>>create(emitter -> {
                            client.querySkuDetailsAsync(params, (billingResult, skuDetailsList) -> {
                                if (!emitter.isCancelled()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        if (skuDetailsList == null) {
                                            skuDetailsList = Collections.emptyList();
                                        }
                                        emitter.onNext(skuDetailsList);
                                    } else {
                                        emitter.onError(new RuntimeException("query error: " + billingResult.getDebugMessage()));
                                    }
                                }
                            });
                        }, BackpressureStrategy.LATEST))
                .firstOrError();
    }

    Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        BillingFlowParams purchaseParams = BillingFlowParams
                .newBuilder()
                .setSkuDetails(skuDetails)
//                .setOldSku() // TODO: add this!
                .build();

        return connectionFlowable
                .flatMap(client ->
                        Flowable.just(client.launchBillingFlow(activity, purchaseParams)))
                .firstOrError()
                .flatMapCompletable(billingResult -> {
                    if(billingResult.getResponseCode() == BillingResponseCode.OK) {
                        return Completable.complete();
                    } else {
                        return Completable.error(new RuntimeException("launchFlow error: " + billingResult.getDebugMessage()));
                    }
                });
    }


    /*
    override fun launchFlow(activity: Activity, params: BillingFlowParams): Completable {
        return connectionFlowable
                .flatMap {
                    val responseCode = it.launchBillingFlow(activity, params)
                    return@flatMap Flowable.just(responseCode)
                }
                .firstOrError()
                .flatMapCompletable {
                    return@flatMapCompletable if (isSuccess(it)) {
                        Completable.complete()
                    } else {
                        Completable.error(BillingException.fromCode(it))
                    }
                }
    }
     */
}