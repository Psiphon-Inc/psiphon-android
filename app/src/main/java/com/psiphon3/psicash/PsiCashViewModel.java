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
import android.support.annotation.NonNull;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.mvibase.MviAction;
import com.psiphon3.psicash.mvibase.MviIntent;
import com.psiphon3.psicash.mvibase.MviResult;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.mvibase.MviViewModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.Date;
import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
public class PsiCashViewModel extends AndroidViewModel implements MviViewModel {
    private static final String DISTINGUISHER_1HR = "1hr";
    private static final String TAG = "PsiCashViewModel";
    private final PublishRelay<PsiCashIntent> intentPublishRelay;
    private final Observable<PsiCashViewState> psiCashViewStateObservable;
    private final CompositeDisposable compositeDisposable;

    @NonNull
    private final PsiCashActionProcessorHolder psiCashActionProcessorHolder;

    PsiCashViewModel(@NonNull Application application, PsiCashListener psiCashListener) {
        super(application);

        intentPublishRelay = PublishRelay.create();
        psiCashActionProcessorHolder = new PsiCashActionProcessorHolder(application, psiCashListener);
        psiCashViewStateObservable = compose();
        compositeDisposable = new CompositeDisposable();
    }

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

                    PsiCashLib.PurchasePrice price = null;
                    Date nextPurchaseExpiryDate = null;

                    PsiCashModel.PsiCash model = getPsiCashResult.model();
                    int uiBalance = 0;
                    if (model != null) {
                        long balance = model.balance();
                        long reward = model.reward();
                        uiBalance = (int)(Math.floor((long) ((reward * 1e9 + balance) / 1e9)));

                        List<PsiCashLib.PurchasePrice> purchasePriceList = model.purchasePrices();
                        if(purchasePriceList != null) {
                            for (PsiCashLib.PurchasePrice p : purchasePriceList) {
                                if (p.distinguisher.equals(DISTINGUISHER_1HR)) {
                                    price = p;
                                }
                            }
                        }

                        PsiCashLib.Purchase nextExpiringPurchase = model.nextExpiringPurchase();
                        if (nextExpiringPurchase != null) {
                            nextPurchaseExpiryDate = nextExpiringPurchase.expiry;
                        }
                    }
                    switch (getPsiCashResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .uiBalance(uiBalance)
                                    .purchasePrice(price)
                                    .nextPurchaseExpiryDate(nextPurchaseExpiryDate)
                                    // after first success animate on consecutive balance changes
                                    .animateOnNextBalanceChange(true)
                                    .build();
                        case FAILURE:
                            Utils.MyLog.g("PsiCash error has occurred: " + getPsiCashResult.error());
                            if (model != null) {
                                Utils.MyLog.g("PsiCash diagnostic info: " + model.diagnosticInfo());
                            }
                            return stateBuilder
                                    .psiCashError(getPsiCashResult.error())
                                    .build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .build();
                    }
                } else if (result instanceof PsiCashResult.ClearErrorState) {
                    return stateBuilder
                            .psiCashError(null)
                            .build();
                } else if (result instanceof PsiCashResult.ExpiringPurchase) {
                    PsiCashResult.ExpiringPurchase purchaseResult = (PsiCashResult.ExpiringPurchase) result;
                    Date nextPurchaseExpiryDate = null;

                    PsiCashModel.ExpiringPurchase model = purchaseResult.model();
                    if (model != null) {
                        nextPurchaseExpiryDate = model.expiringPurchase().expiry;
                    }

                    switch (purchaseResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .purchaseInFlight(false)
                                    .nextPurchaseExpiryDate(nextPurchaseExpiryDate)
                                    .build();
                        case FAILURE:
                            PsiCashViewState.Builder builder = stateBuilder
                                    .purchaseInFlight(false)
                                    .psiCashError(purchaseResult.error());
                            return builder.build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .purchaseInFlight(true)
                                    .build();
                    }
                } else if (result instanceof PsiCashResult.Video) {
                    PsiCashResult.Video videoResult = (PsiCashResult.Video) result;

                    switch (videoResult.status()) {
                        case LOADING:
                            return stateBuilder
                                    .videoIsLoaded(false)
                                    .videoIsPlaying(false)
                                    .videoIsLoading(true)
                                    .videoIsFinished(false)
                                    .videoError(null)
                                    .build();
                        case LOADED:
                            return stateBuilder
                                    .videoIsLoaded(true)
                                    .videoIsPlaying(false)
                                    .videoIsLoading(false)
                                    .videoIsFinished(false)
                                    .videoError(null)
                                    .build();
                        case FAILURE:
                            return stateBuilder
                                    .videoIsLoaded(false)
                                    .videoIsPlaying(false)
                                    .videoIsLoading(false)
                                    .videoIsFinished(false)
                                    .videoError(videoResult.error())
                                    .build();
                        case PLAYING:
                            return stateBuilder
                                    .videoIsLoaded(false)
                                    .videoIsPlaying(true)
                                    .videoIsLoading(false)
                                    .videoIsFinished(false)
                                    .videoError(null)
                                    .build();
                        case FINISHED:
                            return stateBuilder
                                    .videoIsLoaded(false)
                                    .videoIsPlaying(false)
                                    .videoIsLoading(false)
                                    .videoIsFinished(true)
                                    .videoError(null)
                                    .build();
                    }

                } else if (result instanceof PsiCashResult.Reward) {
                    // Reward is taken care of by the processor, just return previous state
                    return  previousState;
                }

                throw new IllegalArgumentException("Don't know this result " + result);
            };

    private Observable<PsiCashViewState> compose() {
        return intentPublishRelay
                .observeOn(Schedulers.computation())
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

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private Observable<PsiCashAction> actionFromIntent(MviIntent intent) {
        if (intent instanceof PsiCashIntent.GetPsiCashRemote) {
            PsiCashIntent.GetPsiCashRemote getPsiCashRemoteIntent = (PsiCashIntent.GetPsiCashRemote) intent;
            final TunnelState status = getPsiCashRemoteIntent.connectionState();
            return Observable.just(PsiCashAction.GetPsiCashRemote.create(status));
        }
        if (intent instanceof PsiCashIntent.GetPsiCashLocal) {
            return Observable.just(PsiCashAction.GetPsiCashLocal.create());
        }
        if (intent instanceof PsiCashIntent.ClearErrorState) {
            return Observable.just(PsiCashAction.ClearErrorState.create());
        }
        if (intent instanceof PsiCashIntent.PurchaseSpeedBoost) {
            PsiCashIntent.PurchaseSpeedBoost purchaseSpeedBoostIntent = (PsiCashIntent.PurchaseSpeedBoost) intent;
            final PsiCashLib.PurchasePrice price = purchaseSpeedBoostIntent.purchasePrice();
            final TunnelState tunnelState = purchaseSpeedBoostIntent.connectionState();
            final boolean hasActiveBoost = purchaseSpeedBoostIntent.hasActiveBoost();
            return Observable.just(PsiCashAction.MakeExpiringPurchase.create(tunnelState, price, hasActiveBoost));
        }
        if (intent instanceof PsiCashIntent.RemovePurchases) {
            PsiCashIntent.RemovePurchases removePurchases = (PsiCashIntent.RemovePurchases) intent;
            final List<String> purchases = removePurchases.purchases();
            return Observable.just(PsiCashAction.RemovePurchases.create(purchases));
        }
        if (intent instanceof PsiCashIntent.LoadVideoAd) {
            PsiCashIntent.LoadVideoAd loadVideoAd = (PsiCashIntent.LoadVideoAd) intent;
            final TunnelState tunnelState = loadVideoAd.connectionState();
            return Observable.just(PsiCashAction.LoadVideoAd.create(tunnelState));
        }
        throw new IllegalArgumentException("Do not know how to treat this intent " + intent);
    }

    @Override
    public void processIntents(Observable intents) {
        compositeDisposable.clear();
        compositeDisposable.add(intents.subscribe(intentPublishRelay));
    }

    @Override
    public Observable<PsiCashViewState> states() {
        return psiCashViewStateObservable;
    }

}
