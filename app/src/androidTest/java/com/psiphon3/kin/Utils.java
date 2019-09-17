package com.psiphon3.kin;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kin.sdk.AccountStatus;
import kin.sdk.KinAccount;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;

public class Utils {

    static final double DELTA = 5d;
    static final double FUND_AMOUNT = 100d;
    static final BigDecimal FUND_AMOUNT_BD = new BigDecimal(FUND_AMOUNT);
    static final double TRANSFER_AMOUNT = 10d;
    static final double CONNECTION_TRANSFER_AMOUNT = 1d;
    static final int WAIT_TIME_S = 10;

    static void ensureAccountCreated(KinAccount account) throws InterruptedException, OperationFailedException {
        CountDownLatch accountCreationLatch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = account.addAccountCreationListener(data -> accountCreationLatch.countDown());

        // Wait for the listener to fire
        accountCreationLatch.await(Utils.WAIT_TIME_S, TimeUnit.SECONDS);
        assertEquals(AccountStatus.CREATED, account.getStatusSync());

        listenerRegistration.remove();
    }
}
