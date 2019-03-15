package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionState;
import com.psiphon3.psicash.mvibase.MviAction;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

public interface Action extends MviAction {
    @AutoValue
    abstract class GetPsiCashRemote implements Action {
        public static GetPsiCashRemote create(TunnelConnectionState status) {
            return new AutoValue_Action_GetPsiCashRemote(status);
        }

        abstract TunnelConnectionState connectionState();
    }

    @AutoValue
    abstract class MakeExpiringPurchase implements Action {
        public static MakeExpiringPurchase create(TunnelConnectionState status, @Nullable PsiCashLib.PurchasePrice price, boolean hasActiveBoost) {
            return new AutoValue_Action_MakeExpiringPurchase(status, price, hasActiveBoost);
        }

        abstract TunnelConnectionState connectionState();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();

        abstract boolean hasActiveBoost();
    }

    @AutoValue
    abstract class ClearErrorState implements Action {
        public static ClearErrorState create() {
            return new AutoValue_Action_ClearErrorState();
        }
    }

    @AutoValue
    abstract class GetPsiCashLocal implements Action {
        public static GetPsiCashLocal create() {
            return new AutoValue_Action_GetPsiCashLocal();
        }
    }

    @AutoValue
    abstract class RemovePurchases implements Action {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_Action_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }
}
