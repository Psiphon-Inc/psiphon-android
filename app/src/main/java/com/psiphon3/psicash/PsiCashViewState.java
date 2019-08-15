/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;

import java.util.Date;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
public abstract class PsiCashViewState implements MviViewState {
    public abstract int uiBalance();

    public abstract boolean psiCashTransactionInFlight();

    public abstract boolean animateOnNextBalanceChange();

    @Nullable
    public abstract PsiCashLib.PurchasePrice purchasePrice();

    @Nullable
    public abstract Date nextPurchaseExpiryDate();

    @Nullable
    public abstract Throwable error();

    public abstract boolean videoIsLoading();

    public abstract boolean videoIsLoaded();

    public abstract boolean videoIsPlaying();

    public abstract boolean videoIsFinished();

    abstract Builder withState();

    static PsiCashViewState idle() {
        return new AutoValue_PsiCashViewState.Builder()
                .uiBalance(0)
                .purchasePrice(null)
                .nextPurchaseExpiryDate(null)
                .error(null)
                .psiCashTransactionInFlight(false)
                .animateOnNextBalanceChange(false)
                .videoIsLoading(false)
                .videoIsLoaded(false)
                .videoIsPlaying(false)
                .videoIsFinished(false)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder uiBalance(int balance);

        abstract Builder error(@Nullable Throwable error);

        abstract Builder purchasePrice(@Nullable PsiCashLib.PurchasePrice price);

        abstract Builder nextPurchaseExpiryDate(@Nullable Date date);

        public abstract Builder psiCashTransactionInFlight(boolean b);

        public abstract Builder animateOnNextBalanceChange(boolean b);

        abstract Builder videoIsLoaded(boolean b);

        abstract Builder videoIsLoading(boolean b);

        abstract Builder videoIsPlaying(boolean b);

        abstract Builder videoIsFinished(boolean b);

        abstract PsiCashViewState build();
    }
}