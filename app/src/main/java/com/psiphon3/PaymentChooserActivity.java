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

import com.psiphon3.subscription.R;

public class PaymentChooserActivity extends Activity {
    public static final String BUY_TYPE_EXTRA = "BUY_TYPE";
    public static final int BUY_SUBSCRIPTION = 1;
    public static final int BUY_TIMEPASS = 2;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.payment_chooser);

        // Watch for button clicks.
        Button buySubscriptionButton = (Button)findViewById(R.id.buy_subscription);
        buySubscriptionButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // When button is clicked, call up to owning activity.
                Intent intent = getIntent();
                intent.putExtra(BUY_TYPE_EXTRA, BUY_SUBSCRIPTION);
                setResult(RESULT_OK, intent);
                finish();
            }
        });

        Button buyTimePassButton = (Button)findViewById(R.id.buy_timepass);
        buyTimePassButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // When button is clicked, call up to owning activity.
                Intent intent = getIntent();
                intent.putExtra(BUY_TYPE_EXTRA, BUY_TIMEPASS);
                setResult(RESULT_OK, intent);
                finish();
            }
        });
    }
}
