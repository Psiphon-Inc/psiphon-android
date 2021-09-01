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

package com.psiphon3.psicash.details;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewModel;

import java.util.Date;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class PsiCashDetailsViewModel extends AndroidViewModel
        implements MviViewModel<PsiCashDetailsIntent, PsiCashDetailsViewState> {
    private static final String TAG = "PsiCashDetailsViewModel";

    private final PublishRelay<PsiCashDetailsIntent> intentsPublishRelay;
    private final Observable<PsiCashDetailsViewState> viewStateObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @NonNull
    private final PsiCashDetailsActionProcessorHolder actionProcessorHolder;

    public PsiCashDetailsViewModel(@NonNull Application application) {
        super(application);

        intentsPublishRelay = PublishRelay.create();
        actionProcessorHolder = new PsiCashDetailsActionProcessorHolder(application);
        viewStateObservable = compose(application.getApplicationContext());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }

    private Observable<PsiCashDetailsViewState> compose(Context appContext) {
        return intentsPublishRelay
                .startWith(PsiCashDetailsIntent.InitialIntent.create())
                .compose(intentFilter)
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(actionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(PsiCashDetailsViewState.initialViewState(), reduce(appContext))
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
    private static BiFunction<PsiCashDetailsViewState, PsiCashDetailsResult, PsiCashDetailsViewState> reduce(Context appContext) {
        return (previousState, detailsResult) -> {
            PsiCashDetailsViewState.Builder stateBuilder = previousState.withState();

            if (detailsResult instanceof PsiCashDetailsResult.GetPsiCash) {
                PsiCashDetailsResult.GetPsiCash result = (PsiCashDetailsResult.GetPsiCash) detailsResult;
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
            }  else if (detailsResult instanceof PsiCashDetailsResult.RemovePurchase) {
                PsiCashDetailsResult.RemovePurchase result = (PsiCashDetailsResult.RemovePurchase) detailsResult;
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
            }

            throw new IllegalArgumentException(TAG + ": unknown result: " + detailsResult);
        };
    }

    private final ObservableTransformer<PsiCashDetailsIntent, PsiCashDetailsIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(PsiCashDetailsIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof PsiCashDetailsIntent.InitialIntent))
                    )
            );

    private Observable<PsiCashDetailsAction> actionFromIntent(PsiCashDetailsIntent intent) {
        if (intent instanceof PsiCashDetailsIntent.InitialIntent) {
            return Observable.just(PsiCashDetailsAction.InitialAction.create());
        }
        if (intent instanceof PsiCashDetailsIntent.GetPsiCash) {
            PsiCashDetailsIntent.GetPsiCash getPsiCashIntent = (PsiCashDetailsIntent.GetPsiCash) intent;
            return Observable.just(PsiCashDetailsAction.GetPsiCash.create(getPsiCashIntent.tunnelStateFlowable()));
        }
        if (intent instanceof PsiCashDetailsIntent.RemovePurchases) {
            PsiCashDetailsIntent.RemovePurchases removePurchasesIntent = (PsiCashDetailsIntent.RemovePurchases) intent;
            return Observable.just(PsiCashDetailsAction.RemovePurchases.create(removePurchasesIntent.purchases()));
        }
        throw new IllegalArgumentException(TAG + ": unknown intent: " + intent);
    }

    public Observable<Boolean> hasActiveSpeedBoostObservable() {
        return states().map(psiCashViewState -> {
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

    @Override
    public void processIntents(Observable<PsiCashDetailsIntent> intents) {
        compositeDisposable.add(intents.subscribe(intentsPublishRelay));
    }

    @Override
    public Observable<PsiCashDetailsViewState> states() {
        return viewStateObservable
                .hide();
    }
}
