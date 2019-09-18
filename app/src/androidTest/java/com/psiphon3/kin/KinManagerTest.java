package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import kin.sdk.KinAccount;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KinManagerTest {

    private static final Environment env = Environment.TEST;
    private Context context;
    private KinAccount account;
    private AccountHelper accountHelper;
    private ClientHelper clientHelper;
    private ServerCommunicator serverCommunicator;
    private KinPermissionManager kinPermissionManager;
    private KinManager kinManager;

    @Before
    public void setUp() {
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        account = mock(KinAccount.class);

        accountHelper = mock(AccountHelper.class);

        clientHelper = mock(ClientHelper.class);
        when(clientHelper.getAccount()).thenReturn(Single.just(account));
        when(clientHelper.getAccountHelper(account, env)).thenReturn(accountHelper);

        serverCommunicator = mock(ServerCommunicator.class);

        kinPermissionManager = mock(KinPermissionManager.class);
        when(kinPermissionManager.getUsersAgreementToKin(context)).thenReturn(Single.just(true));

        kinManager = new KinManager(context, clientHelper, serverCommunicator, kinPermissionManager, env);
        kinManager.isReadyObservable().filter(v -> v).test().awaitCount(1).assertValue(true);
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
        assertNotEquals(kinManager, instance2);
    }

    @Test
    public void isReady() {
        // assertTrue(kinManager.isReady());

        // TODO: Should we add tests to make sure opt-in/out toggle isReady?
    }

    @Test
    public void getCurrentBalance() {
        when(accountHelper.getCurrentBalance()).thenReturn(Single.just(Utils.FUND_AMOUNT_BD));

        TestObserver<BigDecimal> test = kinManager.getCurrentBalance().test();
        Utils.assertTestCompleted(test);
        test.assertValue(Utils.FUND_AMOUNT_BD);
        verify(accountHelper, times(1)).getCurrentBalance();

        // Should still be the same
        test = kinManager.getCurrentBalance().test();
        Utils.assertTestCompleted(test);
        test.assertValue(Utils.FUND_AMOUNT_BD);
        verify(accountHelper, times(2)).getCurrentBalance();
    }

    @Test
    public void transferOut() {
        when(accountHelper.transferOut(anyDouble())).thenReturn(Completable.complete());

        TestObserver<Void> test = kinManager.transferOut(Utils.TRANSFER_AMOUNT).test();
        Utils.assertTestCompleted(test);
        verify(accountHelper, times(1)).transferOut(Utils.TRANSFER_AMOUNT);
    }

    @Test
    public void chargeForConnection() {
        // Check that when they don't confirm we return false
        when(kinPermissionManager.confirmPay(context)).thenReturn(Single.just(false));

        TestObserver<Boolean> test = kinManager.chargeForConnection(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(false);
        verify(accountHelper, times(0)).transferOut(anyDouble());

        // Check that confirmation returns true
        when(accountHelper.transferOut(anyDouble())).thenReturn(Completable.complete());
        when(kinPermissionManager.confirmPay(context)).thenReturn(Single.just(true));

        test = kinManager.chargeForConnection(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(true);
        verify(accountHelper, times(1)).transferOut(Utils.CONNECTION_TRANSFER_AMOUNT);
    }

    @Test
    public void isOptedIn() {
        when(kinPermissionManager.hasAgreedToKin(context)).thenReturn(true);
        assertTrue(kinManager.isOptedIn(context));
        when(kinPermissionManager.hasAgreedToKin(context)).thenReturn(false);
        assertFalse(kinManager.isOptedIn(context));
    }

    @Test
    public void optIn() {
        // Check that a disagreement doesn't fire the opt-in
        when(kinPermissionManager.optIn(context)).thenReturn(Single.just(false));

        TestObserver<Boolean> test = kinManager.optIn(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(false);
        verify(kinPermissionManager, times(1)).optIn(context);

        // Check agreement works as expected
        when(kinPermissionManager.optIn(context)).thenReturn(Single.just(true));

        // We check that before this runs getAccount has been called once, and after twice
        verify(clientHelper, times(1)).getAccount();

        test = kinManager.optIn(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(true);
        verify(kinPermissionManager, times(2)).optIn(context);
        verify(clientHelper, times(2)).getAccount();
    }

    @Test
    public void optOut() {
        // Counter to verify the number of times is ready gets fired
        final int[] counter = {0};
        kinManager.isReadyObservable().doOnNext(v -> ++counter[0]).subscribe();

        // Check that a disagreement doesn't fire the opt-out
        when(kinPermissionManager.optOut(context)).thenReturn(Single.just(false));

        TestObserver<Boolean> test = kinManager.optOut(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(false);
        verify(kinPermissionManager, times(1)).optOut(context);
        assertEquals(1, counter[0]);

        // Check agreement works as expected
        when(kinPermissionManager.optOut(context)).thenReturn(Single.just(true));

        test = kinManager.optOut(context).test();
        Utils.assertTestCompleted(test);
        test.assertValue(true);
        verify(kinPermissionManager, times(2)).optOut(context);
        verify(clientHelper, times(1)).deleteAccount();
        verify(serverCommunicator, times(1)).optOut();
        assertEquals(2, counter[0]);
    }
}