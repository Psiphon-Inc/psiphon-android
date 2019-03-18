package com.psiphon3.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.mvibase.MviAction;
import com.psiphon3.TunnelState;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

public interface PsiCashAction extends MviAction {
    @AutoValue
    abstract class GetPsiCashRemote implements PsiCashAction {
        public static GetPsiCashRemote create(TunnelState status) {
            return new AutoValue_PsiCashAction_GetPsiCashRemote(status);
        }

        abstract TunnelState connectionState();
    }

    @AutoValue
    abstract class MakeExpiringPurchase implements PsiCashAction {
        public static MakeExpiringPurchase create(TunnelState status, @Nullable PsiCashLib.PurchasePrice price, boolean hasActiveBoost) {
            return new AutoValue_PsiCashAction_MakeExpiringPurchase(status, price, hasActiveBoost);
        }

        abstract TunnelState connectionState();

        @Nullable
        abstract PsiCashLib.PurchasePrice purchasePrice();

        abstract boolean hasActiveBoost();
    }

    @AutoValue
    abstract class ClearErrorState implements PsiCashAction {
        public static ClearErrorState create() {
            return new AutoValue_PsiCashAction_ClearErrorState();
        }
    }

    @AutoValue
    abstract class GetPsiCashLocal implements PsiCashAction {
        public static GetPsiCashLocal create() {
            return new AutoValue_PsiCashAction_GetPsiCashLocal();
        }
    }

    @AutoValue
    abstract class RemovePurchases implements PsiCashAction {
        public static RemovePurchases create(List<String> purchases) {
            return new AutoValue_PsiCashAction_RemovePurchases(purchases);
        }

        public abstract List<String> purchases();
    }

    @AutoValue
    abstract class LoadVideoAd implements PsiCashAction {
        public static LoadVideoAd create(TunnelState status) {
            return new AutoValue_PsiCashAction_LoadVideoAd(status);
        }

        abstract TunnelState connectionState();
    }

}
