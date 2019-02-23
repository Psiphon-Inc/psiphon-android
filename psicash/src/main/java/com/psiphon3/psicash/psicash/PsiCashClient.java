package com.psiphon3.psicash.psicash;


import android.content.Context;
import android.content.SharedPreferences;
import android.support.v4.util.Pair;
import android.util.Log;

import com.psiphon3.psicash.util.TunnelConnectionState;

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
import java.util.concurrent.TimeUnit;

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

    private PsiCashClient(final Context context) throws PsiCashException {
        sharedPreferences = context.getSharedPreferences(PSICASH_PREFERENCES_KEY, Context.MODE_PRIVATE);
        httpProxyPort = 0;
        psiCashLib = new PsiCashLibTester();
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
                }).build();
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
            String errorMessage = "Could not initialize PsiCash lib: error: " + err.message;
            if (err.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    public static synchronized PsiCashClient getInstance(final Context context) throws PsiCashException {
        if (INSTANCE == null) {
            INSTANCE = new PsiCashClient(context);
        }
        return INSTANCE;
    }

    private List<String> getPurchaseClasses() {
        return new ArrayList<>(Arrays.asList("speed-boost"));
    }

    private void setPsiCashRequestMetaData(TunnelConnectionState.PsiCashMetaData psiCashMetaData) throws PsiCashException {
        Map<String, String> metaData = new HashMap<>();
        metaData.put("client_version", psiCashMetaData.clientVersion());
        metaData.put("propagation_channel_id", psiCashMetaData.propagationChannelId());
        metaData.put("client_region", psiCashMetaData.clientRegion());
        metaData.put("sponsor_id", psiCashMetaData.sponsorId());
        for (Map.Entry<String, String> h : metaData.entrySet()) {
            PsiCashLib.Error error = psiCashLib.setRequestMetadataItem(h.getKey(), h.getValue());
            if (error != null) {
                String errorMessage = error.message;
                if (error.critical) {
                    throw new PsiCashException.Recoverable(errorMessage);
                } else {
                    throw new PsiCashException.Critical(errorMessage);
                }
            }
        }
    }

    public void setOkHttpClientHttpProxyPort(int httpProxyPort) throws PsiCashException.Critical {
        if (httpProxyPort <= 0) {
            throw new PsiCashException.Critical("Bad OkHttp client proxy port value: " + httpProxyPort);
        }
        this.httpProxyPort = httpProxyPort;
    }

    public String modifiedHomePageURL(String originaUrl) throws PsiCashException {
        PsiCashLib.ModifyLandingPageResult modifyLandingPageResult = psiCashLib.modifyLandingPage(originaUrl);
        if (modifyLandingPageResult.error == null) {
            return modifyLandingPageResult.url;
        } else {
            String errorMessage = modifyLandingPageResult.error.message;
            if (modifyLandingPageResult.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
    }

    public String rewardedVideoCustomData() throws PsiCashException {
        PsiCashLib.GetRewardedActivityDataResult rewardedActivityData = psiCashLib.getRewardedActivityData();
        if (rewardedActivityData.error == null) {
            return rewardedActivityData.data;
        } else {
            String errorMessage = rewardedActivityData.error.message;
            if (rewardedActivityData.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
    }

    // TODO implement and use this
    public String logForFeedback() {
        return "";
    }

    public boolean hasValidTokens() throws PsiCashException {
        PsiCashLib.ValidTokenTypesResult validTokenTypesResult = psiCashLib.validTokenTypes();
        if (validTokenTypesResult.error == null) {
            return validTokenTypesResult.validTokenTypes.size() > 0;
        } else {
            String errorMessage = validTokenTypesResult.error.message;
            if (validTokenTypesResult.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
    }

    private boolean hasToken(PsiCashLib.TokenType tokenType) throws PsiCashException {
        PsiCashLib.ValidTokenTypesResult validTokenTypesResult = psiCashLib.validTokenTypes();
        if (validTokenTypesResult.error  == null) {
            return validTokenTypesResult.validTokenTypes.contains(tokenType);
        } else {
            String errorMessage = validTokenTypesResult.error.message;
            if (validTokenTypesResult.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
    }

    public boolean hasEarnerToken() throws PsiCashException {
        return hasToken(PsiCashLib.TokenType.EARNER);
    }

    public boolean hasSpenderToken() throws PsiCashException {
        return hasToken(PsiCashLib.TokenType.SPENDER);
    }

    public boolean hasIndicatorToken() throws PsiCashException {
        return hasToken(PsiCashLib.TokenType.INDICATOR);
    }

    boolean hasAccountToken() throws PsiCashException {
        return hasToken(PsiCashLib.TokenType.ACCOUNT);
    }


    private PsiCashModel.PsiCash psiCashModelFromLib() throws PsiCashException {
        PsiCashModel.PsiCash.Builder builder = PsiCashModel.PsiCash.builder();

        PsiCashLib.BalanceResult balanceResult = psiCashLib.balance();
        if (balanceResult.error != null) {
            String errorMessage = "PsiCashLib.BalanceResult error: " + balanceResult.error.message;
            if (balanceResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }

        builder.balance(balanceResult.balance);

        PsiCashLib.GetPurchasePricesResult getPurchasePricesResult = psiCashLib.getPurchasePrices();
        if (getPurchasePricesResult.error != null) {
            String errorMessage = "PsiCashLib.GetPurchasePricesResult error: " + balanceResult.error.message;
            if (getPurchasePricesResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
        builder.purchasePrices(getPurchasePricesResult.purchasePrices);

        PsiCashLib.NextExpiringPurchaseResult nextExpiringPurchaseResult = psiCashLib.nextExpiringPurchase();
        if (nextExpiringPurchaseResult.error != null) {
            String errorMessage = "PsiCashLib.NextExpiringPurchaseResult error: " + balanceResult.error.message;
            if (nextExpiringPurchaseResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
        builder.nextExpiringPurchase(nextExpiringPurchaseResult.purchase);

        builder.reward(getVideoReward());

        return builder.build();
    }

    // May return either PsiCashModel.PsiCash or PsiCashModel.ExpiringPurchase
    Observable<? extends PsiCashModel> makeExpiringPurchase(TunnelConnectionState connectionState, PsiCashLib.PurchasePrice price) {
        return Observable.just(Pair.create(connectionState, price))
                .observeOn(Schedulers.io())
                .flatMap(pair -> Single.fromCallable(() -> {
                    TunnelConnectionState state = pair.first;
                    PsiCashLib.PurchasePrice p = pair.second;

                    if (state.status() == TunnelConnectionState.Status.DISCONNECTED) {
                        throw new PsiCashException.Recoverable("makeExpiringPurchase: not connected.", "Please connect to make a Speed Boost purchase.");
                    }
                    if (p == null) {
                        throw new PsiCashException.Critical("Purchase price is null!");
                    }

                    if (connectionState.psiCashMetaData() != null) {
                        setPsiCashRequestMetaData(connectionState.psiCashMetaData());
                    }
                    setOkHttpClientHttpProxyPort(state.httpPort());

                    PsiCashLib.NewExpiringPurchaseResult result =
                            psiCashLib.newExpiringPurchase(p.transactionClass,
                                    p.distinguisher, p.price);

                    if (result.error != null) {
                        if (result.error.critical) {
                            throw new PsiCashException.Critical(result.error.message);
                        } else {
                            throw new PsiCashException.Recoverable(result.error.message);
                        }
                    }

                    if (result.status != PsiCashLib.Status.SUCCESS) {
                        throw new PsiCashException.Transaction(result.status);
                    }
                    return PsiCashModel.ExpiringPurchase.builder()
                            .expiringPurchase(result.purchase)
                            .build();
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


    Observable<PsiCashModel.PsiCash> getPsiCash(TunnelConnectionState connectionState) {
        int retryCount = 5;
        return Observable.just(connectionState)
                .observeOn(Schedulers.io())
                .flatMap(c -> {
                    if (c.status() == TunnelConnectionState.Status.DISCONNECTED) {
                        return getPsiCashLocal();
                    } else if (c.status() == TunnelConnectionState.Status.CONNECTED) {
                        setOkHttpClientHttpProxyPort(c.httpPort());
                        if (c.psiCashMetaData() != null) {
                            setPsiCashRequestMetaData(c.psiCashMetaData());
                        }
                        return Completable.fromAction(() -> {
                            PsiCashLib.RefreshStateResult result = psiCashLib.refreshState(getPurchaseClasses());
                            if (result.error != null) {
                                if (result.error.critical) {
                                    throw new PsiCashException.Critical(result.error.message, "Cannot retrieve PsiCash balance from the server. Please send feedback.");
                                } else {
                                    throw new PsiCashException.Recoverable(result.error.message, "Cannot retrieve PsiCash balance from the server at the moment. Please try again later.");
                                }
                            }
                            if (result.status != PsiCashLib.Status.SUCCESS) {
                                throw new PsiCashException.Transaction(result.status);
                            }
                        })
                                .andThen(getPsiCashLocal());
                    }
                    throw new IllegalArgumentException("Unknown TunnelConnectionState: " + c);
                })
                // retry {retryCount} times every 2 seconds before giving up
                .retryWhen(errors -> errors
                        .zipWith(Observable.range(1, retryCount), (err, i) -> {
                            if (i < retryCount && !(err instanceof PsiCashException.Critical)) {
                                return Observable.timer(2, TimeUnit.SECONDS);
                            } // else
                            return Observable.error(err);
                        })
                        .flatMap(x -> x));
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


