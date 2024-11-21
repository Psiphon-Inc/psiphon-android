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
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.TunnelConfigManager;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseVerifier {
    private static final String PREFERENCE_PURCHASE_AUTHORIZATION_ID = "preferencePurchaseAuthorization";
    private static final String PREFERENCE_PURCHASE_TOKEN = "preferencePurchaseToken";

    private final AppPreferences appPreferences;
    private final Context context;
    private final VerificationResultListener verificationResultListener;
    private final GooglePlayBillingHelper googlePlayBillingHelper;

    private final PublishRelay<TunnelState> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final Set<String> invalidPurchaseTokensSet = new HashSet<>();

    public PurchaseVerifier(@NonNull Context context, @NonNull VerificationResultListener verificationResultListener) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        this.googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(context);
        this.verificationResultListener = verificationResultListener;
    }

    private Flowable<TunnelState> tunnelConnectionStateFlowable() {
        return tunnelConnectionStatePublishRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Disposable psiCashPurchaseVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    // Once connected run IAB check and pass PsiCash purchase and
                    // current tunnel state connection data downstream.
                    if (tunnelState.isRunning() && tunnelState.connectionData().isConnected()) {
                        return googlePlayBillingHelper.purchaseStateFlowable()
                                .flatMapIterable(PurchaseState::purchaseList)
                                // Only pass through PsiCash purchases that we didn't previously
                                // marked as invalid
                                .filter(purchase -> {
                                    if (purchase == null || !GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                                        return false;
                                    }
                                    // Check if we previously marked this purchase as 'bad'
                                    if (invalidPurchaseTokensSet.size() > 0 &&
                                            invalidPurchaseTokensSet.contains(purchase.getPurchaseToken())) {
                                        MyLog.w("PurchaseVerifier: bad PsiCash purchase, continue.");
                                        return false;
                                    }
                                    return true;
                                })
                                .map(purchase -> {
                                    final AppPreferences mp = new AppPreferences(context);
                                    final String psiCashCustomData = mp.getString(context.getString(R.string.persistentPsiCashCustomData), "");
                                    return new Pair<>(purchase, psiCashCustomData);
                                })

                                // We want to avoid trying to redeem the same purchase multiple times
                                // so we consider purchases distinct only if their purchase tokens and
                                // order IDs differ and are ignoring all other fields such as isAcknowledged
                                // which may change anytime for a purchase we have seen already.
                                //
                                // See comments in GooglePlayBillingHelper::processPurchases for more
                                // details on the purchase acknowledgement.
                                //
                                // UPDATE: we also want to (re)try purchase verification in case the PsiCash
                                // custom data has changed due to user login status change.
                                .distinctUntilChanged((a, b) -> {
                                    final Purchase purchaseA = a.first;
                                    final Purchase purchaseB = b.first;
                                    final String customDataA = a.second;
                                    final String customDataB = b.second;

                                    return purchaseA.getPurchaseToken().equals(purchaseB.getPurchaseToken()) &&
                                            purchaseA.getOrderId().equals(purchaseB.getOrderId()) &&
                                            customDataA.equals(customDataB);
                                })
                                .map(pair -> new Pair<>(pair, tunnelState.connectionData()));
                    }
                    // Not connected, do nothing
                    return Flowable.empty();
                })
                // Do not use switchMap here, run the verification in full for each distinct purchase
                .flatMap(pair -> {
                    final Purchase purchase = pair.first.first;
                    final String psiCashCustomData = pair.first.second;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (TextUtils.isEmpty(psiCashCustomData)) {
                        MyLog.w("PurchaseVerifier: error: can't redeem PsiCash purchase, custom data is empty");
                        return Flowable.empty();
                    }

                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .withCustomData(psiCashCustomData)
                                    .build();

                    MyLog.i("PurchaseVerifier: will try and redeem PsiCash purchase.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .flatMap(json -> {
                                // Purchase redeemed, consume and send PSICASH_PURCHASE_REDEEMED
                                return googlePlayBillingHelper.consumePurchase(purchase)
                                        .map(__ -> VerificationResult.PSICASH_PURCHASE_REDEEMED)
                                        .toFlowable();
                            })
                            .doOnError(e -> MyLog.e("PurchaseVerifier: verifying PsiCash purchase failed with error: " + e))
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(verificationResultListener::onVerificationResult)
                .subscribe();
    }

    private Disposable subscriptionVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    if (!(tunnelState.isRunning() && tunnelState.connectionData().isConnected())) {
                        // Not connected, do nothing
                        return Flowable.empty();
                    }
                    // Once connected run IAB check and pass the subscription state and
                    // current tunnel state connection data downstream.
                    return googlePlayBillingHelper.subscriptionStateFlowable()
                            .map(subscriptionState -> new Pair<>(subscriptionState, tunnelState.connectionData()));
                })
                .switchMap(pair -> {
                    final SubscriptionState subscriptionState = pair.first;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (subscriptionState.error() != null) {
                        MyLog.w("PurchaseVerifier: continue due to subscription check error: " + subscriptionState.error());
                        return Flowable.empty();
                    }

                    if (!subscriptionState.hasValidPurchase()) {
                        // If for some reason the tunnel config is set to a subscription sponsor ID
                        // return NO_SUBSCRIPTION to update the tunnel config sponsorship state.
                        if (BuildConfig.SUBSCRIPTION_SPONSOR_ID.equals(connectionData.sponsorId())) {
                            MyLog.i("PurchaseVerifier: subscription check: user has no subscription, returning NO_SUBSCRIPTION.");
                            return Flowable.just(VerificationResult.NO_SUBSCRIPTION);
                        } else {
                            MyLog.i("PurchaseVerifier: user has no subscription, continue.");
                            return Flowable.empty();
                        }
                    }

                    final Purchase purchase = subscriptionState.purchase();

                    // Check if we previously marked this purchase as 'bad'
                    if (invalidPurchaseTokensSet.size() > 0 &&
                            invalidPurchaseTokensSet.contains(purchase.getPurchaseToken())) {
                        MyLog.w("PurchaseVerifier: bad subscription purchase, continue.");
                        return Flowable.empty();
                    }

                    // Otherwise check if we already have an authorization for this token
                    String persistedPurchaseToken = appPreferences.getString(PREFERENCE_PURCHASE_TOKEN, "");
                    String persistedPurchaseAuthorizationId = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    if (persistedPurchaseToken.equals(purchase.getPurchaseToken()) &&
                            !persistedPurchaseAuthorizationId.isEmpty()) {
                        MyLog.i("PurchaseVerifier: already have authorization for this purchase, continue.");
                        // We already aware of this purchase, do nothing
                        return Flowable.empty();
                    }

                    // We have a fresh purchase. Store the purchase token and reset the persisted authorization Id
                    MyLog.i("PurchaseVerifier: user has new valid subscription purchase.");
                    appPreferences.put(PREFERENCE_PURCHASE_TOKEN, purchase.getPurchaseToken());
                    appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    // Now try and fetch authorization for this purchase
                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .build();

                    MyLog.i("PurchaseVerifier: will try and fetch new authorization.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .map(json -> {
                                        // Note that response with other than 200 HTTP code from the server is
                                        // treated the same as a 200 OK response with empty payload and should result
                                        // in connection restart as a non-subscriber.

                                        if (TextUtils.isEmpty(json)) {
                                            // If payload is empty then do not try to JSON decode,
                                            // remember the bad token and restart as non-subscriber.
                                            handleServerVerificationFailure(purchase.getPurchaseToken(), "empty payload");
                                            return VerificationResult.NO_SUBSCRIPTION;
                                        }

                                        String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                        Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                        if (authorization == null) {
                                            // Expired or invalid purchase,
                                            // remember the bad token and restart as non-subscriber.
                                            handleServerVerificationFailure(purchase.getPurchaseToken(), "invalid or expired authorization");
                                            return VerificationResult.NO_SUBSCRIPTION;
                                        }

                                        // Persist authorization ID and authorization.
                                        appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, authorization.Id());
                                        Authorization.storeAuthorization(context, authorization);

                                        MyLog.i("PurchaseVerifier: subscription verification: server returned new authorization: " + authorization.accessType());

                                        if (authorization.accessType().equals(Authorization.ACCESS_TYPE_GOOGLE_SUBSCRIPTION_LIMITED)) {
                                            return VerificationResult.LIMITED_SUBSCRIPTION;
                                        } else if (authorization.accessType().equals(Authorization.ACCESS_TYPE_GOOGLE_SUBSCRIPTION)) {
                                            return VerificationResult.UNLIMITED_SUBSCRIPTION;
                                        }
                                        // Unknown authorization type, fall back to no-subscription but do not mark the purchase as bad
                                        // as we may have a legit purchase and while we can't upgrade the connection with new sponsorship we
                                        // do not want to verify the purchase again nor we want to mark it as bad.
                                        MyLog.w("PurchaseVerifier: subscription verification: unknown authorization type, treating as no subscription.");
                                        return VerificationResult.NO_SUBSCRIPTION;
                                    }
                            )
                            .doOnError(e -> {
                                if (e instanceof PurchaseVerificationNetworkHelper.FatalException) {
                                    handleServerVerificationFailure(purchase.getPurchaseToken(), e.getMessage());
                                }
                                MyLog.e("PurchaseVerifier: subscription verification: fetching authorization failed with error: " + e);
                            })
                            // If we fail HTTP request after all retries for whatever reason do not
                            // restart connection as a non-subscriber. The user may have a legit purchase
                            // and while we can't upgrade the connection we should try and not show home
                            // pages at least.
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(verificationResultListener::onVerificationResult)
                .subscribe();
    }

    private void handleServerVerificationFailure(String purchaseToken, String reason) {
        MyLog.w("PurchaseVerifier: marking purchase token as bad - " + reason);
        invalidPurchaseTokensSet.add(purchaseToken);
    }

    public Single<TunnelConfigManager.SubscriptionState> subscriptionStateSingle() {
        return googlePlayBillingHelper.subscriptionStateFlowable()
                .firstOrError()
                .map(subscriptionState -> {
                            if (subscriptionState.hasValidPurchase()) {
                                // Check if we previously marked the purchase as 'bad'
                                String purchaseToken = subscriptionState.purchase().getPurchaseToken();
                                if (invalidPurchaseTokensSet.size() > 0 &&
                                        invalidPurchaseTokensSet.contains(purchaseToken)) {
                                    MyLog.i("PurchaseVerifier::subscriptionStateSingle: subscription purchase marked as bad, user has no subscription.");
                                    return TunnelConfigManager.SubscriptionState.NONE;
                                }
                                if (subscriptionState.status() == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION) {
                                    MyLog.i("PurchaseVerifier::subscriptionStateSingle: user has limited subscription.");
                                    return TunnelConfigManager.SubscriptionState.LIMITED;
                                }
                                // Unlimited or time pass
                                MyLog.i("PurchaseVerifier::subscriptionStateSingle: user has unlimited subscription or time pass.");
                                return TunnelConfigManager.SubscriptionState.UNLIMITED;
                            }
                            MyLog.i("PurchaseVerifier::subscriptionStateSingle: user has no subscription.");
                            return TunnelConfigManager.SubscriptionState.NONE;
                        }
                );
    }

    public void onTunnelState(TunnelState tunnelState) {
        tunnelConnectionStatePublishRelay.accept(tunnelState);
    }

    public void onActiveAuthorizationIDs(List<String> acceptedAuthorizationIds) {
        String purchaseAuthorizationID = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

        if (TextUtils.isEmpty(purchaseAuthorizationID)) {
            // There is no persisted authorization, do nothing
            MyLog.i("PurchaseVerifier::onActiveAuthorizationIDs: no persisted purchase authorization ID, continue.");
            return;
        }

        // If server hasn't accepted any authorizations or persisted authorization ID hasn't been
        // accepted then reset persisted purchase token and trigger new IAB check
        if (acceptedAuthorizationIds.isEmpty() || !acceptedAuthorizationIds.contains(purchaseAuthorizationID)) {
            MyLog.i("PurchaseVerifier::onActiveAuthorizationIDs: persisted purchase authorization ID is not active, will query subscription status.");
            appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
            appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
            googlePlayBillingHelper.queryAllPurchases();
        } else {
            MyLog.i("PurchaseVerifier::onActiveAuthorizationIDs: persisted purchase authorization ID is active, continue.");
        }
    }

    public void start() {
        // Start observing purchases and tunnel state and trigger verification
        compositeDisposable.add(subscriptionVerificationDisposable());
        compositeDisposable.add(psiCashPurchaseVerificationDisposable());

        // Perform initial query to establish the baseline state of purchases
        googlePlayBillingHelper.queryAllPurchases();
        // Begin observing live purchase updates
        googlePlayBillingHelper.startObservePurchasesUpdates();
    }

    public void stop() {
        compositeDisposable.clear();
        googlePlayBillingHelper.stopObservePurchasesUpdates();
    }

    public Observable<Boolean> hasPendingPsiCashPurchaseObservable() {
        return googlePlayBillingHelper.purchaseStateFlowable()
                .map(PurchaseState::purchaseList)
                .distinctUntilChanged()
                .toObservable()
                .switchMap(purchaseList -> {
                    for (Purchase purchase : purchaseList) {
                        if (GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                            return Observable.just(Boolean.TRUE);
                        }
                    }
                    return Observable.just(Boolean.FALSE);
                });
    }

    public enum VerificationResult {
        NO_SUBSCRIPTION,
        LIMITED_SUBSCRIPTION,
        UNLIMITED_SUBSCRIPTION,
        PSICASH_PURCHASE_REDEEMED,
    }

    public interface VerificationResultListener {
        void onVerificationResult(VerificationResult action);
    }
}
