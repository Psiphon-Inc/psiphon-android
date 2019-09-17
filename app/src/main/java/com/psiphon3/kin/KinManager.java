package com.psiphon3.kin;

import android.content.Context;

import com.psiphon3.TunnelState;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.ReplaySubject;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final ServerCommunicator serverCommunicator;
    private final KinPermissionManager kinPermissionManager;
    private final Environment environment;

    private final ReplaySubject<Boolean> isReadyObservableSource;

    private AccountHelper accountHelper;

    KinManager(Context context, ClientHelper clientHelper, ServerCommunicator serverCommunicator, KinPermissionManager kinPermissionManager, Environment environment) {
        this.clientHelper = clientHelper;
        this.serverCommunicator = serverCommunicator;
        this.kinPermissionManager = kinPermissionManager;
        this.environment = environment;

        // Use a ReplaySubject with size 1, this means that it will only ever emit the latest on next
        // Start with false
        isReadyObservableSource = ReplaySubject.createWithSize(1);
        kinPermissionManager
                .getUsersAgreementToKin(context)
                .flatMap(agreed -> {
                    if (!agreed) {
                        return Single.never();
                    }

                    return clientHelper
                            .getAccount()
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread());
                })
                .doOnSuccess(account -> {
                    accountHelper = clientHelper.getAccountHelper(account, environment);
                    isReadyObservableSource.onNext(true);
                })
                .doOnError(e -> {
                    isReadyObservableSource.onNext(false);
                })
                .subscribe();
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
     * @return false when not ready yet or opted-out; true otherwise.
     */
    public boolean isReady() {
        return isReadyObservableSource.getValue();
    }

    /**
     * @return an observable to check if the KinManager is ready.
     * Observable returns false when not ready yet or opted-out; true otherwise.
     */
    public Observable<Boolean> isReadyObservable() {
        return isReadyObservableSource;
    }

    /**
     * @return the current balance of the active account
     */
    public Single<BigDecimal> getCurrentBalance() {
        if (!isReady()) {
            // TODO: Would an error be better here?
            return Single.just(new BigDecimal(-1));
        }

        return accountHelper.getCurrentBalance()
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
        if (!isReady()) {
            // TODO: Would an error be better here?
            return Completable.complete();
        }

        return accountHelper.transferOut(amount);
    }

    /**
     * Prompts the user if they're ok with spending 1 Kin to connect and charges if they are.
     *
     * @param context the context
     * @return a single which returns true on agreement to pay; otherwise false.
     */
    public Single<Boolean> chargeForConnection(Context context) {
        if (!isReady()) {
            // If we aren't ready yet just let them connect
            return Single.just(true);
        }

        return kinPermissionManager
                .confirmPay(context)
                .doOnSuccess(agreed -> {
                    if (agreed) {
                        transferOut(1d).subscribeOn(Schedulers.io()).subscribe();
                    }
                });
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
                    if (optedIn) {
                        clientHelper
                                .getAccount()
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .doOnSuccess(account -> {
                                    accountHelper = clientHelper.getAccountHelper(account, environment);
                                    isReadyObservableSource.onNext(true);
                                })
                                .subscribe();
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
                        accountHelper = null; // Not really needed but might as well
                        clientHelper.deleteAccount();
                        serverCommunicator.optOut();
                        isReadyObservableSource.onNext(false);
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
