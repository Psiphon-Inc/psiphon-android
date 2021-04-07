/*
 * Copyright (c) 2021, Psiphon Inc.
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
 */

package com.psiphon3.psicash.store;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psicash.util.SingleViewEvent;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
public abstract class PsiCashStoreViewState implements MviViewState {
    @Nullable
    public abstract PsiCashModel psiCashModel();

    public abstract boolean psiCashTransactionInFlight();

    @Nullable
    public abstract SingleViewEvent<Throwable> errorViewEvent();

    @Nullable
    public abstract SingleViewEvent<Object> purchaseSuccessViewEvent();

    public boolean hasTokens() {
        return psiCashModel() != null && psiCashModel().hasTokens();
    }

    public boolean isAccount() {
        return psiCashModel() != null && psiCashModel().isAccount();
    }

    public int uiBalance() {
        if (psiCashModel() == null) {
            return 0;
        }
        return (int) (Math.floor((long)
                ((psiCashModel().reward() * 1e9 + psiCashModel().balance()) / 1e9)));
    }

    @Nullable
    public List<PsiCashLib.PurchasePrice> purchasePrices() {
        return psiCashModel() == null ? null : psiCashModel().purchasePrices();
    }

    @Nullable
    public PsiCashLib.Purchase purchase() {
        return psiCashModel() == null ? null : psiCashModel().nextExpiringPurchase();
    }

    public boolean pendingRefresh() {
        return psiCashModel() != null && psiCashModel().pendingRefresh();
    }

    abstract Builder withState();

    static PsiCashStoreViewState initialViewState() {
        return new AutoValue_PsiCashStoreViewState.Builder()
                .psiCashModel(null)
                .psiCashTransactionInFlight(false)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder psiCashModel(@Nullable PsiCashModel psiCashModel);

        abstract Builder psiCashTransactionInFlight(boolean b);

        abstract Builder errorViewEvent(@Nullable SingleViewEvent<Throwable> viewEvent);

        abstract Builder purchaseSuccessViewEvent(SingleViewEvent<Object> viewEvent);

        Builder error(@Nullable Throwable error) {
            if (error == null) {
                return errorViewEvent(null);
            }
            SingleViewEvent<Throwable> singleViewEvent = new SingleViewEvent<>(error);
            return errorViewEvent(singleViewEvent);
        }

        Builder purchaseSuccess() {
            SingleViewEvent<Object> singleViewEvent = new SingleViewEvent<>(new Object());
            return purchaseSuccessViewEvent(singleViewEvent);
        }

        abstract PsiCashStoreViewState build();
    }
}
