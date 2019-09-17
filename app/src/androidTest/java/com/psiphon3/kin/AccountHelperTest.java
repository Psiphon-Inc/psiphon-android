package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

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

public class AccountHelperTest {

    private static final Environment env = Environment.TEST;
    private KinAccount account;
    private ServerCommunicator serverCommunicator;
    private AccountHelper accountHelper;

    @Before
    public void setUp() {
        SharedPreferences sharedPreferences = InstrumentationRegistry.getTargetContext().getSharedPreferences("test", Context.MODE_PRIVATE);
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences);

        account = mock(KinAccount.class);
        when(account.getPublicAddress()).thenReturn("public_address");

        serverCommunicator = mock(ServerCommunicator.class);

        accountHelper = new AccountHelper(account, serverCommunicator, env.getPsiphonWalletAddress());
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

        TestObserver<Void> test = accountHelper.transferOut(Utils.TRANSFER_AMOUNT).test();
        Utils.assertTestCompleted(test);

        verify(account, times(1)).buildTransactionSync(env.getPsiphonWalletAddress(), Utils.TRANSFER_AMOUNT_BD, 0);
        verify(account, times(1)).sendWhitelistTransactionSync("whitelist");
        verify(serverCommunicator, times(1)).whitelistTransaction(whitelistableTransaction);
    }

    @Test
    public void getCurrentBalance() throws OperationFailedException {
        Balance balance = mock(Balance.class);
        when(balance.value()).thenReturn(Utils.FUND_AMOUNT_BD);
        when(account.getBalanceSync()).thenReturn(balance);

        TestObserver<BigDecimal> test = accountHelper.getCurrentBalance().test();

        // Check that it completed
        Utils.assertTestCompleted(test);
        test.assertValue(Utils.FUND_AMOUNT_BD);
    }
}