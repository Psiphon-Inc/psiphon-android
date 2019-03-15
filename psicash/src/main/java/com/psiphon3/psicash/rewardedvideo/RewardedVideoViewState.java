package com.psiphon3.psicash.rewardedvideo;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

@AutoValue
public abstract class RewardedVideoViewState implements MviViewState {

    @Nullable
    public abstract Runnable videoPlayRunnable();

    @Nullable
    public abstract Throwable error();

    public abstract boolean inFlight();

    public abstract boolean shouldAutoLoadOnNextForeground();

    abstract Builder toBuilder();

    static RewardedVideoViewState idle() {
        return new AutoValue_RewardedVideoViewState.Builder()
                .videoPlayRunnable(null)
                .error(null)
                .inFlight(false)
                .shouldAutoLoadOnNextForeground(false)
                .build();
    }

    Builder withLastState() {
        return toBuilder();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder videoPlayRunnable(Runnable runnable);

        abstract Builder error(@Nullable Throwable error);

        public abstract Builder inFlight(boolean b);

        abstract Builder shouldAutoLoadOnNextForeground(boolean b);

        abstract RewardedVideoViewState build();
    }
}
