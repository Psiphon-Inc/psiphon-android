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
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class BillingRepository {
    static public final String IAB_PUBLIC_KEY = BuildConfig.IAB_PUBLIC_KEY;
    static public final String IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU = "speed_limited_ad_free_subscription";
    static public final String IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU = "basic_ad_free_subscription_5";

    static final String[] IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS = {
            IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU,
            "basic_ad_free_subscription",
            "basic_ad_free_subscription_2",
            "basic_ad_free_subscription_3",
            "basic_ad_free_subscription_4"
    };

    static public final Map<String, Long> IAB_TIMEPASS_SKUS_TO_DAYS;
    static {
        Map<String, Long> m = new HashMap<>();
        m.put("basic_ad_free_7_day_timepass", 7L);
        m.put("basic_ad_free_30_day_timepass", 30L);
        m.put("basic_ad_free_360_day_timepass", 360L);
        IAB_TIMEPASS_SKUS_TO_DAYS = Collections.unmodifiableMap(m);
    }

    static public final Map<String, Long> IAB_PSICASH_SKUS_TO_VALUE;
    static {
        Map<String, Long> m = new HashMap<>();
        m.put("psicash_tier_1", 100L);
        IAB_PSICASH_SKUS_TO_VALUE = Collections.unmodifiableMap(m);
    }

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

        }, BackpressureStrategy.LATEST)
                .repeat(); // reconnect automatically if client disconnects

        this.connectionFlowable =
                Completable.complete()
                        .observeOn(AndroidSchedulers.mainThread()) // just to be sure billing client is called from main thread
                        .andThen(billingClientFlowable)
                        .replay(1) // return same last instance for all observers
                        .refCount(); // keep connection if at least one observer exists
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
        return getOwnedItems(BillingClient.SkuType.INAPP);
    }

    Single<List<Purchase>> getSubscriptions() {
        return getOwnedItems(BillingClient.SkuType.SUBS);
    }

    private Single<List<Purchase>> getOwnedItems(String type) {
        return connectionFlowable
                .flatMap(client -> {
                    // If subscriptions are not supported return an empty purchase list, do not send error.
                    if(type.equals(BillingClient.SkuType.SUBS)) {
                        BillingResult billingResult = client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS);
                        if (billingResult.getResponseCode() != BillingResponseCode.OK) {
                            Utils.MyLog.g("Subscriptions are not supported, billing response code: " + billingResult.getResponseCode());
                            List<Purchase> purchaseList = Collections.emptyList();
                            return Flowable.just(purchaseList);
                        }
                    }

                    Purchase.PurchasesResult purchasesResult = client.queryPurchases(type);
                    if (purchasesResult.getResponseCode() == BillingResponseCode.OK) {
                        List<Purchase> purchaseList = purchasesResult.getPurchasesList();
                        if (purchaseList == null) {
                            purchaseList = Collections.emptyList();
                        }
                        return Flowable.just(purchaseList);
                    } else {
                        return Flowable.error(new RuntimeException("Billing response code: " + purchasesResult.getResponseCode()));
                    }
                })
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("BillingRepository::getOwnedItems type: " + type + " error: " + err));
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
                                        emitter.onError(new RuntimeException("Billing response code: " + billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }, BackpressureStrategy.LATEST))
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("BillingRepository::getSkuDetails error: " + err));
    }

    Completable launchFlow(Activity activity, String oldSku, String oldPurchaseToken, SkuDetails skuDetails) {
        BillingFlowParams.Builder billingParamsBuilder = BillingFlowParams
                .newBuilder();

        billingParamsBuilder.setSkuDetails(skuDetails);
        if (!TextUtils.isEmpty(oldSku) && !TextUtils.isEmpty(oldPurchaseToken)) {
            billingParamsBuilder.setOldSku(oldSku, oldPurchaseToken);
        }

        return connectionFlowable
                .flatMap(client ->
                        Flowable.just(client.launchBillingFlow(activity, billingParamsBuilder.build())))
                .firstOrError()
                .flatMapCompletable(billingResult -> {
                    if(billingResult.getResponseCode() == BillingResponseCode.OK) {
                        return Completable.complete();
                    } else {
                        return Completable.error(new RuntimeException("Billing response code: " + billingResult.getResponseCode()));
                    }
                })
                .doOnError(err -> Utils.MyLog.g("BillingRepository::launchFlow error: " + err));
    }

    Completable acknowledgePurchase(Purchase purchase) {
        if (purchase.isAcknowledged()) {
            return Completable.complete();
        }
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        return connectionFlowable
                .firstOrError()
                .flatMapCompletable(client ->
                        Completable.create(emitter -> {
                            client.acknowledgePurchase(params, billingResult -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onComplete();
                                    } else {
                                        emitter.onError(new RuntimeException("Billing response code: " + billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }))
                .doOnError(err -> Utils.MyLog.g("BillingRepository::acknowledgePurchase error: " + err))
                .onErrorComplete();
    }

    static boolean hasUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getSku());
    }

    static boolean hasLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getSku().equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    static boolean hasTimePass(@NonNull Purchase purchase) {
        String purchaseSku = purchase.getSku();
        Long lifetimeInDays = IAB_TIMEPASS_SKUS_TO_DAYS.get(purchaseSku);
        if (lifetimeInDays == null) {
            // not a time pass SKU
            return false;
        }
        // calculate expiry date based on the lifetime and purchase date
        long lifetimeMillis = lifetimeInDays * 24 * 60 * 60 * 1000;
        long timepassExpiryMillis = purchase.getPurchaseTime() + lifetimeMillis;
        if (System.currentTimeMillis() < timepassExpiryMillis) {
            // This time pass is still valid.
            return true;
        }
        return false;
    }

    Single consumePurchase(Purchase purchase) {
        ConsumeParams params = ConsumeParams
                .newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        return connectionFlowable
                .flatMap(client ->
                        Flowable.<String>create(emitter -> {
                            client.consumeAsync(params, (billingResult, purchaseToken) -> {
                                if (!emitter.isCancelled()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onNext(purchaseToken);
                                    } else {
                                        emitter.onError(new RuntimeException("Billing response code: " + billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }, BackpressureStrategy.LATEST))
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("BillingRepository::consumePurchase error: " + err))
                .onErrorReturnItem("");
    }
}