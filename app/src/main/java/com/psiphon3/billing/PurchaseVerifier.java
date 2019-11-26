package com.psiphon3.billing;

import android.util.Pair;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class PurchaseVerifier {
    private PublishRelay<Pair<Boolean, Integer>> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private BehaviorRelay<SubscriptionState> subscriptionStateBehaviorRelay = BehaviorRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private BillingRepository repository;

    public PurchaseVerifier(BillingRepository repository) {
        this.repository = repository;
    }

    public void startIab() {
        compositeDisposable.add(
                repository.observeUpdates()
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
                        )
        );

        queryCurrentSubscriptionStatus();
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

    public void queryCurrentSubscriptionStatus() {
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

    private Flowable<SubscriptionState> subscriptionStatusFlowable() {
        return subscriptionStateBehaviorRelay
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public Single<String> sponsorIdSingle() {
        return subscriptionStatusFlowable()
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

    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }

    public enum UpdateConnectionAction {
        NO_ACTION,
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER
    }

    public interface PurchaseAuthorizationListener {
        void updateConnection(UpdateConnectionAction action);
    }
}
