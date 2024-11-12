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
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.FeatureType;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.log.MyLog;
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
import io.reactivex.SingleEmitter;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

// TODO: address deprecation of various SKU types and related methods
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
    private final Completable connectionCompletable;
    private final PublishRelay<PurchasesUpdate> purchasesUpdatedRelay;

    private final CompositeDisposable compositeDisposable;
    private final BehaviorRelay<List<SkuDetails>> allSkuDetailsBehaviorRelay;
    private final BehaviorRelay<PurchaseState> purchaseStateBehaviorRelay;
    private Disposable observePurchasesUpdatesDisposable;

    private final BillingClientManager billingClientManager;


    private GooglePlayBillingHelper(final Context ctx) {
        purchasesUpdatedRelay = PublishRelay.create();
        compositeDisposable = new CompositeDisposable();
        purchaseStateBehaviorRelay = BehaviorRelay.create();
        allSkuDetailsBehaviorRelay = BehaviorRelay.create();

        this.billingClientManager = new BillingClientManager(ctx.getApplicationContext(), (billingResult, purchases) -> {
            @BillingResponseCode int responseCode = billingResult.getResponseCode();
            PurchasesUpdate purchasesUpdate = PurchasesUpdate.create(responseCode, purchases);
            purchasesUpdatedRelay.accept(purchasesUpdate);
        });

        this.connectionCompletable = billingClientManager.ensureConnected();
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

    public void startObservePurchasesUpdates() {
        if (observePurchasesUpdatesDisposable != null && !observePurchasesUpdatesDisposable.isDisposed()) {
            // already subscribed to updates, do nothing
            return;
        }
        observePurchasesUpdatesDisposable = purchasesUpdatedRelay.toFlowable(BackpressureStrategy.LATEST)
                .subscribe(
                        purchasesUpdate -> {
                            if (purchasesUpdate.responseCode() == BillingResponseCode.OK) {
                                processPurchases(purchasesUpdate.purchases());
                            } else if (purchasesUpdate.responseCode() == BillingResponseCode.ITEM_ALREADY_OWNED) {
                                queryAllPurchases();
                            } else {
                                String message = BillingUtils.getBillingResponseMessage(purchasesUpdate.responseCode());
                                MyLog.e("GooglePlayBillingHelper::startObservePurchasesUpdates purchase update error response code: " +
                                        purchasesUpdate.responseCode() + ", message: " + message);
                            }
                        },
                        err -> purchaseStateBehaviorRelay.accept(PurchaseState.error(err))
                );

        compositeDisposable.add(observePurchasesUpdatesDisposable);
    }

    public void stopObservePurchasesUpdates() {
        if (observePurchasesUpdatesDisposable != null && !observePurchasesUpdatesDisposable.isDisposed()) {
            observePurchasesUpdatesDisposable.dispose();
        }
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
        if (purchaseList == null || purchaseList.isEmpty()) {
            purchaseStateBehaviorRelay.accept(PurchaseState.empty());
            return;
        }

        List<Completable> acknowledgeCompletables = new ArrayList<>();

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
                MyLog.e("GooglePlayBillingHelper::processPurchases: failed verification for purchase: " + purchase);
                purchaseList.remove(i);
                continue;
            }

            // From Google sample app:
            // If you do not acknowledge a purchase, the Google Play Store will provide a refund to the
            // users within a few days of the transaction. Therefore you have to implement
            // [BillingClient.acknowledgePurchaseAsync] inside your app.
            acknowledgeCompletables.add(
                    acknowledgePurchase(purchase)
                            .onErrorResumeNext(error -> {
                                MyLog.e("GooglePlayBillingHelper::processPurchases: failed to acknowledge purchase: " + purchase + ", error: " + error);
                                // Complete to allow other acknowledgments to proceed
                                return Completable.complete();
                            })
            );

            // Check if this purchase is an expired timepass which needs to be consumed
            if (IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(purchase.getSkus().get(0))) {
                if (!isValidTimePass(purchase)) {
                    compositeDisposable.add(consumePurchase(purchase).subscribe());
                }
            }
        }

        // Wait for all acknowledgePurchase subscriptions to complete
        compositeDisposable.add(Completable.merge(acknowledgeCompletables)
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

    public Single<List<Purchase>> getPurchases() {
        return getOwnedItems(BillingClient.SkuType.INAPP);
    }

    public Single<List<Purchase>> getSubscriptions() {
        return getOwnedItems(BillingClient.SkuType.SUBS);
    }

    private Single<List<Purchase>> getOwnedItems(String type) {
        // Wrap the main logic with RetryUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Single.create((SingleEmitter<List<Purchase>> emitter) -> {
                            // Retrieve the BillingClient instance
                            BillingClient client = billingClientManager.getBillingClient();

                            // Handle the subscriptions feature check
                            if (type.equals(BillingClient.SkuType.SUBS)) {
                                BillingResult featureCheckResult = client.isFeatureSupported(FeatureType.SUBSCRIPTIONS);

                                if (featureCheckResult.getResponseCode() == BillingResponseCode.FEATURE_NOT_SUPPORTED) {
                                    // Log and return an empty list for unsupported subscriptions
                                    String message = BillingUtils.getBillingResponseMessage(featureCheckResult.getResponseCode());
                                    MyLog.w("Subscriptions are not supported, billing response code: " +
                                            featureCheckResult.getResponseCode() + ", message: " + message);
                                    if (!emitter.isDisposed()) {
                                        emitter.onSuccess(Collections.emptyList());
                                    }
                                    return;
                                } else if (featureCheckResult.getResponseCode() != BillingResponseCode.OK) {
                                    // Return an error early for other feature check errors
                                    if (!emitter.isDisposed()) {
                                        emitter.onError(new BillingException(featureCheckResult.getResponseCode()));
                                    }
                                    return;
                                }
                            }

                            // Perform the asynchronous query
                            client.queryPurchasesAsync(type, (result, purchases) -> {
                                if (!emitter.isDisposed()) {
                                    if (result.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onSuccess(purchases); // Success
                                    } else {
                                        emitter.onError(new BillingException(result.getResponseCode())); // Error
                                    }
                                }
                            });
                        })).toFlowable(), // Convert Single to Flowable for retry logic
                        3 // Retry up to 3 times
                )
                .firstOrError()
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::getOwnedItems type: " + type + " error: " + err));
    }

    public Single<List<SkuDetails>> getSkuDetails(List<String> ids, String type) {
        SkuDetailsParams params = SkuDetailsParams
                .newBuilder()
                .setSkusList(ids)
                .setType(type)
                .build();

        // Wrap the main logic with BillingUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Single.create((SingleEmitter<List<SkuDetails>> emitter) -> {
                            // Retrieve the BillingClient instance
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform the asynchronous query
                            client.querySkuDetailsAsync(params, (billingResult, skuDetailsList) -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        if (skuDetailsList == null) {
                                            skuDetailsList = Collections.emptyList();
                                        }
                                        emitter.onSuccess(skuDetailsList); // Success
                                    } else {
                                        emitter.onError(new BillingException(billingResult.getResponseCode())); // Error
                                    }
                                }
                            });
                        })).toFlowable(), // Convert Single to Flowable for retry logic
                        3 // Retry up to 3 times
                )
                .firstOrError() // Convert back to Single
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::getSkuDetails error: " + err));
    }

    public Completable launchFlow(Activity activity, String oldSku, String oldPurchaseToken, SkuDetails skuDetails) {
        BillingFlowParams.Builder billingParamsBuilder = BillingFlowParams
                .newBuilder()
                .setSkuDetails(skuDetails);

        if (!TextUtils.isEmpty(oldSku) && !TextUtils.isEmpty(oldPurchaseToken)) {
            billingParamsBuilder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldSkuPurchaseToken(oldPurchaseToken)
                            .build());
        }

        // Wrap the main logic with BillingUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Completable.create(emitter -> {
                            // Retrieve the BillingClient instance
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform the launch billing flow
                            BillingResult billingResult = client.launchBillingFlow(activity, billingParamsBuilder.build());

                            if (!emitter.isDisposed()) {
                                if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                    emitter.onComplete(); // Success
                                } else {
                                    emitter.onError(new BillingException(billingResult.getResponseCode())); // Error
                                }
                            }
                        })).toFlowable(), // Convert Completable to Flowable for retry logic
                        3 // Retry up to 3 times
                )
                .ignoreElements() // Convert back to Completable
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::launchFlow error: " + err));
    }

    public Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        return launchFlow(activity, null, null, skuDetails);
    }

    private Completable acknowledgePurchase(Purchase purchase) {
        if (purchase.isAcknowledged()) {
            return Completable.complete();
        }

        AcknowledgePurchaseParams params = AcknowledgePurchaseParams
                .newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        // Wrap the main logic with BillingUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Completable.create(emitter -> {
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform acknowledgment
                            client.acknowledgePurchase(params, billingResult -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onComplete(); // Success
                                    } else {
                                        emitter.onError(new BillingException(billingResult.getResponseCode())); // Error
                                    }
                                }
                            });
                        })).toFlowable(), // Convert Completable to Flowable for retry logic
                        3 // Retry up to 3 times
                )
                .ignoreElements() // Convert back to Completable
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::acknowledgePurchase error: " + err));
    }

    Single<String> consumePurchase(Purchase purchase) {
        ConsumeParams params = ConsumeParams
                .newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();

        // Wrap the main logic with BillingUtils.withRetry
        // Note that consume operation is important and we will retry it up to 5 times because failing
        // to consume a purchase will result in "Unfinished purchase" UI state for PsiCash purchases or
        // prevent the user from purchasing a new time pass after the old one expires.
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Single.create((SingleEmitter<String> emitter) -> {
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform the consume operation
                            client.consumeAsync(params, (billingResult, purchaseToken) -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onSuccess(purchaseToken); // Success
                                    } else {
                                        emitter.onError(new BillingException(billingResult.getResponseCode())); // Error
                                    }
                                }
                            });
                        })).toFlowable(), // Convert Single to Flowable for retry logic
                        5 // Retry up to 5 times
                )
                .firstOrError() // Convert back to Single
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::consumePurchase error: " + err));
    }

    static boolean isUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getSkus().get(0));
    }

    static boolean isLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getSkus().get(0).equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    static boolean isValidTimePass(@NonNull Purchase purchase) {
        Long lifetimeInDays = IAB_TIMEPASS_SKUS_TO_DAYS.get(purchase.getSkus().get(0));
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
        return IAB_PSICASH_SKUS_TO_VALUE.containsKey(purchase.getSkus().get(0));
    }

    public static class BillingException extends Exception {
        private final @BillingResponseCode int billingResultResponseCode;
        public BillingException(@BillingResponseCode int billingResultResponseCode) {
            this.billingResultResponseCode = billingResultResponseCode;
        }

        public int getBillingResultResponseCode() {
            return billingResultResponseCode;
        }

        @NonNull
        @Override
        public String toString() {
            return "GooglePlayBillingHelper.BillingException{" +
                    "billingResultResponseCode=" + billingResultResponseCode +
                    ", billingResultMessage='" + BillingUtils.getBillingResponseMessage(billingResultResponseCode) + '\'' +
                    '}';
        }
    }
}