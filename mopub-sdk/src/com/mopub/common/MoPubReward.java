package com.mopub.common;

import android.support.annotation.NonNull;

/**
 * Represents a reward to the user for completing a rewarded task like watching a video.
 */
public final class MoPubReward {
    public static final String NO_REWARD_LABEL = "";
    public static final int NO_REWARD_AMOUNT = -123;
    private final boolean mSuccess;
    private final @NonNull String mLabel;
    private final int mAmount;

    private MoPubReward(boolean success, @NonNull String label, int amount) {
        mSuccess = success;
        mLabel = label;
        mAmount = amount;
    }

    @NonNull
    public static MoPubReward failure() {
        return new MoPubReward(false, NO_REWARD_LABEL, 0);
    }

    @NonNull
    public static MoPubReward success(@NonNull final String rewardLabel, final int amount) {
        return new MoPubReward(true, rewardLabel, amount);
    }

    public final boolean isSuccessful() {
        return mSuccess;
    }

    @NonNull
    public final String getLabel() {
        return mLabel;
    }

    public final int getAmount() {
        return mAmount;
    }
}
