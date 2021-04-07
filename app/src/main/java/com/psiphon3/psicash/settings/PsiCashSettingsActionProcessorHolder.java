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

import android.content.Context;

import com.psiphon3.psicash.PsiCashClient;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
class PsiCashSettingsActionProcessorHolder {
    private final ObservableTransformer<PsiCashSettingsAction.GetPsiCash, PsiCashSettingsResult> getPsiCashProcessor;
    private final Single<PsiCashClient> psiCashClientSingle;
    private final ObservableTransformer<PsiCashSettingsAction.InitialAction, PsiCashSettingsResult> initialActionProcessor;
    private final ObservableTransformer<PsiCashSettingsAction.LogoutAccount, PsiCashSettingsResult> logoutAccountProcessor;

    final ObservableTransformer<PsiCashSettingsAction, PsiCashSettingsResult> actionProcessor;

    PsiCashSettingsActionProcessorHolder(Context appContext) {
        this.psiCashClientSingle = Single.fromCallable(() -> PsiCashClient.getInstance(appContext));

        this.initialActionProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle.flatMap(PsiCashClient::getPsiCashModel)
                                    .map(PsiCashSettingsResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashSettingsResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashSettingsResult.GetPsiCash.inFlight());
                        }
                );

        this.getPsiCashProcessor = actions ->
                actions.switchMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.refreshState(action.tunnelStateFlowable(), false)
                                        .flatMap(psiCashClient::getPsiCashModel))
                                .map(PsiCashSettingsResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashSettingsResult.GetPsiCash::failure)
                                .toObservable()
                                .startWith(PsiCashSettingsResult.GetPsiCash.inFlight()));

        this.logoutAccountProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.logoutAccount(action.tunnelStateFlowable())
                                        .flatMap(psiCashClient::getPsiCashModel))
                                .map(PsiCashSettingsResult.AccountLogout::success)
                                .onErrorReturn(PsiCashSettingsResult.AccountLogout::failure)
                                .toObservable()
                                .startWith(PsiCashSettingsResult.AccountLogout.inFlight()));


        this.actionProcessor = actions ->
                actions.observeOn(Schedulers.io()).publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashSettingsAction.InitialAction.class).compose(initialActionProcessor),
                                shared.ofType(PsiCashSettingsAction.GetPsiCash.class).compose(getPsiCashProcessor),
                                shared.ofType(PsiCashSettingsAction.LogoutAccount.class).compose(logoutAccountProcessor)

                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashSettingsAction.InitialAction)
                                        && !(v instanceof PsiCashSettingsAction.GetPsiCash)
                                        && !(v instanceof PsiCashSettingsAction.LogoutAccount)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
