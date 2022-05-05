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

package com.psiphon3;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.freestar.android.ads.AdRequest;
import com.freestar.android.ads.AdSize;
import com.freestar.android.ads.BannerAd;
import com.freestar.android.ads.BannerAdListener;
import com.freestar.android.ads.FreeStarAds;
import com.freestar.android.ads.InterstitialAd;
import com.google.auto.value.AutoValue;
import com.psiphon3.log.MyLog;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiphonAdManager {

    private static final String TUNNELED_BANNER_FREESTAR_PLACEMENT_ID = "inview_p1";

    @AutoValue
    static abstract class AdResult {
        public enum Type {TUNNELED, UNTUNNELED, NONE}

        @NonNull
        static AdResult tunneled(TunnelState.ConnectionData connectionData) {
            return new AutoValue_PsiphonAdManager_AdResult(Type.TUNNELED, connectionData);
        }

        static AdResult unTunneled() {
            return new AutoValue_PsiphonAdManager_AdResult(Type.UNTUNNELED, null);
        }

        static AdResult none() {
            return new AutoValue_PsiphonAdManager_AdResult(Type.NONE, null);
        }

        public abstract Type type();

        @Nullable
        abstract TunnelState.ConnectionData connectionData();
    }

    interface InterstitialResult {
        enum State {LOADING, READY, SHOWING}

        State state();

        void show();

        @AutoValue
        abstract class Freestar implements InterstitialResult {

            public void show() {
                interstitial().show();
            }

            abstract InterstitialAd interstitial();

            public abstract State state();

            @NonNull
            static Freestar create(InterstitialAd interstitial, State state) {
                return new AutoValue_PsiphonAdManager_InterstitialResult_Freestar(interstitial, state);
            }
        }
    }

    private BannerAd unTunneledFreestarBannerAd;
    private BannerAd tunneledFreestarBannerAd;

    private final WeakReference<ViewGroup> bannerViewGroupWeakReference;
    private final Context appContext;

    private final Observable<AdResult> currentAdTypeObservable;
    private Disposable loadBannersDisposable;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    PsiphonAdManager(Context appContext,
                     ViewGroup bannerViewGroup,
                     Observable<Boolean> hasBoostOrSubscriptionObservable,
                     Flowable<TunnelState> tunnelConnectionStateFlowable) {
        this.appContext = appContext;
        this.bannerViewGroupWeakReference = new WeakReference<>(bannerViewGroup);

        // If the user has a speed boost or a subscription disable all ads.
        this.currentAdTypeObservable = hasBoostOrSubscriptionObservable.switchMap(noAds ->
                noAds || !canShowAds() ? Observable.just(AdResult.none()) :
                        tunnelConnectionStateFlowable.toObservable()
                                // debounce the tunnel state result in case the activity gets resumed and
                                // then immediately paused due to orientation change while the start up
                                // interstitial is showing.
                                // This also delays loading tunneled ads until the activity
                                // is resumed after loading the landing page
                                .debounce(tunnelState ->
                                        Observable.timer(100, TimeUnit.MILLISECONDS))
                                .switchMap(tunnelState -> {
                                    if (tunnelState.isRunning() && tunnelState.connectionData().isConnected()) {
                                        return Observable.just(AdResult.tunneled(tunnelState.connectionData()));
                                    } else if (tunnelState.isStopped()) {
                                        return Observable.just(AdResult.unTunneled());
                                    }
                                    return Observable.empty();
                                }))
                .distinctUntilChanged()
                .replay(1)
                .refCount();
    }

    static boolean canShowAds() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;
    }

    void startLoadingAds() {
        // Load and show banners
        if (loadBannersDisposable == null || loadBannersDisposable.isDisposed()) {
            loadBannersDisposable = currentAdTypeObservable
                    .switchMapCompletable(adResult ->
                            getBannerCompletable(adResult)
                                    .onErrorComplete()
                    )
                    .subscribe();
            compositeDisposable.add(loadBannersDisposable);
        }
    }

    private Completable getBannerCompletable(AdResult adResult) {
       Completable bannerCompletable;
        switch (adResult.type()) {
            case NONE:
                bannerCompletable = Completable.complete();
                break;
            case TUNNELED:
                bannerCompletable = SdkInitializer.getFreeStar(appContext).andThen(Completable.fromAction(() -> {
                    tunneledFreestarBannerAd = new BannerAd(appContext);
                    tunneledFreestarBannerAd.setAdSize(AdSize.MEDIUM_RECTANGLE_300_250);
                    tunneledFreestarBannerAd.setBannerAdListener(new BannerAdListener() {
                        @Override
                        public void onBannerAdLoaded(View bannerAd, String placement) {
                            if (tunneledFreestarBannerAd != null &&
                                    tunneledFreestarBannerAd.getParent() == null) {
                                ViewGroup viewGroup = bannerViewGroupWeakReference.get();
                                if (viewGroup != null) {
                                    // Make parent view's background transparent to avoid visual
                                    // overlap if the banner's and placeholder's sizes differ.
                                    viewGroup.getBackground().setAlpha(0);

                                    viewGroup.removeAllViewsInLayout();
                                    viewGroup.addView(tunneledFreestarBannerAd);
                                }
                            }
                        }

                        @Override
                        public void onBannerAdFailed(View bannerAd, String placement, int errorCode) {
                        }

                        @Override
                        public void onBannerAdClicked(View bannerAd, String placement) {
                        }

                        @Override
                        public void onBannerAdClosed(View bannerAd, String placement) {
                        }
                    });
                    AdRequest adRequest = new AdRequest(appContext);
                    // Set current client region keyword on the ad
                    TunnelState.ConnectionData connectionData = adResult.connectionData();
                    if (connectionData != null) {
                        adRequest.addCustomTargeting("client_region", connectionData.clientRegion());
                    }

                    tunneledFreestarBannerAd.loadAd(adRequest, TUNNELED_BANNER_FREESTAR_PLACEMENT_ID);
                }));
                break;
            case UNTUNNELED:
                bannerCompletable = SdkInitializer.getFreeStar(appContext).andThen(Completable.fromAction(() -> {
                    unTunneledFreestarBannerAd = new BannerAd(appContext);
                    unTunneledFreestarBannerAd.setAdSize(AdSize.MEDIUM_RECTANGLE_300_250);
                    unTunneledFreestarBannerAd.setBannerAdListener(new BannerAdListener() {
                        @Override
                        public void onBannerAdLoaded(View bannerAd, String placement) {
                            if (unTunneledFreestarBannerAd != null &&
                                    unTunneledFreestarBannerAd.getParent() == null) {
                                ViewGroup viewGroup = bannerViewGroupWeakReference.get();
                                if (viewGroup != null) {
                                    // Make parent view's background transparent to avoid visual
                                    // overlap if the banner's and placeholder's sizes differ.
                                    viewGroup.getBackground().setAlpha(0);

                                    viewGroup.removeAllViewsInLayout();
                                    viewGroup.addView(unTunneledFreestarBannerAd);
                                }
                            }
                        }

                        @Override
                        public void onBannerAdFailed(View bannerAd, String placement, int errorCode) {
                        }

                        @Override
                        public void onBannerAdClicked(View bannerAd, String placement) {
                        }

                        @Override
                        public void onBannerAdClosed(View bannerAd, String placement) {
                        }
                    });
                    unTunneledFreestarBannerAd.loadAd(new AdRequest(appContext));
                }));
                break;
            default:
                throw new IllegalArgumentException("loadAndShowBanner: unhandled AdResult.Type: " + adResult.type());
        }
        // Perform complete banner cleanup before returning new banner completable
        return Completable.fromAction(this::destroyAllBanners)
                .andThen(bannerCompletable)
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    private void destroyTunneledBanners() {
        if (tunneledFreestarBannerAd != null) {
            tunneledFreestarBannerAd.setBannerAdListener(null);
            ViewGroup parent = (ViewGroup) tunneledFreestarBannerAd.getParent();
            if (parent != null) {
                // Remove from parent and restore parent's background opacity.
                parent.removeView(tunneledFreestarBannerAd);
                parent.getBackground().setAlpha(255);
            }
            tunneledFreestarBannerAd.destroyView();
            tunneledFreestarBannerAd = null;
        }
    }

    private void destroyUnTunneledBanners() {
        if (unTunneledFreestarBannerAd != null) {
            unTunneledFreestarBannerAd.setBannerAdListener(null);
            ViewGroup parent = (ViewGroup) unTunneledFreestarBannerAd.getParent();
            if (parent != null) {
                // Remove from parent and restore parent's background opacity.
                parent.removeView(unTunneledFreestarBannerAd);
                parent.getBackground().setAlpha(255);
            }
            unTunneledFreestarBannerAd.destroyView();
            unTunneledFreestarBannerAd = null;
        }
    }

    private void destroyAllBanners() {
        destroyTunneledBanners();
        destroyUnTunneledBanners();
    }

    public void onDestroy() {
        destroyAllBanners();
        compositeDisposable.dispose();
    }

    // A static ads SDKs initializer container
    static class SdkInitializer {
        private static Completable freeStar;

        public static Completable getFreeStar(Context context) {
            if (freeStar == null) {
                // Call init only once
                FreeStarAds.init(context.getApplicationContext(), "0P9gcV");

                freeStar = Completable.create(
                        emitter -> {
                            if (!emitter.isDisposed()) {
                                if (FreeStarAds.isInitialized()) {
                                    emitter.onComplete();
                                } else {
                                    emitter.onError(new Throwable());
                                }
                            }
                        })
                        // Keep polling FreeStarAds.isInitialized every 250 ms
                        .retryWhen(errors -> errors.delay(250, TimeUnit.MILLISECONDS))
                        // Short delay as we have observed failures to load ads if requested too soon after
                        // initialization
                        .delay(500, TimeUnit.MILLISECONDS)
                        .subscribeOn(AndroidSchedulers.mainThread())
                        // Cache normal completion of the upstream or imeout after 5 seconds without
                        // caching so the upstream could be retried again next time
                        .cache()
                        .ambWith(Completable.timer(5, TimeUnit.SECONDS)
                                .andThen(Completable.error(new TimeoutException("FreeStarAds init timed out"))))
                        .doOnError(e -> MyLog.e("FreeStarAds SDK init error: " + e));
            }
            return freeStar;
        }
    }
}
