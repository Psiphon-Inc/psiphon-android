/*
 *
 * Copyright (c) 2019, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.billing;

import android.support.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.google.auto.value.AutoValue;

import java.util.List;

@AutoValue
public abstract class PurchasesUpdate {
    abstract @BillingClient.BillingResponseCode
    int responseCode();

    @Nullable
    abstract List<Purchase> purchases();

    public static PurchasesUpdate create(@BillingClient.BillingResponseCode int responseCode, List<Purchase> purchases) {
        return new AutoValue_PurchasesUpdate(responseCode, purchases);
    }

    boolean isSuccess() {
        return responseCode() == BillingClient.BillingResponseCode.OK;
    }
}
