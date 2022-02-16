/*
 * Copyright (c) 2020, Psiphon Inc.
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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.google.android.material.tabs.TabLayout;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.R;

import org.threeten.bp.Period;
import org.threeten.bp.format.DateTimeParseException;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;

public class PaymentChooserActivity extends LocalizedActivities.AppCompatActivity {
    public static final String USER_PICKED_SKU_DETAILS_EXTRA = "USER_PICKED_SKU_DETAILS_EXTRA";
    public static final String USER_OLD_SKU_EXTRA = "USER_OLD_SKU_EXTRA";
    public static final String USER_OLD_PURCHASE_TOKEN_EXTRA = "USER_OLD_PURCHASE_TOKEN_EXTRA";
    private GooglePlayBillingHelper googlePlayBillingHelper;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        setContentView(R.layout.payment_chooser);

        googlePlayBillingHelper.subscriptionStateFlowable()
                .firstOrError()
                .doOnSuccess(subscriptionState -> {
                    findViewById(R.id.progress_overlay).setVisibility(View.GONE);
                    switch (subscriptionState.status()) {
                        case IAB_FAILURE:
                            // Finishing the activity with RESULT_OK and empty SKU will result
                            // in "Subscriptions options are not available" toast.
                            // The error will be logged elsewhere
                            finishActivity(RESULT_OK);
                            return;

                        case HAS_UNLIMITED_SUBSCRIPTION:
                        case HAS_TIME_PASS:
                            // User has an unlimited subscription, finish the activity silently.
                            finishActivity(RESULT_CANCELED);
                            return;

                        case HAS_LIMITED_SUBSCRIPTION:
                            // Update the "You are using...plan" text
                            ((TextView) findViewById(R.id.payment_chooser_current_plan)).setText(R.string.PaymentChooserActivity_UsingLimitedPlan);
                            // If user has a limited subscription then do not display the tabbed layout with both
                            // subscriptions and time passes. Display just the subscriptions instead by replacing
                            // the tabs container with an instance of SubscriptionFragment
                            ((ViewGroup) findViewById(R.id.payment_chooser_tabs_wrapper)).removeAllViews();
                            SubscriptionFragment subscriptionFragment = new SubscriptionFragment();
                            // Make sure the SubscriptionFragment knows to show the unlimited option only.
                            subscriptionFragment.setLimitedSubscriptionPurchase(subscriptionState.purchase());
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.payment_chooser_tabs_wrapper, subscriptionFragment)
                                    .commit();
                            return;

                        default:
                            // Update the "You are using...plan" text
                            ((TextView) findViewById(R.id.payment_chooser_current_plan)).setText(R.string.PaymentChooserActivity_UsingFreePlan);
                            // Setup the two tab pager with all purchase options
                            TabLayout tabLayout = findViewById(R.id.payment_chooser_tablayout);
                            PageAdapter pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount());
                            ViewPager viewPager = findViewById(R.id.payment_chooser_viewpager);
                            viewPager.setAdapter(pageAdapter);
                            viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
                            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                                @Override
                                public void onTabSelected(TabLayout.Tab tab) {
                                    viewPager.setCurrentItem(tab.getPosition());
                                }

                                @Override
                                public void onTabUnselected(TabLayout.Tab tab) {
                                }

                                @Override
                                public void onTabReselected(TabLayout.Tab tab) {
                                }
                            });
                            // Go to the tab specified in the opening intent extra
                            int tabIndex = getIntent().getIntExtra("tabIndex", getResources().getInteger(R.integer.subscriptionTabIndex));
                            if (tabIndex < pageAdapter.getCount()) {
                                // Switch to the tab when view pager is ready
                                viewPager.post(() -> viewPager.setCurrentItem(tabIndex));
                            }
                    }
                })
                .subscribe();
    }

    @Override
    protected void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllPurchases();
        googlePlayBillingHelper.queryAllSkuDetails();
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new SubscriptionFragment();
                case 1:
                    return new TimePassFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }

    public static class SubscriptionFragment extends Fragment {
        private Purchase limitedSubscriptionPurchase;
        private View fragmentView;
        private CompositeDisposable compositeDisposable = new CompositeDisposable();

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            fragmentView = inflater.inflate(R.layout.payment_subscriptions_tab_fragment, container, false);
            return fragmentView;
        }

        @Override
        public void onActivityCreated(@Nullable Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            final FragmentActivity activity = getActivity();
            GooglePlayBillingHelper googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(activity.getApplicationContext());
            compositeDisposable.add(googlePlayBillingHelper.allSkuDetailsSingle()
                    .toObservable()
                    .flatMap(Observable::fromIterable)
                    .filter(skuDetails -> {
                        String sku = skuDetails.getSku();
                        // If user has limited subscription only pass through unlimited subscription
                        if (limitedSubscriptionPurchase != null) {
                            return sku.equals(GooglePlayBillingHelper.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
                        }
                        // Otherwise pass all available subscriptions
                        return sku.equals(GooglePlayBillingHelper.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU) ||
                                sku.equals(GooglePlayBillingHelper.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
                    })
                    .toList()
                    .doOnSuccess(skuDetailsList -> {
                        if (skuDetailsList == null || skuDetailsList.size() == 0) {
                            MyLog.e("PaymentChooserActivity: subscription SKU error: sku details list is empty.");
                            // finish the activity and show "Subscription options not available" toast.
                            if (!activity.isFinishing()) {
                                activity.finishActivity(RESULT_OK);
                            }
                            return;
                        } // else
                        for (SkuDetails skuDetails : skuDetailsList) {
                            if (skuDetails == null) {
                                MyLog.e("PaymentChooserActivity: subscription SKU error: sku details is null.");
                                continue;
                            }

                            ViewGroup clickable;
                            TextView titlePriceTv;
                            TextView freeTrialTv;
                            TextView priceAfterFreeTrialTv;

                            if (skuDetails.getSku().equals(GooglePlayBillingHelper.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                                clickable = fragmentView.findViewById(R.id.unlimitedSubscriptionClickable);
                                titlePriceTv = fragmentView.findViewById(R.id.unlimitedSubscriptionTitlePrice);
                                freeTrialTv = fragmentView.findViewById(R.id.unlimitedSubscriptionFreeTrialPeriod);
                                priceAfterFreeTrialTv = fragmentView.findViewById(R.id.unlimitedSubscriptionPriceAfterFreeTrial);
                            } else {
                                clickable = fragmentView.findViewById(R.id.limitedSubscriptionClickable);
                                titlePriceTv = fragmentView.findViewById(R.id.limitedSubscriptionTitlePrice);
                                freeTrialTv = fragmentView.findViewById(R.id.limitedSubscriptionFreeTrialPeriod);
                                priceAfterFreeTrialTv = fragmentView.findViewById(R.id.limitedSubscriptionPriceAfterFreeTrial);
                            }
                            // skuDetails.getPrice() returns a differently looking string than the one
                            // we get by using priceFormatter below, so for consistency we'll use
                            // priceFormatter on all prices.
                            // If the formatting for priceText fails, use skuDetails.getPrice() as default
                            String priceText = skuDetails.getPrice();

                            try {
                                Currency currency = Currency.getInstance(skuDetails.getPriceCurrencyCode());
                                NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
                                priceFormatter.setCurrency(currency);
                                priceText = priceFormatter.format(skuDetails.getPriceAmountMicros() / 1000000.0f);
                            } catch (IllegalArgumentException e) {
                                // do nothing
                            }
                            String formatString = titlePriceTv.getText().toString();
                            titlePriceTv.setText(String.format(formatString, priceText));
                            formatString = priceAfterFreeTrialTv.getText().toString();
                            priceAfterFreeTrialTv.setText(String.format(formatString, priceText));

                            clickable.setVisibility(View.VISIBLE);
                            clickable.setOnClickListener(v -> {
                                if (!activity.isFinishing()) {
                                    MyLog.i("PaymentChooserActivity: subscription purchase button clicked.");
                                    Intent data = new Intent();
                                    data.putExtra(USER_PICKED_SKU_DETAILS_EXTRA, skuDetails.getOriginalJson());
                                    if (limitedSubscriptionPurchase != null) {
                                        data.putExtra(USER_OLD_SKU_EXTRA, limitedSubscriptionPurchase.getSku());
                                        data.putExtra(USER_OLD_PURCHASE_TOKEN_EXTRA, limitedSubscriptionPurchase.getPurchaseToken());
                                    }
                                    activity.setResult(RESULT_OK, data);
                                    activity.finish();
                                }
                            });

                            String freeTrialPeriodISO8061 = skuDetails.getFreeTrialPeriod();
                            if (!TextUtils.isEmpty(freeTrialPeriodISO8061) &&
                                    // Make sure we are compatible with the minSdk 15 of com.jakewharton.threetenabp
                                    Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                                try {
                                    Period period = Period.parse(freeTrialPeriodISO8061);
                                    long freeTrialPeriodInDays = period.getDays();
                                    if (freeTrialPeriodInDays > 0L) {
                                        formatString = freeTrialTv.getText().toString();
                                        String freeTrialPeriodText = String.format(formatString, freeTrialPeriodInDays);
                                        freeTrialTv.setText(freeTrialPeriodText);
                                        freeTrialTv.setVisibility(View.VISIBLE);
                                    }
                                } catch (DateTimeParseException e) {
                                    MyLog.w("PaymentChooserActivity: failed to parse free trial period: " + freeTrialPeriodISO8061);
                                }
                            }
                        }
                    })
                    .subscribe());
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            compositeDisposable.dispose();
        }

        public void setLimitedSubscriptionPurchase(Purchase purchase) {
            limitedSubscriptionPurchase = purchase;
        }
    }

    public static class TimePassFragment extends Fragment {
        private View fragmentView;
        private CompositeDisposable compositeDisposable = new CompositeDisposable();

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            fragmentView = inflater.inflate(R.layout.payment_timepasses_tab_fragment, container, false);
            return fragmentView;
        }

        @Override
        public void onActivityCreated(@Nullable Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            final FragmentActivity activity = getActivity();
            final String packageName = activity.getPackageName();
            GooglePlayBillingHelper googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(activity.getApplicationContext());
            compositeDisposable.add(googlePlayBillingHelper.allSkuDetailsSingle()
                    .toObservable()
                    .flatMap(Observable::fromIterable)
                    .filter(skuDetails -> {
                        String sku = skuDetails.getSku();
                        return GooglePlayBillingHelper.IAB_TIMEPASS_SKUS_TO_DAYS.containsKey(sku);
                    })
                    .toList()
                    .doOnSuccess(skuDetailsList -> {
                        if (skuDetailsList == null || skuDetailsList.size() == 0) {
                            MyLog.e("PaymentChooserActivity: time pass SKU error: sku details list is empty.");
                            // finish the activity and show "Subscription options not available" toast.
                            if (!activity.isFinishing()) {
                                activity.finishActivity(RESULT_OK);
                            }
                            return;
                        } // else
                        for (SkuDetails skuDetails : skuDetailsList) {
                            if (skuDetails == null) {
                                MyLog.e("PaymentChooserActivity: time pass SKU error: sku details is null.");
                                continue;
                            }
                            // Get pre-calculated life time in days for time passes
                            Long lifetimeInDays = GooglePlayBillingHelper.IAB_TIMEPASS_SKUS_TO_DAYS.get(skuDetails.getSku());
                            if (lifetimeInDays == null || lifetimeInDays == 0L) {
                                MyLog.w("PaymentChooserActivity error: unknown time pass period for sku: " + skuDetails);
                                continue;
                            }
                            // Calculate price per day
                            float pricePerDay = skuDetails.getPriceAmountMicros() / 1000000.0f / lifetimeInDays;

                            int clickableResId = getResources().getIdentifier("timepassClickable" + lifetimeInDays, "id", packageName);
                            int titlePriceTvResId = getResources().getIdentifier("timepassTitlePrice" + lifetimeInDays, "id", packageName);
                            int pricePerDayTvResId = getResources().getIdentifier("timepassPricePerDay" + lifetimeInDays, "id", packageName);

                            ViewGroup clickable = fragmentView.findViewById(clickableResId);
                            TextView titlePriceTv = fragmentView.findViewById(titlePriceTvResId);
                            TextView pricePerDayTv = fragmentView.findViewById(pricePerDayTvResId);

                            // skuDetails.getPrice() returns a differently looking string than the one
                            // we get by using priceFormatter below, so for consistency we'll use
                            // priceFormatter on all prices.
                            // If the formatting for pricePerDayText or priceText fails, use these as default
                            String pricePerDayText = String.format(Locale.getDefault(), "%s%.2f",
                                    skuDetails.getPriceCurrencyCode(), pricePerDay);
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

                            String formatString = titlePriceTv.getText().toString();
                            titlePriceTv.setText(String.format(formatString, priceText));
                            formatString = pricePerDayTv.getText().toString();
                            pricePerDayTv.setText(String.format(formatString, pricePerDayText));

                            clickable.setVisibility(View.VISIBLE);
                            clickable.setOnClickListener(v -> {
                                if (!activity.isFinishing()) {
                                    MyLog.i("PaymentChooserActivity: time pass purchase button clicked.");
                                    Intent data = new Intent();
                                    data.putExtra(USER_PICKED_SKU_DETAILS_EXTRA, skuDetails.getOriginalJson());
                                    activity.setResult(RESULT_OK, data);
                                    activity.finish();
                                }
                            });
                        }
                    })
                    .subscribe());
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            compositeDisposable.dispose();
        }
    }
}
