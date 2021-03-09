package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import com.freestar.android.ads.AdRequest;
import com.freestar.android.ads.FreeStarAds;
import com.freestar.android.ads.RewardedAd;
import com.freestar.android.ads.RewardedAdListener;
import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import io.reactivex.Flowable;
import io.reactivex.Observable;

// Last commit with MoPub rewarded videos 31f71f47d2dd98fdb7711a362eb4464d294d491f
class RewardedVideoHelper {
    private final Observable<RewardedVideoPlayable> freestarVideoObservable;
    private final RewardListener rewardListener;
    private RewardedAd rewardedAd;

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
                        return freestarVideoObservable;
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

        this.freestarVideoObservable = Observable.create(emitter -> {
            // Do not try and load the ad if the SDK is not initialized (it should be at this point).
            // TODO: move RewardedVideoHelper to PsiphonAdManager and use chained completable initializers
            if (!FreeStarAds.isInitialized()) {
                if (!emitter.isDisposed()) {
                    emitter.onError(new PsiCashException.Video("RewardedVideoHelper error: Freestar SDK is not initialized"));
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
            rewardedAd = new RewardedAd(context, new RewardedAdListener() {

                @Override
                public void onRewardedVideoLoaded(String placement) {
                    if (!emitter.isDisposed()) {
                        RewardedVideoPlayable rewardedVideoPlayable = new RewardedVideoPlayable() {
                            @Override
                            public void play(Activity activity) {
                                // TODO: use R.string.psicash_purchase_free_amount ?
                                rewardedAd.showRewardAd("", customData, "PsiCash", "35");
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
                public void onRewardedVideoFailed(String placement, int errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("RewardedAd failed to load with code: " + errorCode));
                    }
                }

                @Override
                public void onRewardedVideoShown(String placement) {

                }

                @Override
                public void onRewardedVideoShownError(String placement, int errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("RewardedAd failed to show with code: " + errorCode));
                    }
                }

                @Override
                public void onRewardedVideoDismissed(String placement) {
                    if (!emitter.isDisposed()) {
                        emitter.onNext(() -> RewardedVideoPlayable.State.CLOSED);
                    }
                }

                @Override
                public void onRewardedVideoCompleted(String placement) {
                    if (RewardedVideoHelper.this.rewardListener != null) {
                        // TODO: can we get the amount from the ad server?
                        // or use R.string.psicash_purchase_free_amount ?
                        RewardedVideoHelper.this.rewardListener.onReward(35);
                    }
                }
            });

            rewardedAd.loadAd(new AdRequest(context));
        });
    }

    public interface RewardListener {
        void onReward(int amount);
    }
}
