package com.psiphon3.kin;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ServerCommunicator {

    private final String mBaseUrl;
    private final Handler mHandler;
    private final OkHttpClient mOkHttpClient;

    public ServerCommunicator(String baseUrl) {
        mBaseUrl = baseUrl;

        mHandler = new Handler(Looper.getMainLooper());
        mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private HttpUrl.Builder getUrlBuilder(String address, Double amount) {
        return new HttpUrl.Builder()
                .host(mBaseUrl)
                .addQueryParameter("addr", address)
                .addQueryParameter("amount", amount.toString());
    }

    private HttpUrl getCreateAccountUrl(String address, Double amount) {
        return getUrlBuilder(address, amount).build();
    }

    private HttpUrl getFundAccountUrl(String address, Double amount) {
        return getUrlBuilder(address, amount).addPathSegment("fund").build();
    }

    public void createAccount(String address, Double amount, @NonNull Callbacks callbacks) {
        Request request = new Request.Builder()
                .url(getCreateAccountUrl(address, amount))
                .get()
                .build();

        mOkHttpClient.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        fireOnFailure(callbacks, e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        int code = response.code();
                        response.close();

                        // Only care about failure
                        if (code != 200) {
                            fireOnFailure(callbacks, new Exception("Create account - response code is " + response.code()));
                        }
                    }
                });
    }

    public void fundAccount(String address, Double amount, @NonNull Callbacks callbacks) {
        Request request = new Request.Builder()
                .url(getFundAccountUrl(address, amount))
                .get()
                .build();

        mOkHttpClient.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        fireOnFailure(callbacks, e);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        int code = response.code();
                        response.close();

                        // Only care about failure because the account listener will pickup the success
                        if (code != 200) {
                            fireOnFailure(callbacks, new Exception("Create account - response code is " + response.code()));
                        }
                    }
                });
    }


    private void fireOnFailure(@NonNull Callbacks callbacks, Exception ex) {
        mHandler.post(() -> callbacks.onFailure(ex));
    }

    private void fireOnSuccess(@NonNull Callbacks callbacks) {
        mHandler.post(callbacks::onSuccess);
    }
}
