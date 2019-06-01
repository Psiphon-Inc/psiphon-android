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

import android.app.Activity;
import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.NonNull;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.SkuDetails;

import java.util.List;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class BillingViewModel extends AndroidViewModel {
    private BillingRepository repository;
    private CompositeDisposable compositeDisposable;

    public BillingViewModel(@NonNull Application application) {
        super(application);
        repository = BillingRepository.getInstance(application);
        compositeDisposable = new CompositeDisposable();
    }

    public void startIab() {
        compositeDisposable.add(repository.observeUpdates().subscribe());
    }

    public void stopIab() {
        compositeDisposable.dispose();
    }

    public Single<List<SkuDetails>> getPurchaseSkuDetails(List<String> ids) {
        return repository.getSkuDetails(ids, BillingClient.SkuType.INAPP);
    }

    public Single<List<SkuDetails>> getSubscriptionSkuDetails(List<String> ids) {
        return repository.getSkuDetails(ids, BillingClient.SkuType.SUBS);
    }

    public Flowable<PurchasesUpdate> observeUpdates() {
        return repository.observeUpdates();
    }

    public Completable launchFlow(Activity activity, SkuDetails skuDetails) {
        return repository.launchFlow(activity, skuDetails);
    }
}
