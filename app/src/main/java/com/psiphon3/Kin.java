package com.psiphon3;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import kin.sdk.Balance;
import kin.sdk.Environment;
import kin.sdk.EventListener;
import kin.sdk.KinAccount;
import kin.sdk.KinClient;
import kin.sdk.Transaction;
import kin.sdk.TransactionId;
import kin.sdk.exception.CreateAccountException;
import kin.utils.Request;
import kin.utils.ResultCallback;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class Kin {
    private final static String PSIPHON_WALLET_ADDRESS = "GDIRGGTBE3H4CUIHNIFZGUECGFQ5MBGIZTPWGUHPIEVOOHFHSCAGMEHO";

    private final KinClient mKinClient;
    private final KinAccount mAccount;
    private final OkHttpClient mOkHttpClient;

    private BigDecimal mBalance;

    public Kin(Context context) throws CreateAccountException {
        mKinClient = new KinClient(context, Environment.TEST, "1acd");
        mAccount = initializeAccount();
        onboardAccount();
        mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        mAccount.addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance balance) {
                setBalance(balance.value());
            }
        });
    }

    private KinAccount initializeAccount() throws CreateAccountException {
        try {
            if (mKinClient.hasAccount()) {
                return mKinClient.getAccount(0);
            } else {
                return mKinClient.addAccount();
            }
        } catch (CreateAccountException e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void onboardAccount() {
        final KinAccount account = mAccount;
        if (account == null) {
            return;
        }
        // balance.setText(null);
        // balanceProgress.setVisibility(View.VISIBLE);
        // onboardBtn.setClickable(false);

        OnBoarding onBoarding = new OnBoarding();
        onBoarding.onBoard(account, new OnBoarding.Callbacks() {
            @Override
            public void onSuccess() {
                // updateAccountInfo(true);
                // onboardBtn.setClickable(true);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("kin", "onBoarding", e);
                // KinAlertDialog.createErrorDialog(WalletActivity.this, e.getMessage()).show();
                // onboardBtn.setClickable(true);
            }
        });
    }

    public void addBalanceListener(EventListener<Balance> listener) {
        mAccount.addBalanceListener(listener);
    }


    public synchronized BigDecimal getBalance() {
        return mBalance;
    }

    private synchronized void setBalance(BigDecimal balance) {
        mBalance = balance;
    }

    public void getBalance(ResultCallback<Balance> callback) {
        Request<Balance> balanceRequest = mAccount.getBalance();
        balanceRequest.run(callback);
    }

    public String getAccountId() {
        return mAccount.getPublicAddress();
    }

    public void transferIn() {
        String url = "http://friendbot-testnet.kininfrastructure.com/fund?addr=%s&amount=100";
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(String.format(url, getAccountId()))
                .get()
                .build();
        mOkHttpClient.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e("kin", "transferIn failure", e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        int code = response.code();
                        response.close();
                        if (code != 200) {
                            Log.e("kin", "transferIn non200 " + code);
                        } else {
                            Log.e("kin", "good transferIn");
                        }
                    }
                });
    }

    public void transferOut(String amount) {
        BigDecimal amountInKin = new BigDecimal(amount);

        // set a fixed fee, because I'm lazy
        int fee = 100;

        // Build the transaction and get a Request<Transaction> object.
        Request<Transaction> transactionRequest = mAccount.buildTransaction(PSIPHON_WALLET_ADDRESS, amountInKin, fee);
        // Actually run the build transaction code in a background thread
        transactionRequest.run(new ResultCallback<Transaction>() {

            @Override
            public void onResult(Transaction transaction) {
                // Here we got a Transaction object before actually sending the
                // transaction this way we can save information for later if anything goes wrong
                Log.d("example", "The transaction id before sending: " + transaction.getId().id());

                // Create the send transaction request
                Request<TransactionId> sendTransaction = mAccount.sendTransaction(transaction);
                // Actually send the transaction in a background thread.
                sendTransaction.run(new ResultCallback<TransactionId>() {

                    @Override
                    public void onResult(TransactionId id) {
                        Log.d("example", "The transaction id: " + id);
                    }

                    @Override
                    public void onError(Exception e) {
                        e.printStackTrace();
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                e.printStackTrace();
            }
        });
    }
}
