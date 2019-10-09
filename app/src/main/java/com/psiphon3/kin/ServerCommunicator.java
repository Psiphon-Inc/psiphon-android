package com.psiphon3.kin;

import android.support.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.psiphon3.psiphonlibrary.Utils;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Flowable;
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
    private static final int RETRIES_LIMIT = 5;
    private final String kinApplicationServerUrl;
    private final OkHttpClient okHttpClient;

    /**
     * @param kinApplicationServerUrl the URL to the kin application server URL
     */
    ServerCommunicator(@NonNull String kinApplicationServerUrl) {
        this.kinApplicationServerUrl = kinApplicationServerUrl;

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
        return createAccountInner(address)
                .retryWhen(errors ->
                        errors.zipWith(
                                Flowable.range(1, RETRIES_LIMIT + 1),
                                (error, retryCount) -> {
                                    if (retryCount > RETRIES_LIMIT || error instanceof FatalException) {
                                        return Flowable.error(error);
                                    } else {
                                        // exponential backoff with timer
                                        double retryInSeconds = Math.pow(3, retryCount);
                                        Utils.MyLog.g("KinManager: will retry registering account on blockchain in " + retryInSeconds + " seconds due to error: " + error);
                                        return Flowable.timer((long) retryInSeconds, TimeUnit.SECONDS);
                                    }
                                })
                                .flatMap(x -> x)
                )
                .doOnDispose(() ->  Utils.MyLog.g("KinManager: register account subscription is disposed"))
                .doOnComplete(() -> Utils.MyLog.g("KinManager: success registering account on the blockchain"));
    }

    private Completable createAccountInner(@NonNull String address) {
        return Completable.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getCreateAccountUrl(address))
                    .get()
                    .build();

            final Call call;
            try {
                Utils.MyLog.g("KinManager: registering account on the blockchain");
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
                    final Throwable e;
                    if (response.code() >= 400 && response.code() <= 499) {
                        e = new FatalException(msg);
                    } else {
                        e = new RetriableException(msg);
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onError(e);
                    }
                }
                response.close();
            } catch (IOException e) {
                Utils.MyLog.g(e.getMessage());
                if (!emitter.isDisposed()) {
                    emitter.onError(new RetriableException(e.toString()));
                }
            }
        });
    }

    /**
     * Attempts to whitelist the transaction.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param whitelistableTransaction a transaction to be whitelisted by the server
     * @return the whitelist transaction data from the server
     */
    Single<String> whitelistTransaction(@NonNull String address, @NonNull WhitelistableTransaction whitelistableTransaction) {
        return whitelistTransactionInner(address, whitelistableTransaction)
                .retryWhen(errors ->
                        errors.zipWith(
                                Flowable.range(1, RETRIES_LIMIT + 1),
                                (error, retryCount) -> {
                                    if (retryCount > RETRIES_LIMIT || error instanceof FatalException) {
                                        return Flowable.error(error);
                                    } else {
                                        // exponential backoff with timer
                                        double retryInSeconds = Math.pow(3, retryCount);
                                        Utils.MyLog.g("KinManager: will retry whitelisting transaction in " + retryInSeconds + " seconds due to error: " + error);
                                        return Flowable.timer((long) retryInSeconds, TimeUnit.SECONDS);
                                    }
                                })
                                .flatMap(x -> x)
                )
                .doOnDispose(() ->  Utils.MyLog.g("KinManager: whitelisting a transaction subscription is disposed"))
                .doOnSuccess(__ -> Utils.MyLog.g("KinManager: success whitelisting a transaction"));
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
                        String hash = parseKinApplicationServerResponse(response.body().charStream());
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(hash);
                        }
                    } else {
                        String msg = "KinManager: whitelist transaction failed with empty body";
                        Utils.MyLog.g(msg);
                        if (!emitter.isDisposed()) {
                            emitter.onError(new RetriableException(msg));
                        }
                    }
                } else {
                    String msg = "KinManager: whitelist transaction failed with code " + response.code();
                    Utils.MyLog.g(msg);
                    final Throwable e;
                    if (response.code() >= 400 && response.code() <= 499) {
                        e = new FatalException(msg);
                    } else {
                        e = new RetriableException(msg);
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onError(e);
                    }
                }
                response.close();
            } catch (IOException e) {
                Utils.MyLog.g(e.getMessage());
                if (!emitter.isDisposed()) {
                    emitter.onError(new RetriableException(e.toString()));
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
    private String parseKinApplicationServerResponse(@NonNull Reader reader) {
        JsonParser jsonParser = new JsonParser();
        JsonObject jsonObject = jsonParser.parse(reader).getAsJsonObject();
        return jsonObject.get("hash").getAsString();
    }

    @NonNull
    private HttpUrl.Builder getKinApplicationServerUrlBuilder() {
        return new HttpUrl.Builder()
                .scheme("https")
                .host(kinApplicationServerUrl)
                .addPathSegment("v1");
    }

    @NonNull
    private HttpUrl getCreateAccountUrl(@NonNull String address) {
        return getKinApplicationServerUrlBuilder()
                .addPathSegment("create")
                .addQueryParameter("address", address)
                .build();
    }

    @NonNull
    private HttpUrl getWhiteListTransactionUrl(@NonNull String address) {
        return getKinApplicationServerUrlBuilder()
                .addPathSegment("whitelist")
                .addQueryParameter("address", address)
                .build();
    }

    private class RetriableException extends Throwable {
        RetriableException(String cause) {
            super(cause);
        }
    }

    private class FatalException extends Throwable {
        FatalException(String cause) {
            super(cause);
        }
    }
}
