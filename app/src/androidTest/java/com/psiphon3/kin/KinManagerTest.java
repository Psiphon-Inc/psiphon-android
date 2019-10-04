package com.psiphon3.kin;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import kin.sdk.KinAccount;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class KinManagerTest extends BaseKinTest {
    @Mock
    private KinAccount account;
    @Mock
    private ClientHelper clientHelper;
    @Mock
    private AccountHelper accountHelper;
    @Mock
    private ServerCommunicator serverCommunicator;
    @Mock
    private SettingsManager settingsManager;
    private KinManager kinManager;

    @Before
    public void setUp() {
        initMocks();

        when(clientHelper.getAccount()).thenReturn(Single.just(account));

        kinManager = new KinManager(context, clientHelper, accountHelper, serverCommunicator, settingsManager);
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
    public void transferOut() {
        when(accountHelper.transferOut(anyDouble())).thenReturn(Completable.complete());

        TestObserver<Void> test = kinManager.transferOut(TRANSFER_AMOUNT).test();
        assertTestCompleted(test);
        verify(accountHelper, times(1)).transferOut(TRANSFER_AMOUNT);
    }

    @Test
    public void isOptedIn() {
        when(settingsManager.isOptedIn(context)).thenReturn(true);
        assertTrue(kinManager.isOptedIn(context));
        when(settingsManager.isOptedIn(context)).thenReturn(false);
        assertFalse(kinManager.isOptedIn(context));
        when(settingsManager.needsToOptIn(context)).thenReturn(true);
        assertFalse(kinManager.isOptedIn(context));
    }
}
