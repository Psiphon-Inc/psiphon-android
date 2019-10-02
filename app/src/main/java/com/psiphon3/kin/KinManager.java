package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;

import io.reactivex.Completable;
import io.reactivex.Observable;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final AccountHelper accountHelper;
    private final ServerCommunicator serverCommunicator;
    private final SettingsManager settingsManager;

    private final PublishRelay<Boolean> chargeForConnectionPublishRelay;

    KinManager(Context context, ClientHelper clientHelper, AccountHelper accountHelper, ServerCommunicator serverCommunicator, SettingsManager settingsManager) {
        this.clientHelper = clientHelper;
        this.accountHelper = accountHelper;
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;

        chargeForConnectionPublishRelay = PublishRelay.create();

        isOptedInObservable()
                // only observe opt-ins
                .filter(optedIn -> optedIn)
                // if they opted in get an account
                .flatMapSingle(__ -> clientHelper.getAccount())
                // pass the new account to account helper
                .doOnNext(account -> accountHelper.onNewAccount(context, account))
                .subscribe();

        isOptedInObservable()
                // skip the first emission to avoid starting opted out (so this triggers), opting in,
                // and having the waiting for registration fire once the new opted in account is created
                .skip(1)
                // only observe opts out
                .filter(optedIn -> !optedIn)
                // delete the account and transfer its funds out
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
        if (context.getApplicationContext() != null) {
            context = context.getApplicationContext();
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        SettingsManager settingsManager = new SettingsManager(context);
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
        return settingsManager.isOptedInObservable();
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

    public void chargeForNextConnection(boolean agreed) {
        chargeForConnectionPublishRelay.accept(agreed);
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
