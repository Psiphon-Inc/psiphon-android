package com.psiphon3.kin;

import android.content.Context;
import android.util.Log;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;

import java.math.BigDecimal;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final ServerCommunicator serverCommunicator;
    private final KinPermissionManager kinPermissionManager;
    private final Environment environment;

    private final PublishRelay<Boolean> isReadyPublishRelay;

    private final Flowable<AccountHelper> accountHelperFlowable;

    KinManager(Context context, ClientHelper clientHelper, ServerCommunicator serverCommunicator, KinPermissionManager kinPermissionManager, Environment environment) {
        this.clientHelper = clientHelper;
        this.serverCommunicator = serverCommunicator;
        this.kinPermissionManager = kinPermissionManager;
        this.environment = environment;

        // Create the relay
        isReadyPublishRelay = PublishRelay.create();
        isReadyPublishRelay.accept(false); // not strictly needed but doesn't hurt

        kinPermissionManager
                .getUsersAgreementToKin(context)
                .doOnSuccess(isReadyPublishRelay)
                .doOnError(e -> isReadyPublishRelay.accept(false))
                .subscribe();

        accountHelperFlowable = isReadyObservable()
                .flatMapMaybe(ready -> {
                    if (ready) {
                        return clientHelper
                                .getAccount()
                                .toMaybe();
                    } else {
                        return Maybe.empty();
                    }
                })
                .map(account -> clientHelper.getAccountHelper(account, environment))
                .toFlowable(BackpressureStrategy.LATEST)
                .repeat(1);
    }

    static KinManager getInstance(Context context, Environment environment) {
        if (instance != null) {
            return instance;
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        KinPermissionManager kinPermissionManager = new KinPermissionManager();

        return instance = new KinManager(context, clientHelper, serverCommunicator, kinPermissionManager, environment);
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
    public Observable<Boolean> isReadyObservable() {
        return isReadyPublishRelay.hide().distinctUntilChanged();
    }

    /**
     * @return the current balance of the active account
     */
    public Single<BigDecimal> getCurrentBalance() {
        return isReadyObservable()
                .lastOrError()
                .onErrorReturnItem(false)
                .flatMap(ready -> {
                    if (ready) {
                        return accountHelperFlowable
                                .lastElement()
                                .flatMapSingle(AccountHelper::getCurrentBalance);
                    } else {
                        // TODO: Would an error be better?
                        return Single.just(new BigDecimal(-1));
                    }
                })
                .subscribeOn(Schedulers.io())
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
        return isReadyObservable()
                .lastOrError()
                .onErrorReturnItem(false)
                .flatMapCompletable(ready -> {
                    if (ready) {
                        return accountHelperFlowable
                                .lastElement()
                                .flatMapCompletable(accountHelper -> accountHelper.transferOut(amount));
                    } else {
                        // TODO: Would an error be better?
                        return Completable.complete();
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Prompts the user if they're ok with spending 1 Kin to connect and charges if they are.
     *
     * @param context the context
     * @return a single which returns true on agreement to pay; otherwise false.
     */
    public Single<Boolean> chargeForConnection(Context context) {
        return isReadyObservable()
                .lastOrError()
                .flatMapMaybe(ready -> {
                    if (ready) {
                        return kinPermissionManager
                                .confirmPay(context)
                                .toMaybe();
                    } else {
                        return Maybe.empty();
                    }
                })
                .doOnSuccess(ready -> {
                    if (ready) {
                        accountHelperFlowable
                                .lastElement()
                                .flatMapCompletable(accountHelper -> accountHelper.transferOut(1d))
                                .subscribeOn(Schedulers.io())
                                .subscribe();
                    }
                })
                .toSingle(true);
    }

    /**
     * @param context the context
     * @return true if the user is opted in to Kin; false otherwise.
     */
    public boolean isOptedIn(Context context) {
        return kinPermissionManager.hasAgreedToKin(context);
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
                    // Only fire if they opted in
                    if (optedIn) {
                        isReadyPublishRelay.accept(true);
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
                        // TODO: Transfer excess funds back into our account?
                        clientHelper.deleteAccount();
                        serverCommunicator.optOut();
                        isReadyPublishRelay.accept(false);
                    }
                });
    }

    public void onTunnelConnectionState(TunnelState tunnelState) {
        // For now we just need to update the port so don't need any relay or such to hold it
        TunnelState.ConnectionData connectionData = tunnelState.connectionData();
        if (connectionData == null) {
            serverCommunicator.setProxyPort(0);
        } else {
            serverCommunicator.setProxyPort(connectionData.httpPort());
        }
    }
}
