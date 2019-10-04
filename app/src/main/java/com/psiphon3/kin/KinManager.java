package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.InsufficientKinException;

public class KinManager {
    private static final Double CONNECTION_COST = 1d;

    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final AccountHelper accountHelper;
    private final ServerCommunicator serverCommunicator;
    private final SettingsManager settingsManager;

    private final PublishRelay<Object> chargeForConnectionPublishRelay = PublishRelay.create();

    KinManager(Context context, ClientHelper clientHelper, AccountHelper accountHelper, ServerCommunicator serverCommunicator, SettingsManager settingsManager) {
        this.clientHelper = clientHelper;
        this.accountHelper = accountHelper;
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;

        // This is the main subscription that monitors opt in state and reacts accordingly
        isOptedInObservable()
                .doOnNext(isOptedIn -> Utils.MyLog.g("KinManager: user " + (isOptedIn ? "opted in" : "opted out")))
                // On opt outs schedule emptying and deletion of existing account.
                // On opt ins just get an account locally, ether existing or new, do not try to
                // register the account on the blockchain at this point, AccountHelper::emptyAccount
                // and AccountHelper::transferOut will register the account on the blockchain if
                // needed when tunnel connects.
                //
                // Note the usage of switchMap, we want to cancel any downstream chain when we are
                // signaled a new opt in status.
                .switchMapSingle(isOptedIn -> {
                    Completable actionCompletable;
                    if (isOptedIn) {
                        actionCompletable = clientHelper.getAccount()
                                .doOnError(e -> Utils.MyLog.g("KinManager: error getting an account: " + e))
                                .ignoreElement();
                    } else {
                        actionCompletable = clientHelper.accountMaybe()
                                .flatMapCompletable(kinAccount -> accountHelper.emptyAccount(context, kinAccount)
                                        .andThen(Completable.fromRunnable(clientHelper::deleteAccount)))
                                .doOnError(e -> Utils.MyLog.g("KinManager: error emptying account: " + e));
                    }
                    return actionCompletable
                            // Pass through original opt in value
                            .toSingleDefault(isOptedIn)
                            .onErrorResumeNext(Single.just(isOptedIn));
                })
                .switchMap(isOptedIn -> {
                    if (isOptedIn) {
                        return chargeForConnectionPublishRelay
                                // transfer out 1 kin for connection
                                .flatMapSingle(__ -> clientHelper.getAccount())
                                .flatMapCompletable(kinAccount -> accountHelper.transferOut(context, kinAccount, CONNECTION_COST)
                                        .onErrorResumeNext(e -> chargeErrorHandler(context, kinAccount, e))
                                        .doOnError(e -> Utils.MyLog.g("KinManager: error charging " + CONNECTION_COST + " Kin(s) for connection: " + e))
                                        .onErrorComplete())
                                .toObservable();
                    } //else
                    return Observable.empty();
                })
                .subscribe();
    }

    private Completable chargeErrorHandler(Context context, KinAccount kinAccount, Throwable e) {
        // If it says we don't have enough kin, check if we should get new account
        // otherwise just let the error continue
        if (!(e instanceof InsufficientKinException)) {
            return Completable.error(e);
        }
        return accountHelper.getCurrentBalance(context, kinAccount)
                .onErrorReturnItem(BigDecimal.ZERO)
                .map(BigDecimal::doubleValue)
                .flatMapCompletable(balance -> {
                    if(balance <  CONNECTION_COST) {
                        return accountHelper.emptyAccount(context, kinAccount)
                                .andThen(Completable.fromAction(clientHelper::deleteAccount))
                                .andThen(Single.defer(clientHelper::getAccount))
                                .ignoreElement();
                    } //else
                    return Completable.error(e);
                });
    }

    static KinManager getInstance(Context context, Environment environment) {
        if (instance != null) {
            return instance;
        }

        // Use the application context to try and avoid memory leaks
        // TODO: Why doesn't this work?
        if (context.getApplicationContext() != null) {
            context = context.getApplicationContext();
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ClientHelper clientHelper = new ClientHelper(kinClient);
        SettingsManager settingsManager = new SettingsManager(context);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        AccountHelper accountHelper = new AccountHelper(serverCommunicator, settingsManager, environment.getPsiphonWalletAddress());

        return instance = new KinManager(context, clientHelper, accountHelper, serverCommunicator, settingsManager);
    }

    /**
     * @param context the context
     * @return the instance of the KinManager
     */
    public static KinManager getInstance(Context context) {
        // TODO: Switch to prod at some point
        return getInstance(context, Environment.TEST);
    }

    /**
     * @return an observable to check if the KinManager is ready.
     * Observable returns false when not ready yet or opted-out; true otherwise.
     */
    public Observable<Boolean> isOptedInObservable() {
        return settingsManager.isOptedInObservable();
    }

    public void chargeForNextConnection(boolean agreed) {
        if(agreed) {
            chargeForConnectionPublishRelay.accept(new Object());
        }
    }

    public void onTunnelConnectionState(TunnelState tunnelState) {
        serverCommunicator.onTunnelConnectionState(tunnelState);
    }

    public KinPermissionManager getPermissionManager() {
        return new KinPermissionManager(settingsManager);
    }

    public boolean isOptedIn(Context context) {
        return settingsManager.isOptedIn(context);
    }
}
