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

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.google.android.material.tabs.TabLayout;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PaymentChooserActivity extends LocalizedActivities.AppCompatActivity {
    public static final String INTENT_EXTRA_UNLOCK_REQUIRED = "INTENT_EXTRA_UNLOCK_REQUIRED";
    private GooglePlayBillingHelper googlePlayBillingHelper;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        setContentView(R.layout.payment_chooser);

        googlePlayBillingHelper.subscriptionStateFlowable()
                .firstOrError()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(subscriptionState -> {
                    findViewById(R.id.progress_overlay).setVisibility(View.GONE);
                    switch (subscriptionState.status()) {
                        case IAB_FAILURE:
                            // Show a toast indicating that subscription options are not available
                            // and finish the activity
                            if (!isFinishing()) {
                                showSubscriptionNotAvailableToast(this);
                                finish();
                            }
                            return;

                        case HAS_UNLIMITED_SUBSCRIPTION:
                        case HAS_TIME_PASS:
                            // User has an unlimited subscription, finish the activity silently.
                            if (!isFinishing()) {
                                finish();
                            }
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
                            boolean unlockRequired = false;
                            if (getIntent() != null) {
                                unlockRequired =
                                        getIntent().getBooleanExtra(INTENT_EXTRA_UNLOCK_REQUIRED, false);
                            }
                            @StringRes int stringResId = unlockRequired ?
                                    R.string.PaymentChooserActivity_PsiphonNotFreeRegion : R.string.PaymentChooserActivity_UsingFreePlan;
                            ((TextView) findViewById(R.id.payment_chooser_current_plan)).setText(stringResId);
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
        private final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private Disposable purchaseFlowDisposable;

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
            compositeDisposable.add(googlePlayBillingHelper.getSubscriptionProductDetails()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(productDetailsList -> {
                        if (productDetailsList == null || productDetailsList.isEmpty()) {
                            MyLog.e("PaymentChooserActivity: subscription error: product details list is empty, closing activity.");
                            // Show a toast indicating that subscription options are not available
                            // and finish the activity
                            if (!activity.isFinishing()) {
                                showSubscriptionNotAvailableToast(activity);
                                activity.finish();
                            }
                            return;
                        } // else
                        for (ProductDetails productDetails : productDetailsList) {
                            if (productDetails == null ) {
                                MyLog.e("PaymentChooserActivity: subscription product details list error: product details is null.");
                                continue;
                            }
                            // If the user has a limited subscription, skip showing, so we only show the unlimited subscription
                            if (limitedSubscriptionPurchase != null &&
                                    productDetails.getProductId().equals(GooglePlayBillingHelper.IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
                                MyLog.i("PaymentChooserActivity: skipping limited subscription product details because user already has it.");
                                continue;
                            }

                            // Get subscription offer details
                            List<ProductDetails.SubscriptionOfferDetails> offerDetailsList = productDetails.getSubscriptionOfferDetails();
                            if (offerDetailsList == null || offerDetailsList.isEmpty()) {
                                MyLog.e("PaymentChooserActivity: no subscription offers for product " + productDetails.getProductId());
                                continue;
                            }

                            ProductDetails.SubscriptionOfferDetails offerDetails = findBestMonthlyOffer(productDetails);
                            if (offerDetails == null) {
                                MyLog.e("PaymentChooserActivity: no monthly subscription offer for product " + productDetails.getProductId());
                                continue;
                            }


                            ViewGroup clickable;
                            TextView titlePriceTv;
                            TextView freeTrialTv;
                            TextView priceAfterFreeTrialTv;

                            if (productDetails.getProductId().equals(GooglePlayBillingHelper.IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU)) {
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

                            String priceText = getOfferPriceText(offerDetails);

                            String formatString = titlePriceTv.getText().toString();
                            titlePriceTv.setText(String.format(formatString, priceText));
                            formatString = priceAfterFreeTrialTv.getText().toString();
                            priceAfterFreeTrialTv.setText(String.format(formatString, priceText));

                            clickable.setVisibility(View.VISIBLE);
                            clickable.setOnClickListener(v -> {
                                if (activity.isFinishing()) {
                                    return;
                                }
                                MyLog.i("PaymentChooserActivity: subscription purchase button clicked.");
                                if (purchaseFlowDisposable != null && !purchaseFlowDisposable.isDisposed()) {
                                    MyLog.i("PaymentChooserActivity: a purchase flow already in progress, ignoring click.");
                                    return;
                                }


                                Completable subscriptionPurchaseFlowCompletable =
                                        limitedSubscriptionPurchase != null ?
                                        googlePlayBillingHelper.launchReplacementSubscriptionPurchaseFlow(activity, productDetails, offerDetails, limitedSubscriptionPurchase.getPurchaseToken()) :
                                        googlePlayBillingHelper.launchNewSubscriptionPurchaseFlow(activity, productDetails, offerDetails);

                                purchaseFlowDisposable = subscriptionPurchaseFlowCompletable
                                        .doOnComplete(() -> {
                                            if (!activity.isFinishing()) {
                                                // finish the activity on successful purchase flow launch
                                                activity.finish();
                                            }
                                        })
                                        .doOnError(err -> {
                                            if (!activity.isFinishing()) {
                                                // Show a toast indicating that subscription options are not available on error
                                                // and finish the activity
                                                showSubscriptionNotAvailableToast(activity);
                                                activity.finish();
                                            }
                                        })
                                        .subscribe();
                                compositeDisposable.add(purchaseFlowDisposable);
                            });

                            long freeTrialDays = getOfferFreeTrialDays(offerDetails);
                            if (freeTrialDays > 0L) {
                                String formatStringFreeTrial = freeTrialTv.getText().toString();
                                String freeTrialPeriodText = String.format(formatStringFreeTrial, freeTrialDays);
                                freeTrialTv.setText(freeTrialPeriodText);
                                // If free trial offer is available, show the free trial period and the price after free trial
                                freeTrialTv.setVisibility(View.VISIBLE);
                                priceAfterFreeTrialTv.setVisibility(View.VISIBLE);
                            }
                        }
                    })
                    .subscribe());
        }

        private int getOfferFreeTrialDays(ProductDetails.SubscriptionOfferDetails offerDetails) {
            if (!hasFreeTrialOffer(offerDetails)) {
                return 0;
            }
            ProductDetails.PricingPhase freeTrialPhase = offerDetails.getPricingPhases().getPricingPhaseList().get(0);
            String freeTrialPeriodISO8061 = freeTrialPhase.getBillingPeriod();
            try {
                Period period = Period.parse(freeTrialPeriodISO8061);
                // Convert the period to days, note that the month and year conversion is approximate
                return period.getDays() + period.getMonths() * 30 + period.getYears() * 365;
            } catch (DateTimeParseException e) {
                MyLog.w("PaymentChooserActivity: failed to parse free trial period: " + freeTrialPeriodISO8061);
                return 0;
            }
        }

        private String getOfferPriceText(ProductDetails.SubscriptionOfferDetails offerDetails) {
            List<ProductDetails.PricingPhase> phases = offerDetails.getPricingPhases().getPricingPhaseList();

            // Get the non-free-trial pricing phase
            ProductDetails.PricingPhase pricingPhase;
            if (hasFreeTrialOffer(offerDetails)) {
                pricingPhase = phases.get(1); // Get the phase after the free trial
            } else {
                pricingPhase = phases.get(0); // Get the first phase
            }

            String priceText = pricingPhase.getFormattedPrice();

            // Use price formatter to format the price, fall back to the formatted price if it fails
            try {
                Currency currency = Currency.getInstance(pricingPhase.getPriceCurrencyCode());
                NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
                priceFormatter.setCurrency(currency);
                priceText = priceFormatter.format(pricingPhase.getPriceAmountMicros() / 1000000.0f);
            } catch (IllegalArgumentException e) {
                // do nothing
            }

            return priceText;
        }

        private ProductDetails.SubscriptionOfferDetails findBestMonthlyOffer(ProductDetails productDetails) {
            List<ProductDetails.SubscriptionOfferDetails> offerDetailsList = productDetails.getSubscriptionOfferDetails();
            if (offerDetailsList == null || offerDetailsList.isEmpty()) {
                return null;
            }

            // First priority: Find any monthly offer with a free trial
            for (ProductDetails.SubscriptionOfferDetails offer : offerDetailsList) {
                // Check if it's a monthly plan
                if (isMonthlySubscription(offer)) {
                    // Check if it has a free trial
                    if (hasFreeTrialOffer(offer)) {
                        return offer;
                    }
                }
            }

            // Second priority: Just find any monthly offer
            for (ProductDetails.SubscriptionOfferDetails offer : offerDetailsList) {
                if (isMonthlySubscription(offer)) {
                    return offer;
                }
            }

            // No suitable offers found
            return null;
        }

        private boolean isMonthlySubscription(ProductDetails.SubscriptionOfferDetails offer) {
            List<ProductDetails.PricingPhase> pricingPhases = offer.getPricingPhases().getPricingPhaseList();
            if (pricingPhases.isEmpty()) return false;

            // Get the base pricing phase (the one that's not a free trial)
            ProductDetails.PricingPhase basePricingPhase;
            if (pricingPhases.size() > 1 && pricingPhases.get(0).getPriceAmountMicros() == 0) {
                basePricingPhase = pricingPhases.get(1);
            } else {
                basePricingPhase = pricingPhases.get(0);
            }

            // Check if it's monthly (P1M = Period 1 Month in ISO 8601)
            return basePricingPhase.getBillingPeriod().equals("P1M");
        }

        private boolean hasFreeTrialOffer(ProductDetails.SubscriptionOfferDetails offer) {
            List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
            // Check if there are at least two phases and the first one is free
            return phases.size() > 1 && phases.get(0).getPriceAmountMicros() == 0;
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
        private final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private Disposable purchaseFlowDisposable;

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
            compositeDisposable.add(googlePlayBillingHelper.getTimePassProductDetails()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(productDetailsList -> {
                        if (productDetailsList == null || productDetailsList.isEmpty()) {
                            MyLog.e("PaymentChooserActivity: time pass error: product details list is empty, closing activity.");
                            // Show a toast indicating that subscription options are not available
                            // and finish the activity
                            if (!activity.isFinishing()) {
                                showSubscriptionNotAvailableToast(activity);
                                activity.finish();
                            }
                            return;
                        } // else
                        for (ProductDetails productDetails : productDetailsList) {
                            if (productDetails == null) {
                                MyLog.e("PaymentChooserActivity: skipping time pass product with null ProductDetails");
                                continue;
                            }
                            ProductDetails.OneTimePurchaseOfferDetails offerDetails = productDetails.getOneTimePurchaseOfferDetails();
                            if (offerDetails == null) {
                                MyLog.w("PaymentChooserActivity: skipping one time product with null OneTimePurchaseOfferDetails");
                                continue;
                            }

                            // Get pre-calculated life time in days for time passes
                            Long lifetimeInDays = GooglePlayBillingHelper.IAB_TIMEPASS_SKUS_TO_DAYS.get(productDetails.getProductId());
                            if (lifetimeInDays == null || lifetimeInDays == 0L) {
                                MyLog.w("PaymentChooserActivity error: unknown time pass period for sku: " + productDetails);
                                continue;
                            }
                            // Calculate price per day
                            float pricePerDay = offerDetails.getPriceAmountMicros() / 1000000.0f / lifetimeInDays;

                            int clickableResId = getResources().getIdentifier("timepassClickable" + lifetimeInDays, "id", packageName);
                            int titlePriceTvResId = getResources().getIdentifier("timepassTitlePrice" + lifetimeInDays, "id", packageName);
                            int pricePerDayTvResId = getResources().getIdentifier("timepassPricePerDay" + lifetimeInDays, "id", packageName);

                            ViewGroup clickable = fragmentView.findViewById(clickableResId);
                            TextView titlePriceTv = fragmentView.findViewById(titlePriceTvResId);
                            TextView pricePerDayTv = fragmentView.findViewById(pricePerDayTvResId);

                            // offerDetails.getFormattedPrice() returns a differently looking string than the one
                            // we get by using priceFormatter below, so for consistency we'll use
                            // priceFormatter on all prices.
                            // If the formatting for pricePerDayText or priceText fails, use these as default
                            String pricePerDayText = String.format(Locale.getDefault(), "%s%.2f",
                                    offerDetails.getPriceCurrencyCode(), pricePerDay);
                            String priceText = offerDetails.getFormattedPrice();

                            try {
                                Currency currency = Currency.getInstance(offerDetails.getPriceCurrencyCode());
                                NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
                                priceFormatter.setCurrency(currency);
                                pricePerDayText = priceFormatter.format(pricePerDay);
                                priceText = priceFormatter.format(offerDetails.getPriceAmountMicros() / 1000000.0f);
                            } catch (IllegalArgumentException e) {
                                // do nothing
                            }

                            String formatString = titlePriceTv.getText().toString();
                            titlePriceTv.setText(String.format(formatString, priceText));
                            formatString = pricePerDayTv.getText().toString();
                            pricePerDayTv.setText(String.format(formatString, pricePerDayText));

                            clickable.setVisibility(View.VISIBLE);
                            clickable.setOnClickListener(v -> {
                                if (activity.isFinishing()) {
                                    return;
                                }

                                MyLog.i("PaymentChooserActivity: time pass purchase button clicked.");

                                if (purchaseFlowDisposable != null && !purchaseFlowDisposable.isDisposed()) {
                                    MyLog.i("PaymentChooserActivity: a purchase flow already in progress, ignoring click.");
                                    return;
                                }

                                purchaseFlowDisposable = googlePlayBillingHelper
                                        .launchOneTimePurchaseFlow(activity, productDetails)
                                        .doOnComplete(() -> {
                                            if (!activity.isFinishing()) {
                                                // finish the activity after successful purchase flow launch
                                                activity.finish();
                                            }
                                        })
                                        .doOnError(err -> {
                                            // Show a toast indicating that subscription options are not available on error
                                            // and finish the activity
                                            showSubscriptionNotAvailableToast(activity);
                                            activity.finish();
                                        })
                                        .subscribe();
                                compositeDisposable.add(purchaseFlowDisposable);
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

    static void showSubscriptionNotAvailableToast(Context context) {
        // Show a toast indicating that subscription options are not available
        // This is a generic message that is shown when billing errors occur
        Toast toast = Toast.makeText(context, R.string.subscription_options_currently_not_available, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }
}
