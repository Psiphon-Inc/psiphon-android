/*
 * Copyright (c) 2021, Psiphon Inc.
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
 */

package com.psiphon3.psicash;


import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.psiphon3.TunnelState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Completable;
import io.reactivex.Flowable;
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
    public static final String PSICASH_PREFERENCES_KEY = "app_prefs";

    private static PsiCashClient INSTANCE = null;
    private final Context appContext;

    private final PsiCashLibWrapper psiCashLibWrapper;
    private int httpProxyPort;
    private final OkHttpClient okHttpClient;
    private final SharedPreferences sharedPreferences;
    private final AppPreferences multiProcessPreferences;
    private String customDataCached = null;

    private PsiCashClient(final Context ctx) throws PsiCashException {
        this.appContext = ctx;
        sharedPreferences = ctx.getSharedPreferences(PSICASH_PREFERENCES_KEY, Context.MODE_PRIVATE);
        multiProcessPreferences = new AppPreferences(ctx);
        try {
            customDataCached = multiProcessPreferences.getString(ctx.getString(R.string.persistentPsiCashCustomData));
        } catch (ItemNotFoundException ignored) {
        }

        httpProxyPort = 0;
        psiCashLibWrapper = new PsiCashLibWrapper();

        okHttpClient = new OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .proxySelector(new ProxySelector() {
                    @Override
                    public List<Proxy> select(URI uri) {
                        // Always use a proxy for PsiCash HTTP requests.
                        // If httpProxyPort is 0 the request will fail immediately with
                        // "java.net.SocketException: No route to 127.0.0.1:0; port is out of range"
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("localhost", httpProxyPort));
                        return Collections.singletonList(proxy);
                    }

                    @Override
                    public void connectFailed(URI uri, SocketAddress socketAddress, IOException e) {
                    }
                })
                .build();

        final PsiCashLib.HTTPRequester httpRequester = reqParams -> {
            PsiCashLib.HTTPRequester.Result result = new PsiCashLib.HTTPRequester.Result();
            Request.Builder reqBuilder = new Request.Builder();
            try {
                reqBuilder.url(reqParams.uri.toString());
                if (reqParams.method.equalsIgnoreCase("GET")) {
                    reqBuilder.get();
                } else if (reqParams.method.equalsIgnoreCase("POST")) {
                    reqBuilder.post(RequestBody.create(null, reqParams.body));
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
                result.headers = response.headers().toMultimap();
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
        };

        PsiCashLib.Error err = psiCashLibWrapper.init(ctx.getFilesDir().toString(), httpRequester, false);

        if (err != null) {
            String errorMessage = "Could not initialize PsiCash lib: error: " + err.message;
            Utils.MyLog.g("PsiCash: " + errorMessage);
            if (err.critical) {
                // Reset the datastore and throw original error.
                psiCashLibWrapper.init(ctx.getFilesDir().toString(), httpRequester, true);
                throw new PsiCashException.Critical(errorMessage, appContext.getString(R.string.psicash_critical_error_reset_message));
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
            PsiCashLib.Error error = psiCashLibWrapper.setRequestMetadataItem(h.getKey(), h.getValue());
            if (error != null) {
                String errorMessage = error.message;
                if (error.critical) {
                    throw new PsiCashException.Critical(errorMessage);
                } else {
                    throw new PsiCashException.Recoverable(errorMessage);
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
        PsiCashLib.ModifyLandingPageResult modifyLandingPageResult = psiCashLibWrapper.modifyLandingPage(originaUrl);
        if (modifyLandingPageResult.error == null) {
            return modifyLandingPageResult.url;
        } else {
            String errorMessage = modifyLandingPageResult.error.message;
            if (modifyLandingPageResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    private String accountUsername() throws PsiCashException {
        PsiCashLib.AccountUsername accountUsernameResult = psiCashLibWrapper.getAccountUsername();
        if (accountUsernameResult.error == null) {
            return accountUsernameResult.username;
        } else {
            String errorMessage = accountUsernameResult.error.message;
            if (accountUsernameResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    private boolean hasTokens() throws PsiCashException {
        PsiCashLib.HasTokensResult hasTokensResult = psiCashLibWrapper.hasTokens();
        if (hasTokensResult.error == null) {
            return hasTokensResult.hasTokens;
        } else {
            String errorMessage = hasTokensResult.error.message;
            if (hasTokensResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    private boolean isAccount() throws PsiCashException {
        PsiCashLib.IsAccountResult isAccountResult = psiCashLibWrapper.isAccount();
        if (isAccountResult.error == null) {
            return isAccountResult.isAccount;
        } else {
            String errorMessage = isAccountResult.error.message;
            if (isAccountResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    private PsiCashModel psiCashModelFromLib(boolean forceCheckPurchases) throws PsiCashException {
        Locale locale = Locale.getDefault();
        if (locale != null) {
            psiCashLibWrapper.setLocale(toBcp47Language(locale));
        }

        PsiCashModel.Builder builder = PsiCashModel.builder();

        PsiCashLib.BalanceResult balanceResult = psiCashLibWrapper.balance();
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

        PsiCashLib.GetPurchasePricesResult getPurchasePricesResult = psiCashLibWrapper.getPurchasePrices();
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

        PsiCashLib.GetPurchasesResult getPurchasesResult = psiCashLibWrapper.getPurchases();
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

        builder.accountUsername(accountUsername());

        boolean hasTokens = hasTokens();
        builder.hasTokens(hasTokens);

        boolean isAccount = isAccount();
        builder.isAccount(isAccount);

        boolean pendingRefresh = multiProcessPreferences
                .getBoolean(appContext.getString(R.string.persistentPsiCashPurchaseRedeemedFlag),
                        false) && hasTokens;

        builder.pendingRefresh(pendingRefresh);

        String customData = null;
        if (hasTokens) {
            PsiCashLib.GetRewardedActivityDataResult rewardedActivityData = psiCashLibWrapper.getRewardedActivityData();
            if (rewardedActivityData.error == null) {
                customData = rewardedActivityData.data;
            } else {
                String errorMessage = rewardedActivityData.error.message;
                if (rewardedActivityData.error.critical) {
                    throw new PsiCashException.Critical(errorMessage);
                } else {
                    throw new PsiCashException.Recoverable(errorMessage);
                }
            }
        }

        if (customData != null) {
            if (!customData.equals(customDataCached)) {
                multiProcessPreferences.put(appContext.getString(R.string.persistentPsiCashCustomData), customData);
            }
        } else {
            multiProcessPreferences.remove(appContext.getString(R.string.persistentPsiCashCustomData));
        }
        customDataCached = customData;

        builder.accountSignupUrl(psiCashLibWrapper.getAccountSignupURL());
        builder.accountForgotUrl(psiCashLibWrapper.getAccountForgotURL());
        builder.accountManagementUrl(psiCashLibWrapper.getAccountManagementURL());

        PsiCashModel psiCashModel = builder.build();

        if (forceCheckPurchases) {
            boolean authStorageChanged;
            Utils.MyLog.g("PsiCash: force checking next expiring purchase for new authorizations.");
            // If there are new authorizations in the purchases list then signal a tunnel restart
            PsiCashLib.Purchase purchase = psiCashModel.nextExpiringPurchase();
            if (purchase != null) {
                Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization.encoded);
                authStorageChanged = Authorization.storeAuthorization(appContext, authorization);
                if (authStorageChanged) {
                    Utils.MyLog.g("PsiCash: stored a new authorization of accessType: " +
                            purchase.authorization.accessType + ", expires: " +
                            Utils.getISO8601String(purchase.authorization.expires)
                    );
                } else {
                    Utils.MyLog.g("PsiCash: there are no new authorizations, continue.");
                }
            } else {
                Utils.MyLog.g("PsiCash: purchases list is empty, will remove all authorizations of accessType: " + Authorization.ACCESS_TYPE_SPEED_BOOST);
                authStorageChanged = Authorization.purgeAuthorizationsOfAccessType(appContext, Authorization.ACCESS_TYPE_SPEED_BOOST);
            }

            if (authStorageChanged) {
                Utils.MyLog.g("PsiCash: authorization storage contents changed, send tunnel restart broadcast");
                android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
        return psiCashModel;
    }

    // Modified from https://github.com/apache/cordova-plugin-globalization/blob/83f6cce89128cc569985681a05b92e1ef516fd0c/src/android/Globalization.java
    /*
           Licensed to the Apache Software Foundation (ASF) under one
           or more contributor license agreements.  See the NOTICE file
           distributed with this work for additional information
           regarding copyright ownership.  The ASF licenses this file
           to you under the Apache License, Version 2.0 (the
           "License"); you may not use this file except in compliance
           with the License.  You may obtain a copy of the License at
             http://www.apache.org/licenses/LICENSE-2.0
           Unless required by applicable law or agreed to in writing,
           software distributed under the License is distributed on an
           "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
           KIND, either express or implied.  See the License for the
           specific language governing permissions and limitations
           under the License.
    */
    private static String toBcp47Language(@NonNull Locale loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return loc.toLanguageTag();
        }

        final char SEP = '-';       // we will use a dash as per BCP 47
        String language = loc.getLanguage();
        String region = loc.getCountry();
        String variant = loc.getVariant();

        // special case for Norwegian Nynorsk since "NY" cannot be a variant as per BCP 47
        // this goes before the string matching since "NY" wont pass the variant checks
        if (language.equals("no") && region.equals("NO") && variant.equals("NY")) {
            language = "nn";
            region = "NO";
            variant = "";
        }

        if (language.isEmpty() || !language.matches("\\p{Alpha}{2,8}")) {
            language = "und";       // Follow the Locale#toLanguageTag() implementation
            // which says to return "und" for Undetermined
        } else if (language.equals("iw")) {
            language = "he";        // correct deprecated "Hebrew"
        } else if (language.equals("in")) {
            language = "id";        // correct deprecated "Indonesian"
        } else if (language.equals("ji")) {
            language = "yi";        // correct deprecated "Yiddish"
        }

        // ensure valid country code, if not well formed, it's omitted
        if (!region.matches("\\p{Alpha}{2}|\\p{Digit}{3}")) {
            region = "";
        }

        // variant subtags that begin with a letter must be at least 5 characters long
        if (!variant.matches("\\p{Alnum}{5,8}|\\p{Digit}\\p{Alnum}{3}")) {
            variant = "";
        }

        StringBuilder bcp47Tag = new StringBuilder(language);
        if (!region.isEmpty()) {
            bcp47Tag.append(SEP).append(region);
        }
        if (!variant.isEmpty()) {
            bcp47Tag.append(SEP).append(variant);
        }

        return bcp47Tag.toString();
    }

    public String getDiagnosticInfoJson() {
        PsiCashLib.GetDiagnosticInfoResult diagnosticInfoResult = psiCashLibWrapper.getDiagnosticInfo();
        if (diagnosticInfoResult.error != null) {
            return JSONObject.quote(diagnosticInfoResult.error.message);
        }
        return diagnosticInfoResult.jsonString;
    }

    public Completable makeExpiringPurchase(Flowable<TunnelState> tunnelStateFlowable,
                                            String distinguisher,
                                            String transactionClass,
                                            long price) {
        return tunnelStateFlowable
                .observeOn(Schedulers.io())
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .flatMapCompletable(state ->
                        Completable.fromAction(() -> {
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
                            if (distinguisher == null) {
                                throw new PsiCashException.Critical("makeExpiringPurchase: purchase distinguisher is null.");
                            }

                            if (transactionClass == null) {
                                throw new PsiCashException.Critical("makeExpiringPurchase: purchase transaction class is null.");
                            }
                            setPsiCashRequestMetaData(connectionData);
                            setOkHttpClientHttpProxyPort(connectionData.httpPort());

                            PsiCashLib.NewExpiringPurchaseResult result =
                                    psiCashLibWrapper.newExpiringPurchase(transactionClass,
                                            distinguisher, price);

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
                                throw new PsiCashException.Transaction(result.status, isAccount());
                            }
                            Utils.MyLog.g("PsiCash: got new purchase of transactionClass " + result.purchase.transactionClass);
                        })
                )
                // If INSUFFICIENT_BALANCE retry for up to 30 seconds
                // in hope that the server-to-server reward callback will catch up
                .retryWhen(errors -> {
                    AtomicReference<Throwable> capturedThrowable = new AtomicReference<>();
                    return errors
                            .flatMap(throwable -> {
                                capturedThrowable.set(throwable);
                                if (throwable instanceof PsiCashException.Transaction) {
                                    PsiCashException.Transaction psiCashException = (PsiCashException.Transaction) throwable;
                                    if (psiCashException.getStatus() == PsiCashLib.Status.INSUFFICIENT_BALANCE) {
                                        return Flowable.timer(2, TimeUnit.SECONDS);
                                    }
                                    return Flowable.error(psiCashException);
                                } else {
                                    return Flowable.error(throwable);
                                }
                            })
                            // if captured an error then timeout after 30 seconds and bubble up the error
                            .mergeWith(Completable.timer(30, TimeUnit.SECONDS)
                                    .andThen(Completable.create(emitter -> {
                                        final Throwable e = capturedThrowable.get();
                                        if (!emitter.isDisposed()) {
                                            if (e != null) {
                                                emitter.onError(e);
                                            } else {
                                                emitter.onComplete();
                                            }
                                        }
                                    }))
                            );
                });
    }

    // Do not force purchases check by default
    public Single<PsiCashModel> getPsiCashModel() {
        return Single.fromCallable(() -> psiCashModelFromLib(false));
    }

    public Single<PsiCashModel> getPsiCashModel(boolean forceCheckPurchases) {
        return Single.fromCallable(() -> psiCashModelFromLib(forceCheckPurchases));
    }

    public Single<Boolean> refreshState(Flowable<TunnelState> tunnelStateFlowable,
                                        boolean forceRemote) {
        return tunnelStateFlowable
                .observeOn(Schedulers.io())
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .flatMap(state ->
                        Single.fromCallable(() -> {
                            boolean isConnected = true;
                            if (!state.isRunning()) {
                                isConnected = false;
                                if (forceRemote) {
                                    throw new PsiCashException.Recoverable("refreshState: tunnel not running.",
                                            appContext.getString(R.string.psicash_connect_to_refresh_message));
                                }
                            } else {
                                if (!state.connectionData().isConnected()) {
                                    isConnected = false;
                                    if (forceRemote) {
                                        throw new PsiCashException.Recoverable("refreshState: tunnel not connected.",
                                                appContext.getString(R.string.psicash_wait_to_connect_to_refresh_message));
                                    }
                                }
                            }

                            if (isConnected) {
                                setOkHttpClientHttpProxyPort(state.connectionData().httpPort());
                                setPsiCashRequestMetaData(state.connectionData());
                            }
                            PsiCashLib.RefreshStateResult result =
                                    psiCashLibWrapper.refreshState(!isConnected, getPurchaseClasses());
                            if (result.error != null) {
                                if (result.error.critical) {
                                    throw new PsiCashException.Critical(result.error.message,
                                            appContext.getString(R.string.psicash_cant_get_balance_critical_message));
                                } else {
                                    throw new PsiCashException.Recoverable(result.error.message,
                                            appContext.getString(R.string.psicash_cant_get_balance_recoverable_message));
                                }
                            }
                            if (result.status != PsiCashLib.Status.SUCCESS) {
                                throw new PsiCashException.Transaction(result.status, isAccount());
                            }
                            if (isConnected) {
                                // reset optimistic reward if remote refresh succeeded
                                resetVideoReward();
                                // Also reset purchase redeemed flag
                                multiProcessPreferences
                                        .put(appContext.getString(R.string.persistentPsiCashPurchaseRedeemedFlag),
                                                false);
                            }
                            return result.reconnectRequired;
                        }));
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

    public Completable removePurchases(List<String> purchasesToRemove) {
        return Completable.fromAction(() -> {
            PsiCashLib.RemovePurchasesResult removePurchasesResult = psiCashLibWrapper.removePurchases(purchasesToRemove);
            if (removePurchasesResult != null && removePurchasesResult.error != null) {
                String errorMessage = removePurchasesResult.error.message;
                if (removePurchasesResult.error.critical) {
                    throw new PsiCashException.Critical(errorMessage);
                } else {
                    throw new PsiCashException.Recoverable(errorMessage);
                }
            }
        });
    }

    public List<PsiCashLib.Purchase> getPurchases() throws PsiCashException {
        PsiCashLib.GetPurchasesResult getPurchasesResult = psiCashLibWrapper.getPurchases();
        if (getPurchasesResult.error == null) {
            return getPurchasesResult.purchases;
        } else {
            String errorMessage = getPurchasesResult.error.message;
            if (getPurchasesResult.error.critical) {
                throw new PsiCashException.Critical(errorMessage);
            } else {
                throw new PsiCashException.Recoverable(errorMessage);
            }
        }
    }

    public Single<Boolean> loginAccount(Flowable<TunnelState> tunnelStateFlowable,
                                        String username,
                                        String password) {
        return tunnelStateFlowable
                .observeOn(Schedulers.io())
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .flatMap(state ->
                        Single.fromCallable(() -> {
                            TunnelState.ConnectionData connectionData = state.connectionData();

                            if (!state.isRunning()) {
                                throw new PsiCashException.Recoverable("loginAccount: tunnel not running.",
                                        appContext.getString(R.string.psicash_connect_to_login_message));
                            } else {
                                if (!connectionData.isConnected()) {
                                    throw new PsiCashException.Recoverable("loginAccount: tunnel is still connecting.",
                                            appContext.getString(R.string.psicash_wait_to_connect_to_login_message));
                                }
                            }
                            if (TextUtils.isEmpty(username)) {
                                throw new PsiCashException.Recoverable("loginAccount: username is empty.",
                                        appContext.getString(R.string.psicash_login_empty_username_message));
                            }

                            if (TextUtils.isEmpty(password)) {
                                throw new PsiCashException.Recoverable("loginAccount: password is empty.",
                                        appContext.getString(R.string.psicash_login_empty_password_message));
                            }
                            setPsiCashRequestMetaData(connectionData);
                            setOkHttpClientHttpProxyPort(connectionData.httpPort());

                            PsiCashLib.AccountLoginResult result =
                                    psiCashLibWrapper.accountLogin(username, password);

                            if (result.status != PsiCashLib.Status.SUCCESS) {
                                Utils.MyLog.g("PsiCash: transaction error logging in: " + result.status);
                                throw new PsiCashException.Transaction(result.status, isAccount());
                            }
                            if (result.error != null) {
                                Utils.MyLog.g("PsiCash: error logging in: " + result.error.message);
                                if (result.error.critical) {
                                    throw new PsiCashException.Critical(result.error.message);
                                } else {
                                    throw new PsiCashException.Recoverable(result.error.message);
                                }
                            }

                            Utils.MyLog.g("PsiCash: got new login with lastTrackerMerge: " + result.lastTrackerMerge);
                            return result.lastTrackerMerge != null && result.lastTrackerMerge;
                        })
                );
    }

    public Single<Boolean> logoutAccount(Flowable<TunnelState> tunnelStateFlowable) {
        return tunnelStateFlowable
                .observeOn(Schedulers.io())
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .flatMap(state ->
                        Single.fromCallable(() -> {
                            if (state.isRunning() && state.connectionData().isConnected()) {
                                setPsiCashRequestMetaData(state.connectionData());
                                setOkHttpClientHttpProxyPort(state.connectionData().httpPort());
                            }

                            PsiCashLib.AccountLogoutResult result = psiCashLibWrapper.accountLogout();
                            if (result.error != null) {
                                Utils.MyLog.g("PsiCash: error logging out: " + result.error.message);
                                if (result.error.critical) {
                                    throw new PsiCashException.Critical(result.error.message);
                                } else {
                                    throw new PsiCashException.Recoverable(result.error.message);
                                }
                            }
                            return result.reconnectRequired;
                        })
                );
    }

    public static class PsiCashLibWrapper extends PsiCashLib {
        @Override
        public Error init(String fileStoreRoot, HTTPRequester httpRequester, boolean forceReset) {
            return init(fileStoreRoot, httpRequester, forceReset, BuildConfig.PSICASH_DEV_ENVIRONMENT);
        }
    }
}
