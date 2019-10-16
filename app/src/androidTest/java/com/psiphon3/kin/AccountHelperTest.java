package com.psiphon3.kin;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.math.BigDecimal;

import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import kin.sdk.Balance;
import kin.sdk.KinAccount;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;
import kin.sdk.WhitelistableTransaction;
import kin.sdk.exception.OperationFailedException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AccountHelperTest extends BaseKinTest {
    @Mock
    private KinAccount account;
    @Mock
    private ServerCommunicator serverCommunicator;
    private AccountHelper accountHelper;

    @Before
    public void setUp() {
        initMocks();

        when(account.getPublicAddress()).thenReturn("public_address");

        accountHelper = new AccountHelper(serverCommunicator, new SettingsManager(context), env.getPsiphonWalletAddress());
    }

    @Test
    public void transferOut() throws OperationFailedException {
        Transaction transaction = mock(Transaction.class);
        WhitelistableTransaction whitelistableTransaction = mock(WhitelistableTransaction.class);
        TransactionId transactionId = mock(TransactionId.class);

        when(account.buildTransactionSync(anyString(), any(BigDecimal.class), anyInt())).thenReturn(transaction);
        when(transaction.getWhitelistableTransaction()).thenReturn(whitelistableTransaction);
        when(serverCommunicator.whitelistTransaction(any())).thenReturn(Single.just("whitelist"));
        when(account.sendWhitelistTransactionSync(any())).thenReturn(transactionId);

        TestObserver<Void> test = accountHelper.transferOut(TRANSFER_AMOUNT).test();
        assertTestCompleted(test);

        verify(account, times(1)).buildTransactionSync(env.getPsiphonWalletAddress(), TRANSFER_AMOUNT_BD, 0);
        verify(account, times(1)).sendWhitelistTransactionSync("whitelist");
        verify(serverCommunicator, times(1)).whitelistTransaction(whitelistableTransaction);
    }

    @Test
    public void getCurrentBalance() throws OperationFailedException {
        Balance balance = mock(Balance.class);
        when(balance.value()).thenReturn(FUND_AMOUNT_BD);
        when(account.getBalanceSync()).thenReturn(balance);

        TestObserver<BigDecimal> test = accountHelper.getCurrentBalance().test();

        // check that it completed
        assertTestCompleted(test);
        test.assertValue(FUND_AMOUNT_BD);
    }
}