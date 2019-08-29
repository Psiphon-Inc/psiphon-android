package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kin.sdk.AccountStatus;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.CreateAccountException;
import kin.sdk.exception.OperationFailedException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
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
    public void createAccount() throws CreateAccountException, OperationFailedException, InterruptedException {
        KinAccount kinAccount = kinClient.addAccount();
        CountDownLatch latch1 = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = kinAccount.addAccountCreationListener(data -> {
            try {
                assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
                assertEquals(100, kinAccount.getBalanceSync().value().intValue());
                latch1.countDown();
            } catch (OperationFailedException e) {
                fail(e.getMessage());
            }
        });

        serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d, new Callbacks<String>() {
            @Override
            public void onSuccess(String result) {
                // Make sure the result isn't empty or null
                assertNotNull(result);
                assertNotEquals("", result);
            }

            @Override
            public void onFailure(Exception e) {
                fail("unable to create account - " + e.getMessage());
            }
        });

        latch1.await(10, TimeUnit.SECONDS);

        // Try to create the account again, this should not work
        CountDownLatch latch2 = new CountDownLatch(1);
        serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d, new Callbacks<String>() {
            @Override
            public void onSuccess(String result) {
                fail("successfully re-created an account");
            }

            @Override
            public void onFailure(Exception e) {
                latch2.countDown();
            }
        });

        latch2.await(10, TimeUnit.SECONDS);

        // Ensure these didn't change
        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());
        assertEquals(100, kinAccount.getBalanceSync().value().intValue());

        listenerRegistration.remove();
    }

    @Test
    public void fundAccount() throws CreateAccountException, OperationFailedException, InterruptedException {
        // We have to have an account to verify the accounts balance
        KinAccount kinAccount = kinClient.addAccount();
        CountDownLatch latch1 = new CountDownLatch(1);
        serverCommunicator.createAccount(kinAccount.getPublicAddress(), 100d, new Callbacks<String>() {
            @Override
            public void onSuccess(String result) {
                // Make sure the result isn't empty or null
                assertNotNull(result);
                assertNotEquals("", result);
                latch1.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                fail("unable to create account - " + e.getMessage());
            }
        });

        latch1.await(10, TimeUnit.SECONDS);

        assertEquals(AccountStatus.CREATED, kinAccount.getStatusSync());

        // Now we can test
        CountDownLatch latch2 = new CountDownLatch(1);
        serverCommunicator.fundAccount(kinAccount.getPublicAddress(), 100d, new Callbacks<String>() {
            @Override
            public void onSuccess(String result) {
                // Make sure the result isn't empty or null
                assertNotNull(result);
                assertNotEquals("", result);
                latch2.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                fail("unable to fund account - " + e.getMessage());
            }
        });

        latch2.await(10, TimeUnit.SECONDS);

        assertEquals(200, kinAccount.getBalanceSync().value().intValue());
    }
}