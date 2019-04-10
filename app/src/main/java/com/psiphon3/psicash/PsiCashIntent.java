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
import com.psiphon3.psicash.mvibase.MviIntent;
import com.psiphon3.TunnelState;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;


public interface PsiCashIntent extends MviIntent {
    @AutoValue
    abstract class PurchaseSpeedBoost implements PsiCashIntent {
        public static PurchaseSpeedBoost create(TunnelState state, @Nullable PsiCashLib.PurchasePrice price, boolean hasActiveBoost) {
            return new AutoValue_PsiCashIntent_PurchaseSpeedBoost(state, price, hasActiveBoost);
        }

        abstract TunnelState connectionState();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();

        abstract boolean hasActiveBoost();
    }

    @AutoValue
    abstract class ClearErrorState implements PsiCashIntent {
        public static ClearErrorState create() {
            return new AutoValue_PsiCashIntent_ClearErrorState();
        }
    }

    @AutoValue
    abstract class GetPsiCashLocal implements PsiCashIntent {
        public static GetPsiCashLocal create() {
            return new AutoValue_PsiCashIntent_GetPsiCashLocal();
        }
    }

    @AutoValue
    abstract class GetPsiCashRemote implements PsiCashIntent {
        public static GetPsiCashRemote create(TunnelState state) {
            return new AutoValue_PsiCashIntent_GetPsiCashRemote(state);
        }

        public abstract TunnelState connectionState();
    }

    @AutoValue
    abstract class RemovePurchases implements PsiCashIntent {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_PsiCashIntent_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }

    @AutoValue
    abstract class LoadVideoAd implements PsiCashIntent {
        public static LoadVideoAd create(TunnelState status) {
            return new AutoValue_PsiCashIntent_LoadVideoAd(status);
        }

        abstract TunnelState connectionState();
    }

}
