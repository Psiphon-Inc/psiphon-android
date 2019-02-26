package com.psiphon3.psicash.rewardedvideo;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.LceStatus;

public interface Result {
    @AutoValue
    abstract class VideoReady implements Result {
        @NonNull
        static VideoReady success(RewardedVideoModel.VideoReady model) {
            return new AutoValue_Result_VideoReady(LceStatus.SUCCESS, model, null);
        }
        @NonNull
        static VideoReady inFlight() {
            return new AutoValue_Result_VideoReady(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        static VideoReady failure(Throwable error) {
            return new AutoValue_Result_VideoReady(LceStatus.FAILURE, null, error);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract RewardedVideoModel.VideoReady model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class Reward implements Result {
        @NonNull
        static Reward success(RewardedVideoModel.Reward model) {
            return new AutoValue_Result_Reward(model);
        }

        @Nullable
        abstract RewardedVideoModel.Reward model();
    }
}
