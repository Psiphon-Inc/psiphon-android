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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.PsiCashModel;
import com.psiphon3.psicash.util.LceStatus;

public interface PsiCashSettingsResult {
    @AutoValue
    abstract class GetPsiCash implements PsiCashSettingsResult {
        @NonNull
        static PsiCashSettingsResult.GetPsiCash success(PsiCashModel model) {
            return new AutoValue_PsiCashSettingsResult_GetPsiCash(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static PsiCashSettingsResult.GetPsiCash failure(Throwable error) {
            return new AutoValue_PsiCashSettingsResult_GetPsiCash(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static PsiCashSettingsResult.GetPsiCash inFlight() {
            return new AutoValue_PsiCashSettingsResult_GetPsiCash(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class AccountLogout implements PsiCashSettingsResult {
        @NonNull
        static PsiCashSettingsResult.AccountLogout success(PsiCashModel model) {
            return new AutoValue_PsiCashSettingsResult_AccountLogout(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static PsiCashSettingsResult.AccountLogout failure(Throwable error) {
            return new AutoValue_PsiCashSettingsResult_AccountLogout(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static PsiCashSettingsResult.AccountLogout inFlight() {
            return new AutoValue_PsiCashSettingsResult_AccountLogout(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel model();

        @Nullable
        abstract Throwable error();
    }
}
