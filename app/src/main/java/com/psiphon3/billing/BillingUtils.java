/*
 * Copyright (c) 2024, Psiphon Inc.
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

import com.android.billingclient.api.BillingClient.BillingResponseCode;

import java.util.concurrent.TimeUnit;

import io.reactivex.Flowable;

public class BillingUtils {
    // Extract the human-readable message from a billing response code for logging
    public static String getBillingResponseMessage(@BillingResponseCode int responseCode) {
        switch (responseCode) {
            case BillingResponseCode.SERVICE_TIMEOUT:
                return "Service timeout.";
            case BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "Feature not supported.";
            case BillingResponseCode.SERVICE_DISCONNECTED:
                return "Service disconnected.";
            case BillingResponseCode.OK:
                return "Operation succeeded.";
            case BillingResponseCode.USER_CANCELED:
                return "User canceled the operation.";
            case BillingResponseCode.SERVICE_UNAVAILABLE:
                return "Service unavailable.";
            case BillingResponseCode.BILLING_UNAVAILABLE:
                return "Billing unavailable.";
            case BillingResponseCode.ITEM_UNAVAILABLE:
                return "Item unavailable.";
            case BillingResponseCode.DEVELOPER_ERROR:
                return "Developer error.";
            case BillingResponseCode.ERROR:
                return "Generic error.";
            case BillingResponseCode.ITEM_ALREADY_OWNED:
                return "Item already owned.";
            case BillingResponseCode.ITEM_NOT_OWNED:
                return "Item not owned.";
            case BillingResponseCode.NETWORK_ERROR:
                return "Network error.";
            default:
                return "Unknown error code: " + responseCode;
        }
    }

    // Retry the source Flowable on transient errors up to maxRetries times
    // with a delay of attemptNumber * 500ms between retries
    public static <T> Flowable<T> withRetry(Flowable<T> source, int maxRetries) {
        return source.retryWhen(errors ->
                errors.zipWith(
                        Flowable.range(1, maxRetries + 1),
                        (error, attempt) -> {
                            if (isTransientError(error) && attempt <= maxRetries) {
                                return attempt; // continue retrying
                            } else {
                                throw (error instanceof Exception)
                                        ? (Exception) error // Propagate non-transient errors
                                        : new RuntimeException("Non-transient error or max retries reached", error);
                            }
                        }
                ).flatMap(attempt -> Flowable.timer(attempt * 500L, TimeUnit.MILLISECONDS))
        );
    }

    // Check if the error is a transient billing error
    // see: https://developer.android.com/reference/com/android/billingclient/api/BillingClient.BillingResponseCode
    // for details on the response codes that should be retried automatically
    private static boolean isTransientError(Throwable error) {
        if (error instanceof GooglePlayBillingHelper.BillingException) {
            int responseCode = ((GooglePlayBillingHelper.BillingException) error).getBillingResultResponseCode();
            return responseCode == BillingResponseCode.SERVICE_DISCONNECTED
                    || responseCode == BillingResponseCode.SERVICE_UNAVAILABLE
                    || responseCode == BillingResponseCode.ERROR;
        }
        return false;
    }
}
