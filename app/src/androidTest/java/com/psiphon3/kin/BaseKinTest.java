package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.mockito.Mock;
import org.mockito.Spy;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.observers.TestObserver;
import kin.sdk.AccountStatus;
import kin.sdk.KinAccount;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.when;

class BaseKinTest {
    static final double DELTA = 5d;
    static final double FUND_AMOUNT = 100d;
    static final BigDecimal FUND_AMOUNT_BD = new BigDecimal(FUND_AMOUNT);
    static final double TRANSFER_AMOUNT = 10d;
    static final BigDecimal TRANSFER_AMOUNT_BD = new BigDecimal(TRANSFER_AMOUNT);
    static final double CONNECTION_TRANSFER_AMOUNT = 1d;
    static final int WAIT_TIME_S = 10;
    static final Environment env = Environment.TEST;

    @Spy
    SharedPreferences sharedPreferences;
    @Mock
    Context context;

    void initMocks() {
        mockitoSession().initMocks(this);

        sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);
    }

    static void ensureAccountCreated(KinAccount account) throws InterruptedException, OperationFailedException {
        CountDownLatch accountCreationLatch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = account.addAccountCreationListener(data -> accountCreationLatch.countDown());

        // wait for the listener to fire
        accountCreationLatch.await(BaseKinTest.WAIT_TIME_S, TimeUnit.SECONDS);
        assertEquals(AccountStatus.CREATED, account.getStatusSync());

        listenerRegistration.remove();
    }

    static void assertTestCompleted(TestObserver test) {
        // await terminal event returns true iff it didn't run out of time
        assertTrue(test.awaitTerminalEvent(BaseKinTest.WAIT_TIME_S, TimeUnit.SECONDS));
        test.assertComplete();
    }

    static void assertTestError(TestObserver test) {
        // await terminal event returns true iff it didn't run out of time
        assertTrue(test.awaitTerminalEvent(BaseKinTest.WAIT_TIME_S, TimeUnit.SECONDS));
        test.assertError(throwable -> true);
    }
}
