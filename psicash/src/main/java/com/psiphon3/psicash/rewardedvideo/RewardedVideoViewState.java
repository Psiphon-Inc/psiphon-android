package com.psiphon3.psicash.rewardedvideo;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

import java.util.ArrayList;
import java.util.List;

@AutoValue
public abstract class RewardedVideoViewState implements MviViewState {

    public enum ViewAction{LOAD_VIDEO_ACTION, REWARD_ACTION};

    @Nullable
    public abstract Runnable videoPlayRunnable();

    public abstract List<RewardedVideoViewState.ViewAction> viewActions();


    @Nullable
    abstract Throwable error();

    abstract Builder toBuilder();

    static RewardedVideoViewState idle() {
        return new AutoValue_RewardedVideoViewState.Builder()
                .videoPlayRunnable(null)
                .error(null)
                .viewActions(new ArrayList<>())
                .build();
    }

    Builder withLastState() {
        return toBuilder()
                .viewActions(new ArrayList<>());
    }

    @AutoValue.Builder
    static abstract class Builder {
        private List<ViewAction> actionList = new ArrayList<>();

        abstract Builder videoPlayRunnable(Runnable runnable);

        abstract Builder error(@Nullable Throwable error);

        protected abstract Builder viewActions(List<ViewAction> list);

        public Builder addViewAction(ViewAction action) {
            actionList.add(action);
            return this.viewActions(actionList);
        }

        abstract RewardedVideoViewState build();
    }
}
