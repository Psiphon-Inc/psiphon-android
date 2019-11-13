package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import kin.sdk.KinClient;
import kin.sdk.exception.InsufficientKinException;

public class KinManager {
    private static final Double CONNECTION_COST = 1d;
    private final Environment environment = Environment.PRODUCTION;
    private final BehaviorRelay<Boolean> tunnelConnectedBehaviorRelay = BehaviorRelay.create();
    private final BehaviorRelay<Boolean> kinOptInStateBehaviorRelay = BehaviorRelay.create();

    /**
     * @param context the context
     * @return disposable subscription that monitors tunnel connection state and Kin opt in state,
     * creates and deletes accounts, and makes transactions on the remote blockchain.
     * This subscription completes when the user opts out or a Kin operation error occurs.
     */
    public Disposable kinFlowDisposable(Context context) {
        final KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        final ClientHelper clientHelper = new ClientHelper(kinClient);
        final SettingsManager settingsManager = new SettingsManager();
        final ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getKinApplicationServerUrl());
        final AccountHelper accountHelper = new AccountHelper(serverCommunicator, settingsManager, environment.getPsiphonWalletAddress());
        AtomicBoolean hasBeenCharged = new AtomicBoolean(false);

        return tunnelConnectedBehaviorRelay
                .distinctUntilChanged()
                .switchMap(isConnected -> {
                    if (!isConnected) {
                        return Observable.empty();
                    }
                    // Tunnel is connected, we can perform Kin operations now.
                    return kinOptInStateBehaviorRelay
                            .distinctUntilChanged()
                            .concatMapMaybe(isOptedIn -> {
                                Utils.MyLog.g("KinManager: user " + (isOptedIn ? "opted in" : "opted out"));
                                // On opt in try and charge connection fee.
                                if (isOptedIn) {
                                    // Charge only once per subscription run.
                                    if(hasBeenCharged.compareAndSet(true, true)) {
                                        return Maybe.empty();
                                    }
                                    return clientHelper.getAccount()
                                            .flatMapCompletable(kinAccount -> accountHelper.transferOut(context, kinAccount, CONNECTION_COST)
                                                    .onErrorResumeNext(e -> {
                                                        // If it says we don't have enough kin then empty current account, get a new one and try
                                                        // charging for connection again, otherwise just let the error continue
                                                        if (e instanceof InsufficientKinException) {
                                                            return accountHelper.emptyAccount(context, kinAccount)
                                                                    .andThen(Completable.fromAction(clientHelper::deleteAccount))
                                                                    .andThen(clientHelper.getAccount())
                                                                    .flatMapCompletable(newAccount -> accountHelper.transferOut(context, newAccount, CONNECTION_COST));
                                                        } //else
                                                        return Completable.error(e);
                                                    }))
                                            .toMaybe();
                                }
                                // On opt out try emptying the account and emit any object to cancel the subscription.
                                return clientHelper.accountMaybe()
                                        .flatMapCompletable(kinAccount -> accountHelper.emptyAccount(context, kinAccount)
                                                .andThen(Completable.fromRunnable(clientHelper::deleteAccount)))
                                        .doOnError(e -> Utils.MyLog.g("KinManager: error emptying account: " + e))
                                        .toSingleDefault(new Object())
                                        .toMaybe();
                            });
                })
                .firstOrError()
                .ignoreElement()
                .doOnError(e -> Utils.MyLog.g("KinManager: kin flow error: " + e))
                .onErrorComplete()
                .doOnComplete(() -> Utils.MyLog.g("KinManager: completed kin flow"))
                .subscribe();
    }

    public void onTunnelConnected(boolean isConnected) {
        tunnelConnectedBehaviorRelay.accept(isConnected);
    }

    public void onKinOptInState(Boolean isOptedIn) {
        kinOptInStateBehaviorRelay.accept(isOptedIn);
    }
}
