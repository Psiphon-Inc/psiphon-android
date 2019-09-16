package com.psiphon3.kin;

import android.content.Context;
import android.util.Log;

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
    private final Environment environment;
    private final KinPermissionManager kinPermissionManager;

    private final ReplaySubject<Boolean> isReadyObservableSource;

    private AccountHelper accountHelper;

    KinManager(Context context, ClientHelper clientHelper, ServerCommunicator serverCommunicator, Environment environment, KinPermissionManager kinPermissionManager) {
        this.clientHelper = clientHelper;
        this.serverCommunicator = serverCommunicator;
        this.environment = environment;
        this.kinPermissionManager = kinPermissionManager;

        // Use a ReplaySubject with size 1, this means that it will only ever emit the latest on next
        isReadyObservableSource = ReplaySubject.createWithSize(1);
        kinPermissionManager
                .getUsersAgreementToKin(context)
                .flatMap(agreed -> {
                    Log.e("tst", "KinManager: " + agreed);
                    if (!agreed) {
                        return Single.never();
                    }

                    return clientHelper
                            .getAccount()
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread());
                })
                .doOnSuccess(account -> {
                    accountHelper = new AccountHelper(account, serverCommunicator, environment.getPsiphonWalletAddress());
                    isReadyObservableSource.onNext(true);
                })
                .doOnError(e -> {
                    isReadyObservableSource.onNext(false);
                })
                .subscribe();
    }

    public static KinManager getInstance(Context context, Environment environment) {
        if (instance != null) {
            return instance;
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        KinPermissionManager kinPermissionManager = new KinPermissionManager();

        return instance = new KinManager(context, clientHelper, serverCommunicator, environment, kinPermissionManager);
    }

    public Observable<Boolean> isReady() {
        return isReadyObservableSource;
    }

    /**
     * @return the current balance of the active account
     */
    public Single<BigDecimal> getCurrentBalance() {
        if (accountHelper == null) {
            // TODO: Would an error be better here?
            return Single.just(new BigDecimal(-1));
        }

        return accountHelper.getCurrentBalance();
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferOut(Double amount) {
        if (accountHelper == null) {
            // TODO: Would an error be better here?
            return Completable.complete();
        }

        return accountHelper.transferOut(amount);
    }

    public void deleteAccount() {
        clientHelper.deleteAccount();
    }

    public Single<Boolean> chargeForConnection(Context context) {
        if (accountHelper == null) {
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

    public boolean isOptedIn(Context context) {
        return kinPermissionManager.hasAgreedToKin(context);
    }

    public Single<Boolean> optIn(Context context) {
        return kinPermissionManager.optIn(context)
                .doOnSuccess(optedIn -> isReadyObservableSource.onNext(true));
    }

    public Single<Boolean> optOut(Context context) {
        return kinPermissionManager.optOut(context)
                .doOnSuccess(optedOut -> {
                    if (optedOut) {
                        // TODO: Transfer excess funds back into our account?
                        deleteAccount();
                        serverCommunicator.optOut();
                        isReadyObservableSource.onNext(false);
                    }
                });
    }
}
