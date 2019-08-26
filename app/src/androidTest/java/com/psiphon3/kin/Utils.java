package com.psiphon3.kin;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kin.sdk.AccountStatus;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;

public class Utils {


    static void ensureAccountCreated(KinAccount account) throws InterruptedException, OperationFailedException {
        CountDownLatch accountCreationLatch = new CountDownLatch(1);
        account.addAccountCreationListener(new EventListener<Void>() {
            @Override
            public void onEvent(Void data) {
                accountCreationLatch.countDown();
            }
        });

        // Wait for the listener to fire
        accountCreationLatch.await(10, TimeUnit.SECONDS);
        assertEquals(AccountStatus.CREATED, account.getStatusSync());
    }
}
