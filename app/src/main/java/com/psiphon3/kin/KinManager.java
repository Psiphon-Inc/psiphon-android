package com.psiphon3.kin;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

public class KinManager {
    private static KinManager mInstance;

    private final KinAccount mAccount;
    private final AccountTransactionHelper mTransactionHelper;

    KinManager(KinAccount account, AccountTransactionHelper transactionHelper) {
        mAccount = account;
        mTransactionHelper = transactionHelper;
    }

    @SuppressLint("CheckResult")
    private static Single<KinManager> getInstance(Context context, Environment environment) {
        if (mInstance != null) {
            return Single.just(mInstance);
        }

        return Single.create(emitter -> {
            try {
                // Set up base communication & helper classes
                KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
                ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());

                // Set up the data
                KinAccount account = AccountHelper.getAccount(kinClient, serverCommunicator).blockingGet();
                AccountTransactionHelper transactionHelper = new AccountTransactionHelper(account, serverCommunicator, environment.getPsiphonWalletAddress());

                // Create the instance
                mInstance = new KinManager(account, transactionHelper);
                emitter.onSuccess(mInstance);
            } catch (Exception e) {
                // TODO: Should we retry or anything or just consider this a failure?
                emitter.onError(e);
            }
        });
    }

    /**
     * Returns an instance of KinManager for production use.
     *
     * @param context the context of the calling activity
     * @return an instance of KinManager for the passed context
     */
    public static Single<KinManager> getInstance(Context context) {
        return getInstance(context, Environment.PRODUCTION);
    }

    /**
     * Returns an instance of KinManager for use in tests.
     *
     * @param context the context of the calling activity
     * @return a test instance of KinManager for the passed context
     */
    public static Single<KinManager> getTestInstance(Context context) {
        return getInstance(context, Environment.TEST);
    }

    /**
     * Adds listener to the balance of the active account, which will be called whenever the balance
     * is changed.
     *
     * @param listener the listener to register to account balance changes
     * @return the registration to allow it's removal
     */
    public ListenerRegistration addBalanceListener(EventListener<Balance> listener) {
        return mAccount.addBalanceListener(listener);
    }

    /**
     * @return the public wallet address of the active account
     */
    public String getWalletAddress() {
        return mAccount.getPublicAddress();
    }

    /**
     * @return the current balance of the active account
     */
    public BigDecimal getCurrentBalance() {
        try {
            return mAccount.getBalanceSync().value();
        } catch (OperationFailedException e) {
            // TODO: What should we do?
            Log.e("kin", "getCurrentBalance", e);
        }

        return null;
    }

    /**
     * Requests that amount of Kin gets transferred into the active accounts wallet.
     * Runs asynchronously.
     *
     * @param amount the amount to be given to the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferIn(Double amount) {
        return mTransactionHelper.transferInAsync(amount);
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs asynchronously.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferOut(Double amount) {
        return mTransactionHelper.transferOutAsync(amount);
    }
}
