/*
 * Copyright (c) 2016, Psiphon Inc.
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

package com.psiphon3;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import org.json.JSONException;
import org.threeten.bp.Period;
import org.threeten.bp.format.DateTimeParseException;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;

public class PaymentChooserActivity extends LocalizedActivities.AppCompatActivity {
    public static final String USER_PICKED_SKU_DETAILS_EXTRA = "USER_PICKED_SKU_DETAILS_EXTRA";
    public static final String SKU_DETAILS_ARRAY_LIST_EXTRA = "SKU_DETAILS_ARRAY_LIST_EXTRA";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.payment_chooser);

        ArrayList<String> jsonSkuDetailsList =
                getIntent().getStringArrayListExtra(SKU_DETAILS_ARRAY_LIST_EXTRA);

        for (String jsonSkuDetails : jsonSkuDetailsList) {
            SkuDetails skuDetails;
            try {
                skuDetails = new SkuDetails(jsonSkuDetails);
            } catch (JSONException e) {
                Utils.MyLog.g("PaymentChooserActivity: error parsing SkuDetails: " + e);
                continue;
            }

            int buttonResId = 0;
            float pricePerDay = 0f;

            // Calculate life time in days for subscriptions
            // Note: we are not using Period.parse() because there are only 5 possible predefined periods
            String subscriptionPeriod = skuDetails.getSubscriptionPeriod();
            if(TextUtils.isEmpty(subscriptionPeriod)) {
                // Our  subscriptions are all 1 month, set as default in case the user has a very old
                // Google Play Services version which doesn't return subscription period value.
                subscriptionPeriod = "P1M";
            }

            if (skuDetails.getType().equals(BillingClient.SkuType.SUBS)) {
                if (subscriptionPeriod.equals("P1W")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / 7f;
                } else if (subscriptionPeriod.equals("P1M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 12);
                } else if (subscriptionPeriod.equals("P3M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 4);
                } else if (subscriptionPeriod.equals("P6M")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / (365f / 2);
                } else if (subscriptionPeriod.equals("P1Y")) {
                    pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / 365f;
                }
                if (pricePerDay == 0f) {
                    Utils.MyLog.g("PaymentChooserActivity error: bad subscription period for sku: " + skuDetails);
                    continue;
                }

                // Get button resource ID
                if (skuDetails.getSku().equals(GooglePlayBillingHelper.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                    buttonResId = R.id.limitedSubscription;
                } else if (skuDetails.getSku().equals(GooglePlayBillingHelper.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                    buttonResId = R.id.unlimitedSubscription;
                }
            } else {
                String timepassSku = skuDetails.getSku();
                // Get pre-calculated life time in days for time passes
                Long lifetimeInDays = GooglePlayBillingHelper.IAB_TIMEPASS_SKUS_TO_DAYS.get(timepassSku);
                if (lifetimeInDays == null || lifetimeInDays == 0L) {
                    Utils.MyLog.g("PaymentChooserActivity error: unknown timepass period for sku: " + skuDetails);
                    continue;
                }
                // Get button resource ID
                buttonResId = getResources().getIdentifier("timepass" + lifetimeInDays, "id", getPackageName());
                pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / lifetimeInDays;
            }

            if (buttonResId == 0) {
                Utils.MyLog.g("PaymentChooserActivity error: no button resource for sku: " + skuDetails);
                continue;
            }

            setUpButton(buttonResId, skuDetails, pricePerDay);
        }
    }

    /**
     * Sets up the payment chooser buttons. This includes putting the price-per-day value into the label.
     *
     * @param buttonId   ID of the button to set up.
     * @param skuDetails Info about the SKU.
     * @param pricePerDay Price per day value.
     */
    private void setUpButton(int buttonId, SkuDetails skuDetails, float pricePerDay) {
        Button button = findViewById(buttonId);

        // skuDetails.getPrice() returns a differently looking string than the one we get by using priceFormatter
        // below, so for consistency we'll use priceFormatter on the subscription price amount too.
        // If the formatting for pricePerDayText or priceText fails, use these as default
        String pricePerDayText = String.format("%s %.2f", skuDetails.getPriceCurrencyCode(), pricePerDay);
        String priceText = skuDetails.getPrice();

        try {
            Currency currency = Currency.getInstance(skuDetails.getPriceCurrencyCode());
            NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
            priceFormatter.setCurrency(currency);
            pricePerDayText = priceFormatter.format(pricePerDay);
            priceText = priceFormatter.format(skuDetails.getPriceAmountMicros() / 1000000.0f);
        } catch (IllegalArgumentException e) {
            // do nothing
        }
        String formatString = button.getText().toString();
        String buttonTextHtml = String.format(formatString, priceText, pricePerDayText).replace("\n", "<br>");
        String freeTrialPeriodISO8061 = skuDetails.getFreeTrialPeriod();
        if(!TextUtils.isEmpty(freeTrialPeriodISO8061) &&
                // Make sure we are compatible with the minSdk 15 of com.jakewharton.threetenabp
                Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            try {
                Period period = Period.parse(freeTrialPeriodISO8061);
                long freeTrialPeriodInDays = period.getDays();
                if (freeTrialPeriodInDays > 0L) {
                    String freeTrialPeriodText = String.format(getString(R.string.PaymentChooserFragment_FreeTrialPeriod), freeTrialPeriodInDays);
                    buttonTextHtml = String.format("%s<br><sub><small>%s</small></sub>", buttonTextHtml, freeTrialPeriodText);
                }
            } catch (DateTimeParseException e) {
                Utils.MyLog.g("PaymentChooserActivity failed to parse free trial period: " + freeTrialPeriodISO8061);
            }
        }

        button.setText(Html.fromHtml(buttonTextHtml));
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> {
            Utils.MyLog.g("PaymentChooserActivity purchase button clicked.");
            Intent intent = getIntent();
            intent.putExtra(USER_PICKED_SKU_DETAILS_EXTRA, skuDetails.getOriginalJson());
            setResult(RESULT_OK, intent);
            finish();
        });
    }
}