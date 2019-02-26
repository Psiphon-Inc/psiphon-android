package com.psiphon3.psicash.psicash;

import android.app.Application;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.support.annotation.NonNull;

public class PsiCashViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final ExpiringPurchaseListener expiringPurchaseListener;

    public PsiCashViewModelFactory(@NonNull Application application, @NonNull ExpiringPurchaseListener listener) {
        this.application = application;
        this.expiringPurchaseListener = listener;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(PsiCashViewModel.class)) {
            return (T) new PsiCashViewModel(application, expiringPurchaseListener);
        }
        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}
