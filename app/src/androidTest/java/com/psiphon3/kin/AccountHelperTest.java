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
import kin.utils.ResultCallback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccountHelperTest {

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
    public void getAccount() throws InterruptedException {
        KinAccount account1 = AccountHelper.getAccount(kinClient, serverCommunicator).blockingGet();
        KinAccount account2 = AccountHelper.getAccount(kinClient, serverCommunicator).blockingGet();
        assertNotNull(account1);
        assertNotNull(account2);
        assertEquals(account1, account2);

        kinClient.clearAllAccounts();

        account2 = AccountHelper.getAccount(kinClient, serverCommunicator).blockingGet();
        assertNotNull(account2);
        assertNotEquals(account1, account2);

        CountDownLatch account2CreationLatch = new CountDownLatch(1);
        ListenerRegistration listenerRegistration = account2.addAccountCreationListener(data -> account2CreationLatch.countDown());

        CountDownLatch latch1 = new CountDownLatch(1);
        account1.getStatus().run(new ResultCallback<Integer>() {
            @Override
            public void onResult(Integer result) {
                fail("should be deleted");
            }

            @Override
            public void onError(Exception e) {
                latch1.countDown();
            }
        });

        latch1.await(10, TimeUnit.SECONDS);

        // Wait for the listener to fire
        account2CreationLatch.await(10, TimeUnit.SECONDS);

        CountDownLatch latch2 = new CountDownLatch(1);
        account2.getStatus().run(new ResultCallback<Integer>() {
            @Override
            public void onResult(Integer result) {
                assertEquals(AccountStatus.CREATED, result.intValue());
                latch2.countDown();
            }

            @Override
            public void onError(Exception e) {
                fail("unable to get account status - " + e.getMessage());
            }
        });

        latch2.await(10, TimeUnit.SECONDS);

        listenerRegistration.remove();
    }
}