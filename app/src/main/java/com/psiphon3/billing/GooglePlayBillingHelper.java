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
import androidx.annotation.Nullable;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClient.FeatureType;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
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
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
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

    private static GooglePlayBillingHelper INSTANCE = null;
    private final Completable connectionCompletable;
    private final PublishRelay<PurchasesUpdate> purchasesUpdatedRelay;

    private final CompositeDisposable compositeDisposable;
    private final BehaviorRelay<PurchaseState> purchaseStateBehaviorRelay;
    private final BehaviorRelay<List<ProductDetails>> productDetailsBehaviorRelay = BehaviorRelay.create();
    private final AtomicBoolean isFetchingProductDetails = new AtomicBoolean(false);
    private Disposable observePurchasesUpdatesDisposable;

    private final BillingClientManager billingClientManager;


    private GooglePlayBillingHelper(final Context ctx) {
        purchasesUpdatedRelay = PublishRelay.create();
        compositeDisposable = new CompositeDisposable();
        purchaseStateBehaviorRelay = BehaviorRelay.create();

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

    private Single<List<ProductDetails>> getProductDetails(List<String> productIds, String productType) {
        // Return empty list immediately if no products
        if (productIds == null || productIds.isEmpty()) {
            return Single.just(Collections.emptyList());
        }

        List<QueryProductDetailsParams.Product> products = new ArrayList<>();
        for (String productId : productIds) {
            products.add(
                    QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(productType)
                            .build()
            );
        }

        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build();

        // Wrap the main logic with BillingUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Single.create((SingleEmitter<List<ProductDetails>> emitter) -> {
                            // Retrieve the BillingClient instance
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform the asynchronous query
                            client.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
                                if (!emitter.isDisposed()) {
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK) {
                                        emitter.onSuccess(productDetailsList); // Success
                                    } else {
                                        emitter.onError(new BillingException(billingResult.getResponseCode())); // Error
                                    }
                                }
                            });
                        })).toFlowable(), // Convert Single to Flowable for retry logic
                        3
                )
                .firstOrError() // Convert back to Single
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::getProductDetails error: " + err))
                .onErrorReturnItem(Collections.emptyList());
    }

    public Single<List<ProductDetails>> getTimePassProductDetails() {
        return productDetailsBehaviorRelay
                .firstOrError()
                .map(allProducts -> {
                    List<ProductDetails> timePassProducts = new ArrayList<>();
                    for (ProductDetails product : allProducts) {
                        if (IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(product.getProductId()) &&
                                product.getProductType().equals(BillingClient.ProductType.INAPP)) {
                            timePassProducts.add(product);
                        }
                    }
                    return timePassProducts;
                });
    }

    public Single<List<ProductDetails>> getSubscriptionProductDetails() {
        return productDetailsBehaviorRelay
                .firstOrError()
                .map(allProducts -> {
                    List<ProductDetails> subscriptionProducts = new ArrayList<>();
                    for (ProductDetails product : allProducts) {
                        if ((product.getProductId().equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU) ||
                                product.getProductId().equals(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU)) &&
                                product.getProductType().equals(BillingClient.ProductType.SUBS)) {
                            subscriptionProducts.add(product);
                        }
                    }
                    return subscriptionProducts;
                });
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

    public void fetchAllProducts() {
        if (isFetchingProductDetails.compareAndSet(false, true)) {
            // All one time products IDs (time passes)
            List<String> allInAppProductIds = new ArrayList<>(IAB_TIMEPASS_SKUS_TO_DAYS.keySet());

            // All subscription products IDs
            List<String> allSubscriptionProductIds = new ArrayList<>(
                    Arrays.asList(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU, IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU));

            compositeDisposable.add(
                    Single.zip(
                                    getProductDetails(allInAppProductIds, BillingClient.ProductType.INAPP),
                                    getProductDetails(allSubscriptionProductIds, BillingClient.ProductType.SUBS),
                                    (inAppProducts, subscriptionProducts) -> {
                                        List<ProductDetails> allProducts = new ArrayList<>(inAppProducts);
                                        allProducts.addAll(subscriptionProducts);
                                        return allProducts;
                                    })

                            .doFinally(() -> isFetchingProductDetails.set(false))
                            .subscribe(
                                    productDetailsBehaviorRelay,
                                    err -> MyLog.e("GooglePlayBillingHelper::fetchAllProducts error: " + err)
                            )
            );
        }
    }

    private void processPurchases(List<Purchase> purchaseList) {
        if (purchaseList == null || purchaseList.isEmpty()) {
            purchaseStateBehaviorRelay.accept(PurchaseState.empty());
            return;
        }

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

            // From Google developer documentation:
            // Warning! All purchases require acknowledgement. Failure to acknowledge a purchase will result in that purchase being refunded.
            //
            // https://developer.android.com/google/play/billing/integrate#notifying-google
            // After your app has granted entitlement to the user and notified them about the successful transaction, your app needs to notify
            // Google that the purchase was successfully processed. This is done by acknowledging the purchase and must be done within three
            // days to ensure the purchase isn't automatically refunded and entitlement revoked.
            compositeDisposable.add(acknowledgePurchase(purchase).subscribe());

            // Check if this purchase is an expired timepass which needs to be consumed
            if (IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(purchase.getProducts().get(0))) {
                if (!isValidTimePass(purchase)) {
                    compositeDisposable.add(consumePurchase(purchase).subscribe());
                }
            }
        }

        // Update the purchase state without waiting for the acknowledgment or consumption to complete.
        // We call processPurchases() often enough to ensure that the purchases are eventually acknowledged and consumed.
        purchaseStateBehaviorRelay.accept(PurchaseState.create(purchaseList));
    }

    public Single<List<Purchase>> getPurchases() {
        return getOwnedItems(BillingClient.ProductType.INAPP);
    }

    public Single<List<Purchase>> getSubscriptions() {
        return getOwnedItems(BillingClient.ProductType.SUBS);
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

    public Completable launchOneTimePurchaseFlow(
            Activity activity,
            ProductDetails productDetails) {
        // validate product type
        if (productDetails == null || !productDetails.getProductType().equals(BillingClient.ProductType.INAPP)) {
            return Completable.error(new IllegalArgumentException("productDetails is null or not a one time product"));
        }
        return launchPurchaseFlow(activity, productDetails, null, null);
    }

    public Completable launchNewSubscriptionPurchaseFlow(Activity activity, ProductDetails productDetails, ProductDetails.SubscriptionOfferDetails offerDetails) {
        // validate product type
        if (productDetails == null || !productDetails.getProductType().equals(BillingClient.ProductType.SUBS)) {
            return Completable.error(new IllegalArgumentException("productDetails is null or not a subscription product"));
        }
        return launchPurchaseFlow(activity, productDetails, offerDetails, null);
    }

    public Completable launchReplacementSubscriptionPurchaseFlow(
            Activity activity,
            ProductDetails productDetails,
            ProductDetails.SubscriptionOfferDetails offerDetails,
            String oldPurchaseToken) {
        // validate product type
        if (productDetails == null || !productDetails.getProductType().equals(BillingClient.ProductType.SUBS)) {
            return Completable.error(new IllegalArgumentException("productDetails is null or not a subscription product"));
        }
        // validate old purchase token
        if (TextUtils.isEmpty(oldPurchaseToken)) {
            return Completable.error(new IllegalArgumentException("oldPurchaseToken is null or empty"));
        }
        return launchPurchaseFlow(activity, productDetails, offerDetails, oldPurchaseToken);
    }

    private Completable launchPurchaseFlow(
            @NonNull Activity activity,
            @NonNull ProductDetails productDetails,
            @Nullable ProductDetails.SubscriptionOfferDetails offerDetails,
            @Nullable String oldPurchaseToken) {
        boolean isSubscription = productDetails.getProductType().equals(BillingClient.ProductType.SUBS);

        // validate product type and offer combination
        if (!isSubscription && offerDetails != null) {
            return Completable.error(new IllegalArgumentException("One time product cannot have offer details"));
        }
        // Build the FlowParams
        BillingFlowParams.Builder flowParamsBuilder = BillingFlowParams.newBuilder();

        // Build the ProductDetailsParams
        BillingFlowParams.ProductDetailsParams.Builder productDetailsParamsBuilder =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails);

        // Add offer token for subscriptions that have an offer
        if (isSubscription && offerDetails != null) {
            productDetailsParamsBuilder.setOfferToken(offerDetails.getOfferToken());
        }

        // Set the product details params list on the flow params builder
        flowParamsBuilder.setProductDetailsParamsList(Collections.singletonList(productDetailsParamsBuilder.build()));

        // Add subscription to replace if upgrading/downgrading
        if (isSubscription && !TextUtils.isEmpty(oldPurchaseToken)) {
            flowParamsBuilder.setSubscriptionUpdateParams(
                    BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                            .setOldPurchaseToken(oldPurchaseToken)
                            // Note that the replacement mode is set to WITH_TIME_PRORATION which is the default value.
                            // https://developer.android.com/reference/com/android/billingclient/api/BillingFlowParams.SubscriptionUpdateParams.ReplacementMode#WITH_TIME_PRORATION()
                            .setSubscriptionReplacementMode(BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.WITH_TIME_PRORATION)
                            .build());
        }

        // Wrap the main logic with BillingUtils.withRetry
        return BillingUtils.withRetry(
                        connectionCompletable.andThen(Completable.create(emitter -> {
                            // Retrieve the BillingClient instance
                            BillingClient client = billingClientManager.getBillingClient();

                            // Perform the launch billing flow
                            BillingResult billingResult = client.launchBillingFlow(activity, flowParamsBuilder.build());

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
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::launchPurchaseFlow error: " + err));
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
        // Note that acknowledge operation is important since failing to acknowledge a purchase will result in
        // that purchase being refunded. Therefore, we will retry it up to 5 times.
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
                        5 // Retry up to 5 times
                )
                .ignoreElements() // Convert back to Completable
                .doOnComplete(() -> MyLog.i("GooglePlayBillingHelper::acknowledgePurchase success for SKU: " + purchase.getProducts().get(0)))
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
                                    // It is possible that the purchase was already consumed by another purchase update flow,
                                    // started by purchase verifier, for example, which will result in ITEM_NOT_OWNED response code.
                                    // Do not treat this as an error, just complete the operation.
                                    if (billingResult.getResponseCode() == BillingResponseCode.OK
                                            || billingResult.getResponseCode() == BillingResponseCode.ITEM_NOT_OWNED) {
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
                .doOnSuccess(__ -> {
                    MyLog.i("GooglePlayBillingHelper::consumePurchase success for SKU: " + purchase.getProducts().get(0));
                    // Query all purchases to update the state of purchases after successful consumption.
                    // This is necessary because the PurchaseUpdateListener is not triggered after a successful consumption, so we need to manually update the state.
                    queryAllPurchases();
                })
                .doOnError(err -> MyLog.e("GooglePlayBillingHelper::consumePurchase error: " + err));
    }

    static boolean isUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getProducts().get(0));
    }

    static boolean isLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getProducts().get(0).equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    static boolean isValidTimePass(@NonNull Purchase purchase) {
        Long lifetimeInDays = IAB_TIMEPASS_SKUS_TO_DAYS.get(purchase.getProducts().get(0));
        if (lifetimeInDays == null) {
            // not a time pass SKU
            return false;
        }
        // calculate expiry date based on the lifetime and purchase date
        long lifetimeMillis = lifetimeInDays * 24 * 60 * 60 * 1000;
        long timepassExpiryMillis = purchase.getPurchaseTime() + lifetimeMillis;

        return System.currentTimeMillis() < timepassExpiryMillis;
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