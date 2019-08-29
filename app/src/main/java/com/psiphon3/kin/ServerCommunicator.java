package com.psiphon3.kin;

import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

class ServerCommunicator {

    private final String mFriendBotUrl;
    private final Handler mHandler;
    private final OkHttpClient mOkHttpClient;

    /**
     * @param friendBotUrl the URL to the friend bot server
     */
    ServerCommunicator(@NonNull String friendBotUrl) {
        mFriendBotUrl = friendBotUrl;

        mHandler = new Handler(Looper.getMainLooper());
        mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Callbacks are fired depending on the success of the operation.
     * OnSuccess contains the id of the transaction.
     *
     * @param address   the wallet address
     * @param amount    the amount of Kin to be funded on creation
     * @param callbacks callbacks for the result of the operation
     */
    void createAccount(@NonNull String address, @NonNull Double amount, @NonNull Callbacks<String> callbacks) {
        Request request = new Request.Builder()
                .url(getCreateAccountUrl(address, amount))
                .get()
                .build();

        mOkHttpClient.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        mHandler.post(() -> callbacks.onFailure(e));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        handleFriendBotResponse("createAccount", response, callbacks);
                    }
                });
    }

    /**
     * Gives amount Kin to the wallet at address.
     * Callbacks are fired depending on the success of the operation.
     * OnSuccess contains the id of the transaction.
     *
     * @param address   the wallet address
     * @param amount    the amount of Kin to be given
     * @param callbacks callbacks for the result of the operation
     */
    void fundAccount(@NonNull String address, @NonNull Double amount, @NonNull Callbacks<String> callbacks) {
        Request request = new Request.Builder()
                .url(getFundAccountUrl(address, amount))
                .get()
                .build();

        mOkHttpClient.newCall(request)
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        mHandler.post(() -> callbacks.onFailure(e));
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        handleFriendBotResponse("fundAccount", response, callbacks);
                    }
                });
    }

    private void handleFriendBotResponse(@NonNull String function, @NonNull Response response, @NonNull Callbacks<String> callbacks) {
        try {
            int code = response.code();
            ResponseBody body = response.body();

            if (code != 200) {
                mHandler.post(() -> callbacks.onFailure(new Exception(function + " - response code is " + response.code())));
                response.close();
                return;
            }

            if (body == null) {
                mHandler.post(() -> callbacks.onFailure(new Exception(function + " - no body")));
                response.close();
                return;
            }

            JsonObject jsonObject = new JsonParser().parse(body.string()).getAsJsonObject();
            mHandler.post(() -> callbacks.onSuccess(jsonObject.get("hash").getAsString()));
            response.close();
        } catch (IOException e) {
            mHandler.post(() -> callbacks.onFailure(e));
        }
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Runs synchronously, returning a completable for subscription.
     *
     * @param address   the wallet address
     * @param amount    the amount of Kin to be funded on creation
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable createAccountSync(@NonNull String address, @NonNull Double amount) {
        return Completable.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getCreateAccountUrl(address, amount))
                    .get()
                    .build();

            try {
                Response response = mOkHttpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    emitter.onComplete();
                } else {
                    emitter.onError(new Exception("fund account failed with code " + response.code()));
                }
            } catch (IOException e) {
                emitter.onError(e);
            }
        });
    }

    /**
     * Gives amount Kin to the wallet at address.
     * Runs synchronously, returning a completable for subscription.
     *
     * @param address   the wallet address
     * @param amount    the amount of Kin to be given
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable fundAccountSync(@NonNull String address, @NonNull Double amount) {
        return Completable.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getFundAccountUrl(address, amount))
                    .get()
                    .build();

            try {
                Response response = mOkHttpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    emitter.onComplete();
                } else {
                    emitter.onError(new Exception("fund account failed with code " + response.code()));
                }
            } catch (IOException e) {
                emitter.onError(e);
            }
        });
    }

    @NonNull
    private HttpUrl.Builder getFriendBotUrlBuilder(@NonNull String address, @NonNull Double amount) {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(mFriendBotUrl)
                .addQueryParameter("addr", address)
                .addQueryParameter("amount", amount.toString());
    }

    @NonNull
    private HttpUrl getCreateAccountUrl(@NonNull String address, @NonNull Double amount) {
        return getFriendBotUrlBuilder(address, amount).build();
    }

    @NonNull
    private HttpUrl getFundAccountUrl(@NonNull String address, @NonNull Double amount) {
        return getFriendBotUrlBuilder(address, amount).addPathSegment("fund").build();
    }
}
