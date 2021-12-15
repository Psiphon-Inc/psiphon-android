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

import android.content.Context;

import com.psiphon3.psicash.PsiCashClient;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
class PsiCashStoreActionProcessorHolder {
    private final ObservableTransformer<PsiCashStoreAction.GetPsiCash, PsiCashStoreResult> getPsiCashProcessor;
    private final Single<PsiCashClient> psiCashClientSingle;
    private final ObservableTransformer<PsiCashStoreAction.InitialAction, PsiCashStoreResult> initialActionProcessor;
    private final ObservableTransformer<PsiCashStoreAction.MakeExpiringPurchase, PsiCashStoreResult> makeExpiringPurchaseProcessor;

    final ObservableTransformer<PsiCashStoreAction, PsiCashStoreResult> actionProcessor;

    PsiCashStoreActionProcessorHolder(Context appContext) {
        this.psiCashClientSingle = Single.fromCallable(() -> PsiCashClient.getInstance(appContext));

        this.initialActionProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle.flatMap(PsiCashClient::getPsiCashModel)
                                    .map(PsiCashStoreResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashStoreResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashStoreResult.GetPsiCash.inFlight());
                        }
                );

        this.getPsiCashProcessor = actions ->
                actions.switchMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient.refreshState(action.tunnelStateFlowable(), false)
                                        .flatMap(psiCashClient::getPsiCashModel))
                                .map(PsiCashStoreResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashStoreResult.GetPsiCash::failure)
                                .toObservable()
                                .startWith(PsiCashStoreResult.GetPsiCash.inFlight()));

        this.makeExpiringPurchaseProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient ->
                                psiCashClient
                                        .makeExpiringPurchase(action.tunnelStateFlowable(),
                                        action.distinguisher(),
                                        action.transactionClass(),
                                        action.expectedPrice())
                                        .andThen(psiCashClient.getPsiCashModel(true)))
                                .map(PsiCashStoreResult.MakeExpiringPurchase::success)
                                .onErrorReturn(PsiCashStoreResult.MakeExpiringPurchase::failure)
                                .toObservable()
                                .startWith(PsiCashStoreResult.MakeExpiringPurchase.inFlight()));

        this.actionProcessor = actions ->
                actions.observeOn(Schedulers.io()).publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashStoreAction.InitialAction.class).compose(initialActionProcessor),
                                shared.ofType(PsiCashStoreAction.MakeExpiringPurchase.class).compose(makeExpiringPurchaseProcessor),
                                shared.ofType(PsiCashStoreAction.GetPsiCash.class).compose(getPsiCashProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashStoreAction.InitialAction)
                                        && !(v instanceof PsiCashStoreAction.MakeExpiringPurchase)
                                        && !(v instanceof PsiCashStoreAction.GetPsiCash)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
