package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccountTransactionHelperTest {

    private KinClient kinClient;
    private KinAccount account;
    private AccountTransactionHelper accountTransactionHelper;

    @Before
    public void setUp() throws InterruptedException, OperationFailedException {
        Environment env = Environment.TEST;
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        ServerCommunicator serverCommunicator = new ServerCommunicator(env.getFriendBotServerUrl());
        kinClient = new KinClient(context, env.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        kinClient.clearAllAccounts();

        account = AccountHelper.getAccount(kinClient, serverCommunicator).blockingGet();
        accountTransactionHelper = new AccountTransactionHelper(account, serverCommunicator, env.getPsiphonWalletAddress());

        // Setup isn't finished until the account is created
        Utils.ensureAccountCreated(account);
    }

    @After
    public void tearDown() {
        kinClient.clearAllAccounts();
    }

    @Test
    public void transferIn() throws OperationFailedException, InterruptedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        accountTransactionHelper.transferIn(100d);

        CountDownLatch latch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = account.addBalanceListener(balance -> {
            assertEquals(initialBalance + 100, balance.value().intValue());
            latch.countDown();
        });

        // Wait for the listener to fire
        latch.await(10, TimeUnit.SECONDS);
        assertEquals(initialBalance + 100, account.getBalanceSync().value().intValue());

        // TODO: Determine some way to check if the Psiphon wallet has been charged as well

        listenerRegistration.remove();
    }

    @Test
    public void transferOut() throws OperationFailedException, InterruptedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        assertEquals(AccountHelper.CREATE_ACCOUNT_FUND_AMOUNT.intValue(), initialBalance);

        CountDownLatch latch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = account.addBalanceListener(balance -> {
            // Use 101 because of the transfer fee
            assertEquals(initialBalance - 101, balance.value().intValue());
            latch.countDown();
        });

        accountTransactionHelper.transferOut(100d);

        // Wait for the listener to fire
        latch.await(10, TimeUnit.SECONDS);
        assertEquals(initialBalance - 101, account.getBalanceSync().value().intValue());

        // TODO: Determine some way to check if the Psiphon wallet has been charged as well

        listenerRegistration.remove();
    }
}