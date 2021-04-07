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

package com.psiphon3.psicash.account;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psicash.util.SingleViewEvent;

@AutoValue
public abstract class PsiCashAccountViewState implements MviViewState {

    @Nullable
    public abstract PsiCashModel psiCashModel();

    public abstract boolean psiCashTransactionInFlight();

    @Nullable
    public abstract SingleViewEvent<Throwable> errorViewEvent();

    @Nullable
    public abstract SingleViewEvent<String> notificationViewEvent();

    abstract PsiCashAccountViewState.Builder withState();

    public int uiBalance() {
        if (psiCashModel() == null) {
            return 0;
        }
        return (int) (Math.floor((long)
                ((psiCashModel().reward() * 1e9 + psiCashModel().balance()) / 1e9)));
    }

    static PsiCashAccountViewState initialViewState() {
        return new AutoValue_PsiCashAccountViewState.Builder()
                .psiCashTransactionInFlight(false)
                .error(null)
                .uiNotification(null)
                .psiCashModel(null)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract PsiCashAccountViewState.Builder psiCashModel(@Nullable PsiCashModel psiCashModel);

        abstract PsiCashAccountViewState.Builder psiCashTransactionInFlight(boolean b);

        abstract PsiCashAccountViewState.Builder errorViewEvent(@Nullable SingleViewEvent<Throwable> error);

        abstract PsiCashAccountViewState.Builder notificationViewEvent(@Nullable SingleViewEvent<String> notification);

        PsiCashAccountViewState.Builder error(@Nullable Throwable error) {
            if (error == null) {
                return errorViewEvent(null);
            }
            SingleViewEvent<Throwable> singleViewEvent = new SingleViewEvent<>(error);
            return errorViewEvent(singleViewEvent);
        }

        PsiCashAccountViewState.Builder uiNotification(String notification) {
            if (notification == null) {
                return notificationViewEvent(null);
            }
            SingleViewEvent<String> singleViewEvent = new SingleViewEvent<>(notification);
            return notificationViewEvent(singleViewEvent);
        }

        abstract PsiCashAccountViewState build();
    }
}
