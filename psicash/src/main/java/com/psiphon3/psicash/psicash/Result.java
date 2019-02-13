package com.psiphon3.psicash.psicash;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.LceStatus;

public interface Result {
    @AutoValue
    abstract class GetPsiCash implements Result {
        @NonNull
        static GetPsiCash success(PsiCashModel.PsiCash model) {
            return new AutoValue_Result_GetPsiCash(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static GetPsiCash failure(Throwable error) {
            return new AutoValue_Result_GetPsiCash(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static GetPsiCash inFlight() {
            return new AutoValue_Result_GetPsiCash(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel.PsiCash model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class ExpiringPurchase implements Result {
        @NonNull
        static ExpiringPurchase success(PsiCashModel.ExpiringPurchase model) {
            return new AutoValue_Result_ExpiringPurchase(LceStatus.SUCCESS, model, null);
        }

        @NonNull
        static ExpiringPurchase failure(Throwable error) {
            return new AutoValue_Result_ExpiringPurchase(LceStatus.FAILURE, null, error);
        }

        @NonNull
        static ExpiringPurchase inFlight() {
            return new AutoValue_Result_ExpiringPurchase(LceStatus.IN_FLIGHT, null, null);
        }

        @NonNull
        abstract LceStatus status();

        @Nullable
        abstract PsiCashModel.ExpiringPurchase model();

        @Nullable
        abstract Throwable error();
    }

    @AutoValue
    abstract class ClearErrorState implements Result {
        @NonNull
        static ClearErrorState success() {
            return new AutoValue_Result_ClearErrorState();
        }
    }
}
