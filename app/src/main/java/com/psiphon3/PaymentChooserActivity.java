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

import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    }

    public void onSubscriptionButtonClick(View v)
    {
        Utils.MyLog.g("PaymentChooserActivity::onSubscriptionButtonClick");
        Intent intent = getIntent();
        intent.putExtra(BUY_TYPE_EXTRA, BUY_SUBSCRIPTION);
        setResult(RESULT_OK, intent);
        finish();
    }

    public void onTimePassButtonClick(View v)
    {
        Utils.MyLog.g("PaymentChooserActivity::onTimePassButtonClick");
        Intent intent = getIntent();
        intent.putExtra(BUY_TYPE_EXTRA, BUY_TIMEPASS);
        setResult(RESULT_OK, intent);
        finish();
    }

    static public class SkuInfo {
        static public class Info {
            public String sku;
            public long lifetime;
            public String price;
            public long priceMicros;
        }

        Info mSubscriptionInfo = new Info();
        Map<String, Info> mTimePassSkuToInfo = new HashMap<>();

        public SkuInfo() { }

        public SkuInfo(String jsonString) {
            try {
                JSONObject json = new JSONObject(jsonString);

                JSONObject subscriptionInfo = json.getJSONObject("subscriptionInfo");
                mSubscriptionInfo.sku = subscriptionInfo.getString("sku");
                mSubscriptionInfo.lifetime = subscriptionInfo.getLong("lifetime");
                mSubscriptionInfo.price = subscriptionInfo.getString("price");
                mSubscriptionInfo.priceMicros = subscriptionInfo.getLong("priceMicros");

                JSONArray timepassInfo = json.getJSONArray("timepassInfo");
                for (int i = 0; i < timepassInfo.length(); i++) {
                    JSONObject infoJson = timepassInfo.getJSONObject(i);
                    Info info = new Info();
                    info.sku = infoJson.getString("sku");
                    info.lifetime = infoJson.getLong("lifetime");
                    info.price = infoJson.getString("price");
                    info.priceMicros = infoJson.getLong("priceMicros");

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
                JSONObject jsonSubscriptionObj = new JSONObject();
                jsonSubscriptionObj.put("sku", mSubscriptionInfo.sku);
                jsonSubscriptionObj.put("lifetime", mSubscriptionInfo.lifetime);
                jsonSubscriptionObj.put("price", mSubscriptionInfo.price);
                jsonSubscriptionObj.put("priceMicros", mSubscriptionInfo.priceMicros);

                JSONArray timepassInfo = new JSONArray();
                for (Info info : mTimePassSkuToInfo.values()) {
                    JSONObject jsonTimePassObj = new JSONObject();

                    jsonTimePassObj.put("sku", info.sku);
                    jsonTimePassObj.put("lifetime", info.lifetime);
                    jsonTimePassObj.put("price", info.price);
                    jsonTimePassObj.put("priceMicros", info.priceMicros);

                    timepassInfo.put(jsonTimePassObj);
                }

                json.put("subscriptionInfo", jsonSubscriptionObj);
                json.put("timepassInfo", timepassInfo);
            }
            catch (JSONException e) {
                e.printStackTrace();
            }

            return json.toString();
        }
    }
}
