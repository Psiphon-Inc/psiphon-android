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

package com.psiphon3.psicash.account;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewModel;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import org.jetbrains.annotations.NotNull;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class PsiCashAccountViewModel extends AndroidViewModel
        implements MviViewModel<PsiCashAccountIntent, PsiCashAccountViewState> {
    private final PublishRelay<PsiCashAccountIntent> intentsPublishRelay;
    private final Observable<PsiCashAccountViewState> viewStateObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @NonNull
    private final PsiCashAccountActionProcessorHolder actionProcessorHolder;

    public PsiCashAccountViewModel(@NonNull Application application) {
        super(application);

        intentsPublishRelay = PublishRelay.create();
        actionProcessorHolder = new PsiCashAccountActionProcessorHolder(application);
        viewStateObservable = compose(application.getApplicationContext());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }

    private static final String TAG = "PsiCashAccountViewModel";

    private Observable<PsiCashAccountViewState> compose(Context appContext) {
        return intentsPublishRelay
                .startWith(PsiCashAccountIntent.InitialIntent.create())
                .compose(intentFilter)
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(actionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(PsiCashAccountViewState.initialViewState(), reduce(appContext))
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

    @NotNull
    private static BiFunction<PsiCashAccountViewState, PsiCashAccountResult, PsiCashAccountViewState> reduce(Context appContext) {
        return (previousState, psiCashAccountResult) -> {
            PsiCashAccountViewState.Builder stateBuilder = previousState.withState();

            if (psiCashAccountResult instanceof PsiCashAccountResult.GetPsiCash) {
                PsiCashAccountResult.GetPsiCash result = (PsiCashAccountResult.GetPsiCash) psiCashAccountResult;
                PsiCashModel model = result.model();

                switch (result.status()) {
                    case SUCCESS:
                        return stateBuilder
                                .psiCashModel(model)
                                .psiCashTransactionInFlight(false)
                                .build();
                    case FAILURE:
                        Utils.MyLog.g("PsiCash view state error: " + result.getClass().getSimpleName() + ": " + result.error());
                        return stateBuilder
                                .psiCashTransactionInFlight(false)
                                .error(result.error())
                                .build();
                    case IN_FLIGHT:
                        return stateBuilder
                                .psiCashTransactionInFlight(true)
                                .build();
                }
            }  else if (psiCashAccountResult instanceof PsiCashAccountResult.AccountLogin) {
                PsiCashAccountResult.AccountLogin result = (PsiCashAccountResult.AccountLogin) psiCashAccountResult;
                PsiCashModel model = result.model();
                String uiNotification = result.lastTrackerMerge() ?
                        appContext.getString(R.string.psicash_last_tracker_merge_notification) :
                        null;

                switch (result.status()) {
                    case SUCCESS:
                        return stateBuilder
                                .psiCashModel(model)
                                .uiNotification(uiNotification)
                                .psiCashTransactionInFlight(false)
                                .build();
                    case FAILURE:
                        Utils.MyLog.g("PsiCash view state error: " + result.getClass().getSimpleName() + ": " + result.error());
                        return stateBuilder
                                .psiCashTransactionInFlight(false)
                                .error(result.error())
                                .build();
                    case IN_FLIGHT:
                        return stateBuilder
                                .psiCashTransactionInFlight(true)
                                .build();
                }
            }            throw new IllegalArgumentException(TAG + ": unknown result: " + psiCashAccountResult);
        };
    }

    private final ObservableTransformer<PsiCashAccountIntent, PsiCashAccountIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(PsiCashAccountIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof PsiCashAccountIntent.InitialIntent))
                    )
            );

    private Observable<PsiCashAccountAction> actionFromIntent(PsiCashAccountIntent intent) {
        if (intent instanceof PsiCashAccountIntent.InitialIntent) {
            return Observable.just(PsiCashAccountAction.InitialAction.create());
        }
        if (intent instanceof PsiCashAccountIntent.GetPsiCash) {
            PsiCashAccountIntent.GetPsiCash getPsiCashIntent = (PsiCashAccountIntent.GetPsiCash) intent;
            return Observable.just(PsiCashAccountAction.GetPsiCash.create(getPsiCashIntent.tunnelStateFlowable()));
        }
        if (intent instanceof PsiCashAccountIntent.LoginAccount) {
            PsiCashAccountIntent.LoginAccount loginAccountIntent = (PsiCashAccountIntent.LoginAccount) intent;
            return Observable.just(PsiCashAccountAction.LoginAccount.create(loginAccountIntent.tunnelStateFlowable(),
                    loginAccountIntent.username(),
                    loginAccountIntent.password()));
        }
        throw new IllegalArgumentException(TAG + ": unknown intent: " + intent);
    }

    @Override
    public void processIntents(Observable<PsiCashAccountIntent> intents) {
        compositeDisposable.add(intents.subscribe(intentsPublishRelay));
    }

    @Override
    public Observable<PsiCashAccountViewState> states() {
        return viewStateObservable
                .hide();
    }
}
