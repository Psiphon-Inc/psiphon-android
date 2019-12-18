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

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class PurchaseVerificationNetworkHelper {

    static final int TIMEOUT_SECONDS = 30;
    static final String SUBSCRIPTION_CHECK_URL = "https://subscription.psiphon3.com/playstore";
    static final MediaType JSON = MediaType.parse("application/json");
    static final int TRIES_COUNT = 5;

    OkHttpClient.Builder okHttpClientBuilder;
    Context ctx;
    boolean isSubscription;
    String productId;
    String purchaseToken;
    private int httpProxyPort = 0;

    public static class Builder {
        private Context ctx;
        private boolean isSubscription;
        private String productId;
        private String purchaseToken;
        private int httpProxyPort = 0;

        public Builder(Context ctx) {
            this.ctx = ctx;
        }

        public Builder withIsSubscription(boolean isSubscription) {
            this.isSubscription = isSubscription;
            return this;
        }

        public Builder withProductId(String productId) {
            this.productId = productId;
            return this;
        }

        public Builder withPurchaseToken(String purchaseToken) {
            this.purchaseToken = purchaseToken;
            return this;
        }

        public Builder withHttpProxyPort(int httpProxyPort) {
            this.httpProxyPort = httpProxyPort;
            return this;
        }

        public PurchaseVerificationNetworkHelper build() {
            PurchaseVerificationNetworkHelper helper = new PurchaseVerificationNetworkHelper(this.ctx);
            helper.isSubscription = this.isSubscription;
            helper.productId = this.productId;
            helper.purchaseToken = this.purchaseToken;
            helper.httpProxyPort = this.httpProxyPort;

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

    public Flowable<String> fetchAuthorizationFlowable() {
        return Observable.fromCallable(
                () -> {
                    JSONObject json = new JSONObject();
                    json.put("is_subscription", isSubscription);
                    json.put("package_name", ctx.getPackageName());
                    json.put("product_id", productId);
                    json.put("token", purchaseToken);

                    RequestBody requestBody = RequestBody.create(JSON, json.toString());

                    Request request = new Request.Builder()
                            .url(SUBSCRIPTION_CHECK_URL)
                            .post(requestBody)
                            .build();

                    if(httpProxyPort > 0) {
                        okHttpClientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", httpProxyPort)));
                    }

                    return okHttpClientBuilder.build().newCall(request).execute();
                })
                .retryWhen(
                        errors -> errors
                                .zipWith(Observable.range(1, TRIES_COUNT), (err, i) ->
                                {
                                    if(i < TRIES_COUNT ) {
                                        // exponential backoff with timer
                                        int retryInSeconds = (int) Math.pow(4, i);
                                        MyLog.g("PurchaseVerifier: will retry authorization request in " +
                                                retryInSeconds +
                                                " seconds" +
                                                " due to error: " + err);
                                       return  Observable.timer((long) retryInSeconds, TimeUnit.SECONDS);
                                    } // else
                                    return Observable.error(err);
                                })
                                .flatMap(x -> x))
                .map(response -> {
                            try {
                                if(response.isSuccessful() && response.body() != null) {
                                    return response.body().string();
                                } else {
                                    MyLog.g("PurchaseVerifier: bad response code from verification server: " + response.code());
                                    return "";
                                }
                            } finally {
                                if(response.body() != null) {
                                    response.body().close();
                                }
                            }
                        }
                )
                .toFlowable(BackpressureStrategy.LATEST)
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread());
    }
}
