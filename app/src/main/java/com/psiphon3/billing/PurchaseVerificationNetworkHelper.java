/*
 * Copyright (c) 2018, Psiphon Inc.
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

package com.psiphon3.billing;

import android.content.Context;
import android.os.Build;

import com.android.billingclient.api.Purchase;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposables;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PurchaseVerificationNetworkHelper {

    private static final int TIMEOUT_SECONDS = 30;
    // TODO: replace with production values after testing
    private static final String SUBSCRIPTION_VERIFICATION_URL = "https://dev-subscription.psiphon3.com/v2/playstore/subscription";
    private static final String PSICASH_VERIFICATION_URL = "https://dev-subscription.psiphon3.com/v2/playstore/psicash";
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final String HTTP_USER_AGENT = "Psiphon-Verifier-Android";
    private static final int TRIES_COUNT = 5;

    private OkHttpClient.Builder okHttpClientBuilder;
    private Context ctx;

    @NonNull
    private TunnelState.ConnectionData connectionData = null;

    private String customData = null;

    public static class Builder {
        private Context ctx;
        @NonNull
        private TunnelState.ConnectionData connectionData;
        private String customData = null;

        public Builder(Context ctx) {
            this.ctx = ctx;
        }

        public Builder withConnectionData(TunnelState.ConnectionData connectionData) {
            this.connectionData = connectionData;
            return this;
        }

        public Builder withCustomData(String customData) {
            this.customData = customData;
            return this;
        }

        public PurchaseVerificationNetworkHelper build() {
            PurchaseVerificationNetworkHelper helper = new PurchaseVerificationNetworkHelper(this.ctx);
            helper.connectionData = this.connectionData;
            helper.customData = this.customData;

            helper.okHttpClientBuilder = new OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            return helper;
        }
    }

    private PurchaseVerificationNetworkHelper(Context ctx) {
        this.ctx = ctx;
    }

    public Flowable<String> verifyFlowable(Purchase purchase) {
        return Single.<String>create(emitter -> {
            JSONObject json = new JSONObject();

            final boolean isSubscription =
                    GooglePlayBillingHelper.isLimitedSubscription(purchase) ||
                            GooglePlayBillingHelper.isUnlimitedSubscription(purchase);

            final String url = GooglePlayBillingHelper.isPsiCashPurchase(purchase) ?
                    PSICASH_VERIFICATION_URL : SUBSCRIPTION_VERIFICATION_URL;

            json.put("is_subscription", isSubscription);
            json.put("package_name", ctx.getPackageName());
            json.put("product_id", purchase.getSku());
            json.put("token", purchase.getPurchaseToken());
            json.put("custom_data", customData);

            RequestBody requestBody = RequestBody.create(JSON, json.toString());

            Map<String, String> metaData = new HashMap<>();
            metaData.put("client_platform", getClientPlatform());
            metaData.put("client_version", connectionData.clientVersion());
            metaData.put("propagation_channel_id", connectionData.propagationChannelId());
            metaData.put("client_region", connectionData.clientRegion());
            metaData.put("sponsor_id", connectionData.sponsorId());

            final String metaDataHeader = new JSONObject(metaData).toString();

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("User-Agent", HTTP_USER_AGENT)
                    .addHeader("X-Verifier-Metadata", metaDataHeader)
                    .build();

            final int httpProxyPort = connectionData.httpPort();

            if (httpProxyPort > 0) {
                okHttpClientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", httpProxyPort)));
            }

            Response response = null;
            try {
                final Call call;
                call = okHttpClientBuilder.build().newCall(request);
                emitter.setDisposable(Disposables.fromAction(call::cancel));
                response = call.execute();
                if (response.isSuccessful()) {
                    if (!emitter.isDisposed()) {
                        final String responseString;
                        if (response.body() != null) {
                            responseString = response.body().string();
                        } else {
                            responseString = "";
                        }
                        emitter.onSuccess(responseString);
                    }
                } else {
                    String msg = "PurchaseVerifier: bad response code from verification server: " +
                            response.code();
                    MyLog.g(msg);
                    if (!emitter.isDisposed()) {
                        final RuntimeException e;
                        if (response.code() >= 400 && response.code() <= 499) {
                            e = new FatalException(msg);
                        } else {
                            e = new RetriableException(msg);
                        }
                        emitter.onError(e);
                    }
                }
            } catch (IOException e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(new RetriableException(e.toString()));
                }
            } finally {
                if (response != null && response.body() != null) {
                    response.body().close();
                }
            }
        })
                .toFlowable()
                .retryWhen(errors ->
                        errors.zipWith(Flowable.range(1, TRIES_COUNT), (err, i) -> {
                            if (i < TRIES_COUNT && (err instanceof RetriableException)) {
                                // exponential backoff with timer
                                int retryInSeconds = (int) Math.pow(4, i);
                                MyLog.g("PurchaseVerifier: will retry purchase verification request in " +
                                        retryInSeconds +
                                        " seconds" +
                                        " due to error: " + err);
                                return Flowable.timer((long) retryInSeconds, TimeUnit.SECONDS);
                            } // else
                            return Flowable.error(err);
                        }).flatMap(x -> x))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private String getClientPlatform() {
        // NOTE: this code is basically a duplicate of
        // https://github.com/Psiphon-Labs/psiphon-tunnel-core/blob/master/MobileLibrary/Android/PsiphonTunnel/PsiphonTunnel.java#L684-L701
        // TODO: get this value directly from the library?

        String clientPlatform = "Android_" +
                Build.VERSION.RELEASE +
                "_" +
                ctx.getPackageName();
        return clientPlatform.replaceAll("[^\\w\\-\\.]", "_");
    }

    private class RetriableException extends RuntimeException {
        RetriableException(String cause) {
            super(cause);
        }
    }

    private class FatalException extends RuntimeException {
        FatalException(String cause) {
            super(cause);
        }
    }

}
