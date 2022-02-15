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

import android.content.Context;

import androidx.core.util.Pair;

import com.psiphon3.psicash.PsiCashClient;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

public class PsiCashAccountActionProcessorHolder {
    final ObservableTransformer<PsiCashAccountAction, PsiCashAccountResult> actionProcessor;

    private final Single<PsiCashClient> psiCashClientSingle;

    private final ObservableTransformer<PsiCashAccountAction.GetPsiCash, PsiCashAccountResult> getPsiCashProcessor;
    private final ObservableTransformer<PsiCashAccountAction.InitialAction, PsiCashAccountResult> initialActionProcessor;
    private final ObservableTransformer<PsiCashAccountAction.LoginAccount, PsiCashAccountResult> loginAccountProcessor;

    public PsiCashAccountActionProcessorHolder(Context appContext) {
        this.psiCashClientSingle = Single.fromCallable(() -> PsiCashClient.getInstance(appContext));

        this.initialActionProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle.flatMap(PsiCashClient::getPsiCashModel)
                                    .map(PsiCashAccountResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashAccountResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashAccountResult.GetPsiCash.inFlight());
                        }
                );

        this.getPsiCashProcessor = actions ->
                actions.switchMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.refreshState(action.tunnelStateFlowable(), false)
                                        .flatMap(psiCashClient::getPsiCashModel))
                                .map(PsiCashAccountResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashAccountResult.GetPsiCash::failure)
                                .toObservable()
                                .startWith(PsiCashAccountResult.GetPsiCash.inFlight()));

        this.loginAccountProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.loginAccount(action.tunnelStateFlowable(),
                                        action.username(),
                                        action.password())
                                        .flatMap(lastTrackerMerge ->
                                                psiCashClient.refreshState(action.tunnelStateFlowable(), true)
                                                        .flatMap(psiCashClient::getPsiCashModel)
                                                        .map(psiCashModel -> new Pair<>(psiCashModel, lastTrackerMerge))))
                                .map(pair -> PsiCashAccountResult.AccountLogin.success(pair.first,
                                        pair.second))
                                .onErrorReturn(PsiCashAccountResult.AccountLogin::failure)
                                .toObservable()
                                .startWith(PsiCashAccountResult.AccountLogin.inFlight()));

        this.actionProcessor = actions ->
                actions.observeOn(Schedulers.io()).publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashAccountAction.InitialAction.class).compose(initialActionProcessor),
                                shared.ofType(PsiCashAccountAction.GetPsiCash.class).compose(getPsiCashProcessor),
                                shared.ofType(PsiCashAccountAction.LoginAccount.class).compose(loginAccountProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashAccountAction.InitialAction)
                                        && !(v instanceof PsiCashAccountAction.GetPsiCash)
                                        && !(v instanceof PsiCashAccountAction.LoginAccount)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
