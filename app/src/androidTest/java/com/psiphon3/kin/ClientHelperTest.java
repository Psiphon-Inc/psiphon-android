package com.psiphon3.kin;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import kin.sdk.KinAccount;
import kin.sdk.KinClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class ClientHelperTest extends BaseKinTest {
    private KinClient kinClient;
    private ClientHelper clientHelper;

    @Before
    public void setUp() {
        initMocks();

        kinClient = new KinClient(context, env.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        clientHelper = new ClientHelper(kinClient);
    }

    @After
    public void tearDown() {
        kinClient.clearAllAccounts();
    }

    @Test
    public void getAccount() {
        // make sure that multiple gets return the same account
        KinAccount account1 = clientHelper.getAccount().blockingGet();
        KinAccount account2 = clientHelper.getAccount().blockingGet();
        assertNotNull(account1);
        assertNotNull(account2);
        assertEquals(account1, account2);

        // delete the account
        kinClient.clearAllAccounts();

        // get an account again and verify it's changed
        account2 = clientHelper.getAccount().blockingGet();
        assertNotNull(account2);
        assertNotEquals(account1, account2);
    }

    @Test
    public void deleteAccount() {
        // make sure that multiple gets return the same account
        KinAccount account1 = clientHelper.getAccount().blockingGet();
        KinAccount account2 = clientHelper.getAccount().blockingGet();
        assertNotNull(account1);
        assertNotNull(account2);
        assertEquals(account1, account2);

        // delete the account
        clientHelper.deleteAccount();

        // get an account again and verify it's changed
        account2 = clientHelper.getAccount().blockingGet();
        assertNotNull(account2);
        assertNotEquals(account1, account2);

        // run it 2 times in a row to check that nothing funky happens
        clientHelper.deleteAccount();
        clientHelper.deleteAccount();
    }
}