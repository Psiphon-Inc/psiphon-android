package com.psiphon3.kin;

import android.support.annotation.NonNull;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.WhitelistableTransaction;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class ServerCommunicator {
    // This port is unable to be used for connection
    // According to https://stackoverflow.com/questions/42112735/android-7-reserved-ip-ports-restriction
    // ports 32100 to 32600 are reserved by android
    static final int PREVENT_CONNECTION_PORT = 32123;

    private final String friendBotUrl;
    private final OkHttpClient okHttpClient;
    private final BehaviorRelay<Boolean> isTunneledBehaviorRelay;
    private int port = PREVENT_CONNECTION_PORT; // start with a port which prevents connection

    /**
     * @param friendBotUrl the URL to the friend bot server
     */
    ServerCommunicator(@NonNull String friendBotUrl) {
        this.friendBotUrl = friendBotUrl;

        okHttpClient = new OkHttpClient.Builder()
                .proxySelector(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        return Collections.singletonList(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", port)));
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
                    }
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();

        isTunneledBehaviorRelay = BehaviorRelay.createDefault(false);
    }

    private Single<Boolean> waitUntilTunneled() {
        return isTunneledBehaviorRelay
                .distinctUntilChanged()
                .hide()
                .filter(isTunneled -> isTunneled)
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io());
    }

    /**
     * Creates an account on the server for the wallet address, funding it with amount of Kin.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param address the wallet address
     * @return a completable which fires on complete after receiving a successful response
     */
    Completable createAccount(@NonNull String address) {
        return waitUntilTunneled()
                .map(__ -> address)
                .flatMapCompletable(this::createAccountInner);
    }

    private Completable createAccountInner(@NonNull String address) {
        return Completable.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getCreateAccountUrl(address))
                    .get()
                    .build();

            try {
                Response response = okHttpClient.newCall(request).execute();
                if (response.isSuccessful() && !emitter.isDisposed()) {
                    emitter.onComplete();
                } else if (!emitter.isDisposed()) {
                    String msg = "create account failed with code " + response.code();
                    Utils.MyLog.g(msg);
                    emitter.onError(new Exception(msg));
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
     * Let's the server know this user has opted out for coarse-stats.
     * Runs async.
     */
    void optOut() {
        waitUntilTunneled()
                .doOnSuccess(__ -> optOutInner())
                .subscribe();
    }

    private void optOutInner() {
        Request request = new Request.Builder()
                .url(getOptOutUrl())
                .head()
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Don't give a hoot
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                // Don't give a hoot
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
    Single<String> whitelistTransaction(@NonNull WhitelistableTransaction whitelistableTransaction) {
        return waitUntilTunneled()
                .map(__ -> whitelistableTransaction)
                .flatMap(this::whitelistTransactionInner);
    }

    private Single<String> whitelistTransactionInner(@NonNull WhitelistableTransaction whitelistableTransaction) {
        return Single.create(emitter -> {
            Request request = new Request.Builder()
                    .url(getWhiteListTransactionUrl())
                    .post(createWhitelistableTransactionBody(whitelistableTransaction))
                    .build();

            try {
                Response response = okHttpClient.newCall(request).execute();
                if (response.isSuccessful()) {
                    if (response.body() != null) {
                        String hash = parseFriendBotResponse(response.body().charStream());
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(hash);
                        }
                    } else if (!emitter.isDisposed()) {
                        String msg = "whitelist transaction didn't return a body";
                        Utils.MyLog.g(msg);
                        emitter.onError(new Exception(msg));
                    }
                } else if (!emitter.isDisposed()) {
                    String msg = "whitelist transaction failed with code " + response.code();
                    Utils.MyLog.g(msg);
                    emitter.onError(new Exception(msg));
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
                .host(friendBotUrl);
    }

    @NonNull
    private HttpUrl getCreateAccountUrl(@NonNull String address) {
        return getFriendBotUrlBuilder()
                .addQueryParameter("addr", address)
                .build();
    }

    @NonNull
    private HttpUrl getWhiteListTransactionUrl() {
        return getFriendBotUrlBuilder()
                .addPathSegment("whitelist")
                .build();
    }

    private HttpUrl getOptOutUrl() {
        return getFriendBotUrlBuilder()
                .addPathSegment("no")
                .build();
    }

    public void setProxyPort(int port) {
        this.port = port;
    }

    public void onTunnelConnectionState(TunnelState tunnelState) {
        // Not running, prevent proxy
        TunnelState.ConnectionData connectionData = tunnelState.connectionData();
        if (tunnelState.isRunning() && connectionData.httpPort() > 0) {
            setProxyPort(connectionData.httpPort());
        } else {
            setProxyPort(PREVENT_CONNECTION_PORT);
        }

        if(tunnelState.isRunning() && connectionData.isConnected()) {
            isTunneledBehaviorRelay.accept(true);
        } else {
            isTunneledBehaviorRelay.accept(false);
        }
    }
}
