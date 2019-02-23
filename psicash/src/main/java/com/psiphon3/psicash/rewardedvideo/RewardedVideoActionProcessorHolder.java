package com.psiphon3.psicash.rewardedvideo;

import android.content.Context;

import com.psiphon3.psicash.psicash.PsiCashClient;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

class RewardedVideoActionProcessorHolder {
    private Context context;

    private ObservableTransformer<Action.LoadVideoAd, Result>
            loadVideoAdProcessor = actions ->
            actions.switchMap(action ->
                    RewardedVideoClient.getInstance().loadRewardedVideo(context, action.connectionState(), PsiCashClient.getInstance(context).rewardedVideoCustomData())
                            .map(r -> {
                                if (r instanceof RewardedVideoModel.VideoReady) {
                                    return Result.VideoReady.success((RewardedVideoModel.VideoReady) r);
                                } else if (r instanceof RewardedVideoModel.Reward) {
                                    // Store the reward amount
                                    PsiCashClient.getInstance(context).putVideoReward(((RewardedVideoModel.Reward) r).amount());
                                    return Result.Reward.success((RewardedVideoModel.Reward) r);
                                } else if (r instanceof RewardedVideoModel.VideoClosed) {
                                    return Result.VideoClosed.success();
                                }
                                throw new IllegalArgumentException("Unknown result: " + r);
                            })
                            .startWith(Result.VideoReady.inFlight()))
                    .onErrorReturn(Result.VideoReady::failure);

    ObservableTransformer<Action, Result> actionProcessor =
            actions -> actions.publish(shared ->
                    shared.ofType(Action.LoadVideoAd.class).compose(loadVideoAdProcessor)
                            .cast(Result.class).mergeWith(
                            // Error for not implemented actions
                            shared.filter(v -> !(v instanceof Action.LoadVideoAd))
                                    .flatMap(w -> Observable.error(
                                            new IllegalArgumentException("Unknown action: " + w)))));


    RewardedVideoActionProcessorHolder(Context context) {
        this.context = context;
    }

}
