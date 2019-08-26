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

import kin.sdk.AccountStatus;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.utils.ResultCallback;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccountHelperTest {

    private final String existingWalletPublicKey = "GCG5E6EELYTX2IA7FJTTRHC3DQHZTSYBUEN7H5YE5E7MJWR3GV6Q6KUP";
    private final String existingWalletPrivateKey = "SD4CQMGDNHI5MNITVNWLNOJ3KCLGFI4VF6W2ELWO4FTC4MIVRBI67IMF";

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

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
        KinAccount account1 = AccountHelper.getAccount(kinClient, serverCommunicator);
        KinAccount account2 = AccountHelper.getAccount(kinClient, serverCommunicator);
        assertNotNull(account1);
        assertNotNull(account2);
        assertEquals(account1, account2);

        kinClient.clearAllAccounts();

        account2 = AccountHelper.getAccount(kinClient, serverCommunicator);
        assertNotNull(account2);
        assertNotEquals(account1, account2);

        CountDownLatch account2CreationLatch = new CountDownLatch(1);
        account2.addAccountCreationListener(new EventListener<Void>() {
            @Override
            public void onEvent(Void data) {
                account2CreationLatch.countDown();
            }
        });

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
    }
}