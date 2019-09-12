package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.observers.TestObserver;
import kin.sdk.Balance;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
        account = ClientHelper.getAccount(kinClient, serverCommunicator).blockingGet();
        assertNotNull(account);
        AccountHelper transactionHelper = new AccountHelper(account, serverCommunicator, env.getPsiphonWalletAddress());

        kinManager = new KinManager(context, account, kinClient, transactionHelper, kinPermissionManager);

        // Setup isn't finished until the account is created
        Utils.ensureAccountCreated(account);
    }

    @Test
    public void addBalanceListener() throws InterruptedException {
        final Balance[] balances = {null, null};
        CountDownLatch latch = new CountDownLatch(2);

        // Test with multiple listeners
        ListenerRegistration listenerRegistration1 = kinManager.addBalanceListener(data -> {
            balances[0] = data;
            latch.countDown();
        });

        ListenerRegistration listenerRegistration2 = kinManager.addBalanceListener(data -> {
            balances[1] = data;
            latch.countDown();
        });

        kinManager.transferIn(Utils.TRANSFER_AMOUNT).subscribe();

        // Make sure the latch didn't time out and that the balances returned are the same
        assertTrue(latch.await(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        assertNotNull(balances[0]);
        assertNotNull(balances[1]);
        assertEquals(balances[0].value(5), balances[1].value(5));

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
        BigDecimal currentBalance = kinManager.getCurrentBalanceSync();
        assertEquals(account.getBalanceSync().value().doubleValue(), currentBalance.doubleValue(), Utils.DELTA);
        // TODO: Is this the best way to do this?
    }

    @Test
    public void transferIn() throws OperationFailedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        TestObserver<Void> tester = kinManager.transferIn(Utils.TRANSFER_AMOUNT).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        tester.assertComplete();

        // Check the balance has updated
        assertEquals(initialBalance + Utils.TRANSFER_AMOUNT, account.getBalanceSync().value().doubleValue(), Utils.DELTA);

        // TODO: Determine some way to check if the Psiphon wallet has been changed as well
    }

    @Test
    public void transferOut() throws OperationFailedException {
        // Get the initial balance. OK to use an int because we won't use higher precision stuff for the transfers
        int initialBalance = account.getBalanceSync().value().intValue();
        TestObserver<Void> tester = kinManager.transferOut(Utils.TRANSFER_AMOUNT).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        tester.assertComplete();

        // Check the balance has updated
        assertEquals(initialBalance - Utils.TRANSFER_AMOUNT, account.getBalanceSync().value().doubleValue(), Utils.DELTA);

        // TODO: Determine some way to check if the Psiphon wallet has been changed as well
    }
}