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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.LceStatus;

public interface PsiCashResult {
    @AutoValue
    abstract class GetPsiCash implements PsiCashResult {
        @NonNull
        static GetPsiCash success(PsiCashModel.PsiCash model) {
            return new AutoValue_PsiCashResult_GetPsiCash(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static GetPsiCash failure(Throwable error) {
            return new AutoValue_PsiCashResult_GetPsiCash(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static GetPsiCash inFlight() {
            return new AutoValue_PsiCashResult_GetPsiCash(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel.PsiCash model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class ExpiringPurchase implements PsiCashResult {
        @NonNull
        static ExpiringPurchase success(PsiCashModel.ExpiringPurchase model) {
            return new AutoValue_PsiCashResult_ExpiringPurchase(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static ExpiringPurchase failure(Throwable error) {
            return new AutoValue_PsiCashResult_ExpiringPurchase(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static ExpiringPurchase inFlight() {
            return new AutoValue_PsiCashResult_ExpiringPurchase(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel.ExpiringPurchase model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class ClearErrorState implements PsiCashResult {
        @NonNull
        static ClearErrorState success() {
            return new AutoValue_PsiCashResult_ClearErrorState();
        }
    }
}
