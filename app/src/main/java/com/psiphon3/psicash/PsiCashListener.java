package com.psiphon3.psicash;

import android.content.Context;

import ca.psiphon.psicashlib.PsiCashLib;

public interface PsiCashListener {
    void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase);
    void onNewReward(Context context, long reward);
}
