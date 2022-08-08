/*
 * Copyright (c) 2022, Psiphon Inc.
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

import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.billingclient.api.Purchase;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.details.PsiCashDetailsViewModel;
import com.psiphon3.psicash.details.PsiCashFragment;
import com.psiphon3.subscription.R;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class HomeTabFragment extends Fragment {
    private static final String PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL = "https://play.google.com/store/account/subscriptions?sku=%s&package=%s";
    private View rateLimitedTextSection;
    private TextView rateLimitedText;
    private TextView rateUnlimitedText;
    private TextView manageSubscriptionLink;
    private Button rateLimitUpgradeButton;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashDetailsViewModel psiCashDetailsViewModel;
    private GooglePlayBillingHelper googlePlayBillingHelper;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.home_tab_layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        rateLimitedTextSection = view.findViewById(R.id.rateLimitedTextSection);
        rateLimitedText = view.findViewById(R.id.rateLimitedText);
        rateUnlimitedText = view.findViewById(R.id.rateUnlimitedText);

        manageSubscriptionLink = view.findViewById(R.id.manageSubscriptionLink);

        rateLimitUpgradeButton = view.findViewById(R.id.rateLimitUpgradeButton);
        rateLimitUpgradeButton.setOnClickListener(v ->
                MainActivity.openPaymentChooserActivity(requireActivity(),
                        getResources().getInteger(R.integer.subscriptionTabIndex)));

        if (savedInstanceState == null) {
            getChildFragmentManager()
                    .beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .add(R.id.psicash_fragment_container, new PsiCashFragment())
                    .commit();
        }

        psiCashDetailsViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashDetailsViewModel.class);

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(requireContext());
    }

    @Override
    public void onPause() {
        super.onPause();
        compositeDisposable.clear();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Observe subscription and speed boost states and update rate limit badge and 'Subscribe' button UI
        compositeDisposable.add(Observable.combineLatest(
                        googlePlayBillingHelper.subscriptionStateFlowable()
                                .distinctUntilChanged()
                                .toObservable(),
                        psiCashDetailsViewModel.hasActiveSpeedBoostObservable(),
                        ((BiFunction<SubscriptionState, Boolean, Pair<SubscriptionState, Boolean>>) Pair::new))
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(pair -> {
                    SubscriptionState subscriptionState = pair.first;
                    Boolean hasActiveSpeedBoost = pair.second;

                    // Show "Manage subscription" link if subscription, hide otherwise
                    if (subscriptionState.status() == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION ||
                            subscriptionState.status() == SubscriptionState.Status.HAS_UNLIMITED_SUBSCRIPTION) {
                        String url = String.format(PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL,
                                subscriptionState.purchase().getSkus().get(0),
                                requireContext().getPackageName());
                        CharSequence charSequence = manageSubscriptionLink.getText().toString();
                        SpannableString spannableString = new SpannableString(charSequence);
                        spannableString.setSpan(new URLSpan(url), 0, spannableString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        manageSubscriptionLink.setText(spannableString, TextView.BufferType.SPANNABLE);
                        manageSubscriptionLink.setMovementMethod(LinkMovementMethod.getInstance());
                        manageSubscriptionLink.setVisibility(View.VISIBLE);
                    } else {
                        manageSubscriptionLink.setVisibility(View.GONE);
                    }

                    // If time pass or unlimited subscription hide the "Upgrade" button and show the "UNLIMITED" label.
                    // Otherwise show the "Upgrade" button and rate limit label with either "5Mb/s", "2 Mb/s" or "Speed Boost".
                    if (subscriptionState.status() == SubscriptionState.Status.HAS_TIME_PASS ||
                            subscriptionState.status() == SubscriptionState.Status.HAS_UNLIMITED_SUBSCRIPTION) {
                        // Hide the rate limit label
                        rateLimitedText.setVisibility(View.GONE);
                        // Show the "Unlimited" label
                        rateUnlimitedText.setVisibility(View.VISIBLE);
                        // Hide the "Upgrade" button
                        rateLimitUpgradeButton.setVisibility(View.GONE);
                    } else {
                        // Hide the "Unlimited" label
                        rateUnlimitedText.setVisibility(View.GONE);
                        // Show the "Upgrade" button
                        rateLimitUpgradeButton.setVisibility(View.VISIBLE);
                        // For limited subscription set rate limit label to "5 Mb/s"
                        if (subscriptionState.status() == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION) {
                            rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
                        } else {
                            // No subscription, set the rate limit label to "Speed Boost" if active boost, otherwise to "2 Mb/s"
                            if (hasActiveSpeedBoost) {
                                rateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
                            } else {
                                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
                            }
                        }
                        // Show the rate limit label
                        rateLimitedText.setVisibility(View.VISIBLE);
                    }
                    rateLimitedTextSection.setVisibility(View.VISIBLE);
                })
                .subscribe());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }
}
