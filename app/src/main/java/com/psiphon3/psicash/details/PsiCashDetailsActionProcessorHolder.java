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

import android.content.Context;

import com.psiphon3.psicash.PsiCashClient;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
class PsiCashDetailsActionProcessorHolder {
    private final ObservableTransformer<PsiCashDetailsAction.GetPsiCash, PsiCashDetailsResult> getPsiCashProcessor;
    private final Single<PsiCashClient> psiCashClientSingle;
    private final ObservableTransformer<PsiCashDetailsAction.InitialAction, PsiCashDetailsResult> initialActionProcessor;
    private final ObservableTransformer<PsiCashDetailsAction.RemovePurchases, PsiCashDetailsResult> removePurchasesProcessor;

    final ObservableTransformer<PsiCashDetailsAction, PsiCashDetailsResult> actionProcessor;

    PsiCashDetailsActionProcessorHolder(Context appContext) {
        this.psiCashClientSingle = Single.fromCallable(() -> PsiCashClient.getInstance(appContext));

        this.initialActionProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle.flatMap(PsiCashClient::getPsiCashModel)
                                    .map(PsiCashDetailsResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashDetailsResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashDetailsResult.GetPsiCash.inFlight());
                        }
                );

        this.getPsiCashProcessor = actions ->
                actions.switchMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.refreshState(action.tunnelStateFlowable(), false)
                                        .flatMap(psiCashClient::getPsiCashModel))
                                .map(PsiCashDetailsResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashDetailsResult.GetPsiCash::failure)
                                .toObservable()
                                .startWith(PsiCashDetailsResult.GetPsiCash.inFlight()));

        this.removePurchasesProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.removePurchases(action.purchases())
                                        .andThen(psiCashClient.getPsiCashModel()))
                                .map(PsiCashDetailsResult.RemovePurchase::success)
                                .onErrorReturn(PsiCashDetailsResult.RemovePurchase::failure)
                                .toObservable()
                                .startWith(PsiCashDetailsResult.RemovePurchase.inFlight()));

        this.actionProcessor = actions ->
                actions.observeOn(Schedulers.io()).publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashDetailsAction.InitialAction.class).compose(initialActionProcessor),
                                shared.ofType(PsiCashDetailsAction.RemovePurchases.class).compose(removePurchasesProcessor),
                                shared.ofType(PsiCashDetailsAction.GetPsiCash.class).compose(getPsiCashProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashDetailsAction.InitialAction)
                                        && !(v instanceof PsiCashDetailsAction.RemovePurchases)
                                        && !(v instanceof PsiCashDetailsAction.GetPsiCash)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
