package com.psiphon3.psicash.rewardedvideo;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.reward.RewardItem;
import com.google.android.gms.ads.reward.RewardedVideoAd;
import com.google.android.gms.ads.reward.RewardedVideoAdListener;
import com.mopub.common.MoPub;
import com.mopub.common.MoPubReward;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubRewardedVideoListener;
import com.mopub.mobileads.MoPubRewardedVideos;
import com.psiphon3.psicash.psicash.PsiCashClient;
import com.psiphon3.psicash.util.TunnelConnectionState;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class RewardedVideoClient {
    private static final String TAG = "PsiCashRewardedVideo";


    private static final String MOPUB_VIDEO_AD_UNIT_ID = "7ef66892f0a6417091119b94ce07d6e5";
    private static final String ADMOB_VIDEO_AD_ID = "ca-app-pub-1072041961750291/5751207671";

    private static RewardedVideoClient INSTANCE = null;
    private RewardedVideoAd rewardedVideoAd = null;

    public static synchronized RewardedVideoClient getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new RewardedVideoClient();
        }
        return INSTANCE;
    }

    private Observable<? extends RewardedVideoModel> loadMoPubVideos(final String customData) {
        return Observable.create(emitter -> {
            final int rewardAmount;
            final Set<MoPubReward> rewardsSet = MoPubRewardedVideos.getAvailableRewards(MOPUB_VIDEO_AD_UNIT_ID);
            // Get first value
            if (rewardsSet.iterator().hasNext()) {
                rewardAmount = rewardsSet.iterator().next().getAmount();
            } else {
                rewardAmount = 0;
            }
            MoPubRewardedVideoListener rewardedVideoListener = new MoPubRewardedVideoListener() {
                @Override
                public void onRewardedVideoLoadSuccess(@NonNull String adUnitId) {
                    if (!emitter.isDisposed()) {
                        // Called when the video for the given adUnitId has loaded. At this point you should be able to call MoPubRewardedVideos.showRewardedVideo(String) to show the video.
                        if (adUnitId.equals(MOPUB_VIDEO_AD_UNIT_ID)) {
                            emitter.onNext(RewardedVideoModel.VideoReady.create(() ->
                                    RewardedVideoClient.getInstance().playMoPubVideo(customData)));
                        } else {
                            emitter.onError(new RuntimeException("MoPub video failed, wrong ad unit id, expect: " + MOPUB_VIDEO_AD_UNIT_ID + ", got: " + adUnitId));
                        }
                    }
                }

                @Override
                public void onRewardedVideoLoadFailure(@NonNull String adUnitId, @NonNull MoPubErrorCode errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new RuntimeException("MoPub video failed with error: " + errorCode.toString()));
                    }
                }

                @Override
                public void onRewardedVideoStarted(String adUnitId) {
                }

                @Override
                public void onRewardedVideoPlaybackError(String adUnitId, MoPubErrorCode errorCode) {
                }

                @Override
                public void onRewardedVideoClicked(@NonNull String adUnitId) {
                }

                @Override
                public void onRewardedVideoClosed(@NonNull String adUnitId) {
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onRewardedVideoCompleted(Set<String> adUnitIds, @NonNull MoPubReward reward) {
                    // TODO We may reward in the onRewardedVideoClosed instead?
                    // since MoPub videos are not closeable
                    // check https://developers.mopub.com/docs/ui/apps/rewarded-server-side-setup/ for the web hook docs
                    if (!emitter.isDisposed()) {
                        emitter.onNext(RewardedVideoModel.Reward.create(reward.getAmount()));
                    }
                }
            };
            MoPubRewardedVideos.setRewardedVideoListener(rewardedVideoListener);
            MoPubRewardedVideos.loadRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID);
        });
    }

    private Observable<? extends RewardedVideoModel> loadAdMobVideos(String customData) {
        return Observable.create(emitter -> {
            RewardedVideoAdListener listener = new RewardedVideoAdListener() {
                @Override
                public void onRewarded(RewardItem reward) {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(RewardedVideoModel.Reward.create(reward.getAmount()));
                    }
                }

                @Override
                public void onRewardedVideoAdLeftApplication() {
                }

                @Override
                public void onRewardedVideoAdClosed() {
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onRewardedVideoAdFailedToLoad(int errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new RuntimeException("AdMob video ad failed with code: " + errorCode));
                    }
                }

                @Override
                public void onRewardedVideoAdLoaded() {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(RewardedVideoModel.VideoReady.create(() ->
                                RewardedVideoClient.getInstance().playAdMobVideo()));
                    }
                }

                @Override
                public void onRewardedVideoAdOpened() {
                }

                @Override
                public void onRewardedVideoStarted() {
                }

                @Override
                public void onRewardedVideoCompleted() {
                    Log.d(TAG, "onRewardedVideoCompleted");
                }
            };
            rewardedVideoAd.setCustomData(customData);
            rewardedVideoAd.setRewardedVideoAdListener(listener);
            rewardedVideoAd.loadAd(ADMOB_VIDEO_AD_ID, new AdRequest.Builder().build());
        });
    }

    Observable<? extends RewardedVideoModel> loadRewardedVideo(Context context, TunnelConnectionState connectionState, String rewardCustomData) {
        return Observable.just(Pair.create(connectionState, rewardCustomData))
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(pair -> {
                    TunnelConnectionState state = pair.first;
                    String customData = pair.second;
                    if (TextUtils.isEmpty(customData)) {
                        return Observable.error(new IllegalStateException("Video customData is empty"));
                    }
                    if (!PsiCashClient.getInstance(context).hasEarnerToken()) {
                        return Observable.error(new IllegalStateException("PsiCash lib has no earner token."));
                    }
                    // Either disconnected or BOM should load AdMob ads
                    if (state.status() == TunnelConnectionState.Status.DISCONNECTED ||
                            (state.status() == TunnelConnectionState.Status.CONNECTED && !state.connectionData().vpnMode())) {
                        return loadAdMobVideos(customData);
                    }
                    // Connected WDM should load MoPub
                    if (state.status() == TunnelConnectionState.Status.CONNECTED && state.connectionData().vpnMode()) {
                        return loadMoPubVideos(customData);
                    }
                    // Did we miss a case?
                    throw new IllegalArgumentException("Loading video for " + state + " is not implemented.");
                })
                // if error retry once with 2 seconds delay
                .retryWhen(throwableObservable -> {
                    AtomicInteger counter = new AtomicInteger();
                    return throwableObservable
                            .flatMap(err -> {
                                if (counter.getAndIncrement() < 1) {
                                    Log.d(TAG, "Ad loading error: " + err + ",  will retry");
                                    return Observable.timer(2, TimeUnit.SECONDS);
                                }
                                return Observable.error(err);
                            });
                });
    }

    public void initWithActivity(Activity activity) {
        if (rewardedVideoAd == null) {
            rewardedVideoAd = MobileAds.getRewardedVideoAdInstance(activity);
        }
    }

    private void playAdMobVideo() {
        if (rewardedVideoAd != null && rewardedVideoAd.isLoaded()) {
            rewardedVideoAd.show();
        }
    }

    private void playMoPubVideo(String customData) {
        if (MoPub.isSdkInitialized() && MoPubRewardedVideos.hasRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID)) {
            MoPubRewardedVideos.showRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID, customData);
        }
    }
}
