package com.psiphon3.psicash.rewardedvideo;

import android.content.Context;

import com.psiphon3.psicash.psicash.PsiCashClient;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

class RewardedVideoActionProcessorHolder {
    private final ObservableTransformer<Action.LoadVideoAd, Result> loadVideoAdProcessor;
    final ObservableTransformer<Action, Result> actionProcessor;

    RewardedVideoActionProcessorHolder(Context context, RewardListener rewardListener) {
        this.loadVideoAdProcessor = actions ->
                actions.switchMap(action ->
                        RewardedVideoClient.getInstance().loadRewardedVideo(context, action.connectionState(), PsiCashClient.getInstance(context).rewardedVideoCustomData())
                                .map(r -> {
                                    if (r instanceof RewardedVideoModel.VideoReady) {
                                        return Result.VideoReady.success((RewardedVideoModel.VideoReady) r);
                                    } else if (r instanceof RewardedVideoModel.Reward) {
                                        rewardListener.onNewReward(context, ((RewardedVideoModel.Reward) r).amount());
                                        return Result.Reward.success((RewardedVideoModel.Reward) r);
                                    }
                                    throw new IllegalArgumentException("Unknown result: " + r);
                                })
                                .startWith(Result.VideoReady.inFlight())
                                // load a new one after completion until disposed of by switchMap to next action
                                .repeat()
                                .onErrorReturn(Result.VideoReady::failure));

        this.actionProcessor = actions -> actions.publish(
                shared -> shared.ofType(Action.LoadVideoAd.class).compose(loadVideoAdProcessor)
                        .cast(Result.class).mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof Action.LoadVideoAd))
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));

    }

}
