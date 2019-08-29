package com.psiphon3.kin;

import android.support.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

class ServerCommunicator {

    private final String mFriendBotUrl;
    private final OkHttpClient mOkHttpClient;

    /**
     * @param friendBotUrl the URL to the friend bot server
     */
    ServerCommunicator(@NonNull String friendBotUrl) {
        mFriendBotUrl = friendBotUrl;

        mOkHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param address the wallet address
     * @param amount  the amount of Kin to be funded on creation
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable createAccount(@NonNull String address, @NonNull Double amount) {
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
                    emitter.onError(new Exception("create account failed with code " + response.code()));
                }
            } catch (IOException e) {
                emitter.onError(e);
            }
        });
    }

    /**
     * Gives amount Kin to the wallet at address.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param address the wallet address
     * @param amount  the amount of Kin to be given
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable fundAccount(@NonNull String address, @NonNull Double amount) {
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
