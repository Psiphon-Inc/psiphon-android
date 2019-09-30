package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final AccountHelper accountHelper;
    private final ServerCommunicator serverCommunicator;
    private final SettingsManager settingsManager;
    private final KinPermissionManager kinPermissionManager;
    private final Environment environment;

    private final BehaviorRelay<Boolean> isOptedInBehaviorRelay;
    private final PublishRelay<Boolean> chargeForConnectionPublishRelay;

    KinManager(Context context, ClientHelper clientHelper, AccountHelper accountHelper, ServerCommunicator serverCommunicator, SettingsManager settingsManager, KinPermissionManager kinPermissionManager, Environment environment) {
        this.clientHelper = clientHelper;
        this.accountHelper = accountHelper;
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;
        this.kinPermissionManager = kinPermissionManager;
        this.environment = environment;

        isOptedInBehaviorRelay = BehaviorRelay.create();
        chargeForConnectionPublishRelay = PublishRelay.create();

        kinPermissionManager
                // start with the users opt-in/out
                .getUsersAgreementToKin(context)
                // emit that into isOptedIn
                .doOnSuccess(isOptedInBehaviorRelay)
                .subscribe();

        isOptedInObservable()
                // if they opted in get an account
                .flatMapMaybe(optedIn -> {
                    if (optedIn) {
                        return clientHelper
                                .getAccount()
                                .toMaybe();
                    } else {
                        return Maybe.empty();
                    }
                })
                .doOnNext(account -> accountHelper.onNewAccount(context, account))
                .subscribe();

        isOptedInObservable()
                // skip the first emission to avoid starting opted out (so this triggers), opting in,
                // and having the waiting for registration fire once the new opted in account is created
                .skip(1)
                // only observe opts out
                .filter(optedIn -> !optedIn)
                // delete the account and transfer it's funds out
                .flatMapCompletable(__ -> accountHelper.delete(context)
                        .doOnComplete(clientHelper::deleteAccount))
                .subscribe();

        chargeForConnectionPublishRelay
                // take only distinct events to prevent spam
                .distinctUntilChanged()
                // only take requests to charge them
                .filter(chargeForConnection -> chargeForConnection)
                // transfer out 1 kin, which on complete allows for a new charge for connection
                .flatMapCompletable(__ -> transferOut(1d)
                        .doOnComplete(() -> chargeForConnectionPublishRelay.accept(false)))
                .subscribe();

        // start with this so distinct until changed will work
        chargeForConnectionPublishRelay.accept(false);
    }

    static KinManager getInstance(Context context, Environment environment) {
        if (instance != null) {
            return instance;
        }

        // Use the application context to try and avoid memory leaks
        // TODO: Why doesn't this work?
        // if (context.getApplicationContext() != null) {
        //     context = context.getApplicationContext();
        // }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        SettingsManager settingsManager = new SettingsManager();
        AccountHelper accountHelper = new AccountHelper(serverCommunicator, settingsManager, environment.getPsiphonWalletAddress());
        KinPermissionManager kinPermissionManager = new KinPermissionManager(settingsManager);

        return instance = new KinManager(context, clientHelper, accountHelper, serverCommunicator, settingsManager, kinPermissionManager, environment);
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
     * @return the instance of the KinManager for testing
     */
    public static KinManager getTestInstance(Context context) {
        return getInstance(context, Environment.TEST);
    }

    /**
     * @return an observable to check if the KinManager is ready.
     * Observable returns false when not ready yet or opted-out; true otherwise.
     */
    public Observable<Boolean> isOptedInObservable() {
        return isOptedInBehaviorRelay
                .distinctUntilChanged()
                .hide()
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferOut(Double amount) {
        return isOptedInObservable()
                .firstOrError()
                .filter(optedIn -> optedIn)
                .flatMapCompletable(__ -> accountHelper.transferOut(amount))
                .onErrorComplete();
    }

    /**
     * Prompts the user if they're ok with spending 1 Kin to connect and charges if they are.
     *
     * @param context the context
     * @return a single which returns true on agreement to pay; otherwise false.
     */
    public Single<Boolean> confirmConnectionPay(Context context) {
        return isOptedInObservable()
                // no item, default to opted out
                .first(false)
                // map people opted out to false
                // opted out to check if they want to pay
                .flatMap(optedIn -> {
                    if (optedIn) {
                        return kinPermissionManager.confirmPay(context);
                    } else {
                        return Single.just(false);
                    }
                });
    }

    public void chargeForNextConnection(boolean agreed) {
        chargeForConnectionPublishRelay.accept(agreed);
    }

    /**
     * @param context the context
     * @return true if the user is opted in to Kin; false otherwise.
     */
    public boolean isOptedIn(Context context) {
        return settingsManager.hasAgreedToKin(context);
    }

    /**
     * Raises the dialog to opt in to Kin.
     *
     * @param context the context
     * @return a single returning true if the user has opted in to Kin; otherwise false.
     */
    public Single<Boolean> optIn(Context context) {
        return kinPermissionManager.optIn(context)
                .doOnSuccess(optedIn -> {
                    if (optedIn) {
                        isOptedInBehaviorRelay.accept(true);
                    }
                });
    }

    /**
     * Raises the dialog to opt out of Kin.
     *
     * @param context the context
     * @return a single returning true if the user has opted out of Kin; otherwise false.
     */
    public Single<Boolean> optOut(Context context) {
        return kinPermissionManager.optOut(context)
                .doOnSuccess(optedOut -> {
                    if (optedOut) {
                        isOptedInBehaviorRelay.accept(false);
                    }
                });
    }

    public void onTunnelConnectionState(TunnelState tunnelState) {
        serverCommunicator.onTunnelConnectionState(tunnelState);
    }
}
