package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionState;
import com.psiphon3.psicash.mvibase.MviIntent;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;


public interface Intent extends MviIntent {
    @AutoValue
    abstract class PurchaseSpeedBoost implements Intent {
        public static PurchaseSpeedBoost create(TunnelConnectionState state, @Nullable PsiCashLib.PurchasePrice price, boolean hasActiveBoost) {
            return new AutoValue_Intent_PurchaseSpeedBoost(state, price, hasActiveBoost);
        }

        abstract TunnelConnectionState connectionState();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();

        abstract boolean hasActiveBoost();
    }

    @AutoValue
    abstract class ClearErrorState implements Intent {
        public static ClearErrorState create() {
            return new AutoValue_Intent_ClearErrorState();
        }
    }

    @AutoValue
    abstract class GetPsiCashLocal implements Intent {
        public static GetPsiCashLocal create() {
            return new AutoValue_Intent_GetPsiCashLocal();
        }
    }

    @AutoValue
    abstract class GetPsiCashRemote implements Intent {
        public static GetPsiCashRemote create(TunnelConnectionState state) {
            return new AutoValue_Intent_GetPsiCashRemote(state);
        }

        public abstract TunnelConnectionState connectionState();
    }

    @AutoValue
    abstract class RemovePurchases implements Intent {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_Intent_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }
}
