package com.psiphon3.psicash.psicash;

import android.content.Context;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

class PsiCashActionProcessorHolder {
    private static final String TAG = "PsiCashActionProcessor";
    private final ExpiringPurchaseListener expiringPurchaseListener;
    private final ObservableTransformer<Action.ClearErrorState, Result.ClearErrorState> clearErrorStateProcessor;
    private final ObservableTransformer<Action.GetPsiCashRemote, Result> getPsiCashRemoteProcessor;
    private final ObservableTransformer<Action.GetPsiCashLocal, Result> getPsiCashLocalProcessor;
    private final ObservableTransformer<Action.MakeExpiringPurchase, Result> makeExpiringPurchaseProcessor;
    private final ObservableTransformer<Action.RemovePurchases, Result> removePurchasesProcessor;
    final ObservableTransformer<Action, Result> actionProcessor;

    public PsiCashActionProcessorHolder(Context context, ExpiringPurchaseListener listener) {
        this.expiringPurchaseListener = listener;

        this.clearErrorStateProcessor = actions -> actions.map(a -> Result.ClearErrorState.success());

        this.getPsiCashRemoteProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).getPsiCashRemote(action.connectionState())
                                .map(Result.GetPsiCash::success)
                                .onErrorReturn(Result.GetPsiCash::failure)
                                .startWith(Result.GetPsiCash.inFlight()));

        this.getPsiCashLocalProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).getPsiCashLocal()
                        .map(Result.GetPsiCash::success)
                        .onErrorReturn(Result.GetPsiCash::failure)
                        .startWith(Result.GetPsiCash.inFlight()));

        this.makeExpiringPurchaseProcessor = actions ->
                actions.flatMap(action ->
                        PsiCashClient.getInstance(context).makeExpiringPurchase(action.connectionState(), action.purchasePrice())
                                .map(r -> {
                                    if (r instanceof PsiCashModel.ExpiringPurchase) {
                                        if (expiringPurchaseListener != null) {
                                            expiringPurchaseListener.onNewExpiringPurchase(context, ((PsiCashModel.ExpiringPurchase) r).expiringPurchase());
                                        }
                                        return Result.ExpiringPurchase.success((PsiCashModel.ExpiringPurchase) r);
                                    } else if (r instanceof PsiCashModel.PsiCash) {
                                        return Result.GetPsiCash.success((PsiCashModel.PsiCash) r);
                                    }
                                    throw new IllegalArgumentException("Unknown result: " + r);
                                })
                                .onErrorReturn(Result.ExpiringPurchase::failure)
                                .startWith(Result.ExpiringPurchase.inFlight()));

        this.removePurchasesProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).removePurchases(action.purchases())
                        .map(Result.GetPsiCash::success)
                        .onErrorReturn(Result.GetPsiCash::failure)
                        .startWith(Result.GetPsiCash.inFlight()));

        this.actionProcessor = actions ->
                actions.publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(Action.ClearErrorState.class).compose(clearErrorStateProcessor),
                                shared.ofType(Action.MakeExpiringPurchase.class).compose(makeExpiringPurchaseProcessor),
                                shared.ofType(Action.GetPsiCashLocal.class).compose(getPsiCashLocalProcessor),
                                shared.ofType(Action.RemovePurchases.class).compose(removePurchasesProcessor),
                                shared.ofType(Action.GetPsiCashRemote.class).compose(getPsiCashRemoteProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof Action.ClearErrorState)
                                        && !(v instanceof Action.MakeExpiringPurchase)
                                        && !(v instanceof Action.GetPsiCashLocal)
                                        && !(v instanceof Action.GetPsiCashRemote)
                                        && !(v instanceof Action.RemovePurchases)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
