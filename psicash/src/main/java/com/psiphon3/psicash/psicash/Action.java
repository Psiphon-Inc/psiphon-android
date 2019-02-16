package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionState;
import com.psiphon3.psicash.mvibase.MviAction;

import ca.psiphon.psicashlib.PsiCashLib;

public interface Action extends MviAction {
    @AutoValue
    abstract class GetPsiCash implements Action {
        public static GetPsiCash create(TunnelConnectionState status) {
            return new AutoValue_Action_GetPsiCash(status);
        }

        abstract TunnelConnectionState connectionState();
    }

    @AutoValue
    abstract class MakeExpiringPurchase implements Action {
        public static MakeExpiringPurchase create(TunnelConnectionState status, @Nullable PsiCashLib.PurchasePrice price) {
            return new AutoValue_Action_MakeExpiringPurchase(status, price);
        }

        abstract TunnelConnectionState connectionState();

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
