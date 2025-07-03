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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
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
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class HomeTabFragment extends Fragment {
    private static final String PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL = "https://play.google.com/store/account/subscriptions?sku=%s&package=%s";
    MainActivityViewModel mainActivityViewModel;
    private TextView manageSubscriptionLink;
    private TextView upRateLimitTextView;
    private TextView downRateLimitTextView;
    private TextView combinedRateLimitTextView;
    private TextView accessStatusLabel;
    private Button upgradeButton;
    private Drawable conduitAppIcon;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private View tunnelStateContainer;
    private ImageView tunnelStateImageView;
    private TextView lastLogEntryTv;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.home_tab_layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mainActivityViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(MainActivityViewModel.class);

        manageSubscriptionLink = view.findViewById(R.id.manageSubscriptionLink);
        upRateLimitTextView = view.findViewById(R.id.upRateLimitTextView);
        downRateLimitTextView = view.findViewById(R.id.downRateLimitTextView);
        combinedRateLimitTextView = view.findViewById(R.id.combinedRateLimitTextView);
        accessStatusLabel = view.findViewById(R.id.accessStatusLabel);
        upgradeButton = view.findViewById(R.id.upgradeButton);
        tunnelStateContainer = view.findViewById(R.id.tunnelStateContainer);
        tunnelStateImageView = view.findViewById(R.id.tunnelStateImageView);
        lastLogEntryTv = view.findViewById(R.id.lastLogEntryTv);

        upgradeButton.setOnClickListener(v ->
                MainActivity.openPaymentChooserActivity(requireActivity(),
                        getResources().getInteger(R.integer.subscriptionTabIndex)));

        // Render initial state to avoid incomplete UI
        render(UiState.initial(requireContext()));
    }

    @Override
    public void onPause() {
        super.onPause();
        compositeDisposable.clear();
    }

    @Override
    public void onResume() {
        super.onResume();

        Flowable<TunnelState> tunnelStateFlowable = ((LocalizedActivities.AppCompatActivity) requireActivity())
                .getTunnelServiceInteractor().tunnelStateFlowable();

        Flowable<RateLimitHelper.Display> rateLimitFlowable =
                tunnelStateFlowable.map(state -> RateLimitHelper.getDisplay(requireContext()))
                        .distinctUntilChanged();

        Flowable<Boolean> conduitIsRunningFlowable = tunnelStateFlowable
                .switchMap(tunnelState -> {
                    // If tunnel is running check if sponsor ID matches CONDUIT_RUNNING_SPONSOR_ID
                    if (tunnelState.isRunning() && tunnelState.connectionData() != null) {
                        return Flowable.just(tunnelState.connectionData().sponsorId().equals(BuildConfig.CONDUIT_RUNNING_SPONSOR_ID));
                    } else {
                        // If tunnel is not running, check if Conduit is running directly
                        return ConduitStateManager.newManager(requireContext()).stateFlowable()
                                .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                                .doOnNext(state -> {
                                            if (state.status() == ConduitState.Status.ERROR) {
                                                // Log the error state
                                                MyLog.e("HomeTabFragment: error getting Conduit state: " + state.message());
                                            }
                                        })
                                .map(state -> state.status() == ConduitState.Status.RUNNING)
                                .onErrorReturnItem(false); // Should never happen, but just in case
                    }
                })
                .distinctUntilChanged();

        Flowable<SubscriptionState> subscriptionStateFlowable =
                GooglePlayBillingHelper.getInstance(requireContext())
                        .subscriptionStateFlowable()
                        .distinctUntilChanged();

        Flowable<String> lastLogEntryFlowable = mainActivityViewModel.lastLogEntryFlowable()
                .startWith("")
                .distinctUntilChanged();

        compositeDisposable.add(
                Flowable.combineLatest(
                                rateLimitFlowable,
                                subscriptionStateFlowable,
                                tunnelStateFlowable,
                                conduitIsRunningFlowable,
                                lastLogEntryFlowable,
                                UiState::new)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(this::render)
                        .subscribe());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private void render(UiState uiState) {
        boolean isTunnelConnecting = uiState.tunnelState.isRunning() && !uiState.tunnelState.connectionData().isConnected();
        boolean isTunnelConnected = uiState.tunnelState.isRunning() && uiState.tunnelState.connectionData().isConnected();
        renderRateLimitDisplay(uiState.rateLimitDisplay, isTunnelConnected);
        renderAccessStatus(uiState.subscriptionState, uiState.conduitIsRunning);
        renderSubscriptionActions(uiState.subscriptionState, isTunnelConnecting);
        renderTunnelState(uiState.tunnelState, uiState.lastLogEntry);
    }


    private void renderAccessStatus(SubscriptionState subscriptionState, boolean conduitIsRunning) {
        // Limited subscription - display subscription_limited
        // Unlimited subscription - display subscription_unlimited
        // Time pass - display time_pass_active
        // No subscription - display subscription_none if conduit is not running or conduit_active if conduit is running

        String labelText;

        switch (subscriptionState.status()) {
            case HAS_LIMITED_SUBSCRIPTION:
                labelText = getString(R.string.subscription_limited);
                break;
            case HAS_UNLIMITED_SUBSCRIPTION:
                labelText = getString(R.string.subscription_unlimited);
                break;
            case HAS_TIME_PASS:
                labelText = getString(R.string.time_pass_active);
                break;
            case HAS_NO_SUBSCRIPTION:
            default:
                labelText = conduitIsRunning
                        ? getString(R.string.conduit_active)
                        : getString(R.string.subscription_none);
                break;
        }

        accessStatusLabel.setText(labelText);

        if (subscriptionState.status() == SubscriptionState.Status.HAS_NO_SUBSCRIPTION && conduitIsRunning) {
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
                    int size = accessStatusLabel.getLineHeight();
                    conduitAppIcon.setBounds(0, 0, size, size);
                }
            }

            if (conduitAppIcon != null) {
                accessStatusLabel.setCompoundDrawablePadding(getResources().getDimensionPixelSize(R.dimen.padding_small));
                if (ViewCompat.getLayoutDirection(accessStatusLabel) == ViewCompat.LAYOUT_DIRECTION_RTL) {
                    accessStatusLabel.setCompoundDrawables(null, null, conduitAppIcon, null);
                } else {
                    accessStatusLabel.setCompoundDrawables(conduitAppIcon, null, null, null);
                }
            }
        } else {
            // Clear drawable if not showing Conduit state
            accessStatusLabel.setCompoundDrawables(null, null, null, null);
        }
    }

    private void renderSubscriptionActions(SubscriptionState subscriptionState, boolean disableActions) {
        // No subscription - show the upgrade button, and hide the manage subscription link
        // Limited subscription - show the upgrade button, and show the manage subscription link
        // Unlimited subscription - hide the upgrade button, and show the manage subscription link
        // Time pass - hide the upgrade button, hide the manage subscription link

        SubscriptionState.Status status = subscriptionState.status();

        boolean showUpgradeButton = status == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION ||
                status == SubscriptionState.Status.HAS_NO_SUBSCRIPTION;

        boolean showManageLink = status == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION ||
                status == SubscriptionState.Status.HAS_UNLIMITED_SUBSCRIPTION;

        upgradeButton.setVisibility(showUpgradeButton ? View.VISIBLE : View.GONE);
        upgradeButton.setEnabled(!disableActions);

        if (showManageLink) {
            setManageSubscriptionLink(subscriptionState, !disableActions);
        } else {
            manageSubscriptionLink.setVisibility(View.GONE);
        }
    }

    private void setManageSubscriptionLink(SubscriptionState subscriptionState, boolean enabled) {
        // If we have a subscription, set up and show the manage subscription link
        if (subscriptionState.status() == SubscriptionState.Status.HAS_LIMITED_SUBSCRIPTION ||
                subscriptionState.status() == SubscriptionState.Status.HAS_UNLIMITED_SUBSCRIPTION) {

            String url = String.format(PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL,
                    subscriptionState.purchase().getProducts().get(0),
                    requireContext().getPackageName());

            SpannableString spannable = new SpannableString(manageSubscriptionLink.getText().toString());
            spannable.setSpan(new URLSpan(url), 0, spannable.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            manageSubscriptionLink.setText(spannable, TextView.BufferType.SPANNABLE);
            manageSubscriptionLink.setMovementMethod(LinkMovementMethod.getInstance());

            manageSubscriptionLink.setVisibility(View.VISIBLE);
            manageSubscriptionLink.setEnabled(enabled);
            manageSubscriptionLink.setAlpha(enabled ? 1.0f : 0.5f);
        } else {
            manageSubscriptionLink.setVisibility(View.GONE);
        }
    }

    private void renderRateLimitDisplay(RateLimitHelper.Display rateLimitDisplay, boolean isTunnelConnected) {
        if (rateLimitDisplay.isSymmetric) {
            upRateLimitTextView.setVisibility(View.GONE);
            downRateLimitTextView.setVisibility(View.GONE);

            combinedRateLimitTextView.setVisibility(View.VISIBLE);
            combinedRateLimitTextView.setText(rateLimitDisplay.combined);
            applyEffect(combinedRateLimitTextView, isTunnelConnected);
        } else {
            upRateLimitTextView.setVisibility(View.VISIBLE);
            downRateLimitTextView.setVisibility(View.VISIBLE);
            combinedRateLimitTextView.setVisibility(View.GONE);

            upRateLimitTextView.setText(rateLimitDisplay.up);
            downRateLimitTextView.setText(rateLimitDisplay.down);
            applyEffect(upRateLimitTextView, isTunnelConnected);
            applyEffect(downRateLimitTextView, isTunnelConnected);
        }
    }

    private void applyEffect(TextView textView, boolean isTunnelConnected) {
        if (isTunnelConnected) {
            textView.setShadowLayer(7f, 0f, 0f,
                    ContextCompat.getColor(textView.getContext(), R.color.rate_limit_glow));
            textView.setAlpha(1f); // ensure full opacity
        } else {
            textView.setShadowLayer(0f, 0f, 0f, 0);
            textView.setAlpha(0.4f); // dim if stale
        }
    }

    private void renderTunnelState(TunnelState tunnelState, String lastLogEntry) {
        if (!lastLogEntry.isEmpty()) {
            lastLogEntryTv.setText(lastLogEntry);
        }
        if (tunnelState.isRunning()) {
            if (tunnelState.connectionData().isConnected()) {
                tunnelStateImageView.setImageResource(R.drawable.status_icon_connected);
            } else {
                tunnelStateImageView.setImageResource(R.drawable.status_icon_connecting);
            }
        } else {
            // the tunnel state is either unknown or not running
            tunnelStateImageView.setImageResource(R.drawable.status_icon_disconnected);
        }
    }

    private static class UiState {
        private final SubscriptionState subscriptionState;
        private final TunnelState tunnelState;
        private final boolean conduitIsRunning;
        private final RateLimitHelper.Display rateLimitDisplay;
        private final String lastLogEntry;

        public UiState(RateLimitHelper.Display rateLimitDisplay,
                       SubscriptionState subscriptionState,
                       TunnelState tunnelState,
                       boolean conduitIsRunning,
                       String lastLogEntry) {
            this.subscriptionState = subscriptionState;
            this.tunnelState = tunnelState;
            this.conduitIsRunning = conduitIsRunning;
            this.rateLimitDisplay = rateLimitDisplay;
            this.lastLogEntry = lastLogEntry;
        }

        public static UiState initial(Context context) {
            return new UiState(
                    RateLimitHelper.Display.initial(context),
                    SubscriptionState.notApplicable(),
                    TunnelState.unknown(),
                    false,
                    ""
            );
        }
    }
}
