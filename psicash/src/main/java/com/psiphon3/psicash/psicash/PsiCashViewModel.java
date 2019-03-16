package com.psiphon3.psicash.psicash;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.NonNull;
import android.util.Log;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.mvibase.MviAction;
import com.psiphon3.psicash.mvibase.MviIntent;
import com.psiphon3.psicash.mvibase.MviResult;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.mvibase.MviViewModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psicash.util.TunnelState;

import java.util.Date;
import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;

public class PsiCashViewModel extends AndroidViewModel implements MviViewModel {
    private static final String DISTINGUISHER_1HR = "1hr";
    private static final String TAG = "PsiCashViewModel";
    private final PublishRelay<Intent> intentPublishRelay;
    private final Observable<PsiCashViewState> psiCashViewStateObservable;
    private final CompositeDisposable compositeDisposable;

    @NonNull
    private final PsiCashActionProcessorHolder psiCashActionProcessorHolder;

    PsiCashViewModel(@NonNull Application application, ExpiringPurchaseListener expiringPurchaseListener) {
        super(application);

        intentPublishRelay = PublishRelay.create();
        psiCashActionProcessorHolder = new PsiCashActionProcessorHolder(application, expiringPurchaseListener);
        psiCashViewStateObservable = compose();
        compositeDisposable = new CompositeDisposable();
    }

    /**
     * The Reducer is where {@link MviViewState}, that the {@link MviView} will use to
     * render itself, are created.
     * It takes the last cached {@link MviViewState}, the latest {@link MviResult} and
     * creates a new {@link MviViewState} by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the {@link MviResult}
     */

    private static final BiFunction<PsiCashViewState, Result, PsiCashViewState> reducer =
            (previousState, result) -> {
                Log.d(TAG, "reducer: result: " + result);
                PsiCashViewState.Builder stateBuilder = previousState.withState();

                if (result instanceof Result.GetPsiCash) {
                    Result.GetPsiCash getPsiCashResult = (Result.GetPsiCash) result;

                    PsiCashLib.PurchasePrice price = null;
                    Date nextPurchaseExpiryDate = null;

                    PsiCashModel.PsiCash model = getPsiCashResult.model();
                    int uiBalance = 0;
                    if (model != null) {
                        long balance = model.balance();
                        long reward = model.reward();
                        uiBalance = (int)(Math.floor((long) ((reward * 1e9 + balance) / 1e9)));

                        List<PsiCashLib.PurchasePrice> purchasePriceList = model.purchasePrices();
                        if(purchasePriceList != null) {
                            for (PsiCashLib.PurchasePrice p : purchasePriceList) {
                                if (p.distinguisher.equals(DISTINGUISHER_1HR)) {
                                    price = p;
                                }
                            }
                        }

                        PsiCashLib.Purchase nextExpiringPurchase = model.nextExpiringPurchase();
                        if (nextExpiringPurchase != null) {
                            nextPurchaseExpiryDate = nextExpiringPurchase.expiry;
                        }
                    }
                    switch (getPsiCashResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .uiBalance(uiBalance)
                                    .purchasePrice(price)
                                    .nextPurchaseExpiryDate(nextPurchaseExpiryDate)
                                    // after first success animate on consecutive balance changes
                                    .animateOnNextBalanceChange(true)
                                    .build();
                        case FAILURE:
                            return stateBuilder
                                    .error(getPsiCashResult.error())
                                    .build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .build();
                    }
                } else if (result instanceof Result.ClearErrorState) {
                    return stateBuilder
                            .error(null)
                            .build();
                } else if (result instanceof Result.ExpiringPurchase) {
                    Result.ExpiringPurchase purchaseResult = (Result.ExpiringPurchase) result;
                    Date nextPurchaseExpiryDate = null;

                    PsiCashModel.ExpiringPurchase model = purchaseResult.model();
                    if (model != null) {
                        nextPurchaseExpiryDate = model.expiringPurchase().expiry;
                    }

                    switch (purchaseResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .purchaseInFlight(false)
                                    .nextPurchaseExpiryDate(nextPurchaseExpiryDate)
                                    .build();
                        case FAILURE:
                            PsiCashViewState.Builder builder = stateBuilder
                                    .purchaseInFlight(false)
                                    .error(purchaseResult.error());
                            return builder.build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .purchaseInFlight(true)
                                    .build();
                    }
                }

                throw new IllegalArgumentException("Don't know this result " + result);
            };

    private Observable<PsiCashViewState> compose() {
        return intentPublishRelay
                .observeOn(Schedulers.computation())
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(psiCashActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(PsiCashViewState.idle(), reducer)
                // When a reducer just emits previousState, there's no reason to call render. In fact,
                // redrawing the UI in cases like this can cause junk (e.g. messing up snackbar animations
                // by showing the same snackbar twice in rapid succession).
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription
                // Useful when a View rebinds to the RewardedVideoViewModel after rotation.
                .replay(1)
                // Create the stream on creation without waiting for anyone to subscribe
                // This allows the stream to stay alive even when the UI disconnects and
                // match the stream's lifecycle to the RewardedVideoViewModel's one.
                .autoConnect(0);
    }

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private Observable<Action> actionFromIntent(MviIntent intent) {
        if (intent instanceof Intent.GetPsiCashRemote) {
            Intent.GetPsiCashRemote getPsiCashRemoteIntent = (Intent.GetPsiCashRemote) intent;
            final TunnelState status = getPsiCashRemoteIntent.connectionState();
            return Observable.just(Action.GetPsiCashRemote.create(status));
        }
        if (intent instanceof Intent.GetPsiCashLocal) {
            return Observable.just(Action.GetPsiCashLocal.create());
        }
        if (intent instanceof Intent.ClearErrorState) {
            return Observable.just(Action.ClearErrorState.create());
        }
        if (intent instanceof Intent.PurchaseSpeedBoost) {
            Intent.PurchaseSpeedBoost purchaseSpeedBoostIntent = (Intent.PurchaseSpeedBoost) intent;
            final PsiCashLib.PurchasePrice price = purchaseSpeedBoostIntent.purchasePrice();
            final TunnelState tunnelState = purchaseSpeedBoostIntent.connectionState();
            final boolean hasActiveBoost = purchaseSpeedBoostIntent.hasActiveBoost();
            return Observable.just(Action.MakeExpiringPurchase.create(tunnelState, price, hasActiveBoost));
        }
        if (intent instanceof Intent.RemovePurchases) {
            Intent.RemovePurchases removePurchases = (Intent.RemovePurchases) intent;
            final List<String> purchases = removePurchases.purchases();
            return Observable.just(Action.RemovePurchases.create(purchases));
        }
        throw new IllegalArgumentException("Do not know how to treat this intent " + intent);
    }

    @Override
    public void processIntents(Observable intents) {
        compositeDisposable.clear();
        compositeDisposable.add(intents
                .doOnNext(s -> Log.d(TAG, "processIntent: " + s))
                .subscribe(intentPublishRelay));
    }

    @Override
    public Observable<PsiCashViewState> states() {
        return psiCashViewStateObservable;
    }

}
