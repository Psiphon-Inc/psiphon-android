package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.reactivex.observers.TestObserver;
import kin.sdk.AccountStatus;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ServerCommunicatorTest extends BaseKinTest {
    private ServerCommunicator serverCommunicator;
    private KinClient kinClient;

    @Before
    public void setUp() {
        initMocks();

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
        TestObserver<Void> test = serverCommunicator.createAccount(kinAccount.getPublicAddress()).test();

        // Check that it finished not because of timeout but because of onComplete
        assertTestCompleted(test);

        // Ensure that the account is now created
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(FUND_AMOUNT, kinAccount.getBalanceSync().value().doubleValue(), DELTA);

        // Try to create the account again, this should not work
        test = serverCommunicator.createAccount(kinAccount.getPublicAddress()).test();

        // Check that it finished not because of timeout but because of onError
        assertTestError(test);

        // Ensure these didn't change
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(FUND_AMOUNT, kinAccount.getBalanceSync().value().doubleValue(), DELTA);
    }

    @Test
    public void optOut() {
        // Literally just checking that the call doesn't throw an exception
        serverCommunicator.optOut();
    }

    @Test
    public void whitelistTransaction() {
        // TODO: How to test
    }
}