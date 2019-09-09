package com.psiphon3.kin;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import kin.sdk.KinAccount;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;

class AccountTransactionHelper {
    private final KinAccount mAccount;
    private final ServerCommunicator mServerCommunicator;
    private final String mPsiphonWalletAddress;

    /**
     * @param account              the account to work with. Should already be created
     * @param serverCommunicator   the communicator for the server
     * @param psiphonWalletAddress the address of the Psiphon wallet
     */
    AccountTransactionHelper(KinAccount account, ServerCommunicator serverCommunicator, String psiphonWalletAddress) {
        mAccount = account;
        mServerCommunicator = serverCommunicator;
        mPsiphonWalletAddress = psiphonWalletAddress;
    }

    /**
     * Requests that amount of Kin gets transferred into the active accounts wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be given to the active account
     * @return a completable which fires on complete after the transfer has been completed
     */
    Completable transferIn(Double amount) {
        if (mAccount.getPublicAddress() == null) {
            return Completable.error(new Exception("Account has been deleted"));
        }

        // TODO: Should we be specifying the observeOn or subscribeOn scheduler here?
        return mServerCommunicator.fundAccount(mAccount.getPublicAddress(), amount);
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    Completable transferOut(Double amount) {
        return buildTransaction(mAccount, mPsiphonWalletAddress, new BigDecimal(amount))
                .flatMap(transaction -> mServerCommunicator.whitelistTransaction(transaction.getWhitelistableTransaction()))
                .flatMap(whitelist -> sendWhitelistTransaction(mAccount, whitelist))
                .ignoreElement();
    }

    private Single<Transaction> buildTransaction(KinAccount account, String walletAddress, BigDecimal amount) {
        return Single.fromCallable(() -> account.buildTransactionSync(walletAddress, amount, 0));
    }

    private Single<TransactionId> sendWhitelistTransaction(KinAccount account, String whitelist) {
        return Single.fromCallable(() -> account.sendWhitelistTransactionSync(whitelist));
    }
}
