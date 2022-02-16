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
    private View rateLimitedTextSection;
    private TextView rateLimitedText;
    private TextView rateUnlimitedText;
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
                .map(pair -> {
                    SubscriptionState subscriptionState = pair.first;
                    Boolean hasActiveSpeedBoost = pair.second;
                    switch (subscriptionState.status()) {
                        case HAS_UNLIMITED_SUBSCRIPTION:
                        case HAS_TIME_PASS:
                            return RateLimitMode.UNLIMITED_SUBSCRIPTION;
                        case HAS_LIMITED_SUBSCRIPTION:
                            return RateLimitMode.LIMITED_SUBSCRIPTION;
                        default:
                            return hasActiveSpeedBoost ?
                                    RateLimitMode.SPEED_BOOST : RateLimitMode.AD_MODE_LIMITED;
                    }
                })
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::setRateLimitUI)
                .subscribe());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private void setRateLimitUI(RateLimitMode rateLimitMode) {
        // Update UI elements showing the current speed.
        if (rateLimitMode == RateLimitMode.UNLIMITED_SUBSCRIPTION) {
            rateLimitedText.setVisibility(View.GONE);
            rateUnlimitedText.setVisibility(View.VISIBLE);
            rateLimitUpgradeButton.setVisibility(View.GONE);
            rateLimitedTextSection.setVisibility(View.VISIBLE);
        } else{
            if(rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
            } else if (rateLimitMode == RateLimitMode.LIMITED_SUBSCRIPTION) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
            } else if (rateLimitMode == RateLimitMode.SPEED_BOOST) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
            }
            rateLimitedText.setVisibility(View.VISIBLE);
            rateUnlimitedText.setVisibility(View.GONE);
            rateLimitUpgradeButton.setVisibility(View.VISIBLE);
            rateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }
}
