package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import io.reactivex.observers.TestObserver;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KinManagerTest {

    private Context context;
    private KinClient kinClient;
    private KinAccount account;
    private KinPermissionManager kinPermissionManager;
    private KinManager kinManager;

    @Before
    public void setUp() throws Exception {
        Environment env = Environment.TEST;
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        ServerCommunicator serverCommunicator = new ServerCommunicator(env.getFriendBotServerUrl());
        kinClient = new KinClient(context, env.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        kinClient.clearAllAccounts();

        account = clientHelper.getAccount().blockingGet();

        kinPermissionManager = new KinPermissionManager();

        // Make sure we start out agreed to kin
        kinPermissionManager.setHasAgreedToKin(context, true);
        kinManager = new KinManager(context, clientHelper, serverCommunicator, env, kinPermissionManager);
    }

    @After
    public void tearDown() {
        kinClient.clearAllAccounts();
    }

    @Test
    public void getInstance() {
        // For the moment we expect this to fail
        KinManager instance1 = KinManager.getInstance(context, Environment.PRODUCTION);
        KinManager instance2 = KinManager.getInstance(context);
        assertEquals(instance1, instance2);
        assertNotEquals(kinManager, instance2);
    }

    @Test
    public void getTestInstance() {
        KinManager instance1 = KinManager.getInstance(context, Environment.TEST);
        KinManager instance2 = KinManager.getInstance(context);
        assertEquals(instance1, instance2);
        assertEquals(kinManager, instance2);
    }

    @Test
    public void isReady() {
        Boolean ready = kinManager.isReadyObservable().lastOrError().blockingGet();
        assertTrue(ready);

        // TODO: Should we add tests to make sure opt-in/out toggle is ready?
    }

    @Test
    public void getCurrentBalance() {
        TestObserver<BigDecimal> test = kinManager.getCurrentBalance().test();
        assertFalse(test.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        test.assertComplete();
        test.assertValue(new BigDecimal(Utils.FUND_AMOUNT));

        // Should still be the same
        test = kinManager.getCurrentBalance().test();
        assertFalse(test.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        test.assertComplete();
        test.assertValue(new BigDecimal(Utils.FUND_AMOUNT));
    }

    @Test
    public void transferOut() {
        TestObserver<Void> test = kinManager.transferOut(Utils.TRANSFER_AMOUNT).test();
        assertFalse(test.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        test.assertComplete();

        TestObserver<BigDecimal> balanceTest = kinManager.getCurrentBalance().test();
        assertFalse(balanceTest.awaitTerminalEvent(Utils.WAIT_TIME_S, TimeUnit.SECONDS));
        balanceTest.assertComplete();
        balanceTest.assertValue(new BigDecimal(Utils.FUND_AMOUNT - Utils.TRANSFER_AMOUNT));
    }

    @Test
    public void chargeForConnection() {
        // TODO: This shows a dialog. Is there a good way to test when a dialog is shown?
    }

    @Test
    public void isOptedIn() {
        assertTrue(kinManager.isOptedIn(context));
        kinPermissionManager.setHasAgreedToKin(context, false);
        assertFalse(kinManager.isOptedIn(context));
        kinPermissionManager.setHasAgreedToKin(context, true);
        assertTrue(kinManager.isOptedIn(context));
    }

    @Test
    public void optIn() {
        // TODO: This shows a dialog. Is there a good way to test when a dialog is shown?
    }

    @Test
    public void optOut() {
        // TODO: This shows a dialog. Is there a good way to test when a dialog is shown?
    }
}