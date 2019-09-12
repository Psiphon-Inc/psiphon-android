package com.psiphon3.kin;

import android.content.Context;
import android.util.Log;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.Balance;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.ListenerRegistration;
import kin.sdk.exception.OperationFailedException;
import kin.utils.ResultCallback;

public class KinManager {
    private static KinManager instance;

    private final KinAccount account;
    private final KinClient kinClient;
    private final AccountTransactionHelper transactionHelper;
    private final Observable<BigDecimal> balanceObservable;

    KinManager(KinAccount account, KinClient kinClient, AccountTransactionHelper transactionHelper) {
        this.account = account;
        this.kinClient = kinClient;
        this.transactionHelper = transactionHelper;

        // TODO: Do we have to manually remove this? Is it worth it?
        final ListenerRegistration[] listenerRegistration = new ListenerRegistration[1];
        ObservableOnSubscribe<BigDecimal> handler = emitter ->
                listenerRegistration[0] = addBalanceListener(balance -> {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(balance.value());
                    }
                });
        balanceObservable = Observable.create(handler)
                .doFinally(() -> listenerRegistration[0].remove())
                .startWith(getCurrentBalance().toObservable());
    }

    private static Single<KinManager> getInstance(Context context, Environment environment) {
        if (instance != null) {
            return Single.just(instance);
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());

        // Get the account, may have to go to the server to create one, then transform into the manager and helper
        return AccountHelper.getAccount(kinClient, serverCommunicator)
                .map(account -> {
                    // Create the transaction helper and the instance
                    AccountTransactionHelper transactionHelper = new AccountTransactionHelper(account, serverCommunicator, environment.getPsiphonWalletAddress());
                    return instance = new KinManager(account, kinClient, transactionHelper);
                });
    }

    /**
     * Returns an instance of KinManager for production use.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param context the context of the calling activity
     * @return an instance of KinManager for the passed context
     */
    public static Single<KinManager> getInstance(Context context) {
        return getInstance(context, Environment.PRODUCTION);
    }

    /**
     * Returns an instance of KinManager for use in tests.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param context the context of the calling activity
     * @return a test instance of KinManager for the passed context
     */
    public static Single<KinManager> getTestInstance(Context context) {
        return getInstance(context, Environment.TEST);
    }

    /**
     * Adds listener to the balance of the active account, which will be called whenever the balance
     * is changed.
     *
     * @param listener the listener to register to account balance changes
     * @return the registration to allow it's removal
     */
    public ListenerRegistration addBalanceListener(EventListener<Balance> listener) {
        return account.addBalanceListener(listener);
    }

    /**
     * Adds observer to the balance of the active account, which will be called whenever the balance
     * is changed.
     *
     * @param observer the listener to register to account balance changes
     */
    public void getBalance(Observer<BigDecimal> observer) {
        balanceObservable.safeSubscribe(observer);
    }

    /**
     * @return the public wallet address of the active account
     */
    public String getWalletAddress() {
        return account.getPublicAddress();
    }

    /**
     * @return the current balance of the active account
     */
    public BigDecimal getCurrentBalanceSync() {
        try {
            return account.getBalanceSync().value();
        } catch (OperationFailedException e) {
            // TODO: What should we do?
            Log.e("kin", "getCurrentBalanceSync", e);
        }

        return null;
    }

    /**
     * @return the current balance of the active account
     */
    public Single<BigDecimal> getCurrentBalance() {
        return Single.create(emitter ->
                account.getBalance().run(new ResultCallback<Balance>() {
                    @Override
                    public void onResult(Balance result) {
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(result.value());
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        // TODO: Should we care?
                        if (!emitter.isDisposed()) {
                            emitter.onError(e);
                        }
                    }
                })
        );
    }

    /**
     * Requests that amount of Kin gets transferred into the active accounts wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be given to the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferIn(Double amount) {
        return transactionHelper.transferIn(amount);
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferOut(Double amount) {
        return transactionHelper.transferOut(amount);
    }

    public void deleteAccount() {
        AccountHelper.deleteAccount(kinClient);
    }

    public void chargeForConnection() {
        transferOut(1d).subscribeOn(Schedulers.io()).subscribe();
    }
}
