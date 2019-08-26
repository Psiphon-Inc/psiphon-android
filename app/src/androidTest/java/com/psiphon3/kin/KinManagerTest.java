package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KinManagerTest {

    private KinAccount account;
    private KinManager kinManager;

    @Before
    public void setUp() throws OperationFailedException, InterruptedException {
        Environment env = Environment.TEST;
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        ServerCommunicator serverCommunicator = new ServerCommunicator(env.getFriendBotServerUrl());
        KinClient kinClient = new KinClient(context, env.getKinEnvironment(), Environment.PSIPHON_APP_ID);

        // Clear all accounts first to force the account to be freshly created
        kinClient.clearAllAccounts();
        account = AccountHelper.getAccount(kinClient, serverCommunicator);

        kinManager = KinManager.getTestInstance(context);

        // Setup isn't finished until the account is created
        Utils.ensureAccountCreated(account);
    }

    @Test
    public void getInstance() {
        // TODO: Determine why these are the same
        // assertNotEquals(kinManager, KinManager.getInstance(context));
    }

    @Test
    public void addBalanceListener() throws InterruptedException {
        final boolean[] balanceChanged = {false, false};
        CountDownLatch latch = new CountDownLatch(2);

        // Test with multiple listeners
        ListenerRegistration listenerRegistration1 = kinManager.addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance data) {
                balanceChanged[0] = true;
                latch.countDown();
            }
        });

        ListenerRegistration listenerRegistration2 = kinManager.addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance data) {
                balanceChanged[1] = true;
                latch.countDown();
            }
        });

        kinManager.transferIn(100d);

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(balanceChanged[0]);
        assertTrue(balanceChanged[1]);

        listenerRegistration1.remove();
        listenerRegistration2.remove();
    }

    @Test
    public void getWalletAddress() {
        assertEquals(account.getPublicAddress(), kinManager.getWalletAddress());
        // TODO: Is this the best way to do this?
    }

    @Test
    public void getCurrentBalance() throws OperationFailedException {
        BigDecimal currentBalance = kinManager.getCurrentBalance();
        assertEquals(account.getBalanceSync().value().doubleValue(), currentBalance.doubleValue(), 5d);
        // TODO: Is this the best way to do this?
    }

    @Test
    public void transferIn() throws InterruptedException {
        int initialBalance = kinManager.getCurrentBalance().intValue();
        final boolean[] balanceChanged = {false};
        CountDownLatch latch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = kinManager.addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance data) {
                assertEquals(initialBalance + 100, data.value().intValue());
                balanceChanged[0] = true;
                latch.countDown();
            }
        });

        kinManager.transferIn(100d);

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(balanceChanged[0]);

        listenerRegistration.remove();
    }

    @Test
    public void transferOut() throws InterruptedException {
        int initialBalance = kinManager.getCurrentBalance().intValue();
        final boolean[] balanceChanged = {false};
        CountDownLatch latch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = kinManager.addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance data) {
                // 101 because of transfer fee
                assertEquals(initialBalance - 101, data.value().intValue());
                balanceChanged[0] = true;
                latch.countDown();
            }
        });

        kinManager.transferOut(100d);

        latch.await(10, TimeUnit.SECONDS);
        assertTrue(balanceChanged[0]);

        listenerRegistration.remove();
    }
}