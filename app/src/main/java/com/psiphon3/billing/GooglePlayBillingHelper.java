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
import androidx.annotation.NonNull;
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
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;

import java.util.ArrayList;
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
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class GooglePlayBillingHelper {
    private static final String IAB_PUBLIC_KEY = BuildConfig.IAB_PUBLIC_KEY;
    static public final String IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU = "speed_limited_ad_free_subscription";
    static public final String IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU = "basic_ad_free_subscription_5";

    private static final String[] IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS = {
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

    static public final Map<String, Integer> IAB_PSICASH_SKUS_TO_VALUE;

    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("psicash_1000", 1000);
        m.put("psicash_5000", 5000);
        m.put("psicash_10000", 10000);
        m.put("psicash_30000", 30000);
        m.put("psicash_100000", 100000);
        IAB_PSICASH_SKUS_TO_VALUE = Collections.unmodifiableMap(m);
    }

    private static GooglePlayBillingHelper INSTANCE = null;
    private final Flowable<BillingClient> connectionFlowable;
    private PublishRelay<PurchasesUpdate> purchasesUpdatedRelay;

    private CompositeDisposable compositeDisposable;
    private BehaviorRelay<List<SkuDetails>> allSkuDetailsBehaviorRelay;
    private BehaviorRelay<PurchaseState> purchaseStateBehaviorRelay;
    private Disposable startIabDisposable;


    private GooglePlayBillingHelper(final Context ctx) {
        purchasesUpdatedRelay = PublishRelay.create();
        compositeDisposable = new CompositeDisposable();
        purchaseStateBehaviorRelay = BehaviorRelay.create();
        allSkuDetailsBehaviorRelay = BehaviorRelay.create();

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
                            emitter.onError(new BillingException(responseCode));
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

    public static GooglePlayBillingHelper getInstance(final Context context) {
        if (INSTANCE == null) {
            INSTANCE = new GooglePlayBillingHelper(context);
        }
        return INSTANCE;
    }

    public Flowable<SubscriptionState> subscriptionStateFlowable() {
        return purchaseStateFlowable()
                .map(purchaseState -> {
                    if (purchaseState.error() != null) {
                        return SubscriptionState.billingError(purchaseState.error());
                    }
                    for (Purchase purchase : purchaseState.purchaseList()) {
                        if (isUnlimitedSubscription(purchase)) {
                            return SubscriptionState.unlimitedSubscription(purchase);
                        } else if (isLimitedSubscription(purchase)) {
                            return SubscriptionState.limitedSubscription(purchase);
                        } else if (isValidTimePass(purchase)) {
                            return SubscriptionState.timePass(purchase);
                        }
                    }
                    return SubscriptionState.noSubscription();
                });
    }

    public Flowable<PurchaseState> purchaseStateFlowable() {
        return purchaseStateBehaviorRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Single<List<SkuDetails>> allSkuDetailsSingle() {
        return allSkuDetailsBehaviorRelay
                .firstOrError();
    }

    public void startIab() {
        if (startIabDisposable != null && !startIabDisposable.isDisposed()) {
            // already subscribed to updates, do nothing
            return;
        }
        startIabDisposable = observeUpdates()
                .subscribe(
                        purchasesUpdate -> {
                            if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.OK) {
                                processPurchases(purchasesUpdate.purchases());
                            } else if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                                queryAllPurchases();
                            } else {
                                Utils.MyLog.g("GooglePlayBillingHelper::observeUpdates purchase update error response code: " + purchasesUpdate.responseCode());
                            }
                        },
                        err -> {
                            purchaseStateBehaviorRelay.accept(PurchaseState.error(err));
                        }
                );

        compositeDisposable.add(startIabDisposable);
    }

    private Single<List<SkuDetails>> getConsumablesSkuDetails() {
        List<String> ids = new ArrayList<>(IAB_TIMEPASS_SKUS_TO_DAYS.keySet());
        ids.addAll(new ArrayList<>(IAB_PSICASH_SKUS_TO_VALUE.keySet()));
        return getSkuDetails(ids, BillingClient.SkuType.INAPP);
    }

    private Single<List<SkuDetails>> getSubscriptionsSkuDetails() {
        List<String> ids = Arrays.asList(
                IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU,
                IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU
        );
        return getSkuDetails(ids, BillingClient.SkuType.SUBS);
    }

    public void queryAllSkuDetails() {
        compositeDisposable.add(
                Single.mergeDelayError(getSubscriptionsSkuDetails(), getConsumablesSkuDetails())
                        .flatMapIterable(skuDetails -> skuDetails)
                        .toList()
                        .onErrorReturnItem(Collections.emptyList())
                        .subscribe(allSkuDetailsBehaviorRelay)
        );
    }

    public void queryAllPurchases() {
        compositeDisposable.add(
                Single.mergeDelayError(getSubscriptions(), getPurchases())
                        .toList()
                        .map(listOfLists -> {
                            List<Purchase> purchaseList = new ArrayList<>();
                            for (List<Purchase> list : listOfLists) {
                                purchaseList.addAll(list);
                            }
                            return purchaseList;
                        })
                        .subscribe(
                                this::processPurchases,
                                err -> purchaseStateBehaviorRelay.accept(PurchaseState.error(err))
                        )
        );
    }

    private void processPurchases(List<Purchase> purchaseList) {
        if (purchaseList == null || purchaseList.size() == 0) {
            purchaseStateBehaviorRelay.accept(PurchaseState.empty());
            return;
        }

        List<Completable> completables = new ArrayList<>();
        // Iterate from back to front, making it safe to remove purchases
        for (int i = purchaseList.size() - 1; i >= 0; i--) {
            Purchase purchase = purchaseList.get(i);

            // Remove purchase with pending or unspecified state.
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                purchaseList.remove(i);
                continue;
            }

            // Remove purchase that doesn't pass signature verification.
            if (!Security.verifyPurchase(IAB_PUBLIC_KEY,
                    purchase.getOriginalJson(), purchase.getSignature())) {
                Utils.MyLog.g("StatusActivityBillingViewModel::processPurchases: failed verification for purchase: " + purchase);
                purchaseList.remove(i);
                continue;
            }

            // From Google sample app:
            // If you do not acknowledge a purchase, the Google Play Store will provide a refund to the
            // users within a few days of the transaction. Therefore you have to implement
            // [BillingClient.acknowledgePurchaseAsync] inside your app.
            completables.add(acknowledgePurchase(purchase));

            // Check if this purchase is an expired timepass which needs to be consumed
            if (IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(purchase.getSku())) {
                if (!isValidTimePass(purchase)) {
                    compositeDisposable.add(consumePurchase(purchase).subscribe());
                }
            }
        }

        // Wait for all acknowledgePurchase subscriptions to complete
        compositeDisposable.add(Completable.merge(completables)
                // OLD COMMENT:
                // If the initial purchase list contains not acknowledged purchases we need to
                // update it since the purchases were just acknowledged.
                // BillingClient.acknowledgePurchase does not yield the modified purchase after
                // AcknowledgePurchaseResponseListener is called and the only way to do it currently
                // is to query local purchases again, see https://stackoverflow.com/a/56468423

                // NEW COMMENT:
                // We are reverting to old behavior where the purchases would get acknowledged without
                // bubbling up the error because BillingClient.acknowledgePurchase often results in an error
                // due to the server connection problems. Since we call processPurchases often in the
                // app we are assuming all purchases will get acknowledged eventually. The state of
                // purchase.isAcknowledged field should not stop us from redeeming a purchase.
                .subscribe(() -> purchaseStateBehaviorRelay.accept(PurchaseState.create(purchaseList))));
    }

    Flowable<PurchasesUpdate> observeUpdates() {
        return connectionFlowable.flatMap(s ->
                purchasesUpdatedRelay.toFlowable(BackpressureStrategy.LATEST));
    }

    public Single<List<Purchase>> getPurchases() {
        return getOwnedItems(BillingClient.SkuType.INAPP);
    }

    public Single<List<Purchase>> getSubscriptions() {
        return getOwnedItems(BillingClient.SkuType.SUBS);
    }

    private Single<List<Purchase>> getOwnedItems(String type) {
        return connectionFlowable
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(client -> {
                    // If subscriptions are not supported return an empty purchase list, do not send error.
                    if (type.equals(BillingClient.SkuType.SUBS)) {
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
                        return Flowable.error(new BillingException(purchasesResult.getResponseCode()));
                    }
                })
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("GooglePlayBillingHelper::getOwnedItems type: " + type + " error: " + err));
    }

    public Single<List<SkuDetails>> getSkuDetails(List<String> ids, String type) {
        SkuDetailsParams params = SkuDetailsParams
                .newBuilder()
                .setSkusList(ids)
                .setType(type)
                .build();
        return connectionFlowable
                .observeOn(AndroidSchedulers.mainThread())
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
                                        emitter.onError(new BillingException(billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }, BackpressureStrategy.LATEST))
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("GooglePlayBillingHelper::getSkuDetails error: " + err));
    }

    public Completable launchFlow(Activity activity, String oldSku, String oldPurchaseToken, SkuDetails skuDetails) {
        BillingFlowParams.Builder billingParamsBuilder = BillingFlowParams
                .newBuilder();

        billingParamsBuilder.setSkuDetails(skuDetails);
        if (!TextUtils.isEmpty(oldSku) && !TextUtils.isEmpty(oldPurchaseToken)) {
            billingParamsBuilder.setOldSku(oldSku, oldPurchaseToken);
        }

        return connectionFlowable
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(client ->
                        Flowable.just(client.launchBillingFlow(activity, billingParamsBuilder.build())))
                .firstOrError()
                .flatMapCompletable(billingResult -> {
                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                        return Completable.complete();
                    } else {
                        return Completable.error(new BillingException(billingResult.getResponseCode()));
                    }
                })
                .doOnError(err -> Utils.MyLog.g("GooglePlayBillingHelper::launchFlow error: " + err));
    }

    public Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        return launchFlow(activity, null, null, skuDetails);
    }

    private Completable acknowledgePurchase(Purchase purchase) {
        if (purchase.isAcknowledged()) {
            return Completable.complete();
        }

        AcknowledgePurchaseParams.Builder paramsBuilder = AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken());
        return connectionFlowable
                .observeOn(AndroidSchedulers.mainThread())
                .firstOrError()
                .flatMapCompletable(client ->
                        Completable.create(emitter -> {
                            client.acknowledgePurchase(paramsBuilder.build(), billingResult -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onComplete();
                                    } else {
                                        emitter.onError(new BillingException(billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }))
                .doOnError(err -> Utils.MyLog.g("GooglePlayBillingHelper::acknowledgePurchase error: " + err))
                .onErrorComplete();
    }

    static boolean isUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getSku());
    }

    static boolean isLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getSku().equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    static boolean isValidTimePass(@NonNull Purchase purchase) {
        Long lifetimeInDays = IAB_TIMEPASS_SKUS_TO_DAYS.get(purchase.getSku());
        if (lifetimeInDays == null) {
            // not a time pass SKU
            return false;
        }
        // calculate expiry date based on the lifetime and purchase date
        long lifetimeMillis = lifetimeInDays * 24 * 60 * 60 * 1000;
        long timepassExpiryMillis = purchase.getPurchaseTime() + lifetimeMillis;

        return System.currentTimeMillis() < timepassExpiryMillis;
    }

    static public boolean isPsiCashPurchase(@NonNull Purchase purchase) {
        return IAB_PSICASH_SKUS_TO_VALUE.containsKey(purchase.getSku());
    }

    Single<String> consumePurchase(Purchase purchase) {
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
                                        emitter.onError(new BillingException(billingResult.getResponseCode()));
                                    }
                                }
                            });
                        }, BackpressureStrategy.LATEST))
                .firstOrError()
                .doOnError(err -> Utils.MyLog.g("GooglePlayBillingHelper::consumePurchase error: " + err))
                .onErrorReturnItem("");
    }

    public static class BillingException extends Exception {
        private @BillingResponseCode int billingResultResponseCode;
        public BillingException(@BillingResponseCode int billingResultResponseCode) {
            this.billingResultResponseCode = billingResultResponseCode;
        }

        @Override
        public String toString() {
            return "GooglePlayBillingHelper.BillingException{" +
                    "billingResultResponseCode=" + billingResultResponseCode +
                    '}';
        }
    }
}