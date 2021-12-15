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

package com.psiphon3.psicash.details;

import com.google.auto.value.AutoValue;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.mvibase.MviAction;

import java.util.List;

import io.reactivex.Flowable;

public interface PsiCashDetailsAction extends MviAction {
    @AutoValue
    abstract class InitialAction implements PsiCashDetailsAction {
        public static InitialAction create() {
            return new AutoValue_PsiCashDetailsAction_InitialAction();
        }
    }

    @AutoValue
    abstract class GetPsiCash implements PsiCashDetailsAction {
        public static GetPsiCash create(Flowable<TunnelState> tunnelStateFlowable) {
            return new AutoValue_PsiCashDetailsAction_GetPsiCash(tunnelStateFlowable);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();
    }

    @AutoValue
    abstract class RemovePurchases implements PsiCashDetailsAction {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_PsiCashDetailsAction_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }
}
