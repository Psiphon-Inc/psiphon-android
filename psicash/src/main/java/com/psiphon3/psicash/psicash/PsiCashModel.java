package com.psiphon3.psicash.psicash;

import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;

import java.util.List;

import ca.psiphon.psicashlib.PsiCashLib;

public interface PsiCashModel {
    @AutoValue
    abstract class PsiCash implements PsiCashModel {
        static Builder builder() {
            return new AutoValue_PsiCashModel_PsiCash.Builder();
        }

        public abstract long balance();

        public abstract long reward();

        public abstract String diagnosticInfo();

        @Nullable
        public abstract List<PsiCashLib.PurchasePrice> purchasePrices();

        @Nullable
        public abstract PsiCashLib.Purchase nextExpiringPurchase();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder balance(long balance);

            abstract Builder reward(long reward);

            abstract Builder diagnosticInfo(String info);

            abstract Builder purchasePrices(@Nullable List<PsiCashLib.PurchasePrice> purchasePrices);

            abstract Builder nextExpiringPurchase(@Nullable PsiCashLib.Purchase purchase);

            abstract PsiCash build();
        }
    }

    @AutoValue
    abstract class ExpiringPurchase implements PsiCashModel {

        static Builder builder() {
            return new AutoValue_PsiCashModel_ExpiringPurchase.Builder();
        }

        public abstract PsiCashLib.Purchase expiringPurchase();

        @AutoValue.Builder
        abstract static class Builder {
            abstract Builder expiringPurchase(@Nullable PsiCashLib.Purchase purchase);

            abstract ExpiringPurchase build();
        }
    }
}