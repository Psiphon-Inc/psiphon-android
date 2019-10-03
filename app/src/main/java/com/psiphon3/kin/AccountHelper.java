package com.psiphon3.kin;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.jakewharton.rxrelay2.BehaviorRelay;
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
    BehaviorRelay<KinAccount> accountRelay = BehaviorRelay.create();
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

    void onNewAccount(Context context, KinAccount account) {
        accountRelay.accept(account);
    }

    Single<KinAccount> getRegisteredAccount(Context context) {
        return accountRelay
                .firstOrError()
                .flatMap(kinAccount -> {
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
                });
    }

    Completable emptyAccount(Context context) {
        return getRegisteredAccount(context)
                // get the current balance
                .flatMap(account -> getCurrentBalanceInner(account)
                        // return 0 here if we err
                        .onErrorReturnItem(new BigDecimal(0))
                        // turn it into a double
                        .map(BigDecimal::doubleValue)
                        .doOnSuccess(amount -> Utils.MyLog.g("KinManager: will empty the account"))
                        // pass along the account & balance
                        .map(balance -> new Pair<>(account, balance)))
                // transfer out the balance
                .flatMapCompletable(pair -> {
                    KinAccount account = pair.first;
                    Double amount = pair.second;
                    if(amount > 0d) {
                        return transferOutInner(account, amount);
                    } //else
                    return Completable.complete();
                })
                .onErrorComplete();
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    Completable transferOut(Context context, Double amount) {
        return getRegisteredAccount(context)
                .flatMapCompletable(account -> transferOutInner(account, amount));
    }

    Completable transferOutInner(KinAccount account, Double amount) {
        return buildTransaction(account, psiphonWalletAddress, new BigDecimal(amount))
                // get the whitelistable transaction
                .map(Transaction::getWhitelistableTransaction)
                // whitelist it with the server
                .flatMap(serverCommunicator::whitelistTransaction)
                // actually send the transaction
                .flatMap(transaction -> sendWhitelistTransaction(account, transaction))
                .doOnSuccess(transactionId -> Utils.MyLog.g("KinManager: success sending out " + amount + " Kin"))
                // log any errors
                .doOnError(__ -> Utils.MyLog.g("KinManager: error transferring " + amount + " kin out"))
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
    Single<BigDecimal> getCurrentBalance(Context context) {
        return getRegisteredAccount(context)
                .flatMap(this::getCurrentBalanceInner);
    }

    Single<BigDecimal> getCurrentBalanceInner(KinAccount account) {
        return Single.just(account)
                .map(KinAccount::getBalanceSync)
                .map(Balance::value)
                .doOnError(e -> Utils.MyLog.g("KinManager: error getting account balance"))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Single<Transaction> buildTransaction(KinAccount account, String walletAddress, BigDecimal amount) {
        return Single.fromCallable(() -> account.buildTransactionSync(walletAddress, amount, 0));
    }

    private Single<TransactionId> sendWhitelistTransaction(KinAccount account, String whitelist) {
        return Single.fromCallable(() -> account.sendWhitelistTransactionSync(whitelist));
    }
}
