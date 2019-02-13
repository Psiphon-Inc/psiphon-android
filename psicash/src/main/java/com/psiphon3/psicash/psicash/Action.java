package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.psicash.mvibase.MviAction;

import ca.psiphon.psicashlib.PsiCashLib;

public interface Action extends MviAction {
    @AutoValue
    abstract class GetPsiCash implements Action {
        public static GetPsiCash create(TunnelConnectionStatus status) {
            return new AutoValue_Action_GetPsiCash(status);
        }

        abstract TunnelConnectionStatus connectionStatus();
    }

    @AutoValue
    abstract class MakeExpiringPurchase implements Action {
        public static MakeExpiringPurchase create(TunnelConnectionStatus status, @Nullable PsiCashLib.PurchasePrice price) {
            return new AutoValue_Action_MakeExpiringPurchase(status, price);
        }

        abstract TunnelConnectionStatus connectionStatus();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();
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
}
