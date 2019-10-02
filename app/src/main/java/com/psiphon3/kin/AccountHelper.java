package com.psiphon3.kin;

import android.content.Context;
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

    private final BehaviorRelay<AccountState> accountStateBehaviorRelay;
    private KinAccount account;

    /**
     * @param serverCommunicator   the communicator for the server
     * @param settingsManager
     * @param psiphonWalletAddress the address of the Psiphon wallet
     */
    AccountHelper(ServerCommunicator serverCommunicator, SettingsManager settingsManager, String psiphonWalletAddress) {
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;
        this.psiphonWalletAddress = psiphonWalletAddress;

        accountStateBehaviorRelay = BehaviorRelay.create();
    }

    void onNewAccount(Context context, KinAccount account) {
        this.account = account;

        accountStateBehaviorRelay.accept(AccountState.UNREGISTERED);
        register(context).subscribe();
    }

    Single<KinAccount> getAccountIfRegistered() {
        return accountStateBehaviorRelay
                .firstOrError()
                .flatMap(state -> {
                    switch (state) {
                        case DELETED:
                            return Single.error(new Exception("account deleted"));

                        case REGISTERED:
                            return Single.just(account);

                        case UNREGISTERED:
                        default:
                            return accountStateBehaviorRelay
                                    // wait for a change in state
                                    .filter(state2 -> state2 != AccountState.UNREGISTERED)
                                    .firstOrError()
                                    // handle the new state
                                    .flatMap(state2 -> {
                                        if (state2 == AccountState.REGISTERED) {
                                            return Single.just(account);
                                        } else {
                                            return Single.error(new Exception("account deleted"));
                                        }
                                    });
                    }
                });
    }

    Completable register(Context context) {
        return Single.just(account)
                .map(KinAccount::getPublicAddress)
                .flatMapCompletable(address -> {
                    if (address == null) {
                        accountStateBehaviorRelay.accept(AccountState.DELETED);
                        return Completable.error(new Exception("account deleted, unable to register"));
                    }

                    if (settingsManager.isAccountRegistered(context, address)) {
                        // the account is already registered, don't try to do it again
                        accountStateBehaviorRelay.accept(AccountState.REGISTERED);
                        return Completable.complete();
                    }

                    return serverCommunicator
                            .createAccount(address)
                            .doOnComplete(() -> {
                                accountStateBehaviorRelay.accept(AccountState.REGISTERED);
                                settingsManager.setAccountRegistered(context, address, true);
                            })
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread());
                });
    }

    Completable delete(Context context) {
        return getAccountIfRegistered()
                // get the current balance
                .flatMap(account -> getCurrentBalanceInner(account)
                        // return 0 here if we err
                        .onErrorReturnItem(new BigDecimal(0))
                        // turn it into a double
                        .map(BigDecimal::doubleValue)
                        // pass along the account & balance
                        .map(balance -> new Pair<>(account, balance)))
                // transfer out the balance
                .flatMap(pair -> transferOutInner(pair.first, pair.second)
                        // it is ok if it errs here
                        .onErrorComplete()
                        // pass on the account
                        .toSingle(() -> pair.first))
                // get the address
                .map(KinAccount::getPublicAddress)
                // mark the account as deleted
                .doOnSuccess(address -> {
                    accountStateBehaviorRelay.accept(AccountState.DELETED);
                    settingsManager.setAccountRegistered(context, address, false);
                })
                // listeners don't care about err or not, just that everything is done
                .ignoreElement()
                .onErrorComplete();
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    Completable transferOut(Double amount) {
        return getAccountIfRegistered()
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
                // log any errors
                .doOnError(__ -> Utils.MyLog.g("error transferring " + amount + " kin out"))
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
    Single<BigDecimal> getCurrentBalance() {
        return getAccountIfRegistered()
                .flatMap(this::getCurrentBalanceInner);
    }

    Single<BigDecimal> getCurrentBalanceInner(KinAccount account) {
        return Single.just(account)
                .map(KinAccount::getBalanceSync)
                .map(Balance::value)
                .doOnError(e -> Utils.MyLog.g("error getting account balance"))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private Single<Transaction> buildTransaction(KinAccount account, String walletAddress, BigDecimal amount) {
        return Single.fromCallable(() -> account.buildTransactionSync(walletAddress, amount, 0));
    }

    private Single<TransactionId> sendWhitelistTransaction(KinAccount account, String whitelist) {
        return Single.fromCallable(() -> account.sendWhitelistTransactionSync(whitelist));
    }

    private enum AccountState {
        UNREGISTERED,
        REGISTERED,
        DELETED
    }
}
