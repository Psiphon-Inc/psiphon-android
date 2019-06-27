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

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.SkuDetails;

import java.util.Arrays;
import java.util.List;

import io.reactivex.Single;

public class PaymentChooserBillingViewModel extends AndroidViewModel {
    private BillingRepository repository;

    public PaymentChooserBillingViewModel(@NonNull Application application) {
        super(application);
        repository = BillingRepository.getInstance(application);
    }

    private Single<List<SkuDetails>> getTimePassesSkuDetails() {
        List<String> ids = Arrays.asList(
                BillingRepository.IAB_BASIC_7DAY_TIMEPASS_SKU,
                BillingRepository.IAB_BASIC_30DAY_TIMEPASS_SKU,
                BillingRepository.IAB_BASIC_360DAY_TIMEPASS_SKU
        );
        return repository.getSkuDetails(ids, BillingClient.SkuType.INAPP);
    }

    private Single<List<SkuDetails>> getSubscriptionsSkuDetails() {
        List<String> ids = Arrays.asList(
                BillingRepository.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU,
                BillingRepository.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU
        );
        return repository.getSkuDetails(ids, BillingClient.SkuType.SUBS);
    }

    public Single<List<SkuDetails>> getAllSkuDetails() {
        return Single.mergeDelayError(getSubscriptionsSkuDetails(), getTimePassesSkuDetails())
                .flatMapIterable(skuDetails -> skuDetails)
                .toList();
    }
}
