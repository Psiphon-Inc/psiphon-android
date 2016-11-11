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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;

public class PaymentChooserActivity extends Activity {
    public static final String SKU_INFO_EXTRA = "SKU_INFO";
    public static final String BUY_TYPE_EXTRA = "BUY_TYPE";
    public static final int BUY_SUBSCRIPTION = 1;
    public static final int BUY_TIMEPASS = 2;

    SkuInfo mSkuInfo;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.payment_chooser);

        Intent intent = getIntent();
        String jsonString = intent.getStringExtra(SKU_INFO_EXTRA);

        mSkuInfo = new SkuInfo(jsonString);

        // Set up the buttons, including adding a tag with the product SKU and setting the
        // price-per-day value.

        setUpButton(R.id.limitedSubscription, mSkuInfo.mLimitedSubscriptionInfo);
        setUpButton(R.id.unlimitedSubscription, mSkuInfo.mUnlimitedSubscriptionInfo);

        for (SkuInfo.Info info : mSkuInfo.mTimePassSkuToInfo.values()) {
            long lifetimeInDays = info.lifetime / 24 / 60 / 60 / 1000;
            int id = getResources().getIdentifier("timepass"+lifetimeInDays, "id", getPackageName());

            setUpButton(id, info);
        }
    }

    /**
     * Sets up the payment chooser buttons. This includes adding a tag with the product SKU and
     * putting the price-per-day value into the label.
     * @param buttonId ID of the button to set up.
     * @param skuInfo Info about the SKU.
     */
    private void setUpButton(int buttonId, SkuInfo.Info skuInfo) {
        skuInfo.button = (Button)findViewById(buttonId);

        long lifetimeInDays = skuInfo.lifetime / 24 / 60 / 60 / 1000;
        skuInfo.button.setTag(skuInfo.sku);

        float pricePerDay = skuInfo.priceMicros / 1000000.0f / lifetimeInDays;

        // If the formatting for pricePerDayText fails below, use this as a default.
        String pricePerDayText = skuInfo.priceCurrency + " " + pricePerDay;

        try {
            Currency currency = Currency.getInstance(skuInfo.priceCurrency);
            NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
            priceFormatter.setCurrency(currency);
            pricePerDayText = priceFormatter.format(pricePerDay);
        } catch (IllegalArgumentException e) {
            // do nothing
        }

        String formatString = skuInfo.button.getText().toString();
        String buttonText = String.format(formatString, skuInfo.price, pricePerDayText);
        skuInfo.button.setText(buttonText);
    }

    public void onLimitedSubscriptionButtonClick(View v) {
        Utils.MyLog.g("PaymentChooserActivity::onLimitedSubscriptionButtonClick");
        Intent intent = getIntent();
        intent.putExtra(BUY_TYPE_EXTRA, BUY_SUBSCRIPTION);
        intent.putExtra(SKU_INFO_EXTRA, mSkuInfo.mLimitedSubscriptionInfo.sku);
        setResult(RESULT_OK, intent);
        finish();
    }

    public void onUnlimitedSubscriptionButtonClick(View v) {
        Utils.MyLog.g("PaymentChooserActivity::onUnlimitedSubscriptionButtonClick");
        Intent intent = getIntent();
        intent.putExtra(BUY_TYPE_EXTRA, BUY_SUBSCRIPTION);
        intent.putExtra(SKU_INFO_EXTRA, mSkuInfo.mUnlimitedSubscriptionInfo.sku);
        setResult(RESULT_OK, intent);
        finish();
    }

    public void onTimePassButtonClick(View v) {
        Utils.MyLog.g("PaymentChooserActivity::onTimePassButtonClick");

        // One of the time-pass buttons was clicked, but we don't know which. Figure it out from the
        // tag attribute, which is set to the SKU.

        String sku = (String)v.getTag();

        assert(sku != null);

        Intent intent = getIntent();
        intent.putExtra(BUY_TYPE_EXTRA, BUY_TIMEPASS);
        intent.putExtra(SKU_INFO_EXTRA, sku);
        setResult(RESULT_OK, intent);
        finish();
    }

    static public class SkuInfo {
        static public class Info {
            public String sku;
            public long lifetime;
            public String price;
            public long priceMicros;
            public String priceCurrency;
            public Button button;
        }

        Info mLimitedSubscriptionInfo = new Info();
        Info mUnlimitedSubscriptionInfo = new Info();
        Map<String, Info> mTimePassSkuToInfo = new HashMap<>();

        public SkuInfo() { }

        public SkuInfo(String jsonString) {
            try {
                JSONObject json = new JSONObject(jsonString);

                JSONObject limitedSubscriptionInfo = json.getJSONObject("limitedSubscriptionInfo");
                mLimitedSubscriptionInfo.sku = limitedSubscriptionInfo.getString("sku");
                mLimitedSubscriptionInfo.lifetime = limitedSubscriptionInfo.getLong("lifetime");
                mLimitedSubscriptionInfo.price = limitedSubscriptionInfo.getString("price");
                mLimitedSubscriptionInfo.priceMicros = limitedSubscriptionInfo.getLong("priceMicros");
                mLimitedSubscriptionInfo.priceCurrency = limitedSubscriptionInfo.getString("priceCurrency");

                JSONObject unlimitedSubscriptionInfo = json.getJSONObject("unlimitedSubscriptionInfo");
                mUnlimitedSubscriptionInfo.sku = unlimitedSubscriptionInfo.getString("sku");
                mUnlimitedSubscriptionInfo.lifetime = unlimitedSubscriptionInfo.getLong("lifetime");
                mUnlimitedSubscriptionInfo.price = unlimitedSubscriptionInfo.getString("price");
                mUnlimitedSubscriptionInfo.priceMicros = unlimitedSubscriptionInfo.getLong("priceMicros");
                mUnlimitedSubscriptionInfo.priceCurrency = unlimitedSubscriptionInfo.getString("priceCurrency");

                JSONArray timepassInfo = json.getJSONArray("timepassInfo");
                for (int i = 0; i < timepassInfo.length(); i++) {
                    JSONObject infoJson = timepassInfo.getJSONObject(i);
                    Info info = new Info();
                    info.sku = infoJson.getString("sku");
                    info.lifetime = infoJson.getLong("lifetime");
                    info.price = infoJson.getString("price");
                    info.priceMicros = infoJson.getLong("priceMicros");
                    info.priceCurrency = infoJson.getString("priceCurrency");

                    mTimePassSkuToInfo.put(info.sku, info);
                }
            }
            catch (JSONException e) {
                e.printStackTrace();
            }
        }

        public String toString() {
            JSONObject json = new JSONObject();

            try {
                JSONObject jsonLimitedSubscriptionObj = new JSONObject();
                jsonLimitedSubscriptionObj.put("sku", mLimitedSubscriptionInfo.sku);
                jsonLimitedSubscriptionObj.put("lifetime", mLimitedSubscriptionInfo.lifetime);
                jsonLimitedSubscriptionObj.put("price", mLimitedSubscriptionInfo.price);
                jsonLimitedSubscriptionObj.put("priceMicros", mLimitedSubscriptionInfo.priceMicros);
                jsonLimitedSubscriptionObj.put("priceCurrency", mLimitedSubscriptionInfo.priceCurrency);

                JSONObject jsonUnlimitedSubscriptionObj = new JSONObject();
                jsonUnlimitedSubscriptionObj.put("sku", mUnlimitedSubscriptionInfo.sku);
                jsonUnlimitedSubscriptionObj.put("lifetime", mUnlimitedSubscriptionInfo.lifetime);
                jsonUnlimitedSubscriptionObj.put("price", mUnlimitedSubscriptionInfo.price);
                jsonUnlimitedSubscriptionObj.put("priceMicros", mUnlimitedSubscriptionInfo.priceMicros);
                jsonUnlimitedSubscriptionObj.put("priceCurrency", mUnlimitedSubscriptionInfo.priceCurrency);

                JSONArray timepassInfo = new JSONArray();
                for (Info info : mTimePassSkuToInfo.values()) {
                    JSONObject jsonTimePassObj = new JSONObject();

                    jsonTimePassObj.put("sku", info.sku);
                    jsonTimePassObj.put("lifetime", info.lifetime);
                    jsonTimePassObj.put("price", info.price);
                    jsonTimePassObj.put("priceMicros", info.priceMicros);
                    jsonTimePassObj.put("priceCurrency", info.priceCurrency);

                    timepassInfo.put(jsonTimePassObj);
                }

                json.put("limitedSubscriptionInfo", jsonLimitedSubscriptionObj);
                json.put("unlimitedSubscriptionInfo", jsonUnlimitedSubscriptionObj);
                json.put("timepassInfo", timepassInfo);
            }
            catch (JSONException e) {
                e.printStackTrace();
            }

            return json.toString();
        }
    }
}
