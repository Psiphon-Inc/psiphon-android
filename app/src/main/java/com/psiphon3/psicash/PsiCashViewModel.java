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

package com.psiphon3.psicash;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.PurchaseState;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.mvibase.MviAction;
import com.psiphon3.psicash.mvibase.MviIntent;
import com.psiphon3.psicash.mvibase.MviResult;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.mvibase.MviViewModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.Date;
import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
public class PsiCashViewModel extends AndroidViewModel implements MviViewModel, LifecycleObserver {
    private final PublishRelay<PsiCashIntent> intentPublishRelay;
    private final Observable<PsiCashViewState> psiCashViewStateObservable;
    private final CompositeDisposable compositeDisposable;
    private final TunnelServiceInteractor tunnelServiceInteractor;
    private final BroadcastReceiver broadcastReceiver;
    private GooglePlayBillingHelper googlePlayBillingHelper;

    @NonNull
    private final PsiCashActionProcessorHolder psiCashActionProcessorHolder;
    private boolean isStopped;

    public PsiCashViewModel(@NonNull Application application) {
        super(application);

        tunnelServiceInteractor = new TunnelServiceInteractor(application.getApplicationContext());
        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(application.getApplicationContext());
        intentPublishRelay = PublishRelay.create();
        psiCashActionProcessorHolder = new PsiCashActionProcessorHolder(application);
        psiCashViewStateObservable = compose();
        compositeDisposable = new CompositeDisposable();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT);

        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null && !isStopped) {
                    if (action.equals(TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT)) {
                        // Update purchase state
                        googlePlayBillingHelper.queryAllPurchases();
                        // Try to sync with the server
                        intentPublishRelay.accept(PsiCashIntent.GetPsiCash.create());
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(getApplication()).registerReceiver(broadcastReceiver, intentFilter);

        // Try and fetch PsiCash every time when tunnel goes connected
        compositeDisposable.add(tunnelStateFlowable()
                .distinctUntilChanged()
                .filter(tunnelState -> tunnelState.isRunning() && tunnelState.connectionData().isConnected())
                .subscribe(__ -> intentPublishRelay.accept(PsiCashIntent.GetPsiCash.create())));
    }

    public static String getDiagnosticInfoJson() {
        return diagnosticInfoJson;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    protected void onLifeCycleStop() {
        isStopped = true;
        tunnelServiceInteractor.onStop(getApplication());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    protected void onLifeCycleStart() {
        isStopped = false;
        tunnelServiceInteractor.onStart(getApplication());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    protected void onLifeCycleResume() {
        tunnelServiceInteractor.onResume();
        googlePlayBillingHelper.queryAllPurchases();
        googlePlayBillingHelper.queryAllSkuDetails();
        // Unconditionally get PsiCash state when app is foregrounded
        intentPublishRelay.accept(PsiCashIntent.GetPsiCash.create());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        tunnelServiceInteractor.onDestroy(getApplication());
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(broadcastReceiver);

    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }

    public Flowable<SubscriptionState> subscriptionStateFlowable() {
        return googlePlayBillingHelper.subscriptionStateFlowable();
    }

    private static String diagnosticInfoJson;
    /**
     * The Reducer is where {@link MviViewState}, that the {@link MviView} will use to
     * render itself, are created.
     * It takes the last cached {@link MviViewState}, the latest {@link MviResult} and
     * creates a new {@link MviViewState} by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the {@link MviResult}
     */

    private static final BiFunction<PsiCashViewState, PsiCashResult, PsiCashViewState> reducer =
            (previousState, result) -> {
                PsiCashViewState.Builder stateBuilder = previousState.withState();

                if (result instanceof PsiCashResult.GetPsiCash) {
                    PsiCashResult.GetPsiCash getPsiCashResult = (PsiCashResult.GetPsiCash) result;

                    List<PsiCashLib.PurchasePrice> purchasePriceList = null;

                    PsiCashModel.PsiCash model = getPsiCashResult.model();
                    int uiBalance = 0;
                    boolean pendingRefresh = false;
                    boolean hasTokens = false;
                    PsiCashLib.Purchase nextExpiringPurchase = null;
                    if (model != null) {
                        diagnosticInfoJson = model.diagnosticInfo();
                        long balance = model.balance();
                        long reward = model.reward();
                        pendingRefresh = model.pendingRefresh();
                        hasTokens = model.hasValidTokens();
                        uiBalance = (int) (Math.floor((long) ((reward * 1e9 + balance) / 1e9)));

                        purchasePriceList = model.purchasePrices();
                        nextExpiringPurchase = model.nextExpiringPurchase();
                    }

                    switch (getPsiCashResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .hasValidTokens(hasTokens)
                                    .uiBalance(uiBalance)
                                    .purchasePrices(purchasePriceList)
                                    .purchase(nextExpiringPurchase)
                                    .pendingRefresh(pendingRefresh)
                                    .psiCashTransactionInFlight(false)
                                    .build();
                        case FAILURE:
                            Utils.MyLog.g("PsiCash error has occurred: " + getPsiCashResult.error());
                            if (model != null) {
                                Utils.MyLog.g("PsiCash diagnostic info: " + model.diagnosticInfo());
                            }
                            return stateBuilder
                                    .psiCashTransactionInFlight(false)
                                    .error(getPsiCashResult.error())
                                    .build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .psiCashTransactionInFlight(true)
                                    .build();
                    }
                } else if (result instanceof PsiCashResult.ClearErrorState) {
                    return stateBuilder
                            .error(null)
                            .build();
                } else if (result instanceof PsiCashResult.ExpiringPurchase) {
                    PsiCashLib.Purchase purchase = null;
                    PsiCashResult.ExpiringPurchase purchaseResult = (PsiCashResult.ExpiringPurchase) result;
                    PsiCashModel.ExpiringPurchase model = purchaseResult.model();
                    if (model != null) {
                        purchase = model.expiringPurchase();
                    }

                    switch (purchaseResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .psiCashTransactionInFlight(false)
                                    .purchase(purchase)
                                    .build();
                        case FAILURE:
                            PsiCashViewState.Builder builder = stateBuilder
                                    .psiCashTransactionInFlight(false)
                                    .error(purchaseResult.error());
                            return builder.build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .psiCashTransactionInFlight(true)
                                    .build();
                    }
                }

                throw new IllegalArgumentException("Don't know this result " + result);
            };

    private Observable<PsiCashViewState> compose() {
        return intentPublishRelay
                .startWith(PsiCashIntent.InitialIntent.create())
                .observeOn(Schedulers.computation())
                // Do not let any other than initial intent through if user has a subscription
                .flatMap(this::checkSubscriptionFilter)
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(psiCashActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(PsiCashViewState.idle(), reducer)
                // When a reducer just emits previousState, there's no reason to call render. In fact,
                // redrawing the UI in cases like this can cause junk (e.g. messing up snackbar animations
                // by showing the same snackbar twice in rapid succession).
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription
                // Useful when a View rebinds to the RewardedVideoViewModel after rotation.
                .replay(1)
                // Create the stream on creation without waiting for anyone to subscribe
                // This allows the stream to stay alive even when the UI disconnects and
                // match the stream's lifecycle to the RewardedVideoViewModel's one.
                .autoConnect(0);
    }

    private Observable<PsiCashIntent> checkSubscriptionFilter(PsiCashIntent intent) {
        return subscriptionStateFlowable()
                .distinctUntilChanged()
                .toObservable()
                .switchMap(subscriptionState -> {
                    if (subscriptionState.hasValidPurchase()) {
                        if (!(intent instanceof PsiCashIntent.InitialIntent)) {
                            return Observable.empty();
                        }
                    }
                    return Observable.just(intent);
                });
    }

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private Observable<PsiCashAction> actionFromIntent(MviIntent intent) {
        if (intent instanceof PsiCashIntent.InitialIntent) {
            return Observable.just(PsiCashAction.InitialAction.create());
        }
        if (intent instanceof PsiCashIntent.GetPsiCash) {
            return Observable.just(PsiCashAction.GetPsiCash.create(tunnelStateFlowable()));
        }
        if (intent instanceof PsiCashIntent.ClearErrorState) {
            return Observable.just(PsiCashAction.ClearErrorState.create());
        }
        if (intent instanceof PsiCashIntent.PurchaseSpeedBoost) {
            PsiCashIntent.PurchaseSpeedBoost purchaseSpeedBoostIntent = (PsiCashIntent.PurchaseSpeedBoost) intent;
            return Observable.just(PsiCashAction.MakeExpiringPurchase.create(tunnelStateFlowable(),
                    purchaseSpeedBoostIntent.distinguisher(),
                    purchaseSpeedBoostIntent.transactionClass(),
                    purchaseSpeedBoostIntent.expectedPrice()));
        }
        if (intent instanceof PsiCashIntent.RemovePurchases) {
            PsiCashIntent.RemovePurchases removePurchases = (PsiCashIntent.RemovePurchases) intent;
            return Observable.just(PsiCashAction.RemovePurchases.create(removePurchases.purchases()));
        }
        throw new IllegalArgumentException("Do not know how to treat this intent " + intent);
    }

    @Override
    public void processIntents(Observable intents) {
        compositeDisposable.add(intents.subscribe(intentPublishRelay));
    }

    @Override
    public Observable<PsiCashViewState> states() {
        return psiCashViewStateObservable;
    }

    Single<List<SkuDetails>> getPsiCashSkus() {
        return googlePlayBillingHelper.allSkuDetailsSingle()
                .toObservable()
                .flatMap(Observable::fromIterable)
                .filter(skuDetails -> {
                    String sku = skuDetails.getSku();
                    return GooglePlayBillingHelper.IAB_PSICASH_SKUS_TO_VALUE.containsKey(sku);
                })
                .toList();
    }

    Flowable<List<Purchase>> purchaseListFlowable() {
        return googlePlayBillingHelper.purchaseStateFlowable()
                .map(PurchaseState::purchaseList)
                .distinctUntilChanged();
    }

    public Observable<Boolean> booleanActiveSpeedBoostObservable() {
        return psiCashViewStateObservable.map(psiCashViewState -> {
            if (psiCashViewState.purchase() == null) {
                return false;
            }
            Date expiryDate = psiCashViewState.purchase().expiry;
            if (expiryDate != null) {
                long millisDiff = expiryDate.getTime() - new Date().getTime();
                return millisDiff > 0;
            }
            return false;
        });
    }
}
