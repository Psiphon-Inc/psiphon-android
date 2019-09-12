package com.psiphon3.kin;

import android.content.Context;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final ServerCommunicator serverCommunicator;
    private final Environment environment;
    private final KinPermissionManager kinPermissionManager;

    private final Completable isReadyCompletable;

    private AccountHelper accountHelper;

    KinManager(Context context, ClientHelper clientHelper, ServerCommunicator serverCommunicator, Environment environment, KinPermissionManager kinPermissionManager) {
        this.clientHelper = clientHelper;
        this.serverCommunicator = serverCommunicator;
        this.environment = environment;
        this.kinPermissionManager = kinPermissionManager;

        isReadyCompletable = kinPermissionManager
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
                    accountHelper = new AccountHelper(account, serverCommunicator, environment.getPsiphonWalletAddress());
                })
                .doOnError(e -> {
                    // TODO: Do something?
                })
                .ignoreElement()
                .cache();

        isReadyCompletable.subscribe();
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

    public Completable isReady() {
        return isReadyCompletable;
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
     * Requests that amount of Kin gets transferred into the active accounts wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be given to the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferIn(Double amount) {
        if (accountHelper == null) {
            // TODO: Would an error be better here?
            return Completable.complete();
        }

        return accountHelper.transferIn(amount);
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
}
