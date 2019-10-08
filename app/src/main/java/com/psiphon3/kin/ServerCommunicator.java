package com.psiphon3.kin;

import android.support.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.psiphon3.psiphonlibrary.Utils;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Single;
import kin.sdk.WhitelistableTransaction;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class ServerCommunicator {
    private final String friendBotUrl;
    private final OkHttpClient okHttpClient;

    /**
     * @param friendBotUrl the URL to the friend bot server
     */
    ServerCommunicator(@NonNull String friendBotUrl) {
        this.friendBotUrl = friendBotUrl;

        okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param address the wallet address
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable createAccount(@NonNull String address) {
        return createAccountInner(address);
    }

    private Completable createAccountInner(@NonNull String address) {
        return Completable.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getCreateAccountUrl(address))
                    .get()
                    .build();

            final Call call;
            try {
                call = okHttpClient.newCall(request);
                emitter.setCancellable(call::cancel);
                Response response = okHttpClient.newCall(request).execute();
                // HTTP code 409 means account already registered, treat as success
                if ((response.isSuccessful() || response.code() == 409)) {
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                } else {
                    String msg = "KinManager: register account on the blockchain failed with code " + response.code();
                    Utils.MyLog.g(msg);
                    if (!emitter.isDisposed()) {
                        emitter.onError(new Exception(msg));
                    }
                }
            } catch (IOException e) {
                Utils.MyLog.g(e.getMessage());
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            }
        })
                .doOnComplete(() -> Utils.MyLog.g("KinManager: success registering account on the blockchain"));
    }

    /**
     * Attempts to whitelist the transaction.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param whitelistableTransaction a transaction to be whitelisted by the server
     * @return the whitelist transaction data from the server
     */
    Single<String> whitelistTransaction(@NonNull String address, @NonNull WhitelistableTransaction whitelistableTransaction) {
        return whitelistTransactionInner(address, whitelistableTransaction);
    }

    private Single<String> whitelistTransactionInner(@NonNull String address, @NonNull WhitelistableTransaction whitelistableTransaction) {
        return Single.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getWhiteListTransactionUrl(address))
                    .post(createWhitelistableTransactionBody(whitelistableTransaction))
                    .build();
            final Call call;
            try {
                Utils.MyLog.g("KinManager: whitelisting a transaction");
                call = okHttpClient.newCall(request);
                emitter.setCancellable(call::cancel);
                Response response = call.execute();
                if (response.isSuccessful()) {
                    if (response.body() != null) {
                        String hash = parseFriendBotResponse(response.body().charStream());
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(hash);
                        }
                    } else {
                        String msg = "KinManager: whitelist transaction failed with code " + response.code();
                        Utils.MyLog.g(msg);
                        if (!emitter.isDisposed()) {
                            emitter.onError(new Exception(msg));
                        }
                    }
                } else {
                    String msg = "KinManager: whitelist transaction failed with code " + response.code();
                    Utils.MyLog.g(msg);
                    if (!emitter.isDisposed()) {
                        emitter.onError(new Exception(msg));
                    }
                }
            } catch (IOException e) {
                Utils.MyLog.g(e.getMessage());
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            }
        });
    }

    @NonNull
    private RequestBody createWhitelistableTransactionBody(@NonNull WhitelistableTransaction whitelistableTransaction) {
        MediaType mediaType = MediaType.get("application/json");
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("envelope", whitelistableTransaction.getTransactionPayload());
        jsonObject.addProperty("network_id", whitelistableTransaction.getNetworkPassphrase());
        return RequestBody.create(mediaType, jsonObject.toString());
    }

    @NonNull
    private String parseFriendBotResponse(@NonNull Reader reader) {
        JsonParser jsonParser = new JsonParser();
        JsonObject jsonObject = jsonParser.parse(reader).getAsJsonObject();
        return jsonObject.get("hash").getAsString();
    }

    @NonNull
    private HttpUrl.Builder getFriendBotUrlBuilder() {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(friendBotUrl)
                .addPathSegment("v1");
    }

    @NonNull
    private HttpUrl getCreateAccountUrl(@NonNull String address) {
        return getFriendBotUrlBuilder()
                .addPathSegment("create")
                .addQueryParameter("address", address)
                .build();
    }

    @NonNull
    private HttpUrl getWhiteListTransactionUrl(@NonNull String address) {
        return getFriendBotUrlBuilder()
                .addPathSegment("whitelist")
                .addQueryParameter("address", address)
                .build();
    }
}
