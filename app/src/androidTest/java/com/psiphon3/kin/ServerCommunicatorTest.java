package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import io.reactivex.observers.TestObserver;
import kin.sdk.AccountStatus;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerCommunicatorTest {

    private final String existingWalletPublicKey = "GCG5E6EELYTX2IA7FJTTRHC3DQHZTSYBUEN7H5YE5E7MJWR3GV6Q6KUP";
    private final String existingWalletPrivateKey = "SD4CQMGDNHI5MNITVNWLNOJ3KCLGFI4VF6W2ELWO4FTC4MIVRBI67IMF";

    private ServerCommunicator serverCommunicator;
    private KinClient kinClient;

    @Before
    public void setUp() {
        Environment env = Environment.TEST;
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        serverCommunicator = new ServerCommunicator(env.getFriendBotServerUrl());
        kinClient = new KinClient(context, env.getKinEnvironment(), Environment.PSIPHON_APP_ID);
    }

    @After
    public void tearDown() {
        kinClient.clearAllAccounts();
    }

    @Test
    public void createAccount() throws CreateAccountException, OperationFailedException {
        KinAccount kinAccount = kinClient.addAccount();
        assertNotNull(kinAccount.getPublicAddress());

        // Try and create the account
        TestObserver<Void> tester = serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertComplete();

        // Ensure that the account is now created
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(100, kinAccount.getBalanceSync().value().intValue());

        // Try to create the account again, this should not work
        tester = serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d).test();

        // Check that it finished not because of timeout but because of onError
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertError(throwable -> true);

        // Ensure these didn't change
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(100, kinAccount.getBalanceSync().value().intValue());
    }

    @Test
    public void fundAccount() throws CreateAccountException, OperationFailedException {
        // We have to have an account to verify the accounts balance
        KinAccount kinAccount = kinClient.addAccount();
        assertNotNull(kinAccount.getPublicAddress());

        // Try and create the account
        TestObserver<Void> tester = serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertComplete();

        // Ensure that the account is now created
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(100, kinAccount.getBalanceSync().value().intValue());

        // Now we can test
        tester = serverCommunicator.fundAccount(kinAccount.getPublicAddress(), 100d).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTrue(tester.awaitTerminalEvent(10, TimeUnit.SECONDS));
        tester.assertComplete();

        assertEquals(200, kinAccount.getBalanceSync().value().intValue());
    }
}