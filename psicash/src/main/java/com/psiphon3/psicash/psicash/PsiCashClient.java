package com.psiphon3.psicash.psicash;


import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.util.Pair;
import android.util.Log;

import com.psiphon3.psicash.util.TunnelConnectionStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PsiCashClient {
    private static final String TAG = "PsiCashClient";

    private static final String PSICASH_PERSISTED_VIDEO_REWARD_KEY = "psiCashPersistedVideoRewardKey";
    private static final String PSICASH_PREFERENCES_KEY = "app_prefs";

    private static PsiCashClient INSTANCE = null;

    private PsiCashLib psiCashLib;
    private int httpProxyPort;
    private OkHttpClient okHttpClient;
    private SharedPreferences sharedPreferences;

    private PsiCashClient(final Context context) {
        sharedPreferences = context.getSharedPreferences(PSICASH_PREFERENCES_KEY, Context.MODE_PRIVATE);

        httpProxyPort = 0;
        okHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .proxySelector(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        List<Proxy> list = new ArrayList<>();
                        Proxy proxy;
                        if (httpProxyPort > 0) {
                            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", httpProxyPort));
                        } else {
                            proxy = Proxy.NO_PROXY;
                        }
                        list.add(proxy);
                        return list;
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
                    }
                })
                .build();

        psiCashLib = new PsiCashLibTester();

        PsiCashLib.Error err = psiCashLib.init(
                context.getFilesDir().toString(),
                reqParams -> {
                    PsiCashLib.HTTPRequester.Result result = new PsiCashLib.HTTPRequester.Result();
                    Request.Builder reqBuilder = new Request.Builder();

                    try {
                        reqBuilder.url(reqParams.uri.toString());
                        if (reqParams.method.equalsIgnoreCase("GET")) {
                            reqBuilder.get();
                        } else if (reqParams.method.equalsIgnoreCase("POST")) {
                            reqBuilder.post(RequestBody.create(null, new byte[0]));
                        } else if (reqParams.method.equalsIgnoreCase("PUT")) {
                            reqBuilder.put(RequestBody.create(null, new byte[0]));
                        } else if (reqParams.method.equalsIgnoreCase("HEAD")) {
                            reqBuilder.head();
                        }

                        if (reqParams.headers != null) {
                            reqBuilder.headers(Headers.of(reqParams.headers));
                        }

                        Request request = reqBuilder.build();

                        Response response = okHttpClient.newCall(request).execute();

                        result.code = response.code();
                        result.date = response.header("Date");

                        if (response.body() != null) {
                            result.body = response.body().string();
                            response.body().close();
                            Log.d(TAG, "got network response for " + reqParams.uri.toString() + ": " + result.body);

                        }
                    } catch (IOException e) {
                        result.code = PsiCashLib.HTTPRequester.Result.RECOVERABLE_ERROR;
                        result.error = e.toString();
                        result.body = null;
                    }
                    return result;
                });
        if (err != null) {
            throw new RuntimeException("Could not initialize PsiCash lib, error: " + err.message);
        }
    }

    public static synchronized PsiCashClient getInstance(final Context context) {
        if (INSTANCE == null) {
            INSTANCE = new PsiCashClient(context);
        }
        return INSTANCE;
    }

    private List<String> getPurchaseClasses() {
        return new ArrayList<>(Arrays.asList("speed-boost"));
    }

    // TODO implement actual metadata values
    private void setPsiCashRequestMetaData() {
        Map<String, String> metaData = new HashMap<>();
        metaData.put("client_version", "1.0");
        metaData.put("propagation_channel_id", "PROPAGATION_CHANNEL_ID");
        metaData.put("client_region", "XX");
        metaData.put("sponsor_id", "SPONSOR_ID");

        for (Map.Entry<String, String> h : metaData.entrySet()) {
            PsiCashLib.Error error = psiCashLib.setRequestMetadataItem(h.getKey(), h.getValue());
            if (error != null) {
                throw new RuntimeException("Error setting request metadata item: " + error.message);
            }
        }
    }

    // TODO use this
    public void setOkHttpClientHttpProxyPort(int httpProxyPort) {
        this.httpProxyPort = httpProxyPort;
    }

    // TODO use this
    public String modifiedHomePageURL(String originaUrl) {
        PsiCashLib.ModifyLandingPageResult modifyLandingPageResult = psiCashLib.modifyLandingPage(originaUrl);
        if (modifyLandingPageResult.error == null) {
            return modifyLandingPageResult.url;
        } else {
            // TODO log error
            return originaUrl;
        }
    }

    public String rewardedVideoCustomData() {
        PsiCashLib.GetRewardedActivityDataResult rewardedActivityData = psiCashLib.getRewardedActivityData();
        if (rewardedActivityData.error == null) {
            return rewardedActivityData.data;
        } else {
            // TODO log error
            return null;
        }
    }

    // TODO implement and use this
    public String logForFeedback() {
        return "";
    }

    public boolean hasValidTokens() throws RuntimeException {
        PsiCashLib.ValidTokenTypesResult validTokenTypesResult = psiCashLib.validTokenTypes();
        if (validTokenTypesResult.error != null) {
            throw new PsiCashError.CriticalError("PsiCashLib.ValidTokenTypesResult error: " + validTokenTypesResult.error);
        }
        return validTokenTypesResult.validTokenTypes.size() > 0;
    }

    private boolean hasToken(PsiCashLib.TokenType tokenType) throws RuntimeException {
        PsiCashLib.ValidTokenTypesResult validTokenTypesResult = psiCashLib.validTokenTypes();
        if (validTokenTypesResult.error != null) {
            throw new PsiCashError.CriticalError("PsiCashLib.ValidTokenTypesResult error: " + validTokenTypesResult.error);
        }
        return validTokenTypesResult.validTokenTypes.contains(tokenType);
    }

    public boolean hasEarnerToken() {
        return hasToken(PsiCashLib.TokenType.EARNER);
    }

    boolean hasSpenderToken() {
        return hasToken(PsiCashLib.TokenType.SPENDER);
    }

    boolean hasIndicatorToken() {
        return hasToken(PsiCashLib.TokenType.INDICATOR);
    }

    boolean hasAccountToken() {
        return hasToken(PsiCashLib.TokenType.ACCOUNT);
    }


    private PsiCashModel.PsiCash psiCashModelFromLib() {
        PsiCashModel.PsiCash.Builder builder = PsiCashModel.PsiCash.builder();


        // TODO replace this with the server side expiration mechanism
        psiCashLib.expirePurchases();

        PsiCashLib.BalanceResult balanceResult = psiCashLib.balance();
        if (balanceResult.error != null) {
            throw new RuntimeException("PsiCashLib.BalanceResult error: " + balanceResult.error);
        }
        builder.balance(balanceResult.balance);

        PsiCashLib.GetPurchasePricesResult getPurchasePricesResult = psiCashLib.getPurchasePrices();
        if (getPurchasePricesResult.error != null) {
            throw new RuntimeException("PsiCashLib.BalanceResult error: " + getPurchasePricesResult.error);
        }
        builder.purchasePrices(getPurchasePricesResult.purchasePrices);

        PsiCashLib.NextExpiringPurchaseResult nextExpiringPurchaseResult = psiCashLib.nextExpiringPurchase();
        if (nextExpiringPurchaseResult.error != null) {
            throw new RuntimeException("PsiCashLib.NextExpiringPurchaseResult error: " + nextExpiringPurchaseResult.error);
        }
        builder.nextExpiringPurchase(nextExpiringPurchaseResult.purchase);

        builder.reward(getVideoReward());

        return builder.build();
    }


    // TODO: another processor to send disconnected status just to cancel all inflight requests?
    private void cancelOutstandingNetworkRequests() {
        Log.d(TAG, "cancelling outstanding network requests");
        okHttpClient.dispatcher().cancelAll();
    }

    // May return either PsiCashModel.PsiCash or PsiCashModel.ExpiringPurchase
    Observable<? extends PsiCashModel> makeExpiringPurchase(TunnelConnectionStatus connectionStatus, PsiCashLib.PurchasePrice price) {
        return Observable.just(Pair.create(connectionStatus, price))
                .observeOn(Schedulers.io())
                .flatMap(pair -> Single.fromCallable(() -> {
                    TunnelConnectionStatus s = pair.first;
                    PsiCashLib.PurchasePrice p = pair.second;

                    if (s == TunnelConnectionStatus.DISCONNECTED) {
                        throw new PsiCashError.RecoverableError("makeExpiringPurchase: not connected.", "Please connect to make a Speed Boost purchase.");
                    }
                    if (p == null) {
                        throw new PsiCashError.CriticalError("Purchase price is null!");
                    }

                    setPsiCashRequestMetaData();

                    PsiCashLib.NewExpiringPurchaseResult result =
                            psiCashLib.newExpiringPurchase(p.transactionClass,
                                    p.distinguisher, p.price);

                    if (result.error != null) {
                        if (result.error.critical) {
                            throw new PsiCashError.CriticalError(result.error.message);
                        } else {
                            throw new PsiCashError.RecoverableError(result.error.message);
                        }
                    }

                    if (result.status != PsiCashLib.Status.SUCCESS) {
                        throw new PsiCashError.TransactionError(result.status);
                    }
                    return PsiCashModel.ExpiringPurchase.builder()
                            .expiringPurchase(result.purchase)
                            .build();
                })
                        .doOnSuccess(p -> {
                            // TODO: Store authorization from purchase and restart tunnel
                        })
                        .cast(PsiCashModel.class)
                        .toObservable()
                        .concatWith(getPsiCashLocal())
                        .onErrorResumeNext(err -> {
                            return getPsiCashLocal().concatWith(Single.error(err));
                        }));
    }

    Observable<PsiCashModel.PsiCash> getPsiCashLocal() {
        return Single.fromCallable(this::psiCashModelFromLib)
                .toObservable();
    }


    Observable<PsiCashModel.PsiCash> getPsiCash(TunnelConnectionStatus connectionStatus) {
        return Observable.just(connectionStatus)
                .observeOn(Schedulers.io())
                .flatMap(c -> {
                    if (c == TunnelConnectionStatus.DISCONNECTED) {
                        cancelOutstandingNetworkRequests();
                        return getPsiCashLocal();
                    } else if (c == TunnelConnectionStatus.CONNECTED) {
                        setPsiCashRequestMetaData();
                        return Completable.fromAction(() -> {
                            PsiCashLib.RefreshStateResult result = psiCashLib.refreshState(getPurchaseClasses());
                            if (result.error != null) {
                                if (result.error.critical) {
                                    throw new PsiCashError.CriticalError(result.error.message);
                                } else {
                                    throw new PsiCashError.RecoverableError(result.error.message);
                                }
                            }
                            if (result.status != PsiCashLib.Status.SUCCESS) {
                                throw new PsiCashError.TransactionError(result.status);
                            }
                        })
                                .andThen(getPsiCashLocal());
                    }
                    throw new IllegalArgumentException("Unknown TunnelConnectionStatus: " + c);
                });
    }

    public synchronized void putVideoReward (long reward) {
        long storedReward = getVideoReward();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(PSICASH_PERSISTED_VIDEO_REWARD_KEY, storedReward + reward);
        editor.apply();
    }

    private long getVideoReward() {
        return sharedPreferences.getLong(PSICASH_PERSISTED_VIDEO_REWARD_KEY, 0);
    }

    public void removePurchases(List<String> purchasesToRemove) {
        psiCashLib.removePurchases(purchasesToRemove);
    }

    public class PsiCashLibTester extends PsiCashLib {
        @Override
        public Error init(String fileStoreRoot, HTTPRequester httpRequester) {
            return init(fileStoreRoot, httpRequester, true);
        }
    }
}


