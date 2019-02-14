package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.psicash.mvibase.MviIntent;

import ca.psiphon.psicashlib.PsiCashLib;


public interface Intent extends MviIntent {
    @AutoValue
    abstract class PurchaseSpeedBoost implements Intent {
        public static PurchaseSpeedBoost create(TunnelConnectionStatus status, @Nullable PsiCashLib.PurchasePrice price) {
            return new AutoValue_Intent_PurchaseSpeedBoost(status, price);
        }

        abstract TunnelConnectionStatus connectionStatus();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();
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
    abstract class ConnectionStatus implements Intent {
        public static ConnectionStatus create(TunnelConnectionStatus status) {
            return new AutoValue_Intent_ConnectionStatus(status);
        }

        public abstract TunnelConnectionStatus connectionStatus();
    }

}
