package com.psiphon3.billing;

import com.android.billingclient.api.Purchase;
import com.google.auto.value.AutoValue;

import java.util.Collections;
import java.util.List;

import io.reactivex.annotations.NonNull;
import io.reactivex.annotations.Nullable;

@AutoValue
public abstract class PurchaseState {
    @NonNull
    public abstract List<Purchase> purchaseList();

    @Nullable
    Throwable error;

    @Nullable
    public final Throwable error() {
        return error;
    }

    static PurchaseState create(List<Purchase> purchaseList) {
        return new AutoValue_PurchaseState(purchaseList);
    }

    static PurchaseState empty() {
        return new AutoValue_PurchaseState(Collections.emptyList());
    }

    static PurchaseState error(Throwable error) {
        PurchaseState purchaseState = new AutoValue_PurchaseState(Collections.emptyList());
        purchaseState.error = error;
        return purchaseState;
    }
}


