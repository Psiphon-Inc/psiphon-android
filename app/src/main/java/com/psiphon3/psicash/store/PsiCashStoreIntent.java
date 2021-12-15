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
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.mvibase.MviIntent;

import io.reactivex.Flowable;


public interface PsiCashStoreIntent extends MviIntent {
    @AutoValue
    abstract class InitialIntent implements PsiCashStoreIntent {
        public static InitialIntent create() {
            return new AutoValue_PsiCashStoreIntent_InitialIntent();
        }
    }

    @AutoValue
    abstract class GetPsiCash implements PsiCashStoreIntent {
        public static GetPsiCash create(Flowable<TunnelState> tunnelStateFlowable) {
            return new AutoValue_PsiCashStoreIntent_GetPsiCash(tunnelStateFlowable);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();
    }

    @AutoValue
    abstract class PurchaseSpeedBoost implements PsiCashStoreIntent {
        public static PurchaseSpeedBoost create(Flowable<TunnelState> tunnelStateFlowable,
                                                @Nullable String distinguisher,
                                                @Nullable String transactionClass,
                                                long expectedPrice) {
            return new AutoValue_PsiCashStoreIntent_PurchaseSpeedBoost(
                    tunnelStateFlowable,
                    distinguisher,
                    transactionClass,
                    expectedPrice);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();

        @Nullable
        public abstract String distinguisher();

        @Nullable
        public abstract String transactionClass();

        public abstract long expectedPrice();
    }
}
