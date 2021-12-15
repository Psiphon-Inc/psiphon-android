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

package com.psiphon3.psicash.settings;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psicash.util.SingleViewEvent;

@AutoValue
public abstract class PsiCashSettingsViewState implements MviViewState {
    public enum AccountState {INVALID, NOT_ACCOUNT, ACCOUNT_LOGGED_IN, ACCOUNT_LOGGED_OUT}

    public abstract AccountState accountState();

    public abstract boolean psiCashTransactionInFlight();

    @Nullable
    public abstract SingleViewEvent<Throwable> errorViewEvent();

    @Nullable
    public abstract String accountManagementUrl();

    abstract Builder withState();

    static PsiCashSettingsViewState initialViewState() {
        return new AutoValue_PsiCashSettingsViewState.Builder()
                .accountState(AccountState.INVALID)
                .accountManagementUrl(null)
                .psiCashTransactionInFlight(false)
                .error(null)
                .build();
    }

    @AutoValue.Builder
    static abstract class Builder {
        abstract Builder accountState(AccountState accountState);

        abstract Builder psiCashTransactionInFlight(boolean b);

        abstract Builder errorViewEvent(@Nullable SingleViewEvent<Throwable> error);

        abstract Builder accountManagementUrl(@Nullable String url);

        Builder error(@Nullable Throwable error) {
            if (error == null) {
                return errorViewEvent(null);
            }
            SingleViewEvent<Throwable> singleViewEvent = new SingleViewEvent<>(error);
            return errorViewEvent(singleViewEvent);
        }

        abstract PsiCashSettingsViewState build();
    }
}
