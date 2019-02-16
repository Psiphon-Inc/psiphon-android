package com.psiphon3.psicash.rewardedvideo;

import android.content.Context;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

class RewardedVideoActionProcessorHolder {
    private Context context;

    private ObservableTransformer<Action.LoadVideoAd, Result>
            loadVideoAdProcessor = actions ->
            actions.switchMap(action ->
                    RewardedVideoClient.getInstance(context).loadRewardedVideo(action.connectionState())
                            .map(r -> {
                                if (r instanceof RewardedVideoModel.VideoReady) {
                                    return Result.VideoReady.success((RewardedVideoModel.VideoReady) r);
                                } else if (r instanceof RewardedVideoModel.Reward) {
                                    return Result.Reward.success((RewardedVideoModel.Reward) r);
                                } else if (r instanceof RewardedVideoModel.VideoClosed) {
                                    return Result.VideoClosed.success();
                                }
                                throw new IllegalArgumentException("Unknown result: " + r);
                            })
                            .startWith(Result.VideoReady.inFlight())
                            .onErrorReturn(Result.VideoReady::failure));

    ObservableTransformer<Action, Result> actionProcessor =
            actions -> actions.publish(shared ->
                    shared.ofType(Action.LoadVideoAd.class).compose(loadVideoAdProcessor)
                            .cast(Result.class).mergeWith(
                            // Error for not implemented actions
                            shared.filter(v -> !(v instanceof Action.LoadVideoAd))
                                    .flatMap(w -> Observable.error(
                                            new IllegalArgumentException("Unknown action: " + w)))));


    public RewardedVideoActionProcessorHolder(Context context) {
        this.context = context;
    }

}
