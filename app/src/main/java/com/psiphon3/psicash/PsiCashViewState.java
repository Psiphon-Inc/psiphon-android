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

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
public abstract class PsiCashViewState implements MviViewState {
    public static final int PSICASH_IDLE_BALANCE = -1;

    public abstract boolean hasValidTokens();

    public abstract int uiBalance();

    public abstract boolean psiCashTransactionInFlight();

    @Nullable
    public abstract List<PsiCashLib.PurchasePrice> purchasePrices();

    @Nullable
    public abstract PsiCashLib.Purchase purchase();

    @Nullable
    public abstract Throwable error();

    public abstract boolean videoIsLoading();

    public abstract boolean videoIsLoaded();

    public abstract boolean videoIsPlaying();

    public abstract boolean videoIsFinished();

    public abstract boolean pendingRefresh();

    abstract Builder withState();

    static PsiCashViewState idle() {
        return new AutoValue_PsiCashViewState.Builder()
                .hasValidTokens(false)
                .uiBalance(PSICASH_IDLE_BALANCE)
                .purchasePrices(null)
                .purchase(null)
                .error(null)
                .psiCashTransactionInFlight(false)
                .videoIsLoading(false)
                .videoIsLoaded(false)
                .videoIsPlaying(false)
                .videoIsFinished(false)
                .pendingRefresh(false)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder hasValidTokens(boolean b);

        abstract Builder uiBalance(int balance);

        abstract Builder error(@Nullable Throwable error);

        abstract Builder purchasePrices(@Nullable List<PsiCashLib.PurchasePrice> prices);

        abstract Builder purchase( @Nullable PsiCashLib.Purchase purchase);

        public abstract Builder psiCashTransactionInFlight(boolean b);

        abstract Builder videoIsLoaded(boolean b);

        abstract Builder videoIsLoading(boolean b);

        abstract Builder videoIsPlaying(boolean b);

        abstract Builder videoIsFinished(boolean b);

        abstract Builder pendingRefresh(boolean b);

        abstract PsiCashViewState build();
    }
}