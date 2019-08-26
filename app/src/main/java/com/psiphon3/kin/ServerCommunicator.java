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

class ServerCommunicator {

    private final String mBaseUrl;
    private final Handler mHandler;
    private final OkHttpClient mOkHttpClient;

    /**
     * @param baseUrl the base URL for the server
     */
    ServerCommunicator(String baseUrl) {
        mBaseUrl = baseUrl;

        mHandler = new Handler(Looper.getMainLooper());
        mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Callbacks are fired depending on the success of the operation.
     *
     * @param address the wallet address
     * @param amount the amount of Kin to be funded on creation
     * @param callbacks callbacks for the result of the operation
     */
    void createAccount(String address, Double amount, @NonNull Callbacks callbacks) {
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

    /**
     * Gives amount Kin to the wallet at address.
     * Callbacks are fired depending on the success of the operation.
     *
     * @param address the wallet address
     * @param amount the amount of Kin to be given
     * @param callbacks callbacks for the result of the operation
     */
    void fundAccount(String address, Double amount, @NonNull Callbacks callbacks) {
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

                        // Only care about failure
                        if (code != 200) {
                            fireOnFailure(callbacks, new Exception("Fund account - response code is " + response.code()));
                        }
                    }
                });
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


    private void fireOnFailure(@NonNull Callbacks callbacks, Exception ex) {
        mHandler.post(() -> callbacks.onFailure(ex));
    }

    private void fireOnSuccess(@NonNull Callbacks callbacks) {
        mHandler.post(callbacks::onSuccess);
    }
}
