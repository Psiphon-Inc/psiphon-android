package com.psiphon3.psicash.rewardedvideo;

import android.app.Application;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.support.annotation.NonNull;


public class RewardedVideoViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final RewardListener rewardListener;

    public RewardedVideoViewModelFactory(@NonNull Application application, @NonNull RewardListener listener) {
        this.application = application;
        this.rewardListener = listener;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(RewardedVideoViewModel.class)) {
            return (T) new RewardedVideoViewModel(application, rewardListener);
        }
        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}
