package com.psiphon3.kin;

import java.math.BigDecimal;

import io.reactivex.Completable;
import kin.sdk.KinAccount;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;
import kin.utils.Request;
import kin.utils.ResultCallback;

class AccountTransactionHelper {
    // TODO: decide on transfer fee
    private final static int TRANSACTION_FEE = 100;

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
        return Completable.create(emitter -> {
            // Build the transaction and get a Request<Transaction> object.
            Request<Transaction> transactionRequest = mAccount.buildTransaction(mPsiphonWalletAddress, new BigDecimal(amount), TRANSACTION_FEE);
            // Actually run the build transaction code in a background thread
            transactionRequest.run(new ResultCallback<Transaction>() {
                @Override
                public void onResult(Transaction transaction) {
                    // Here we got a Transaction object before actually sending the
                    // transaction this way we can save information for later if anything goes wrong
                    // Log.d("example", "The transaction id before sending: " + transaction.getId().id());

                    // Create the send transaction request
                    Request<TransactionId> sendTransaction = mAccount.sendTransaction(transaction);
                    // Actually send the transaction in a background thread.
                    sendTransaction.run(new ResultCallback<TransactionId>() {
                        @Override
                        public void onResult(TransactionId id) {
                            emitter.onComplete();
                        }

                        @Override
                        public void onError(Exception e) {
                            emitter.onError(e);
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    emitter.onError(e);
                }
            });
        });
    }
}
