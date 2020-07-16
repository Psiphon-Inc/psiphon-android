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

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviIntent;

import java.util.List;


public interface PsiCashIntent extends MviIntent {
    @AutoValue
    abstract class InitialIntent implements PsiCashIntent {
        public static InitialIntent create() {
            return new AutoValue_PsiCashIntent_InitialIntent();
        }
    }

    @AutoValue
    abstract class PurchaseSpeedBoost implements PsiCashIntent {
        public static PurchaseSpeedBoost create(@Nullable String distinguisher, @Nullable String transactionClass,
                                                long expectedPrice) {
            return new AutoValue_PsiCashIntent_PurchaseSpeedBoost(distinguisher, transactionClass, expectedPrice);
        }

        @Nullable
        public abstract String distinguisher();

        @Nullable
        public abstract String transactionClass();

        public abstract long expectedPrice();
    }

    @AutoValue
    abstract class ClearErrorState implements PsiCashIntent {
        public static ClearErrorState create() {
            return new AutoValue_PsiCashIntent_ClearErrorState();
        }
    }

    @AutoValue
    abstract class GetPsiCash implements PsiCashIntent {
        public static GetPsiCash create() {
            return new AutoValue_PsiCashIntent_GetPsiCash();
        }
    }

    @AutoValue
    abstract class RemovePurchases implements PsiCashIntent {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_PsiCashIntent_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }
}
