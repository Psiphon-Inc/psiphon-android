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

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class SubscriptionState {
    public enum Status {
        HAS_UNLIMITED_SUBSCRIPTION,
        HAS_LIMITED_SUBSCRIPTION,
        HAS_TIME_PASS,
        HAS_NO_SUBSCRIPTION,
        NOT_APPLICABLE,
    }

    public abstract Status status();

    public abstract Throwable error();

    public static SubscriptionState unlimitedSubscription() {
        return new AutoValue_SubscriptionState(Status.HAS_UNLIMITED_SUBSCRIPTION, null);
    }

    public static SubscriptionState limitedSubscription() {
        return new AutoValue_SubscriptionState(Status.HAS_LIMITED_SUBSCRIPTION, null);
    }

    public static SubscriptionState timePass() {
        return new AutoValue_SubscriptionState(Status.HAS_TIME_PASS, null);
    }

    public static SubscriptionState noSubscription() {
        return new AutoValue_SubscriptionState(Status.HAS_NO_SUBSCRIPTION, null);
    }

    public static SubscriptionState notApplicable() {
        return new AutoValue_SubscriptionState(Status.NOT_APPLICABLE, null);
    }

    public static SubscriptionState billingError(Throwable error) {
        return new AutoValue_SubscriptionState(null, error);
    }

    public boolean hasSubsription() {
        return status() == Status.HAS_LIMITED_SUBSCRIPTION ||
                status() == Status.HAS_UNLIMITED_SUBSCRIPTION ||
                status() == Status.HAS_TIME_PASS;
    }
}
