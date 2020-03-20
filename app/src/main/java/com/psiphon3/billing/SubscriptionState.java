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

import com.android.billingclient.api.Purchase;
import com.google.auto.value.AutoValue;

import io.reactivex.annotations.Nullable;

@AutoValue
public abstract class SubscriptionState {
    public enum Status {
        HAS_UNLIMITED_SUBSCRIPTION,
        HAS_LIMITED_SUBSCRIPTION,
        HAS_TIME_PASS,
        HAS_NO_SUBSCRIPTION,
        IAB_FAILURE,
        NOT_APPLICABLE,
    }

    public abstract Status status();

    @Nullable
    public abstract Purchase purchase();

    @Nullable
    Throwable error;

    @Nullable
    public final Throwable error() {
        return error;
    }

    static SubscriptionState unlimitedSubscription(Purchase purchase) {
        return new AutoValue_SubscriptionState(Status.HAS_UNLIMITED_SUBSCRIPTION, purchase);
    }

    static SubscriptionState limitedSubscription(Purchase purchase) {
        return new AutoValue_SubscriptionState(Status.HAS_LIMITED_SUBSCRIPTION, purchase);
    }

    static SubscriptionState timePass(Purchase purchase) {
        return new AutoValue_SubscriptionState(Status.HAS_TIME_PASS, purchase);
    }

    static SubscriptionState noSubscription() {
        return new AutoValue_SubscriptionState(Status.HAS_NO_SUBSCRIPTION, null);
    }

    public static SubscriptionState notApplicable() {
        return new AutoValue_SubscriptionState(Status.NOT_APPLICABLE, null);
    }

    public static SubscriptionState billingError(Throwable error) {
        SubscriptionState state = new AutoValue_SubscriptionState(Status.IAB_FAILURE, null);
        state.error = error;
        return state;
    }

    public boolean hasValidPurchase() {
        return status() == Status.HAS_LIMITED_SUBSCRIPTION ||
                status() == Status.HAS_UNLIMITED_SUBSCRIPTION ||
                status() == Status.HAS_TIME_PASS;
    }
}
