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
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.mvibase.MviAction;

import java.util.List;

import io.reactivex.Flowable;

public interface PsiCashAction extends MviAction {
    @AutoValue
    abstract class InitialAction implements PsiCashAction {
        public static InitialAction create() {
            return new AutoValue_PsiCashAction_InitialAction();
        }
    }

    @AutoValue
    abstract class GetPsiCash implements PsiCashAction {
        public static GetPsiCash create(Flowable<TunnelState> tunnelStateFlowable) {
            return new AutoValue_PsiCashAction_GetPsiCash(tunnelStateFlowable);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();
    }

    @AutoValue
    abstract class MakeExpiringPurchase implements PsiCashAction {
        public static MakeExpiringPurchase create(Flowable<TunnelState> tunnelStateFlowable,
                                                  @Nullable String distinguisher,
                                                  @Nullable String transactionClass,
                                                  long expectedPrice) {
            return new AutoValue_PsiCashAction_MakeExpiringPurchase(tunnelStateFlowable, distinguisher,
                    transactionClass, expectedPrice);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();

        @Nullable
        public abstract String distinguisher();

        @Nullable
        public abstract String transactionClass();

        public abstract long expectedPrice();
    }

    @AutoValue
    abstract class ClearErrorState implements PsiCashAction {
        public static ClearErrorState create() {
            return new AutoValue_PsiCashAction_ClearErrorState();
        }
    }

    @AutoValue
    abstract class RemovePurchases implements PsiCashAction {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_PsiCashAction_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }
}
