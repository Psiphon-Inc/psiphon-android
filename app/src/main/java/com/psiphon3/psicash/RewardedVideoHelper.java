package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.ads.mediation.mopub.MoPubMediationAdapter;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdCallback;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import io.reactivex.Flowable;
import io.reactivex.Observable;

// Last commit with MoPub rewarded videos 31f71f47d2dd98fdb7711a362eb4464d294d491f
class RewardedVideoHelper {
    // Test videos, supposed to always load
    /*
    private static final String MOPUB_VIDEO_AD_UNIT_ID = "920b6145fb1546cf8b5cf2ac34638bb7";
    private static final String ADMOB_VIDEO_AD_ID = "ca-app-pub-3940256099942544/5224354917";
     */

    // Production values
    private static final String ADMOB_VIDEO_AD_ID = "ca-app-pub-1072041961750291/5751207671";

    private final Observable<RewardedVideoPlayable> adMobVideoObservable;
    private final RewardListener rewardListener;

    interface RewardedVideoPlayable {
        enum State {LOADING, READY, CLOSED}

        State state();

        default void play(Activity activity) {
        }
    }

    Observable<RewardedVideoPlayable> getVideoObservable(Flowable<TunnelState> tunnelStateFlowable) {
        return tunnelStateFlowable
                // Only react to distinct tunnel states that are not UNKNOWN
                .filter(tunnelState -> !tunnelState.isUnknown())
                .distinctUntilChanged()
                .toObservable()
                .switchMap(tunnelState -> {
                    if (tunnelState.isStopped()) {
                        return adMobVideoObservable;
                    }
                    return Observable.empty();
                })
                // Complete when ad is closed
                .takeWhile(rewardedVideoPlayable ->
                        rewardedVideoPlayable.state() != RewardedVideoPlayable.State.CLOSED)
                .startWith(Observable.just(() -> RewardedVideoPlayable.State.LOADING));
    }

    public RewardedVideoHelper(Context context, RewardListener rewardListener) {
        this.rewardListener = rewardListener;

        final AppPreferences mp = new AppPreferences(context);
        final String customData = mp.getString(context.getString(R.string.persistentPsiCashCustomData), "");

        this.adMobVideoObservable = Observable.create(emitter -> {
            // Do not try and load the ad if AdMob SDK is not initialized (it should be at this point).
            // TODO: move RewardedVideoHelper to PsiphonAdManager and use chained completable initializers
            try {
                MobileAds.getInitializationStatus();
            } catch (IllegalStateException ignored) {
                if (!emitter.isDisposed()) {
                    emitter.onError(new PsiCashException.Video("RewardedVideoHelper error: AdMob SDK is not initialized"));
                }
                return;
            }

            if (TextUtils.isEmpty(customData)) {
                // If the custom data is empty we should not attempt to load the ad at all.
                // Notify the subscriber(s), log and bail.
                String errorMessage = "RewardedAd error: empty custom data";
                if (!emitter.isDisposed()) {
                    emitter.onError(new PsiCashException.Video(errorMessage));
                }
                Utils.MyLog.g(errorMessage);
                return;
            }
            RewardedAd rewardedAd = new RewardedAd(context, ADMOB_VIDEO_AD_ID);
            ServerSideVerificationOptions.Builder optionsBuilder = new ServerSideVerificationOptions.Builder();
            optionsBuilder.setCustomData(customData);
            rewardedAd.setServerSideVerificationOptions(optionsBuilder.build());
            RewardedAdCallback adCallback = new RewardedAdCallback() {
                @Override
                public void onRewardedAdOpened() {
                }

                @Override
                public void onRewardedAdClosed() {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(() -> RewardedVideoPlayable.State.CLOSED);
                    }
                }

                @Override
                public void onUserEarnedReward(@NonNull RewardItem reward) {
                    if (RewardedVideoHelper.this.rewardListener != null) {
                        RewardedVideoHelper.this.rewardListener.onReward(reward.getAmount());
                    }
                }

                @Override
                public void onRewardedAdFailedToShow(int errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("RewardedAd failed to show with code: " + errorCode));
                    }
                }
            };
            RewardedAdLoadCallback adLoadCallback = new RewardedAdLoadCallback() {
                @Override
                public void onRewardedAdLoaded() {
                    super.onRewardedAdLoaded();
                    if (!emitter.isDisposed()) {
                        RewardedVideoPlayable rewardedVideoPlayable = new RewardedVideoPlayable() {
                            @Override
                            public void play(Activity activity) {
                                rewardedAd.show(activity, adCallback);
                            }

                            @Override
                            public State state() {
                                return State.READY;
                            }
                        };
                        emitter.onNext(rewardedVideoPlayable);
                    }
                }

                @Override
                public void onRewardedAdFailedToLoad(int errorCode) {
                    super.onRewardedAdFailedToLoad(errorCode);
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("RewardedAd failed to load with code: " + errorCode));
                    }
                }
            };
            Bundle extras = new MoPubMediationAdapter.BundleBuilder()
                    .setRewardCustomData(customData)
                    .build();
            AdRequest.Builder requestBuilder = new AdRequest.Builder()
                    .addNetworkExtrasBundle(MoPubMediationAdapter.class, extras);
            rewardedAd.loadAd(requestBuilder.build(), adLoadCallback);
        });
    }

    public interface RewardListener {
        void onReward(int amount);
    }
}
