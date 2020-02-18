package com.psiphon3.billing;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
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
    private GooglePlayBillingHelper repository;

    private PublishRelay<TunnelState> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private ArrayList<String> invalidPurchaseTokensList = new ArrayList<>();

    public PurchaseVerifier(@NonNull Context context, @NonNull VerificationResultListener verificationResultListener) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        this.repository = GooglePlayBillingHelper.getInstance(context);
        this.verificationResultListener = verificationResultListener;

        compositeDisposable.add(subscriptionVerificationDisposable());
        compositeDisposable.add(psiCashPurchaseVerificationDisposable());
        queryAllPurchases();
    }

    private Flowable<TunnelState> tunnelConnectionStateFlowable() {
        return tunnelConnectionStatePublishRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Disposable psiCashPurchaseVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    if (tunnelState.isRunning() && tunnelState.connectionData().isConnected()) {
                        return repository.purchaseStateFlowable()
                                .flatMapIterable(PurchaseState::purchaseList)
                                .map(purchase -> new Pair<>(purchase, tunnelState.connectionData()));
                    }
                    // Not connected, do nothing
                    return Flowable.empty();
                    // Once connected run IAB check and pass PsiCash purchase and
                    // current tunnel state connection data downstream.
                })
                .switchMap(pair -> {
                    final Purchase purchase = pair.first;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (purchase == null || !GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                        return Flowable.empty();
                    }

                    // Check if we previously marked this purchase as 'bad'
                    if (invalidPurchaseTokensList.size() > 0 &&
                            invalidPurchaseTokensList.contains(purchase.getPurchaseToken())) {
                        Utils.MyLog.g("PurchaseVerifier: bad PsiCash purchase, continue.");
                        return Flowable.empty();
                    }

                    final AppPreferences mp = new AppPreferences(context);
                    String psiCashCustomData = mp.getString(context.getString(R.string.persistentPsiCashCustomData), "");
                    if ( TextUtils.isEmpty(psiCashCustomData)) {
                        Utils.MyLog.g("PurchaseVerifier: error: can't redeem PsiCash purchase, custom data is empty");
                        return Flowable.empty();
                    }

                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .withCustomData(psiCashCustomData)
                                    .build();

                    Utils.MyLog.g("PurchaseVerifier: will try and redeem PsiCash purchase.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .flatMap(json -> {
                                // Purchase redeemed, consume and send PSICASH_PURCHASE_REDEEMED
                                return repository.consumePurchase(purchase)
                                        .map(__ -> VerificationResult.PSICASH_PURCHASE_REDEEMED)
                                        .toFlowable();
                                    }
                            )
                            .doOnError(e -> {
                                Utils.MyLog.g("PurchaseVerifier: verifying PsiCash purchase failed with error: " + e);
                            })
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
                    return repository.subscriptionStateFlowable()
                            .map(subscriptionState -> new Pair<>(subscriptionState, tunnelState.connectionData()));
                })
                .switchMap(pair -> {
                    final SubscriptionState subscriptionState = pair.first;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (subscriptionState.error() != null) {
                        Utils.MyLog.g("PurchaseVerifier: continue due to subscription check error: " + subscriptionState.error());
                        return Flowable.empty();
                    }

                    if (!subscriptionState.hasValidPurchase()) {
                        if (BuildConfig.SUBSCRIPTION_SPONSOR_ID.equals(connectionData.sponsorId())) {
                            Utils.MyLog.g("PurchaseVerifier: user has no subscription, will restart as non subscriber.");
                            return Flowable.just(VerificationResult.RESTART_AS_NON_SUBSCRIBER);
                        } else {
                            Utils.MyLog.g("PurchaseVerifier: user has no subscription, continue.");
                            return Flowable.empty();
                        }
                    }

                    final Purchase purchase = subscriptionState.purchase();

                    // Check if we previously marked this purchase as 'bad'
                    if (invalidPurchaseTokensList.size() > 0 &&
                            invalidPurchaseTokensList.contains(purchase.getPurchaseToken())) {
                        Utils.MyLog.g("PurchaseVerifier: bad subscription purchase, continue.");
                        return Flowable.empty();
                    }

                    // Otherwise check if we already have an authorization for this token
                    String persistedPurchaseToken = appPreferences.getString(PREFERENCE_PURCHASE_TOKEN, "");
                    String persistedPurchaseAuthorizationId = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    if (persistedPurchaseToken.equals(purchase.getPurchaseToken()) &&
                            !persistedPurchaseAuthorizationId.isEmpty()) {
                        Utils.MyLog.g("PurchaseVerifier: already have authorization for this purchase, continue.");
                        // We already aware of this purchase, do nothing
                        return Flowable.empty();
                    }

                    // We have a fresh purchase. Store the purchase token and reset the persisted authorization Id
                    Utils.MyLog.g("PurchaseVerifier: user has new valid subscription purchase.");
                    appPreferences.put(PREFERENCE_PURCHASE_TOKEN, purchase.getPurchaseToken());
                    appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    // Now try and fetch authorization for this purchase
                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .build();

                    Utils.MyLog.g("PurchaseVerifier: will try and fetch new authorization.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .map(json -> {
                                        // Note that response with other than 200 HTTP code from the server is
                                        // treated the same as a 200 OK response with empty payload and should result
                                        // in connection restart as a non-subscriber.

                                        if (TextUtils.isEmpty(json)) {
                                            // If payload is empty then do not try to JSON decode,
                                            // remember the bad token and restart as non-subscriber.
                                            // TODO: inspect HTTP response for purchases actually reported as bad
                                            invalidPurchaseTokensList.add(purchase.getPurchaseToken());
                                            Utils.MyLog.g("PurchaseVerifier: subscription verification: server returned empty payload.");
                                            return VerificationResult.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                        Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                        if (authorization == null) {
                                            // Expired or invalid purchase,
                                            // remember the bad token and restart as non-subscriber.
                                            invalidPurchaseTokensList.add(purchase.getPurchaseToken());
                                            Utils.MyLog.g("PurchaseVerifier: subscription verification: server returned empty authorization.");
                                            return VerificationResult.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        // Persist authorization ID and authorization.
                                        appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, authorization.Id());

                                        // Prior to storing authorization remove all other authorizations of this type
                                        // from storage. Psiphon server will only accept one authorization per access type.
                                        // If there are multiple active authorizations of 'google-subscription' type it is
                                        // not guaranteed the server will select the one associated with current purchase which
                                        // may result in client connect-as-subscriber -> server-reject infinite re-connect loop.
                                        List<Authorization> authorizationsToRemove = new ArrayList<>();
                                        for (Authorization a : Authorization.geAllPersistedAuthorizations(context)) {
                                            if (a.accessType().equals(authorization.accessType())) {
                                                authorizationsToRemove.add(a);
                                            }
                                        }
                                        Authorization.removeAuthorizations(context, authorizationsToRemove);
                                        Authorization.storeAuthorization(context, authorization);
                                        Utils.MyLog.g("PurchaseVerifier: subscription verification: server returned new authorization.");
                                        return VerificationResult.RESTART_AS_SUBSCRIBER;
                                    }
                            )
                            .doOnError(e -> {
                                Utils.MyLog.g("PurchaseVerifier: subscription verification: fetching authorization failed with error: " + e);
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

    public Single<String> sponsorIdSingle() {
        return repository.subscriptionStateFlowable()
                .firstOrError()
                .map(subscriptionState -> {
                            if (subscriptionState.hasValidPurchase()) {
                                // Check if we previously marked the purchase as 'bad'
                                String purchaseToken = subscriptionState.purchase().getPurchaseToken();
                                if (invalidPurchaseTokensList.size() > 0 &&
                                        invalidPurchaseTokensList.contains(purchaseToken)) {
                                    Utils.MyLog.g("PurchaseVerifier: will start with non-subscription sponsor ID due to invalid purchase");
                                    return EmbeddedValues.SPONSOR_ID;
                                }
                                Utils.MyLog.g("PurchaseVerifier: will start with subscription sponsor ID");
                                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
                            }
                            Utils.MyLog.g("PurchaseVerifier: will start with non-subscription sponsor ID");
                            return EmbeddedValues.SPONSOR_ID;
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
            return;
        }

        // If server hasn't accepted any authorizations or persisted authorization ID hasn't been
        // accepted then reset persisted purchase token and trigger new IAB check
        if (acceptedAuthorizationIds.isEmpty() || !acceptedAuthorizationIds.contains(purchaseAuthorizationID)) {
            Utils.MyLog.g("PurchaseVerifier: persisted purchase authorization ID is not active, will query subscription status.");
            appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
            appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
            repository.queryAllPurchases();
        } else {
            Utils.MyLog.g("PurchaseVerifier: subscription authorization accepted, continue.");
        }
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }

    public void queryAllPurchases() {
        repository.startIab();
        repository.queryAllPurchases();
    }

    public enum VerificationResult {
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER,
        PSICASH_PURCHASE_REDEEMED,
    }

    public interface VerificationResultListener {
        void onVerificationResult(VerificationResult action);
    }
}
