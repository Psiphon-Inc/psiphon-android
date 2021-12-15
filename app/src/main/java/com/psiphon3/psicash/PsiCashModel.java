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

package com.psiphon3.psicash;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

@AutoValue
public abstract class PsiCashModel {
    public static Builder builder() {
        return new AutoValue_PsiCashModel.Builder();
    }

    public abstract boolean hasTokens();

    public abstract boolean isAccount();

    public abstract long balance();

    public abstract long reward();

    @Nullable
    public abstract List<PsiCashLib.PurchasePrice> purchasePrices();

    @Nullable
    public abstract PsiCashLib.Purchase nextExpiringPurchase();

    public abstract boolean pendingRefresh();

    @Nullable
    public abstract String accountSignupUrl();

    @Nullable
    public abstract String accountForgotUrl();

    @Nullable
    public abstract String accountManagementUrl();

    @Nullable
    public abstract String accountUsername();

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder hasTokens(boolean b);

        public abstract Builder isAccount(boolean b);

        public abstract Builder balance(long balance);

        public abstract Builder reward(long reward);

        abstract Builder purchasePrices(@Nullable List<PsiCashLib.PurchasePrice> purchasePrices);

        abstract Builder nextExpiringPurchase(@Nullable PsiCashLib.Purchase purchase);

        public abstract Builder pendingRefresh(boolean pendingRefresh);

        abstract Builder accountSignupUrl(@Nullable String accountSignupUrl);

        abstract Builder accountForgotUrl(@Nullable String accountForgotUrl);

        abstract Builder accountManagementUrl(@Nullable String accountManagementUrl);

        abstract Builder accountUsername(@Nullable String accountUsername);

        public abstract PsiCashModel build();

    }
}
