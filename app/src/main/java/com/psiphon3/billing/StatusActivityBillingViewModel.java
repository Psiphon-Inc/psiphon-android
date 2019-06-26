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
import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.Arrays;
import java.util.List;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class StatusActivityBillingViewModel extends AndroidViewModel {
    private BillingRepository repository;
    private CompositeDisposable compositeDisposable;
    private PublishRelay<SubscriptionState> subscriptionStatusRelay;

    public StatusActivityBillingViewModel(@NonNull Application application) {
        super(application);
        repository = BillingRepository.getInstance(application);
        compositeDisposable = new CompositeDisposable();
        subscriptionStatusRelay = PublishRelay.create();
    }

    public Flowable<SubscriptionState> subscriptionStatusFlowable() {
        return subscriptionStatusRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    public void startIab() {
        compositeDisposable.add(
                repository.observeUpdates()
                        .subscribe(
                                purchasesUpdate -> {
                                    Log.d("HACK", "startIab: purchaseUpdate: " + purchasesUpdate);
                                    if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.OK) {
                                        processPurchases(purchasesUpdate.purchases());
                                    } else if (purchasesUpdate.responseCode() == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                                        queryCurrentSubscriptionStatus();
                                    } else {
                                        Utils.MyLog.g("BillingRepository::observeUpdates purchase update error response code: " + purchasesUpdate.responseCode());
                                    }
                                },
                                throwable -> {
                                    subscriptionStatusRelay.accept(SubscriptionState.billingError(throwable));
                                    Log.d("HACK", "startIab: purchaseUpdate error: " + throwable);
                                    Utils.MyLog.g("BillingRepository::observeUpdates error: " + throwable);
                                }
                        )
        );
    }

    public void stopIab() {
        compositeDisposable.dispose();
    }

    public void queryCurrentSubscriptionStatus() {
        compositeDisposable.add(
                Single.mergeDelayError(repository.getSubscriptions(), repository.getPurchases())
                        .flatMapIterable(purchases -> purchases)
                        .toList()
                        .subscribe(
                                purchaseList -> processPurchases(purchaseList),
                                throwable -> subscriptionStatusRelay.accept(SubscriptionState.billingError(throwable))
                        )
        );
    }

    private void processPurchases(List<Purchase> purchaseList) {
        if (purchaseList == null) {
            subscriptionStatusRelay.accept(SubscriptionState.noSubscription());
            return;
        }

        for (Purchase purchase : purchaseList) {
            // skip pending purchases.
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED) {
                continue;
            }

            // skip invalid purchases
            if(!Security.verifyPurchase(BillingRepository.IAB_PUBLIC_KEY,
                    purchase.getOriginalJson(), purchase.getSignature())) {
                Utils.MyLog.g("StatusActivityBillingViewModel::processPurchases: failed verification for purchase: " + purchase);
                continue;
            }

            // From Google sample app:
            // If you do not acknowledge a purchase, the Google Play Store will provide a refund to the
            // users within a few days of the transaction. Therefore you have to implement
            // [BillingClient.acknowledgePurchaseAsync] inside your app.
            compositeDisposable.add(repository.acknowledgePurchase(purchase).subscribe());

            if (hasUnlimitedSubscription(purchase)) {
                subscriptionStatusRelay.accept(SubscriptionState.unlimitedSubscription());
                return;
            } else if (hasLimitedSubscription(purchase)) {
                subscriptionStatusRelay.accept(SubscriptionState.limitedSubscription());
                return;
            } else if (hasTimePass(purchase)) {
                subscriptionStatusRelay.accept(SubscriptionState.timePass());
                return;
            }
        }

        subscriptionStatusRelay.accept(SubscriptionState.noSubscription());
    }

    private boolean hasUnlimitedSubscription(@NonNull Purchase purchase) {
        return Arrays.asList(BillingRepository.IAB_ALL_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS).contains(purchase.getSku());
    }

    private boolean hasLimitedSubscription(@NonNull Purchase purchase) {
        return purchase.getSku().equals(BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
    }

    private boolean hasTimePass(@NonNull Purchase purchase) {
        String purchaseSku = purchase.getSku();
        Long lifetimeInDays = BillingRepository.IAB_TIMEPASS_SKUS_TO_DAYS.get(purchaseSku);
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
        // Otherwise consume the purchase and return false.
        compositeDisposable.add(repository.consumePurchase(purchase).subscribe());

        return false;
    }

    public Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        return repository.launchFlow(activity, skuDetails);
    }
}
