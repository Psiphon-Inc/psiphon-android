package com.psiphon3.psicash.rewardedvideo;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

@AutoValue
public abstract class RewardedVideoViewState implements MviViewState {

    @Nullable
    public abstract Runnable videoPlayRunnable();

    @Nullable
    abstract Throwable error();

    abstract Builder toBuilder();

    static RewardedVideoViewState idle() {
        return new AutoValue_RewardedVideoViewState.Builder()
                .videoPlayRunnable(null)
                .error(null)
                .build();
    }

    Builder withLastState() {
        return toBuilder();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder videoPlayRunnable(Runnable runnable);

        abstract Builder error(@Nullable Throwable error);

        abstract RewardedVideoViewState build();
    }
}
