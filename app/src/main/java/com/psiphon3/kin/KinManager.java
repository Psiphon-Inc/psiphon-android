package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psiphonlibrary.Utils;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.InsufficientKinException;

public class KinManager {
    private static final Double CONNECTION_COST = 1d;

    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final AccountHelper accountHelper;
    private final BehaviorRelay<Boolean> tunnelConnectedBehaviorRelay = BehaviorRelay.create();

    private PublishRelay<Boolean> kinOptInStateRelay = PublishRelay.create();

    KinManager(ClientHelper clientHelper, AccountHelper accountHelper) {
        this.clientHelper = clientHelper;
        this.accountHelper = accountHelper;
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
                                .andThen(clientHelper.getAccount())
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
        SettingsManager settingsManager = new SettingsManager();
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        AccountHelper accountHelper = new AccountHelper(serverCommunicator, settingsManager, environment.getPsiphonWalletAddress());

        return instance = new KinManager(clientHelper, accountHelper);
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
     * @param context the context
     * @return disposable subscription that monitors tunnel connection state and Kin opt in state,
     * creates and deletes accounts, and makes transactions on the remote blockchain.
     */
    public Disposable kinFlowDisposable(Context context) {
        // This is the main subscription that does all the work. It waits for exactly one
        // 'connected' event from the tunnel state relay, then checks the Kin opt in state,
        // runs Kin related operations according to that state and completes.
        return tunnelConnectedBehaviorRelay
                .filter(isConnected -> isConnected)
                .firstOrError()
                .flatMap(__ -> kinOptInStateRelay
                        // Once connected take exactly one Kin opt in state
                        .firstOrError())
                .doOnSuccess(isOptedIn -> Utils.MyLog.g("KinManager: user " + (isOptedIn ? "opted in" : "opted out")))
                // On opt outs schedule emptying and deletion of existing account.
                // On opt ins just get an account locally, either existing or new, do not try to
                // register the account on the blockchain at this point, AccountHelper::emptyAccount
                // and AccountHelper::transferOut will register the account on the blockchain if
                // needed.
                .flatMap(isOptedIn -> {
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
                .flatMapCompletable(isOptedIn -> {
                    if (isOptedIn) {
                        return clientHelper.accountMaybe()
                                .flatMapCompletable(kinAccount -> accountHelper.transferOut(context, kinAccount, CONNECTION_COST)
                                        .onErrorResumeNext(e -> chargeErrorHandler(context, kinAccount, e))
                                        .doOnError(e -> Utils.MyLog.g("KinManager: error charging for connection: " + e))
                                        .onErrorComplete());
                    } //else
                    return Completable.complete();
                })
                .subscribe();
    }

    public void onTunnelConnected(boolean isConnected) {
        tunnelConnectedBehaviorRelay.accept(isConnected);
    }

    public void onKinOptInState(Boolean isOptedIn) {
        kinOptInStateRelay.accept(isOptedIn);
    }
}
