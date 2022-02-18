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

package com.psiphon3.psicash.settings;

import static com.psiphon3.psicash.settings.PsiCashSettingsViewState.AccountState;
import static com.psiphon3.psicash.settings.PsiCashSettingsViewState.Builder;
import static com.psiphon3.psicash.settings.PsiCashSettingsViewState.initialViewState;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewModel;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class PsiCashSettingsViewModel extends AndroidViewModel
        implements MviViewModel<PsiCashSettingsIntent, PsiCashSettingsViewState> {
    private static final String TAG = "PsiCashSettingsViewModel";

    private final PublishRelay<PsiCashSettingsIntent> intentsPublishRelay;
    private final Observable<PsiCashSettingsViewState> viewStateObservable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    @NonNull
    private final PsiCashSettingsActionProcessorHolder actionProcessorHolder;

    public PsiCashSettingsViewModel(@NonNull Application application) {
        super(application);

        intentsPublishRelay = PublishRelay.create();
        actionProcessorHolder = new PsiCashSettingsActionProcessorHolder(application);
        viewStateObservable = compose(application.getApplicationContext());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.dispose();
    }

    private Observable<PsiCashSettingsViewState> compose(Context appContext) {
        return intentsPublishRelay
                .startWith(PsiCashSettingsIntent.InitialIntent.create())
                .compose(intentFilter)
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(actionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(initialViewState(), reduce(appContext))
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
    private static BiFunction<PsiCashSettingsViewState, PsiCashSettingsResult, PsiCashSettingsViewState> reduce(Context appContext) {
        return (previousState, detailsResult) -> {
            Builder stateBuilder = previousState.withState();

            if (detailsResult instanceof PsiCashSettingsResult.GetPsiCash) {
                PsiCashSettingsResult.GetPsiCash result = (PsiCashSettingsResult.GetPsiCash) detailsResult;
                PsiCashModel model = result.model();

                switch (result.status()) {
                    case SUCCESS:
                        AccountState accountState = AccountState.INVALID;
                        if (model != null) {
                            if (model.isAccount()) {
                                if (model.hasTokens()) {
                                    accountState = AccountState.ACCOUNT_LOGGED_IN;
                                } else {
                                    accountState = AccountState.ACCOUNT_LOGGED_OUT;
                                }
                            } else if (model.hasTokens()) {
                                accountState = AccountState.NOT_ACCOUNT;
                            }
                        }
                        return stateBuilder
                                .accountManagementUrl(model.accountManagementUrl())
                                .accountState(accountState)
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
            }  else if (detailsResult instanceof PsiCashSettingsResult.AccountLogout) {
                PsiCashSettingsResult.AccountLogout result = (PsiCashSettingsResult.AccountLogout) detailsResult;
                PsiCashModel model = result.model();
                AccountState accountState = AccountState.INVALID;
                if (model != null) {
                    if (model.isAccount()) {
                        if (model.hasTokens()) {
                            accountState = AccountState.ACCOUNT_LOGGED_IN;
                        } else {
                            accountState = AccountState.ACCOUNT_LOGGED_OUT;
                        }
                    } else if (model.hasTokens()) {
                        accountState = AccountState.NOT_ACCOUNT;
                    }
                }

                switch (result.status()) {
                    case SUCCESS:
                        return stateBuilder
                                .accountState(accountState)
                                .accountManagementUrl(model.accountManagementUrl())
                                .psiCashTransactionInFlight(false)
                                .build();
                    case FAILURE:
                        MyLog.w("PsiCash view state error: " + result.getClass().getSimpleName() + ": " + result.error());
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

    private final ObservableTransformer<PsiCashSettingsIntent, PsiCashSettingsIntent> intentFilter =
            intents -> intents.publish(shared ->
                    Observable.merge(
                            shared.ofType(PsiCashSettingsIntent.InitialIntent.class).take(1),
                            shared.filter(intent -> !(intent instanceof PsiCashSettingsIntent.InitialIntent))
                    )
            );

    private Observable<PsiCashSettingsAction> actionFromIntent(PsiCashSettingsIntent intent) {
        if (intent instanceof PsiCashSettingsIntent.InitialIntent) {
            return Observable.just(PsiCashSettingsAction.InitialAction.create());
        }
        if (intent instanceof PsiCashSettingsIntent.GetPsiCash) {
            PsiCashSettingsIntent.GetPsiCash getPsiCashIntent = (PsiCashSettingsIntent.GetPsiCash) intent;
            return Observable.just(PsiCashSettingsAction.GetPsiCash.create(getPsiCashIntent.tunnelStateFlowable()));
        }
        if (intent instanceof PsiCashSettingsIntent.LogoutAccount) {
            PsiCashSettingsIntent.LogoutAccount logoutAccountIntent = (PsiCashSettingsIntent.LogoutAccount) intent;
            return Observable.just(PsiCashSettingsAction.LogoutAccount.create(logoutAccountIntent.tunnelStateFlowable()));
        }
        throw new IllegalArgumentException(TAG + ": unknown intent: " + intent);
    }

    @Override
    public void processIntents(Observable<PsiCashSettingsIntent> intents) {
        compositeDisposable.add(intents.subscribe(intentsPublishRelay));
    }

    @Override
    public Observable<PsiCashSettingsViewState> states() {
        return viewStateObservable
                .hide();
    }
}
