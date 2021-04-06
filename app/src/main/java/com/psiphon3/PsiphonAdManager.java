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

import android.app.Activity;
import android.os.Build;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.freestar.android.ads.AdRequest;
import com.freestar.android.ads.AdSize;
import com.freestar.android.ads.BannerAd;
import com.freestar.android.ads.BannerAdListener;
import com.freestar.android.ads.FreeStarAds;
import com.freestar.android.ads.InterstitialAd;
import com.freestar.android.ads.InterstitialAdListener;
import com.google.auto.value.AutoValue;
import com.mopub.common.MoPub;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.SdkInitializationListener;
import com.mopub.common.privacy.ConsentDialogListener;
import com.mopub.common.privacy.PersonalInfoManager;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubView;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.PsiCashViewModel;
import com.psiphon3.psiphonlibrary.Utils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;

public class PsiphonAdManager {

    @AutoValue
    static abstract class AdResult {
        public enum Type {UNKNOWN, TUNNELED, UNTUNNELED, NONE}

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

        static AdResult unknown() {
            return new AutoValue_PsiphonAdManager_AdResult(Type.UNKNOWN, null);
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
        abstract class MoPub implements InterstitialResult {
            public void show() {
                interstitial().show();
            }

            abstract MoPubInterstitial interstitial();

            public abstract State state();

            @NonNull
            static InterstitialResult create(MoPubInterstitial interstitial, State state) {
                return new AutoValue_PsiphonAdManager_InterstitialResult_MoPub(interstitial, state);
            }
        }

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

    // ----------Production values -----------
    private static final String MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID = "6efb5aa4e0d74a679a6219f9b3aa6221";
    // MoPub test interstitial ID 24534e1901884e398f1253216226017e
    private static final String MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID = "1f9cb36809f04c8d9feaff5deb9f17ed";

    private BannerAd unTunneledFreestarBannerAdView;
    private MoPubView tunneledMoPubBannerAdView;
    private MoPubInterstitial tunneledMoPubInterstitial;
    private InterstitialAd unTunneledFreestarInterstitial;

    private ViewGroup bannerLayout;

    private Activity activity;
    private int tabChangedCount = 0;

    private boolean FreeStarAdsInitCalled = false;
    private final Completable initializeFreestarSdk;
    private final Completable initializeMoPubSdk;
    private final Observable<AdResult> currentAdTypeObservable;
    private Disposable loadBannersDisposable;
    private Disposable loadUnTunneledInterstitialDisposable;
    private Disposable loadTunneledInterstitialDisposable;
    private Disposable showTunneledInterstitialDisposable;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    private final Observable<InterstitialResult> tunneledMoPubInterstitialObservable;
    private final Observable<InterstitialResult> unTunneledFreestarInterstitialObservable;

    private TunnelState.ConnectionData interstitialConnectionData;

    PsiphonAdManager(FragmentActivity activity, ViewGroup bannerLayout,
                     Flowable<TunnelState> tunnelConnectionStateFlowable) {
        this.activity = activity;
        this.bannerLayout = bannerLayout;

        this.initializeFreestarSdk = Completable.create(emitter -> {
            if (!FreeStarAdsInitCalled) {
                FreeStarAdsInitCalled = true;
                FreeStarAds.init(activity.getApplicationContext(), "0P9gcV");
            }
            if (!emitter.isDisposed()) {
                if (!FreeStarAds.isInitialized()) {
                    emitter.onError(new Throwable());
                } else {
                    emitter.onComplete();
                }
            }
        })
                // Keep polling FreeStarAds.isInitialized every 250 ms
                .retryWhen(errors -> errors.delay(250, TimeUnit.MILLISECONDS))
                // Timeout after 5 seconds of polling if still not initialized
                .timeout(5, TimeUnit.SECONDS)
                // Short delay as we have observed failures to load ads if requested too soon after
                // initialization
                .delay(250, TimeUnit.MILLISECONDS)
                .subscribeOn(AndroidSchedulers.mainThread())
                .doOnError(e -> Utils.MyLog.d("initializeFreestarSdk error: " + e));

        // MoPub SDK is also tracking GDPR status and will present a GDPR consent collection dialog if needed.
        this.initializeMoPubSdk = Completable.create(emitter -> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                if (!emitter.isDisposed()) {
                    emitter.onError(new RuntimeException("MoPub initialization error: device SDK is less than 19"));
                }
                return;
            }
            if (MoPub.isSdkInitialized()) {
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
                return;
            }
            MoPub.setLocationAwareness(MoPub.LocationAwareness.DISABLED);
            SdkConfiguration.Builder builder = new SdkConfiguration.Builder(MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
            SdkConfiguration sdkConfiguration = builder.build();
            SdkInitializationListener sdkInitializationListener = () -> {
                final PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                if (personalInfoManager != null) {
                    // subscribe to consent change state event
                    personalInfoManager.subscribeConsentStatusChangeListener((oldConsentStatus, newConsentStatus, canCollectPersonalInformation) -> {
                        if (personalInfoManager.shouldShowConsentDialog()) {
                            personalInfoManager.loadConsentDialog(moPubConsentDialogListener());
                        }
                    });
                    // If consent is required load the consent dialog
                    if (personalInfoManager.shouldShowConsentDialog()) {
                        personalInfoManager.loadConsentDialog(moPubConsentDialogListener());
                    }
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                } else {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new RuntimeException("MoPub SDK has failed to initialize, MoPub.getPersonalInformationManager is null"));
                    }
                }
            };
            MoPub.initializeSdk(activity, sdkConfiguration, sdkInitializationListener);
        })
                .subscribeOn(AndroidSchedulers.mainThread())
                // Make sure initialization runs only once, from .cache() documentation:
                //
                // Subscribes to this Completable only once, when the first CompletableObserver
                // subscribes to the result Completable, caches its terminal event
                // and relays/replays it to observers.
                .cache();

        PsiCashViewModel psiCashViewModel = new ViewModelProvider(activity,
                new ViewModelProvider.AndroidViewModelFactory(activity.getApplication()))
                .get(PsiCashViewModel.class);
        this.currentAdTypeObservable = psiCashViewModel.booleanActiveSpeedBoostObservable()
                .switchMap(hasActiveSpeedBoost -> hasActiveSpeedBoost ?
                        Observable.just(AdResult.none()) :
                        Observable.combineLatest(
                                tunnelConnectionStateFlowable.toObservable(),
                                GooglePlayBillingHelper.getInstance(activity.getApplicationContext())
                                        .subscriptionStateFlowable()
                                        .toObservable(),
                                ((BiFunction<TunnelState, SubscriptionState, Pair>) Pair::new))
                                .switchMap(pair -> {
                                    TunnelState s = (TunnelState) pair.first;
                                    SubscriptionState subscriptionState = (SubscriptionState) pair.second;
                                    if (subscriptionState.hasValidPurchase() ||
                                            Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                                        return Observable.just(AdResult.none());
                                    }
                                    if (s.isRunning() && s.connectionData().isConnected()) {
                                        return Observable.just(AdResult.tunneled(s.connectionData()));
                                    } else if (s.isStopped()) {
                                        return Observable.just(AdResult.unTunneled());
                                    } else {
                                        return Observable.just(AdResult.unknown());
                                    }
                                }))
                .distinctUntilChanged()
                .replay(1)
                .autoConnect(0);

        this.tunneledMoPubInterstitialObservable = initializeMoPubSdk
                .andThen(Observable.<InterstitialResult>create(emitter -> {
                    if (tunneledMoPubInterstitial != null) {
                        tunneledMoPubInterstitial.destroy();
                    }
                    tunneledMoPubInterstitial = new MoPubInterstitial(activity, MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID);
                    // Set current client region keyword on the ad
                    if (interstitialConnectionData != null) {
                        tunneledMoPubInterstitial.setKeywords("client_region:" + interstitialConnectionData.clientRegion());
                        Map<String, Object> localExtras = new HashMap<>();
                        localExtras.put("client_region", interstitialConnectionData.clientRegion());
                        tunneledMoPubInterstitial.setLocalExtras(localExtras);
                    }

                    tunneledMoPubInterstitial.setInterstitialAdListener(new MoPubInterstitial.InterstitialAdListener() {
                        @Override
                        public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.MoPub.create(interstitial, InterstitialResult.State.READY));
                            }
                        }

                        @Override
                        public void onInterstitialFailed(MoPubInterstitial interstitial, MoPubErrorCode errorCode) {
                            if (!emitter.isDisposed()) {
                                emitter.onError(new RuntimeException("MoPub interstitial failed: " + errorCode.toString()));
                            }
                        }

                        @Override
                        public void onInterstitialShown(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.MoPub.create(interstitial, InterstitialResult.State.SHOWING));
                            }
                        }

                        @Override
                        public void onInterstitialClicked(MoPubInterstitial interstitial) {
                        }

                        @Override
                        public void onInterstitialDismissed(MoPubInterstitial interstitial) {
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });
                    if (tunneledMoPubInterstitial.isReady()) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.MoPub.create(tunneledMoPubInterstitial, InterstitialResult.State.READY));
                        }
                    } else {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.MoPub.create(tunneledMoPubInterstitial, InterstitialResult.State.LOADING));
                            tunneledMoPubInterstitial.load();
                        }
                    }
                }))
                .replay(1)
                .refCount();

        this.unTunneledFreestarInterstitialObservable = initializeFreestarSdk
                .andThen(Observable.<InterstitialResult>create(emitter -> {
                    unTunneledFreestarInterstitial = new InterstitialAd(activity, new InterstitialAdListener() {

                        @Override
                        public void onInterstitialLoaded(String placement) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.Freestar.create(unTunneledFreestarInterstitial, InterstitialResult.State.READY));
                            }
                        }

                        @Override
                        public void onInterstitialFailed(String placement, int errorCode) {
                            if (!emitter.isDisposed()) {
                                emitter.onError(new RuntimeException("Freestar interstitial failed with error code: " + errorCode));
                            }
                        }

                        @Override
                        public void onInterstitialShown(String placement) {
                            if (!emitter.isDisposed()) {
                                emitter.onNext(InterstitialResult.Freestar.create(unTunneledFreestarInterstitial, InterstitialResult.State.SHOWING));
                            }
                        }

                        @Override
                        public void onInterstitialClicked(String placement) {
                        }

                        @Override
                        public void onInterstitialDismissed(String placement) {
                            if (!emitter.isDisposed()) {
                                emitter.onComplete();
                            }
                        }
                    });

                    if (unTunneledFreestarInterstitial.isReady()) {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.Freestar.create(unTunneledFreestarInterstitial, InterstitialResult.State.READY));
                        }
                    } else {
                        if (!emitter.isDisposed()) {
                            emitter.onNext(InterstitialResult.Freestar.create(unTunneledFreestarInterstitial, InterstitialResult.State.LOADING));
                            unTunneledFreestarInterstitial.loadAd(new AdRequest(activity.getApplicationContext()));
                        }
                    }
                }))
                .replay(1)
                .refCount();

        // This disposable destroys ads according to subscription and/or
        // connection status without further delay.
        compositeDisposable.add(
                currentAdTypeObservable
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(adResult -> {
                            switch (adResult.type()) {
                                case NONE:
                                    // No ads mode, destroy all ads
                                    destroyAllAds();
                                    break;
                                case UNKNOWN:
                                    // App is backgrounded and tunnel state is unknown, destroy all banners
                                    destroyTunneledBanners();
                                    destroyUnTunneledBanners();
                                    break;
                                case TUNNELED:
                                    // App is tunneled, destroy untunneled banners
                                    destroyUnTunneledBanners();
                                    break;
                                case UNTUNNELED:
                                    // App is not tunneled, destroy tunneled banners
                                    destroyTunneledBanners();
                                    break;
                            }
                        })
                        .subscribe()
        );
    }

    Observable<AdResult> getCurrentAdTypeObservable() {
        return currentAdTypeObservable;
    }

    void onTabChanged() {
        if (showTunneledInterstitialDisposable != null && !showTunneledInterstitialDisposable.isDisposed()) {
            // subscription in progress, do nothing
            return;
        }
        // First tab change triggers the interstitial
        // NOTE: tabChangeCount gets reset when we go tunneled
        if (tabChangedCount % 3 != 0) {
            tabChangedCount++;
            return;
        }

        showTunneledInterstitialDisposable = getCurrentAdTypeObservable()
                .firstOrError()
                .flatMapObservable(adResult -> {
                    if (adResult.type() != AdResult.Type.TUNNELED) {
                        return Observable.empty();
                    }
                    return Observable.just(adResult);
                })
                .compose(getInterstitialWithTimeoutForAdType(3, TimeUnit.SECONDS))
                .doOnNext(interstitialResult -> {
                    if (interstitialResult.state() == InterstitialResult.State.READY) {
                        interstitialResult.show();
                        tabChangedCount++;
                    }
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe();
        compositeDisposable.add(showTunneledInterstitialDisposable);
    }

    void startLoadingAds() {
        // Preload exactly one untunneled interstitial if we are currently untunneled or complete
        if (loadUnTunneledInterstitialDisposable == null || loadUnTunneledInterstitialDisposable.isDisposed()) {
            loadUnTunneledInterstitialDisposable = getCurrentAdTypeObservable()
                    // Filter out UNKNOWN
                    .filter(adResult -> adResult.type() != AdResult.Type.UNKNOWN)
                    .firstOrError()
                    .flatMapObservable(adResult -> {
                        if (adResult.type() != AdResult.Type.UNTUNNELED) {
                            return Observable.empty();
                        }
                        return getInterstitialObservable(adResult)
                                .doOnError(e -> Utils.MyLog.d("Error loading untunneled interstitial: " + e))
                                .onErrorResumeNext(Observable.empty());
                    })
                    .subscribe();
            compositeDisposable.add(loadUnTunneledInterstitialDisposable);
        }

        // Keep pre-loading tunneled interstitial when we go tunneled indefinitely.
        // For this to be usable we want to keep a pre-loaded ad for as long as possible, i.e.
        // dispose of the preloaded ad only if tunnel state changes to untunneled or if tunnel
        // connection data changes which is a good indicator of a re-connect.
        // To achieve this we will filter out UNKNOWN ad result which is emitted when the app is
        // backgrounded and as a result the tunnel state can't be learned.
        // Note that it is possible that an automated re-connect may happen without a change
        // of the connection data fields.
        if (loadTunneledInterstitialDisposable == null || loadTunneledInterstitialDisposable.isDisposed()) {
            loadTunneledInterstitialDisposable = getCurrentAdTypeObservable()
                    // Filter out UNKNOWN
                    .filter(adResult -> adResult.type() != AdResult.Type.UNKNOWN)
                    // We only want to react when the state changes between TUNNELED and UNTUNNELED.
                    // Note that distinctUntilChanged will still pass the result through if the upstream
                    // emits two TUNNELED ad results sequence with different connectionData fields
                    .distinctUntilChanged()
                    .switchMap(adResult -> {
                        if (adResult.type() != AdResult.Type.TUNNELED) {
                            return Observable.empty();
                        }
                        // We are tunneled, reset tabChangedCount
                        tabChangedCount = 0;
                        return getInterstitialObservable(adResult)
                                // Load a new one right after a current one is shown and dismissed
                                .repeat()
                                .doOnError(e -> Utils.MyLog.d("Error loading tunneled interstitial: " + e))
                                .onErrorResumeNext(Observable.empty());
                    })
                    .subscribe();
            compositeDisposable.add(loadTunneledInterstitialDisposable);
        }

        // Finally load and show banners
        if (loadBannersDisposable == null || loadBannersDisposable.isDisposed()) {
            loadBannersDisposable = getCurrentAdTypeObservable()
                    .switchMapCompletable(adResult ->
                            loadAndShowBanner(adResult)
                                    .doOnError(e -> Utils.MyLog.d("loadAndShowBanner: error: " + e))
                                    .onErrorComplete()
                    )
                    .subscribe();
            compositeDisposable.add(loadBannersDisposable);
        }
    }

    ObservableTransformer<AdResult, InterstitialResult> getInterstitialWithTimeoutForAdType(int timeout, TimeUnit timeUnit) {
        return observable -> observable
                .observeOn(AndroidSchedulers.mainThread())
                .switchMap(this::getInterstitialObservable)
                .ambWith(Observable.timer(timeout, timeUnit)
                        .flatMap(l -> Observable.error(new TimeoutException("getInterstitialWithTimeoutForAdType timed out."))));
    }

    private Completable loadAndShowBanner(AdResult adResult) {
        Completable completable;
        switch (adResult.type()) {
            case NONE:
            case UNKNOWN:
                completable = Completable.complete();
                break;
            case TUNNELED:
                completable = initializeMoPubSdk.andThen(Completable.fromAction(() -> {
                    // Call 'destroy' on old instance before grabbing a new one to perform a
                    // proper cleanup so we are not leaking receivers, etc.
                    if (tunneledMoPubBannerAdView != null) {
                        tunneledMoPubBannerAdView.destroy();
                    }
                    tunneledMoPubBannerAdView = new MoPubView(activity);
                    tunneledMoPubBannerAdView.setAdUnitId(MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
                    TunnelState.ConnectionData connectionData = adResult.connectionData();
                    if (connectionData != null) {
                        tunneledMoPubBannerAdView.setKeywords("client_region:" + connectionData.clientRegion());
                        Map<String, Object> localExtras = new HashMap<>();
                        localExtras.put("client_region", connectionData.clientRegion());
                        tunneledMoPubBannerAdView.setLocalExtras(localExtras);
                    }
                    tunneledMoPubBannerAdView.setBannerAdListener(new MoPubView.BannerAdListener() {
                        @Override
                        public void onBannerLoaded(MoPubView banner) {
                            if (tunneledMoPubBannerAdView.getParent() == null) {
                                bannerLayout.removeAllViewsInLayout();
                                bannerLayout.addView(tunneledMoPubBannerAdView);
                            }
                        }

                        @Override
                        public void onBannerClicked(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerCollapsed(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerExpanded(MoPubView arg0) {
                        }

                        @Override
                        public void onBannerFailed(MoPubView v, MoPubErrorCode errorCode) {
                        }
                    });
                    tunneledMoPubBannerAdView.loadAd();
                    tunneledMoPubBannerAdView.setAutorefreshEnabled(true);
                }));
                break;
            case UNTUNNELED:
                completable = initializeFreestarSdk.andThen(Completable.fromAction(() -> {
                    unTunneledFreestarBannerAdView = new BannerAd(activity);
                    unTunneledFreestarBannerAdView.setAdSize(AdSize.MEDIUM_RECTANGLE_300_250);
                    unTunneledFreestarBannerAdView.setBannerAdListener(new BannerAdListener() {
                        @Override
                        public void onBannerAdLoaded(View bannerAd, String placement) {
                            if (unTunneledFreestarBannerAdView.getParent() == null) {
                                bannerLayout.removeAllViewsInLayout();
                                bannerLayout.addView(unTunneledFreestarBannerAdView);
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
                    unTunneledFreestarBannerAdView.loadAd(new AdRequest(activity.getApplicationContext()));
                }));
                break;
            default:
                throw new IllegalArgumentException("loadAndShowBanner: unhandled AdResult.Type: " + adResult.type());
        }
        return completable
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    private Observable<InterstitialResult> getInterstitialObservable(final AdResult adResult) {
        Observable<InterstitialResult> interstitialResultObservable;
        AdResult.Type adType = adResult.type();
        switch (adType) {
            case NONE:
            case UNKNOWN:
                interstitialResultObservable = Observable.empty();
                break;
            case TUNNELED:
                interstitialConnectionData = adResult.connectionData();
                interstitialResultObservable = tunneledMoPubInterstitialObservable;
                break;
            case UNTUNNELED:
                interstitialResultObservable = unTunneledFreestarInterstitialObservable;
                break;
            default:
                throw new IllegalArgumentException("getInterstitialObservable: unhandled AdResult.Type: " + adType);
        }
        return interstitialResultObservable
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    private void destroyTunneledBanners() {
        if (tunneledMoPubBannerAdView != null) {
            ViewGroup parent = (ViewGroup) tunneledMoPubBannerAdView.getParent();
            if (parent != null) {
                parent.removeView(tunneledMoPubBannerAdView);
            }
            tunneledMoPubBannerAdView.destroy();
        }
    }

    private void destroyUnTunneledBanners() {
        if (unTunneledFreestarBannerAdView != null) {
            // Freestar's AdView may still call its listener even after a call to destroy();
            unTunneledFreestarBannerAdView.setBannerAdListener(null);
            ViewGroup parent = (ViewGroup) unTunneledFreestarBannerAdView.getParent();
            if (parent != null) {
                parent.removeView(unTunneledFreestarBannerAdView);
            }
            unTunneledFreestarBannerAdView.destroyView();
        }
    }

    private void destroyAllAds() {
        destroyTunneledBanners();
        destroyUnTunneledBanners();
        if (tunneledMoPubInterstitial != null) {
            tunneledMoPubInterstitial.destroy();
        }
        if (unTunneledFreestarInterstitial != null) {
            unTunneledFreestarInterstitial.destroyView();
        }
    }

    public void onDestroy() {
        destroyAllAds();
        compositeDisposable.dispose();
    }

    private static ConsentDialogListener moPubConsentDialogListener() {
        return new ConsentDialogListener() {
            @Override
            public void onConsentDialogLoaded() {
                PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                if (personalInfoManager != null) {
                    personalInfoManager.showConsentDialog();
                }
            }

            @Override
            public void onConsentDialogLoadFailed(@NonNull MoPubErrorCode moPubErrorCode) {
                Utils.MyLog.d("MoPub consent dialog load error: " + moPubErrorCode.toString());
            }
        };
    }
}
