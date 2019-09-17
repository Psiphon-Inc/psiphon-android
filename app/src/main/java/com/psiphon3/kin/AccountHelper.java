package com.psiphon3.kin;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import kin.sdk.Balance;
import kin.sdk.KinAccount;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;
import kin.sdk.exception.OperationFailedException;

class AccountHelper {
    private final KinAccount account;
    private final ServerCommunicator serverCommunicator;
    private final String psiphonWalletAddress;

    /**
     * @param account              the account to work with. Should already be created
     * @param serverCommunicator   the communicator for the server
     * @param psiphonWalletAddress the address of the Psiphon wallet
     */
    AccountHelper(KinAccount account, ServerCommunicator serverCommunicator, String psiphonWalletAddress) {
        this.account = account;
        this.serverCommunicator = serverCommunicator;
        this.psiphonWalletAddress = psiphonWalletAddress;
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    Completable transferOut(Double amount) {
        return buildTransaction(account, psiphonWalletAddress, new BigDecimal(amount))
                .flatMap(transaction -> serverCommunicator.whitelistTransaction(transaction.getWhitelistableTransaction()))
                .flatMap(whitelist -> sendWhitelistTransaction(account, whitelist))
                .ignoreElement();
    }

    /**
     * Runs synchronously so specify the schedulers if this isn't desired.
     *
     * @return the current balance of the active account
     */
    Single<BigDecimal> getCurrentBalance() {
        return Single.create(emitter -> {
            try {
                Balance balance = account.getBalanceSync();
                if (!emitter.isDisposed()) {
                    emitter.onSuccess(balance.value());
                }
            } catch (OperationFailedException e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            }
        });
    }

    private Single<Transaction> buildTransaction(KinAccount account, String walletAddress, BigDecimal amount) {
        return Single.fromCallable(() -> account.buildTransactionSync(walletAddress, amount, 0));
    }

    private Single<TransactionId> sendWhitelistTransaction(KinAccount account, String whitelist) {
        return Single.fromCallable(() -> account.sendWhitelistTransactionSync(whitelist));
    }
}
