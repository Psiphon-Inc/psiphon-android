package com.psiphon3.kin;

import android.content.Context;

import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager mInstance;

    private final KinAccount mAccount;
    private final AccountTransactionHelper mTransactionHelper;

    private KinManager(KinAccount account, AccountTransactionHelper transactionHelper) {
        mAccount = account;
        mTransactionHelper = transactionHelper;
    }

    private static KinManager getInstance(Context context, boolean test) {
        if (mInstance != null) {
            return mInstance;
        }

        // Set up base communication & helper classes
        Environment environment = test ? Environment.TEST : Environment.PRODUCTION;
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getServerUrl());

        // Set up the data
        KinAccount account = AccountHelper.getAccount(kinClient, serverCommunicator);
        AccountTransactionHelper transactionHelper = new AccountTransactionHelper(account, serverCommunicator, environment.getPsiphonWalletAddress());

        // Create the instance
        mInstance = new KinManager(account, transactionHelper);

        return mInstance;
    }

    /**
     * Returns an instance of KinManager for production use.
     *
     * @param context the context of the calling activity
     * @return an instance of KinManager for the passed context
     */
    public static KinManager getInstance(Context context) {
        return getInstance(context, false);
    }

    /**
     * Returns an instance of KinManager for use in tests.
     *
     * @param context the context of the calling activity
     * @return a test instance of KinManager for the passed context
     */
    public static KinManager getTestInstance(Context context) {
        return getInstance(context, true);
    }

    /**
     * Adds listener to the balance of the active account, which will be called whenever the balance
     * is changed.
     *
     * @param listener the listener to register to account balance changes
     */
    public void addBalanceListener(EventListener<Balance> listener) {
        mAccount.addBalanceListener(listener);
    }

    /**
     * @return the public wallet address of the active account
     */
    public String getWalletAddress() {
        return mAccount.getPublicAddress();
    }

    /**
     * Requests that amount of Kin gets transferred into the active accounts wallet.
     *
     * @param amount the amount to be given to the active account
     */
    public void transferIn(Double amount) {
        mTransactionHelper.transferIn(amount);
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     *
     * @param amount the amount to be taken from the active account
     */
    public void transferOut(Double amount) {
        mTransactionHelper.transferOut(amount);
    }
}
