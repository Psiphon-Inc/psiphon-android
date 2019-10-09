package com.psiphon3.kin;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.psiphon3.psiphonlibrary.Utils;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.Balance;
import kin.sdk.KinAccount;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;

class AccountHelper {
    private final ServerCommunicator serverCommunicator;
    private final SettingsManager settingsManager;
    private final String psiphonWalletAddress;
    /**
     * @param serverCommunicator   the communicator for the server
     * @param settingsManager
     * @param psiphonWalletAddress the address of the Psiphon wallet
     */
    AccountHelper(ServerCommunicator serverCommunicator, SettingsManager settingsManager, String psiphonWalletAddress) {
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;
        this.psiphonWalletAddress = psiphonWalletAddress;
    }

    private Single<KinAccount> getRegisteredAccount(Context context, KinAccount kinAccount) {

        String address = kinAccount.getPublicAddress();
        if (TextUtils.isEmpty(address)) {
            return Single.error(new Exception("account deleted"));
        }
        if (settingsManager.isAccountRegistered(context, address)) {
            return Single.just(kinAccount);
        }
        return serverCommunicator
                .createAccount(address)
                .doOnComplete(() -> settingsManager.setAccountRegistered(context, address, true))
                .toSingleDefault(kinAccount)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    Completable emptyAccount(Context context, KinAccount kinAccount) {
        Utils.MyLog.g("KinManager: schedule emptying the account");
        String address = kinAccount.getPublicAddress();
        if (!settingsManager.isAccountRegistered(context, address)) {
            Utils.MyLog.g("KinManager: account is not registered on the blockchain, skip emptying");
            return Completable.complete();
        }
        return getRegisteredAccount(context, kinAccount)
                // get the current balance
                .flatMap(account -> getCurrentBalanceInner(account)
                        // return 0 here if we err
                        .onErrorReturnItem(new BigDecimal(0))
                        // turn it into a double
                        .map(BigDecimal::doubleValue)
                        // pass along the account & balance
                        .map(balance -> new Pair<>(account, balance)))
                // transfer out the balance
                .flatMapCompletable(pair -> {
                    KinAccount account = pair.first;
                    Double amount = pair.second;
                    if(amount > 0d) {
                        return transferOutInner(account, amount);
                    } //else
                    Utils.MyLog.g("KinManager: account is already empty");
                    return Completable.complete();
                });
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     *
     * @param kinAccount Kin account
     * @param amount the amount to be taken from the kinAccount
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    Completable transferOut(Context context, KinAccount kinAccount, Double amount) {
        return getRegisteredAccount(context, kinAccount)
                .flatMapCompletable(account -> transferOutInner(account, amount));
    }

    Completable transferOutInner(KinAccount account, Double amount) {
        return buildTransaction(account, psiphonWalletAddress, new BigDecimal(amount))
                // get the whitelistable transaction
                .map(Transaction::getWhitelistableTransaction)
                // whitelist it with the server
                .flatMap(whitelistableTransaction -> serverCommunicator.whitelistTransaction(account.getPublicAddress(), whitelistableTransaction))
                // actually send the transaction
                .flatMap(transaction -> sendWhitelistTransaction(account, transaction))
                .doOnSuccess(transactionId -> Utils.MyLog.g("KinManager: success sending out " + amount + " Kin"))
                // log any errors
                .doOnError(e -> Utils.MyLog.g("KinManager: error transferring " + amount + " kin out: " + e))
                // just care if it completed
                .ignoreElement()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * Runs synchronously so specify the schedulers if this isn't desired.
     *
     * @return the current balance of the active account
     */
    Single<BigDecimal> getCurrentBalance(Context context, KinAccount kinAccount) {
        return getRegisteredAccount(context, kinAccount)
                .flatMap(this::getCurrentBalanceInner);
    }

    Single<BigDecimal> getCurrentBalanceInner(KinAccount account) {
        return Single.just(account)
                .map(KinAccount::getBalanceSync)
                .map(Balance::value)
                .doOnError(e -> Utils.MyLog.g("KinManager: error getting account balance"))
                .doOnSuccess(amount -> Utils.MyLog.g("KinManager: account balance is " + amount))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Single<Transaction> buildTransaction(KinAccount account, String walletAddress, BigDecimal amount) {
        return Single.fromCallable(() -> account.buildTransactionSync(walletAddress, amount, 0));
    }

    private Single<TransactionId> sendWhitelistTransaction(KinAccount account, String whitelist) {
        Utils.MyLog.g("KinManager: sending whitelisted transaction");
        return Single.fromCallable(() -> account.sendWhitelistTransactionSync(whitelist));
    }
}
