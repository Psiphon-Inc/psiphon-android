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

import android.content.Context;
import android.support.v4.content.LocalBroadcastManager;

import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.Arrays;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.Single;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
class PsiCashActionProcessorHolder {
    private static final String TAG = "PsiCashActionProcessor";
    private final ObservableTransformer<PsiCashAction.GetPsiCash, PsiCashResult> getPsiCashProcessor;
    private Single<PsiCashClient> psiCashClientSingle;
    private final ObservableTransformer<PsiCashAction.ClearErrorState, PsiCashResult.ClearErrorState> clearErrorStateProcessor;
    private final ObservableTransformer<PsiCashAction.InitialAction, PsiCashResult> initialActionProcessor;
    private final ObservableTransformer<PsiCashAction.MakeExpiringPurchase, PsiCashResult> makeExpiringPurchaseProcessor;
    private final ObservableTransformer<PsiCashAction.RemovePurchases, PsiCashResult> removePurchasesProcessor;

    final ObservableTransformer<PsiCashAction, PsiCashResult> actionProcessor;

    PsiCashActionProcessorHolder(Context appContext) {
        this.psiCashClientSingle = Single.fromCallable(() -> PsiCashClient.getInstance(appContext));

        this.clearErrorStateProcessor = actions -> actions.map(a -> PsiCashResult.ClearErrorState.success());

        this.initialActionProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle
                                    .flatMap(PsiCashClient::getPsiCashLocalSingle)
                                    .map(PsiCashResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashResult.GetPsiCash.inFlight());
                        }
                );

        this.getPsiCashProcessor = actions ->
                actions.switchMap(action -> {
                            return psiCashClientSingle
                                    .flatMap(psiCashClient -> psiCashClient.getPsiCashSingle(action.tunnelStateFlowable()))
                                    .map(PsiCashResult.GetPsiCash::success)
                                    .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                                    .toObservable()
                                    .startWith(PsiCashResult.GetPsiCash.inFlight());
                        }
                );

        this.makeExpiringPurchaseProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle
                                .flatMapObservable(psiCashClient ->
                                        psiCashClient.makeExpiringPurchase(action.tunnelStateFlowable(),
                                                action.distinguisher(),
                                                action.transactionClass(),
                                                action.expectedPrice()))
                                .map(r -> {
                                    if (r instanceof PsiCashModel.ExpiringPurchase) {
                                        // Store authorization from the purchase
                                        PsiCashLib.Purchase purchase = ((PsiCashModel.ExpiringPurchase) r).expiringPurchase();
                                        Utils.MyLog.g("PsiCash: storing new authorization of accessType: " +
                                                purchase.authorization.accessType + ", expires: " +
                                                Utils.getISO8601String(purchase.authorization.expires)
                                        );
                                        Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization.encoded);
                                        Authorization.storeAuthorization(appContext, authorization);

                                        // Send broadcast to restart the tunnel
                                        Utils.MyLog.g("PsiCash::onNewExpiringPurchase: send tunnel restart broadcast");
                                        android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
                                        LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);

                                        return PsiCashResult.ExpiringPurchase.success((PsiCashModel.ExpiringPurchase) r);
                                    } else if (r instanceof PsiCashModel.PsiCash) {
                                        return PsiCashResult.GetPsiCash.success((PsiCashModel.PsiCash) r);
                                    }
                                    throw new IllegalArgumentException("Unknown result: " + r);
                                })
                                .onErrorReturn(PsiCashResult.ExpiringPurchase::failure)
                                .startWith(PsiCashResult.ExpiringPurchase.inFlight()));

        this.removePurchasesProcessor = actions ->
                actions.flatMap(action ->
                        psiCashClientSingle.flatMap(psiCashClient -> psiCashClient.removePurchases(action.purchases()))
                                .map(PsiCashResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                                .toObservable()
                                .startWith(PsiCashResult.GetPsiCash.inFlight()));

        this.actionProcessor = actions ->
                actions.publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashAction.ClearErrorState.class).compose(clearErrorStateProcessor),
                                shared.ofType(PsiCashAction.MakeExpiringPurchase.class).compose(makeExpiringPurchaseProcessor),
                                shared.ofType(PsiCashAction.InitialAction.class).compose(initialActionProcessor),
                                shared.ofType(PsiCashAction.RemovePurchases.class).compose(removePurchasesProcessor),
                                shared.ofType(PsiCashAction.GetPsiCash.class).compose(getPsiCashProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashAction.ClearErrorState)
                                        && !(v instanceof PsiCashAction.MakeExpiringPurchase)
                                        && !(v instanceof PsiCashAction.InitialAction)
                                        && !(v instanceof PsiCashAction.RemovePurchases)
                                        && !(v instanceof PsiCashAction.GetPsiCash)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
