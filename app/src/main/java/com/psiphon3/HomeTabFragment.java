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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.transition.Slide;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.details.PsiCashDetailsViewModel;
import com.psiphon3.psicash.details.PsiCashFragment;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class HomeTabFragment extends Fragment {
    private static final String PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL = "https://play.google.com/store/account/subscriptions?sku=%s&package=%s";
    private View rateLimitConstraintLayout;
    private View rateLimitedTextSection;
    private TextView rateLimitedText;
    private TextView rateUnlimitedText;
    private TextView manageSubscriptionLink;
    private Button rateLimitUpgradeButton;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashDetailsViewModel psiCashDetailsViewModel;
    private GooglePlayBillingHelper googlePlayBillingHelper;
    private Observable<Boolean> showConduitRunningObservable;
    private Drawable conduitAppIcon;


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

        rateLimitConstraintLayout = view.findViewById(R.id.rateLimitConstraintLayout);

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

        showConduitRunningObservable = Observable.fromCallable(() ->
                        new AppPreferences(requireContext()).getBoolean(getString(R.string.showPurchaseRequiredPromptFlag), false))
                .flatMap(isPurchaseRequired -> {
                    if (isPurchaseRequired) {
                        return ConduitStateManager.newManager(requireContext()).stateFlowable()
                                .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                                .map(state -> state.status() == ConduitState.Status.RUNNING)
                                .doOnError(throwable -> MyLog.e("HomeTabFragment: error getting conduit state", throwable))
                                .onErrorReturnItem(false)
                                .toObservable();
                    } else {
                        return Observable.just(false);
                    }
                });
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
                        showConduitRunningObservable,
                        RateLimitDisplayState::new)
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(rateLimitDisplayState -> {
                    SubscriptionState subscriptionState = rateLimitDisplayState.subscriptionState;
                    boolean hasActiveSpeedBoost = rateLimitDisplayState.hasActiveSpeedBoost;
                    boolean showConduitRunning = rateLimitDisplayState.showConduitRunning;

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

                    // If time pass or unlimited subscription hide the "Upgrade" button and show the "UNLIMITED" label
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
                        // Clear all drawables from the rate limit label
                        rateLimitedText.setCompoundDrawables(null, null, null, null);

                        // For limited subscription set rate limit label to "5 Mb/s"
                        if (subscriptionState.status() == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION) {
                            rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
                        } else {
                            // No subscription, check in the following order:
                            // speed boost, show conduit running, default
                            if (hasActiveSpeedBoost) {
                                // If there is an active speed boost, show the speed boost label
                                rateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
                            } else if (showConduitRunning) {
                                // Show Conduit app icon and "Unlimited" label if the conduit is running

                                // Initialize conduitAppIcon only when needed
                                if (conduitAppIcon == null) {
                                    try {
                                        PackageManager pm = requireContext().getPackageManager();
                                        ApplicationInfo appInfo = pm.getApplicationInfo("ca.psiphon.conduit", 0);
                                        conduitAppIcon = pm.getApplicationIcon(appInfo);
                                    } catch (PackageManager.NameNotFoundException e) {
                                        conduitAppIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_conduit_default);
                                    }
                                    if (conduitAppIcon != null) {
                                        int size = (int) (rateLimitedText.getLineHeight());
                                        conduitAppIcon.setBounds(0, 0, size, size);
                                    }
                                }

                                if (conduitAppIcon != null) {
                                    rateLimitedText.setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.padding_small));
                                    // Depending on the layout direction, set the icon to the left or right of the text
                                    if (ViewCompat.getLayoutDirection(rateLimitedText) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                                        rateLimitedText.setCompoundDrawables(null, null, conduitAppIcon, null);
                                    } else {
                                        rateLimitedText.setCompoundDrawables(conduitAppIcon, null, null, null);
                                    }
                                }
                                // Show the rate limit label "Unlimited"
                                rateLimitedText.setText(getString(R.string.rate_limit_text_unlimited));
                            } else {
                                // Set the default rate limit label to "2 Mb/s"
                                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
                            }
                        }
                        // Show the rate limit label
                        rateLimitedText.setVisibility(View.VISIBLE);
                    }
                    rateLimitedTextSection.setVisibility(View.VISIBLE);
                })
                .subscribe());

        compositeDisposable.add(((LocalizedActivities.AppCompatActivity) requireActivity())
                .getTunnelServiceInteractor().tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(tunnelState -> {
                            // Hide the whole rate limit / subscription badge container layout if
                            // tunnel is running and connected
                            if (tunnelState.isRunning() && !tunnelState.connectionData().isConnected()) {
                                if (rateLimitConstraintLayout.getVisibility() == View.VISIBLE) {
                                    changeVisibility(rateLimitConstraintLayout, View.GONE);
                                }
                            } else {
                                if (rateLimitConstraintLayout.getVisibility() == View.GONE) {
                                    changeVisibility(rateLimitConstraintLayout, View.VISIBLE);
                                }
                            }
                        })
                .subscribe());

    }

    // Animate the visibility change of a view if the device is running Lollipop or higher
    private void changeVisibility(View view, int visible) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            Transition transition = new Slide(Gravity.BOTTOM);
            transition.setDuration(300);
            transition.addTarget(view);
            TransitionManager.beginDelayedTransition((ViewGroup) view.getParent(), transition);
        }
        view.setVisibility(visible);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private static class RateLimitDisplayState {
        final SubscriptionState subscriptionState;
        final boolean hasActiveSpeedBoost;
        final boolean showConduitRunning;

        RateLimitDisplayState(SubscriptionState subscriptionState, boolean hasActiveSpeedBoost, boolean showConduitRunning) {
            this.subscriptionState = subscriptionState;
            this.hasActiveSpeedBoost = hasActiveSpeedBoost;
            this.showConduitRunning = showConduitRunning;
        }
    }
}
