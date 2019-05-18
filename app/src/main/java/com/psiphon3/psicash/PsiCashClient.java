/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psicash;


import android.content.Context;
import android.content.SharedPreferences;

import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PsiCashClient {
    private static final String TAG = "PsiCashClient";

    private static final String PSICASH_PERSISTED_VIDEO_REWARD_KEY = "psiCashPersistedVideoRewardKey";
    private static final String PSICASH_PREFERENCES_KEY = "app_prefs";

    private static PsiCashClient INSTANCE = null;
    private Context appContext;

    private final PsiCashLib psiCashLib;
    private int httpProxyPort;
    private final OkHttpClient okHttpClient;
    private final SharedPreferences sharedPreferences;

    private PsiCashClient(final Context ctx) throws PsiCashException {
        this.appContext = ctx;
        sharedPreferences = ctx.getSharedPreferences(PSICASH_PREFERENCES_KEY, Context.MODE_PRIVATE);
        httpProxyPort = 0;
        psiCashLib = new PsiCashLib();
        okHttpClient = new OkHttpClient.Builder()

                // TODO: remove this workaround after applying proxy relay EOF fix in the tunnel-core
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))

                .retryOnConnectionFailure(false)
                .proxySelector(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        Proxy proxy;
                        if (httpProxyPort > 0) {
                            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", httpProxyPort));
                        } else {
                            proxy = Proxy.NO_PROXY;
                        }
                        return Collections.singletonList(proxy);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
                    }
                }).build();
        PsiCashLib.Error err = psiCashLib.init(
                ctx.getFilesDir().toString(),
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

                        // TODO: remove this workaround after applying proxy relay EOF fix in the tunnel-core
                        reqBuilder.addHeader("Connection", "close");

                        Request request = reqBuilder.build();
                        Response response = okHttpClient.newCall(request).execute();
                        result.code = response.code();
                        result.date = response.header("Date");
                        if (response.body() != null) {
                            result.body = response.body().string();
                            response.body().close();
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
            Utils.MyLog.g("PsiCash: " + errorMessage);
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
        return Collections.singletonList("speed-boost");
    }

    private void setPsiCashRequestMetaData(TunnelState.ConnectionData connectionData) throws PsiCashException {
        Map<String, String> metaData = new HashMap<>();
        metaData.put("client_version", connectionData.clientVersion());
        metaData.put("propagation_channel_id", connectionData.propagationChannelId());
        metaData.put("client_region", connectionData.clientRegion());
        metaData.put("sponsor_id", connectionData.sponsorId());
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

    private void setOkHttpClientHttpProxyPort(int httpProxyPort) throws PsiCashException.Critical {
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

    String rewardedVideoCustomData() throws PsiCashException {
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
        if (validTokenTypesResult.error == null) {
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

    boolean hasEarnerToken() throws PsiCashException {
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

        PsiCashLib.GetDiagnosticInfoResult diagnosticInfoResult = psiCashLib.getDiagnosticInfo();
        if (diagnosticInfoResult.error != null) {
            String errorMessage = "PsiCashLib.GetDiagnosticInfoResult error: " + diagnosticInfoResult.error.message;
            Utils.MyLog.g("PsiCash: " + errorMessage);
            if (diagnosticInfoResult.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
        builder.diagnosticInfo(diagnosticInfoResult.jsonString);

        PsiCashLib.BalanceResult balanceResult = psiCashLib.balance();
        if (balanceResult.error != null) {
            String errorMessage = "PsiCashLib.BalanceResult error: " + balanceResult.error.message;
            Utils.MyLog.g("PsiCash: " + errorMessage);
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
            Utils.MyLog.g("PsiCash: " + errorMessage);
            if (getPurchasePricesResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
        builder.purchasePrices(getPurchasePricesResult.purchasePrices);

        PsiCashLib.GetPurchasesResult getPurchasesResult = psiCashLib.getPurchases();
        if (getPurchasesResult.error != null) {
            String errorMessage = "PsiCashLib.GetPurchasesResult error: " + getPurchasesResult.error.message;
            Utils.MyLog.g("PsiCash: " + errorMessage);
            if (getPurchasesResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
        if (getPurchasesResult.purchases.size() > 0) {
            builder.nextExpiringPurchase(Collections.max(getPurchasesResult.purchases,
                    (p1, p2) -> p1.expiry.compareTo(p2.expiry)));
        }

        builder.reward(getVideoReward());

        return builder.build();
    }

    // Emits both PsiCashModel.PsiCash and PsiCashModel.ExpiringPurchase
    Observable<? extends PsiCashModel> makeExpiringPurchase(TunnelState connectionState, PsiCashLib.PurchasePrice price) {
        return Observable.just(connectionState)
                .observeOn(Schedulers.io())
                .flatMap(state -> Single.fromCallable(() -> {
                    TunnelState.ConnectionData connectionData = state.connectionData();

                    if (!state.isRunning()) {
                        throw new PsiCashException.Recoverable("makeExpiringPurchase: tunnel not running.",
                                appContext.getString(R.string.speed_boost_connect_to_purchase_message));
                    } else {
                        if (!connectionData.isConnected()) {
                            throw new PsiCashException.Recoverable("makeExpiringPurchase: tunnel not connected.",
                                    appContext.getString(R.string.speed_boost_wait_tunnel_to_connect_to_purchase_message));
                        }
                    }
                    if (price == null) {
                        throw new PsiCashException.Critical("makeExpiringPurchase: purchase price is null.");
                    }

                    setPsiCashRequestMetaData(connectionData);
                    setOkHttpClientHttpProxyPort(connectionData.httpPort());

                    PsiCashLib.NewExpiringPurchaseResult result =
                            psiCashLib.newExpiringPurchase(price.transactionClass,
                                    price.distinguisher, price.price);

                    if (result.error != null) {
                        Utils.MyLog.g("PsiCash: error making expiring purchase: " + result.error.message);
                        if (result.error.critical) {
                            throw new PsiCashException.Critical(result.error.message);
                        } else {
                            throw new PsiCashException.Recoverable(result.error.message);
                        }
                    }

                    // Reset optimistic reward if there's a response from the server
                    resetVideoReward();

                    if (result.status != PsiCashLib.Status.SUCCESS) {
                        Utils.MyLog.g("PsiCash: transaction error making expiring purchase: " + result.status);
                        throw new PsiCashException.Transaction(result.status);
                    }

                    Utils.MyLog.g("PsiCash: got new purchase of transactionClass " + result.purchase.transactionClass);

                    return PsiCashModel.ExpiringPurchase.create(result.purchase);
                })
                        .cast(PsiCashModel.class)
                        .toObservable()
                        .concatWith(getPsiCashLocal())
                        // If INSUFFICIENT_BALANCE retry for up to 30 seconds
                        // in hope that the server-to-server reward callback will catch up
                        .retryWhen(errors -> {
                            AtomicReference<Throwable> capturedThrowable = new AtomicReference<>();
                            return errors
                                    .flatMap(throwable -> {
                                        capturedThrowable.set(throwable);
                                        if (!(throwable instanceof PsiCashException.Transaction)) {
                                            return Observable.error(throwable);
                                        } else {
                                            PsiCashException.Transaction psiCashException = (PsiCashException.Transaction) throwable;
                                            if (psiCashException.getStatus() == PsiCashLib.Status.INSUFFICIENT_BALANCE) {
                                                return Observable.timer(2, TimeUnit.SECONDS);
                                            }
                                            return Observable.error(psiCashException);
                                        }
                                    })
                                    // timeout after 30 seconds and bubble up the last captured error if any
                                    .mergeWith(Observable.timer(30, TimeUnit.SECONDS)
                                            .flatMap(__ -> {
                                                final Throwable e = capturedThrowable.get();
                                                if (e != null) {
                                                    return Observable.error(e);
                                                }
                                                return Observable.empty();
                                            }));
                        })
                        .onErrorResumeNext(err -> {
                            return getPsiCashLocal().concatWith(Single.error(err));
                        }));
    }

    Observable<PsiCashModel.PsiCash> getPsiCashLocal() {
        return Single.fromCallable(this::psiCashModelFromLib)
                .toObservable();
    }

    Observable<PsiCashModel.PsiCash> getPsiCashRemote(TunnelState tunnelState) {
        int retryCount = 5;
        return Observable.just(tunnelState)
                .observeOn(Schedulers.io())
                .flatMap(state -> {
                    if (!state.isRunning()) {
                        throw new IllegalStateException("Attempting to get PsiCash from remote while in STOPPED tunnel state.");
                    }

                    TunnelState.ConnectionData connectionData = state.connectionData();
                    if (!connectionData.isConnected()) {
                        throw new IllegalStateException("Attempting to get PsiCash from remote while in NOT CONNECTED tunnel state.");
                    }

                    setOkHttpClientHttpProxyPort(connectionData.httpPort());
                    setPsiCashRequestMetaData(connectionData);

                    return Completable.fromAction(() -> {
                        PsiCashLib.RefreshStateResult result = psiCashLib.refreshState(getPurchaseClasses());
                        if (result.error != null) {
                            if (result.error.critical) {
                                throw new PsiCashException.Critical(result.error.message, appContext.getString(R.string.psicash_cant_get_balance_critical_message));
                            } else {
                                throw new PsiCashException.Recoverable(result.error.message, appContext.getString(R.string.psicash_cant_get_balance_recoverable_message));
                            }
                        }
                        if (result.status != PsiCashLib.Status.SUCCESS) {
                            throw new PsiCashException.Transaction(result.status);
                        }
                        // if success reset optimistic reward
                        resetVideoReward();
                    })
                            .andThen(getPsiCashLocal());
                })
                // retry {retryCount} times every 2 seconds before giving up
                .retryWhen(errors -> errors
                        .zipWith(Observable.range(1, retryCount), (err, i) -> {
                            if (i < retryCount && !(err instanceof PsiCashException.Critical)) {
                                return Observable.timer(2, TimeUnit.SECONDS);
                            } // else
                            return Observable.error(err);
                        })
                        .flatMap(x -> x))
                .onErrorResumeNext(err -> {
                    return getPsiCashLocal().concatWith(Single.error(err));
                });
    }

    public synchronized void putVideoReward(long reward) {
        long storedReward = getVideoReward();
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(PSICASH_PERSISTED_VIDEO_REWARD_KEY, storedReward + reward);
        editor.apply();
    }

    private synchronized void resetVideoReward() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(PSICASH_PERSISTED_VIDEO_REWARD_KEY, 0);
        editor.apply();
    }

    private long getVideoReward() {
        return sharedPreferences.getLong(PSICASH_PERSISTED_VIDEO_REWARD_KEY, 0);
    }

    Observable<PsiCashModel.PsiCash> removePurchases(List<String> purchasesToRemove) {
        return Observable.just(purchasesToRemove)
                .observeOn(Schedulers.io())
                .flatMap(p -> Completable.fromAction(() -> psiCashLib.removePurchases(p))
                        .andThen(getPsiCashLocal()));
    }

    public List<PsiCashLib.Purchase> getPurchases() throws PsiCashException {
        PsiCashLib.GetPurchasesResult getPurchasesResult = psiCashLib.getPurchases();
        if (getPurchasesResult.error == null) {
            return getPurchasesResult.purchases;
        } else {
            String errorMessage = getPurchasesResult.error.message;
            if (getPurchasesResult.error.critical) {
                throw new PsiCashException.Recoverable(errorMessage);
            } else {
                throw new PsiCashException.Critical(errorMessage);
            }
        }
    }
}
