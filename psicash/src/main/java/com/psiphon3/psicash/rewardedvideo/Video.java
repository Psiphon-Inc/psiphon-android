package com.psiphon3.psicash.rewardedvideo;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;

interface RewardedVideoModel {
    @AutoValue
    abstract class VideoReady implements RewardedVideoModel {
        public static VideoReady create(Runnable videoRunnable) {
            return new AutoValue_RewardedVideoModel_VideoReady(videoRunnable);
        }
        @Nullable
        public abstract Runnable videoPlayRunnable();
    }

    @AutoValue
    abstract class Reward implements RewardedVideoModel {
        public static Reward create(long amount) {
            return new AutoValue_RewardedVideoModel_Reward(amount);
        }

        public abstract long amount();
    }
}