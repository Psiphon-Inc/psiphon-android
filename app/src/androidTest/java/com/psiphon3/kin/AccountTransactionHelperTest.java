package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import io.reactivex.observers.TestObserver;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
    public void transferIn() throws OperationFailedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        TestObserver<Void> tester = accountTransactionHelper.transferIn(100d).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertComplete();

        // Check the balance has updated
        assertEquals(initialBalance + 100, account.getBalanceSync().value().intValue());

        // TODO: Determine some way to check if the Psiphon wallet has been changed as well
    }

    @Test
    public void transferOut() throws OperationFailedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        TestObserver<Void> tester = accountTransactionHelper.transferOut(100d).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertComplete();

        // Check the balance has updated. Use 101 because rounded transfer fee is 1 kin
        assertEquals(initialBalance - 101, account.getBalanceSync().value().intValue());

        // TODO: Determine some way to check if the Psiphon wallet has been changed as well
    }
}