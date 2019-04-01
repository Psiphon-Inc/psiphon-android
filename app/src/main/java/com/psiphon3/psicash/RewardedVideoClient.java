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

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.text.TextUtils;

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
import com.psiphon3.TunnelState;

import java.util.Set;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class RewardedVideoClient {

    interface RewardedVideoPlayable {
        boolean play();
    }
    private RewardedVideoPlayable rewardedVideoPlayable;

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

    private Observable<? extends PsiCashModel> loadMoPubVideos(final String customData) {
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
                            emitter.onNext(PsiCashModel.RewardedVideoState.loaded());
                            rewardedVideoPlayable = () -> {
                                if (MoPub.isSdkInitialized() && MoPubRewardedVideos.hasRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID)) {
                                    MoPubRewardedVideos.showRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID, customData);
                                    return true;
                                }
                                return false;
                            };

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
                    if (!emitter.isDisposed()) {
                        emitter.onNext(PsiCashModel.RewardedVideoState.playing());
                    }
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
                        emitter.onNext(PsiCashModel.Reward.create(reward.getAmount()));
                    }
                }
            };
            MoPubRewardedVideos.setRewardedVideoListener(rewardedVideoListener);
            MoPubRewardedVideos.loadRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID);
        });
    }

    private Observable<? extends PsiCashModel> loadAdMobVideos(String customData) {
        rewardedVideoPlayable = null;
        return Observable.create(emitter -> {
            RewardedVideoAdListener listener = new RewardedVideoAdListener() {
                @Override
                public void onRewarded(RewardItem reward) {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(PsiCashModel.Reward.create(reward.getAmount()));
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
                        emitter.onNext(PsiCashModel.RewardedVideoState.loaded());
                        rewardedVideoPlayable = () -> {
                            if (rewardedVideoAd != null && rewardedVideoAd.isLoaded()) {
                                rewardedVideoAd.show();
                                return true;
                            }
                            return false;
                        };
                    }
                }

                @Override
                public void onRewardedVideoAdOpened() {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(PsiCashModel.RewardedVideoState.playing());
                    }
                }

                @Override
                public void onRewardedVideoStarted() {
                }

                @Override
                public void onRewardedVideoCompleted() {
                }
            };
            rewardedVideoAd.setCustomData(customData)
            rewardedVideoAd.setRewardedVideoAdListener(listener);
            rewardedVideoAd.loadAd(ADMOB_VIDEO_AD_ID, new AdRequest.Builder().build());
        });
    }

    Observable<? extends PsiCashModel> loadRewardedVideo(Context context, TunnelState connectionState, String rewardCustomData) {
        rewardedVideoPlayable = null;
        return Observable.just(Pair.create(connectionState, rewardCustomData))
                .observeOn(AndroidSchedulers.mainThread())
                .switchMap(pair -> {
                    TunnelState state = pair.first;
                    String customData = pair.second;
                    if (TextUtils.isEmpty(customData)) {
                        return Observable.error(new IllegalStateException("Video customData is empty"));
                    }
                    if (!PsiCashClient.getInstance(context).hasEarnerToken()) {
                        return Observable.error(new IllegalStateException("PsiCash lib has no earner token."));
                    }
                    if (state.isRunning()) {
                        TunnelState.ConnectionData connectionData = state.connectionData();
                        // When running VPN mode should load MoPub ads and BOM should load AdMob.
                        if (connectionData.vpnMode()) {
                            if (connectionData.isConnected()) {
                                return loadMoPubVideos(customData);
                            } else {
                                return Observable.never();
                            }
                        } else {
                            return loadAdMobVideos(customData);
                        }
                    } else {
                        // Not running should load AdMob ads
                        return loadAdMobVideos(customData);
                    }
                });
    }

    public void initWithActivity(Activity activity) {
        if (rewardedVideoAd == null) {
            rewardedVideoAd = MobileAds.getRewardedVideoAdInstance(activity);
        }
    }

    public boolean playRewardedVideo() {
        if (rewardedVideoPlayable != null) {
            boolean attemptedToPlay = rewardedVideoPlayable.play();
            rewardedVideoPlayable = null;
            return attemptedToPlay;
        }
        return false;
    }
}
