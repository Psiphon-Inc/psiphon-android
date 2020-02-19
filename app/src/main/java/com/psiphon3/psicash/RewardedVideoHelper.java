package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.support.annotation.NonNull;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdCallback;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions;
import com.mopub.common.MoPub;
import com.mopub.common.MoPubReward;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.SdkInitializationListener;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubRewardedVideoListener;
import com.mopub.mobileads.MoPubRewardedVideos;
import com.psiphon3.TunnelState;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;

public class RewardedVideoHelper {
    // Production values
    private static final String MOPUB_VIDEO_AD_UNIT_ID = "7ef66892f0a6417091119b94ce07d6e5";
    private static final String ADMOB_VIDEO_AD_ID = "ca-app-pub-1072041961750291/5751207671";

    private final Single<RewardedVideoPlayable> adMobVideoSingle;
    private final Single<RewardedVideoPlayable> moPubVideoSingle;
    private final Completable initializeMoPubSdk;

    interface RewardedVideoPlayable {
        enum State {LOADING, READY}

        State state();

        default void play(Activity activity) {
        }
    }

    Observable<RewardedVideoPlayable> getVideoObservable(Flowable<TunnelState> tunnelStateFlowable) {
        return tunnelStateFlowable
                .filter(tunnelState -> !tunnelState.isUnknown())
                .flatMapMaybe(tunnelState -> {
                    if (tunnelState.isStopped()) {
                        return adMobVideoSingle.toMaybe();
                    }

                    if (tunnelState.isRunning() && tunnelState.connectionData().isConnected()) {
                        return initializeMoPubSdk
                                .andThen(moPubVideoSingle.toMaybe());
                    }
                    return Maybe.empty();
                })
                .firstOrError()
                .toObservable()
                .startWith(Observable.just(() -> RewardedVideoPlayable.State.LOADING));
    }

    public RewardedVideoHelper(Context context) {
        final AppPreferences mp = new AppPreferences(context);
        String customData = mp.getString(context.getString(R.string.persistentPsiCashCustomData), "");

        this.initializeMoPubSdk = Completable.create(emitter -> {
            if (MoPub.isSdkInitialized()) {
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
                return;
            }
            MoPub.setLocationAwareness(MoPub.LocationAwareness.DISABLED);
            SdkConfiguration.Builder builder = new SdkConfiguration.Builder(MOPUB_VIDEO_AD_UNIT_ID);
            SdkConfiguration sdkConfiguration = builder.build();
            SdkInitializationListener sdkInitializationListener = () -> {
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
            };
            MoPub.initializeSdk(context, sdkConfiguration, sdkInitializationListener);
        });

        this.adMobVideoSingle = Single.create(emitter -> {
            RewardedAd rewardedAd = new RewardedAd(context, ADMOB_VIDEO_AD_ID);
            ServerSideVerificationOptions.Builder optionsBuilder = new ServerSideVerificationOptions.Builder();
            optionsBuilder.setCustomData(customData);
            rewardedAd.setServerSideVerificationOptions(optionsBuilder.build());
            RewardedAdLoadCallback adLoadCallback = new RewardedAdLoadCallback() {
                @Override
                public void onRewardedAdLoaded() {
                    super.onRewardedAdLoaded();
                    if (!emitter.isDisposed()) {
                        RewardedAdCallback adCallback = new RewardedAdCallback() {
                            @Override
                            public void onRewardedAdOpened() {
                            }

                            @Override
                            public void onRewardedAdClosed() {
                            }

                            @Override
                            public void onUserEarnedReward(@NonNull RewardItem reward) {
                                try {
                                    PsiCashClient.getInstance(context).putVideoReward(reward.getAmount());
                                } catch (PsiCashException ignored) {
                                }
                            }

                            @Override
                            public void onRewardedAdFailedToShow(int errorCode) {
                            }
                        };

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
                        emitter.onSuccess(rewardedVideoPlayable);
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
            AdRequest.Builder requestBuilder = new AdRequest.Builder();
            rewardedAd.loadAd(requestBuilder.build(), adLoadCallback);
        });

        this.moPubVideoSingle = Single.create(emitter -> {
            MoPubRewardedVideoListener rewardedVideoListener = new MoPubRewardedVideoListener() {
                @Override
                public void onRewardedVideoLoadSuccess(@NonNull String adUnitId) {
                    if (!emitter.isDisposed()) {
                        // Called when the video for the given adUnitId has loaded.
                        // At this point you should be able to call MoPubRewardedVideos.showRewardedVideo(String) to show the video.
                        if (adUnitId.equals(MOPUB_VIDEO_AD_UNIT_ID) && MoPubRewardedVideos.hasRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID)) {
                            emitter.onSuccess(new RewardedVideoPlayable() {
                                @Override
                                public State state() {
                                    return State.READY;
                                }

                                @Override
                                public void play(Activity ignored) {
                                    MoPubRewardedVideos.showRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID, customData);
                                }
                            });
                        } else {
                            emitter.onError(new PsiCashException.Video("MoPub video failed."));
                        }
                    }
                }

                @Override
                public void onRewardedVideoLoadFailure(@NonNull String adUnitId, @NonNull MoPubErrorCode errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("MoPub video failed with error: " + errorCode.toString()));
                    }
                }

                @Override
                public void onRewardedVideoStarted(@NonNull String adUnitId) {
                }

                @Override
                public void onRewardedVideoPlaybackError(@NonNull String adUnitId, @NonNull MoPubErrorCode errorCode) {
                    if (!emitter.isDisposed()) {
                        emitter.onError(new PsiCashException.Video("MoPub video playback failed with error: " + errorCode.toString()));
                    }
                }

                @Override
                public void onRewardedVideoClicked(@NonNull String adUnitId) {
                }

                @Override
                public void onRewardedVideoClosed(@NonNull String adUnitId) {
                }

                @Override
                public void onRewardedVideoCompleted(@NonNull Set<String> adUnitIds, @NonNull MoPubReward reward) {
                    try {
                        PsiCashClient.getInstance(context).putVideoReward(reward.getAmount());
                    } catch (PsiCashException ignored) {
                    }
                }
            };
            MoPubRewardedVideos.setRewardedVideoListener(rewardedVideoListener);
            MoPubRewardedVideos.loadRewardedVideo(MOPUB_VIDEO_AD_UNIT_ID);
        });
    }
}
