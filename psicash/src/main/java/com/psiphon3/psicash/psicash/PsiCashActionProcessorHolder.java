package com.psiphon3.psicash.psicash;

import android.content.Context;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

public class PsiCashActionProcessorHolder {
    private static final String TAG = "PsiCashActionProcessor";
    private Context context;

    private ObservableTransformer<Action.ClearErrorState, Result.ClearErrorState>
            clearErrorStateProcessor = actions -> actions.map(a -> Result.ClearErrorState.success());

    private ObservableTransformer<Action.GetPsiCash, Result>
            getPsiCashProcessor = actions ->
            // only react to distinct connection status actions
            actions.distinctUntilChanged()
                    .switchMap(action -> PsiCashClient.getInstance(context).getPsiCash(action.connectionStatus())
                            .map(Result.GetPsiCash::success)
                            .onErrorReturn(Result.GetPsiCash::failure)
                            .startWith(Result.GetPsiCash.inFlight()));

    private ObservableTransformer<Action.GetPsiCashLocal, Result>
            getPsiCashLocalProcessor = actions ->
            actions.flatMap(action -> PsiCashClient.getInstance(context).getPsiCashLocal()
                    .map(Result.GetPsiCash::success)
                    .onErrorReturn(Result.GetPsiCash::failure)
                    .startWith(Result.GetPsiCash.inFlight()));

    private ObservableTransformer<Action.MakeExpiringPurchase, Result>
            makeExpiringPurchaseProcessor = actions ->
            actions.flatMap(action ->
                    PsiCashClient.getInstance(context).makeExpiringPurchase(action.connectionStatus(), action.purchasePrice())
                            .map(r -> {
                                if (r instanceof PsiCashModel.ExpiringPurchase) {
                                    return Result.ExpiringPurchase.success((PsiCashModel.ExpiringPurchase) r);
                                } else if (r instanceof PsiCashModel.PsiCash) {
                                    return Result.GetPsiCash.success((PsiCashModel.PsiCash) r);
                                }
                                throw new IllegalArgumentException("Unknown result: " + r);
                            })
                            .onErrorReturn(Result.ExpiringPurchase::failure)
                            .startWith(Result.ExpiringPurchase.inFlight()));

    ObservableTransformer<Action, Result> actionProcessor =
            actions -> actions.publish(shared -> Observable.merge(
                    shared.ofType(Action.ClearErrorState.class).compose(clearErrorStateProcessor),
                    shared.ofType(Action.MakeExpiringPurchase.class).compose(makeExpiringPurchaseProcessor),
                    shared.ofType(Action.GetPsiCashLocal.class).compose(getPsiCashLocalProcessor),
                    shared.ofType(Action.GetPsiCash.class).compose(getPsiCashProcessor))
                    .mergeWith(
                            // Error for not implemented actions
                            shared.filter(v -> !(v instanceof Action.ClearErrorState)
                                    && !(v instanceof Action.MakeExpiringPurchase)
                                    && !(v instanceof Action.GetPsiCashLocal)
                                    && !(v instanceof Action.GetPsiCash)
                            )
                                    .flatMap(w -> Observable.error(
                                            new IllegalArgumentException("Unknown action: " + w)))));

    public PsiCashActionProcessorHolder(Context context) {
        this.context = context;
    }
}
