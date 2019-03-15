package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

import java.util.Date;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
public abstract class PsiCashViewState implements MviViewState {
    public abstract int uiBalance();
    public abstract boolean purchaseInFlight();
    public abstract boolean animateOnNextBalanceChange();

    @Nullable
    public abstract PsiCashLib.PurchasePrice purchasePrice();

    @Nullable
    public abstract Date nextPurchaseExpiryDate();

    @Nullable
    public abstract Throwable error();

    abstract Builder withState();

    static PsiCashViewState idle() {
        return new AutoValue_PsiCashViewState.Builder()
                .uiBalance(0)
                .purchasePrice(null)
                .nextPurchaseExpiryDate(null)
                .error(null)
                .purchaseInFlight(false)
                .animateOnNextBalanceChange(false)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder uiBalance(int balance);

        abstract Builder error(@Nullable Throwable error);

        abstract Builder purchasePrice(@Nullable PsiCashLib.PurchasePrice price);

        abstract Builder nextPurchaseExpiryDate(@Nullable Date date);

        public abstract Builder purchaseInFlight(boolean b);

        public abstract Builder animateOnNextBalanceChange(boolean b);

        abstract PsiCashViewState build();
    }
}