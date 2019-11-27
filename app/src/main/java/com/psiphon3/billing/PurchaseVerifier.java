package com.psiphon3.billing;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseVerifier {
    private static final String PREFERENCE_PURCHASE_AUTHORIZATION_ID = "preferencePurchaseAuthorization";
    private static final String PREFERENCE_PURCHASE_TOKEN = "preferencePurchaseToken";

    private final AppPreferences appPreferences;
    private final Context context;
    private final PurchaseAuthorizationListener purchaseAuthorizationListener;
    private BillingRepository repository;

    private PublishRelay<Pair<Boolean, Integer>> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private BehaviorRelay<SubscriptionState> subscriptionStateBehaviorRelay = BehaviorRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    public PurchaseVerifier(Context context, PurchaseAuthorizationListener purchaseAuthorizationListener) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        this.repository = BillingRepository.getInstance(context);
        this.purchaseAuthorizationListener = purchaseAuthorizationListener;
    }

    public void startIab() {
        compositeDisposable.addAll(
                purchaseUpdatesDisposable(),
                purchaseVerificationDisposable()
        );
        queryCurrentSubscriptionStatus();
    }

    private Disposable purchaseUpdatesDisposable() {
        return repository.observeUpdates()
                .subscribe(
                        purchasesUpdate -> {
                            if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.OK) {
                                processPurchases(purchasesUpdate.purchases());
                            } else if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                                queryCurrentSubscriptionStatus();
                            } else {
                                Utils.MyLog.g("PurchaseVerifier::observeUpdates purchase update error response code: " + purchasesUpdate.responseCode());
                            }
                        },
                        err -> {
                            subscriptionStateBehaviorRelay.accept(SubscriptionState.billingError(err));
                        }
                );
    }

    private void processPurchases(List<Purchase> purchaseList) {
        if (purchaseList == null || purchaseList.size() == 0) {
            subscriptionStateBehaviorRelay.accept(SubscriptionState.noSubscription());
            return;
        }

        for (Purchase purchase : purchaseList) {
            // Skip purchase with pending or unspecified state.
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            // Skip purchases that don't pass signature verification.
            if (!Security.verifyPurchase(BillingRepository.IAB_PUBLIC_KEY,
                    purchase.getOriginalJson(), purchase.getSignature())) {
                Utils.MyLog.g("PurchaseVerifier::processPurchases: failed verification for purchase: " + purchase);
                continue;
            }

            if (BillingRepository.hasUnlimitedSubscription(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.unlimitedSubscription(purchase));
                return;
            } else if (BillingRepository.hasLimitedSubscription(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.limitedSubscription(purchase));
                return;
            } else if (BillingRepository.hasTimePass(purchase)) {
                subscriptionStateBehaviorRelay.accept(SubscriptionState.timePass(purchase));
                return;
            }
        }
        subscriptionStateBehaviorRelay.accept(SubscriptionState.noSubscription());
    }

    private void queryCurrentSubscriptionStatus() {
        compositeDisposable.add(
                Single.mergeDelayError(repository.getSubscriptions(), repository.getPurchases())
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
                                err -> subscriptionStateBehaviorRelay.accept(SubscriptionState.billingError(err))
                        )
        );
    }

    private Flowable<SubscriptionState> subscriptionStateFlowable() {
        return subscriptionStateBehaviorRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Flowable<Pair<Boolean, Integer>> tunnelConnectionStateFlowable() {
        return tunnelConnectionStatePublishRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Disposable purchaseVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(pair -> {
                    boolean isConnected = pair.first;
                    final int httpProxyPort = pair.second;
                    if (!isConnected) {
                        // Not connected, do nothing
                        return Flowable.empty();
                    }
                    // Once connected run IAB check and pass the subscription state and
                    // current http proxy port downstream.
                    return subscriptionStateFlowable()
                            .map(subscriptionState -> new Pair<>(subscriptionState, httpProxyPort));
                })
                .switchMap(pair -> {
                    SubscriptionState subscriptionState = pair.first;
                    final int httpProxyPort = pair.second;
                    if (!subscriptionState.hasValidPurchase()) {
                        // No subscription, do nothing
                        return Flowable.empty();
                    }
                    // Otherwise check if we have already tried to fetch an authorization for this token
                    String persistedPurchaseToken = appPreferences.getString(PREFERENCE_PURCHASE_TOKEN, "");
                    if (persistedPurchaseToken.equals(subscriptionState.purchase().getPurchaseToken())) {
                        // We already aware of this purchase, do nothing
                        return Flowable.empty();
                    }
                    // We have a fresh purchase. Store the purchase token and reset the persisted authorization Id
                    Purchase purchase = subscriptionState.purchase();
                    appPreferences.put(PREFERENCE_PURCHASE_TOKEN, purchase.getPurchaseToken());
                    appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    // Now try and fetch authorization for this purchase
                    boolean isSubscription = BillingRepository.hasUnlimitedSubscription(purchase)
                            || BillingRepository.hasLimitedSubscription(purchase);

                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withProductId(purchase.getSku())
                                    .withIsSubscription(isSubscription)
                                    .withPurchaseToken(purchase.getPurchaseToken())
                                    .withHttpProxyPort(httpProxyPort)
                                    .build();

                    return purchaseVerificationNetworkHelper.fetchAuthorizationFlowable()
                            .map(json -> {
                                        String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                        Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                        if (authorization == null) {
                                            // Expired or invalid purchase, do nothing.
                                            // No action will be taken next time we receive the same token
                                            // because we persisted this token already.
                                            return UpdateConnectionAction.RESTART_AS_NON_SUBSCRIBER;
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
                                        return UpdateConnectionAction.RESTART_AS_SUBSCRIBER;
                                    }
                            )
                            .doOnError(e -> {
                                // On fetch error reset persisted purchase token so it can be retried next time
                                appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
                                Utils.MyLog.g("PurchaseVerificationNetworkHelper: fetching authorization failed with error: " + e);
                            })
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(purchaseAuthorizationListener::updateConnection)
                .subscribe();
    }

    public Single<String> sponsorIdSingle() {
        return subscriptionStateFlowable()
                .firstOrError()
                .map(subscriptionState ->
                        subscriptionState.hasValidPurchase() ?
                                BuildConfig.SUBSCRIPTION_SPONSOR_ID :
                                EmbeddedValues.SPONSOR_ID
                );
    }

    public void onTunnelConnected(Pair<Boolean, Integer> pair) {
        tunnelConnectionStatePublishRelay.accept(pair);
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
            appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
            queryCurrentSubscriptionStatus();
        }
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }

    public enum UpdateConnectionAction {
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER
    }

    public interface PurchaseAuthorizationListener {
        void updateConnection(UpdateConnectionAction action);
    }
}
