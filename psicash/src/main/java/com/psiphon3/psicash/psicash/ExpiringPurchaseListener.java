package com.psiphon3.psicash.psicash;

import android.content.Context;

import ca.psiphon.psicashlib.PsiCashLib;

public interface ExpiringPurchaseListener {
    void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase);
}
