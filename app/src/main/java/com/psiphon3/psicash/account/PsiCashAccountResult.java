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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.mvibase.MviResult;
import com.psiphon3.psicash.util.LceStatus;

public interface PsiCashAccountResult extends MviResult {
    @AutoValue
    abstract class GetPsiCash implements PsiCashAccountResult {
        @NonNull
        static GetPsiCash success(PsiCashModel model) {
            return new AutoValue_PsiCashAccountResult_GetPsiCash(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static GetPsiCash failure(Throwable error) {
            return new AutoValue_PsiCashAccountResult_GetPsiCash(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static GetPsiCash inFlight() {
            return new AutoValue_PsiCashAccountResult_GetPsiCash(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class AccountLogin implements PsiCashAccountResult {
        @NonNull
        static AccountLogin success(PsiCashModel model, boolean lastTrackerMerge) {
            return new AutoValue_PsiCashAccountResult_AccountLogin(LceStatus.SUCCESS, model, lastTrackerMerge, null);
        }

        @NonNull
        static AccountLogin failure(Throwable error) {
            return new AutoValue_PsiCashAccountResult_AccountLogin(LceStatus.FAILURE, null, false, error);
        }

        @NonNull
        static AccountLogin inFlight() {
            return new AutoValue_PsiCashAccountResult_AccountLogin(LceStatus.IN_FLIGHT, null, false, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel model();

        abstract boolean lastTrackerMerge();

        @Nullable
        abstract Throwable error();
    }
}
