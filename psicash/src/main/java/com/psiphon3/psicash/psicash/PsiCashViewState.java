package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

import java.util.Date;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
abstract class PsiCashViewState implements MviViewState {

    abstract long balance();
    abstract long reward();
    abstract boolean purchaseInFlight();

    @Nullable
    abstract PsiCashLib.PurchasePrice purchasePrice();

    @Nullable
    abstract Date nextPurchaseExpiryDate();

    @Nullable
    abstract Throwable error();

    abstract Builder withState();

    static PsiCashViewState idle() {
        return new AutoValue_PsiCashViewState.Builder()
                .balance(0L)
                .reward(0L)
                .purchasePrice(null)
                .nextPurchaseExpiryDate(null)
                .error(null)
                .purchaseInFlight(false)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder balance(long balance);

        abstract Builder reward(long reward);

        abstract Builder error(@Nullable Throwable error);

        abstract Builder purchasePrice(@Nullable PsiCashLib.PurchasePrice price);

        abstract Builder nextPurchaseExpiryDate(@Nullable Date date);

        public abstract Builder purchaseInFlight(boolean b);

        abstract PsiCashViewState build();
    }
}