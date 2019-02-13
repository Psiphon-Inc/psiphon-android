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
            return new AutoValue_Result_VideoReady(LceStatus.SUCCESS, model);
        }
        @NonNull
        static VideoReady inFlight() {
            return new AutoValue_Result_VideoReady(LceStatus.IN_FLIGHT, null);
        }

        @NonNull
        static VideoReady failure(Throwable error) {
            // TODO log error
            return new AutoValue_Result_VideoReady(LceStatus.FAILURE, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract RewardedVideoModel.VideoReady model();
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

    @AutoValue
    abstract class VideoClosed implements Result {
        @NonNull
        static VideoClosed success() {
            return new AutoValue_Result_VideoClosed();
        }
    }
}
