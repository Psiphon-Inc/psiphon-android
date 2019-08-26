package com.psiphon3.kin;

import android.content.Context;

import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;

public class KinManager {
    // TODO: Get real app id
    private static final String PSIPHON_APP_ID = "FAKE";

    private static KinManager mInstance;

    private final KinAccount mAccount;
    private final AccountTransactionHelper mTransactionHelper;

    private KinManager(KinAccount account, AccountTransactionHelper transactionHelper) {
        mAccount = account;
        mTransactionHelper = transactionHelper;
    }

    public static KinManager getInstance(Context context, boolean test) {
        if (mInstance != null) {
            return mInstance;
        }

        // Set up base communication & helper classes
        Environment environment = test ? Environment.TEST : Environment.PRODUCTION;
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getServerUrl());

        // Set up the data
        KinAccount account = AccountHelper.getAccount(kinClient, serverCommunicator);
        AccountTransactionHelper transactionHelper = new AccountTransactionHelper(account, serverCommunicator);

        // Create the instance
        mInstance = new KinManager(account, transactionHelper);

        return mInstance;
    }

    public void addBalanceListener(EventListener<Balance> listener) {
        mAccount.addBalanceListener(listener);
    }

    public String getWalletAddress() {
        return mAccount.getPublicAddress();
    }

    public void transferIn(Double amount) {
        mTransactionHelper.transferIn(amount);
    }

    public void transferOut(Double amount) {
        mTransactionHelper.transferOut(amount);
    }
}
