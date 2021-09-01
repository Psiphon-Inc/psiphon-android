/*
 * Copyright (c) 2021, Psiphon Inc.
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
 */

package com.psiphon3.psicash.store;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewModel;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class PsiCashStoreViewModel extends AndroidViewModel
        implements MviViewModel<PsiCashStoreIntent, PsiCashStoreViewState> {
    private static final String TAG = "PsiCashStoreViewModel";

    private final PublishRelay<PsiCashStoreIntent> intentsPublishRelay;
    private final Observable<PsiCashStoreViewState> viewStateObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @NonNull
    private final PsiCashStoreActionProcessorHolder actionProcessorHolder;

    public PsiCashStoreViewModel(@NonNull Application application) {
        super(application);

        intentsPublishRelay = PublishRelay.create();
        actionProcessorHolder = new PsiCashStoreActionProcessorHolder(application);
        viewStateObservable = compose(application.getApplicationContext());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }

    private Observable<PsiCashStoreViewState> compose(Context appContext) {
        return intentsPublishRelay
                .startWith(PsiCashStoreIntent.InitialIntent.create())
                .compose(intentFilter)
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(actionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(PsiCashStoreViewState.initialViewState(), reduce(appContext))
                // Skip the initial seed value
                .skip(1)
                // When a reducer just emits previousState, there's no reason to call render
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription
                // Useful when a View rebinds to the RewardedVideoViewModel after rotation.
                .replay(1)
                // Create the stream on creation without waiting for anyone to subscribe
                // This allows the stream to stay alive even when the UI disconnects and
                // match the stream's lifecycle to the RewardedVideoViewModel's one.
                .autoConnect(0);
    }

    @NonNull
    private static BiFunction<PsiCashStoreViewState, PsiCashStoreResult, PsiCashStoreViewState> reduce(Context appContext) {
        return (previousState, storeResult) -> {
            PsiCashStoreViewState.Builder stateBuilder = previousState.withState();

            if (storeResult instanceof PsiCashStoreResult.GetPsiCash) {
                PsiCashStoreResult.GetPsiCash result = (PsiCashStoreResult.GetPsiCash) storeResult;
                PsiCashModel model = result.model();

                switch (result.status()) {
                    case SUCCESS:
                        return stateBuilder
                                .psiCashModel(model)
                                .psiCashTransactionInFlight(false)
                                .build();
                    case FAILURE:
                        return stateBuilder
                                .psiCashTransactionInFlight(false)
                                .error(result.error())
                                .build();
                    case IN_FLIGHT:
                        return stateBuilder
                                .psiCashTransactionInFlight(true)
                                .build();
                }
            } else if (storeResult instanceof PsiCashStoreResult.MakeExpiringPurchase) {
                PsiCashStoreResult.MakeExpiringPurchase result = (PsiCashStoreResult.MakeExpiringPurchase) storeResult;
                PsiCashModel model = result.model();

                switch (result.status()) {
                    case SUCCESS:
                        return stateBuilder
                                .psiCashModel(model)
                                .purchaseSuccess()
                                .psiCashTransactionInFlight(false)
                                .build();
                    case FAILURE:
                        return stateBuilder
                                .psiCashTransactionInFlight(false)
                                .error(result.error())
                                .build();
                    case IN_FLIGHT:
                        return stateBuilder
                                .psiCashTransactionInFlight(true)
                                .build();
                }
            }

            throw new IllegalArgumentException(TAG + ": unknown result: " + storeResult);
        };
    }

    private final ObservableTransformer<PsiCashStoreIntent, PsiCashStoreIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(PsiCashStoreIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof PsiCashStoreIntent.InitialIntent))
                    )
            );

    private Observable<PsiCashStoreAction> actionFromIntent(PsiCashStoreIntent intent) {
        if (intent instanceof PsiCashStoreIntent.InitialIntent) {
            return Observable.just(PsiCashStoreAction.InitialAction.create());
        }
        if (intent instanceof PsiCashStoreIntent.GetPsiCash) {
            PsiCashStoreIntent.GetPsiCash getPsiCashIntent = (PsiCashStoreIntent.GetPsiCash) intent;
            return Observable.just(PsiCashStoreAction.GetPsiCash.create(getPsiCashIntent.tunnelStateFlowable()));
        }
        if (intent instanceof PsiCashStoreIntent.PurchaseSpeedBoost) {
            PsiCashStoreIntent.PurchaseSpeedBoost purchaseSpeedBoostIntent = (PsiCashStoreIntent.PurchaseSpeedBoost) intent;
            return Observable.just(PsiCashStoreAction.MakeExpiringPurchase.create(
                    purchaseSpeedBoostIntent.tunnelStateFlowable(),
                    purchaseSpeedBoostIntent.distinguisher(),
                    purchaseSpeedBoostIntent.transactionClass(),
                    purchaseSpeedBoostIntent.expectedPrice()));
        }
        throw new IllegalArgumentException(TAG + ": unknown intent: " + intent);
    }

    @Override
    public void processIntents(Observable<PsiCashStoreIntent> intents) {
        compositeDisposable.add(intents.subscribe(intentsPublishRelay));
    }

    @Override
    public Observable<PsiCashStoreViewState> states() {
        return viewStateObservable
                .hide();
    }
}
