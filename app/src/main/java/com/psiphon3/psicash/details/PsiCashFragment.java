/*
 * Copyright (c) 2021, Psiphon Inc.
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
 */

package com.psiphon3.psicash.details;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.CountDownListener;
import com.psiphon3.psicash.util.SingleViewEvent;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashFragment extends Fragment
        implements MviView<PsiCashDetailsIntent, PsiCashDetailsViewState>, CountDownListener {
    private static final String TAG = "PsiCashFragment";
    private static final long SLOW_TIMER_INTERVAL_MILLIS = 1000L * 60;
    private static final long FAST_TIMER_INTERVAL_MILLIS = 1000L;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Flowable<TunnelState> tunnelStateFlowable;

    private final Relay<PsiCashDetailsIntent> intentsPublishRelay = PublishRelay.<PsiCashDetailsIntent>create().toSerialized();
    private final PublishRelay<Pair<Long, Long>> balanceAnimationRelay = PublishRelay.create();

    private View fragmentView;
    private TextView balanceLabel;
    private View speedBoostBtnContainer;
    private View speedBoostBtnClicker;
    private TextView speedBoostBtnClickerLabel;
    private SpeedBoostCountDownTimer countDownTimer;
    private View progressOverlay;
    private ImageView balanceIcon;
    private ViewGroup balanceLayout;
    private Button psiCashAccountBtn;

    private Long currentUiBalance;

    private BroadcastReceiver broadcastReceiver;
    private boolean isStopped;
    private Disposable psiCashUpdatesDisposable;
    private Consumer<Boolean> visibilityChangeListener;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.psicash_fragment, container, false);
        return fragmentView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        PsiCashDetailsViewModel psiCashDetailsViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashDetailsViewModel.class);

        tunnelStateFlowable = ((LocalizedActivities.AppCompatActivity)requireActivity())
                .getTunnelServiceInteractor().tunnelStateFlowable();

        progressOverlay = requireView().findViewById(R.id.progress_overlay);
        speedBoostBtnContainer = requireView().findViewById(R.id.purchase_speedboost_clicker_container);
        speedBoostBtnClicker = requireView().findViewById(R.id.purchase_speedboost_clicker);
        speedBoostBtnClicker.setOnClickListener(v -> UiHelpers.openPsiCashStoreActivity(requireActivity()));
        speedBoostBtnClickerLabel = requireView().findViewById(R.id.purchase_speedboost_clicker_label);
        balanceLabel = requireView().findViewById(R.id.psicash_balance_label);
        balanceIcon = requireView().findViewById(R.id.psicash_balance_icon);
        balanceLayout = requireView().findViewById(R.id.psicash_balance_layout);

        psiCashAccountBtn = requireView().findViewById(R.id.psicash_account_btn);
        psiCashAccountBtn.setOnClickListener(v ->
                UiHelpers.openPsiCashAccountActivity(requireActivity()));

        compositeDisposable.add(GooglePlayBillingHelper.getInstance(requireContext())
                .subscriptionStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged()
                .doOnSubscribe(__ -> progressOverlay.setVisibility(View.VISIBLE))
                .doOnTerminate(() -> progressOverlay.setVisibility(View.GONE))
                .doOnNext(subscriptionState -> {
                    if (progressOverlay.getVisibility() == View.VISIBLE) {
                        progressOverlay.setVisibility(View.GONE);
                    }
                    boolean hidePsiCashActionsUi = subscriptionState.hasValidPurchase();
                    speedBoostBtnContainer.setVisibility(hidePsiCashActionsUi ? View.GONE : View.VISIBLE);
                })
                .subscribe());

        // Balance label increase animations, executed sequentially
        compositeDisposable.add(balanceAnimationRelay
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .concatMap(pair ->
                        UiHelpers.balanceLabelAnimationObservable(pair.first, pair.second, balanceLabel)
                )
                .subscribe(ValueAnimator::start, err -> {
                    MyLog.e("Balance label increase animation error: " + err);
                }));

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TunnelServiceInteractor.AUTHORIZATIONS_REMOVED_BROADCAST_INTENT);
        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null && !isStopped) {
                    if (action.equals(TunnelServiceInteractor.AUTHORIZATIONS_REMOVED_BROADCAST_INTENT)) {
                        checkRemovePurchases();
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(requireActivity()).registerReceiver(broadcastReceiver, intentFilter);

        // Pass the UI's intents to the view model
        psiCashDetailsViewModel.processIntents(intents());

        // Render view states
        compositeDisposable.add(psiCashDetailsViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (psiCashUpdatesDisposable != null) {
            psiCashUpdatesDisposable.dispose();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Get PsiCash updates when foregrounded and on tunnel state changes after
        psiCashUpdatesDisposable = tunnelStateFlowable
                .filter(tunnelState -> !tunnelState.isUnknown())
                .distinctUntilChanged()
                .doOnNext(__ -> intentsPublishRelay.accept(PsiCashDetailsIntent.GetPsiCash.create(
                        tunnelStateFlowable)))
                .subscribe();
        compositeDisposable.add(psiCashUpdatesDisposable);

        // Check if there are SpeedBoost purchases to be removed
        checkRemovePurchases();
    }

    @Override
    public void onStop() {
        super.onStop();
        isStopped = true;
    }

    @Override
    public void onStart() {
        super.onStart();
        isStopped = false;
    }

    public void checkRemovePurchases() {
        Context ctx = getContext();
        final AppPreferences mp = new AppPreferences(getContext());
        if (!mp.getBoolean(getString(R.string.persistentAuthorizationsRemovedFlag), false)) {
            return;
        }
        mp.put(getString(R.string.persistentAuthorizationsRemovedFlag), false);

        List<String> purchasesToRemove = new ArrayList<>();
        // Remove purchases that are not in the authorizations database.
        try {
            // Get a list of all persisted authorizations IDs
            List<String> persistedAuthIds = new ArrayList<>();
            for (Authorization a : Authorization.geAllPersistedAuthorizations(ctx)) {
                persistedAuthIds.add(a.Id());
            }
            List<PsiCashLib.Purchase> purchases = PsiCashClient.getInstance(ctx).getPurchases();
            if (purchases.size() == 0) {
                return;
            }
            // Build a list of purchases to remove by cross referencing each purchase authorization ID
            // against the list of all persisted authorization IDs
            for (PsiCashLib.Purchase purchase : purchases) {
                if (!persistedAuthIds.contains(purchase.authorization.id)) {
                    purchasesToRemove.add(purchase.id);
                    MyLog.i("PsiCash: will remove purchase of transactionClass: " +
                            purchase.transactionClass + ", auth expires: " +
                            Utils.getISO8601String(purchase.authorization.expires)
                    );
                }
            }
            if (purchasesToRemove.size() > 0) {
                intentsPublishRelay.accept(PsiCashDetailsIntent.RemovePurchases.create(purchasesToRemove));
            }
        } catch (PsiCashException e) {
            MyLog.e("PsiCash: error removing expired purchases: " + e);
        }
    }

    @Override
    public Observable<PsiCashDetailsIntent> intents() {
        return intentsPublishRelay
                .hide();
    }

    @Override
    public void render(PsiCashDetailsViewState state) {
        if (fragmentView == null) {
            return;
        }

        boolean shouldHide = shouldHideUi(state);
        fragmentView.setVisibility(shouldHide ? View.GONE : View.VISIBLE);

        if (visibilityChangeListener != null) {
            visibilityChangeListener.accept(!shouldHide);
        }

        if (shouldHide) {
            return;
        }

        // continue updating the UI if the UI is not hidden
        updateUiBalanceIcon(state);
        updatePsiCashBalanceLayout(state);
        updatePsiCashAccountButton(state);
        updateUiBalanceLabel(state);
        updateSpeedBoostButton(state);
        updateUiProgressView(state);
        updateUiPsiCashError(state);
    }

    // Check if the UI should be hidden based on the PsiCash model state
    private boolean shouldHideUi(PsiCashDetailsViewState state) {
        if (state.psiCashModel() == null) {
            return true;
        }
        // Check if the PsiCash library is either not initialized or if the user is not logged in
        if (!state.psiCashModel().hasTokens()) {
            return true;
        }

        // Check if the user has an active boost
        PsiCashLib.Purchase purchase = state.psiCashModel().nextExpiringPurchase();
        if (purchase != null) {
            Date expiryDate = purchase.expiry;
            if (expiryDate != null) {
                long millisDiff = expiryDate.getTime() - new Date().getTime();
                if (millisDiff > 0) {
                    return false;
                }
            }
        }
        // Finally check if the user has enough balance to purchase a "1hr" speed boost
        if (state.psiCashModel().purchasePrices() != null) {
            for (PsiCashLib.PurchasePrice price : state.psiCashModel().purchasePrices()) {
                if (price.distinguisher.equals("1hr") && price.price > state.psiCashModel().balance()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateUiPsiCashError(PsiCashDetailsViewState state) {
        SingleViewEvent<Throwable> errorViewEvent = state.errorViewEvent();
        if (errorViewEvent == null) {
            return;
        }

        errorViewEvent.consume((error) -> {
            String errorMessage;
            if (error instanceof PsiCashException) {
                PsiCashException e = (PsiCashException) error;
                errorMessage = e.getUIMessage(requireActivity());
            } else {
                MyLog.e("Unexpected PsiCash error: " + error);
                errorMessage = getString(R.string.unexpected_error_occured_send_feedback_message);
            }
            if (getParentFragment() != null) {
                View anchorView = getParentFragment().requireView().findViewById(R.id.snackbar_anchor_layout);
                if (anchorView != null) {
                    UiHelpers.getSnackbar(errorMessage, anchorView).show();
                }
            }
        });
    }

    private void updateUiProgressView(PsiCashDetailsViewState state) {
        if (state.psiCashTransactionInFlight()) {
            progressOverlay.setVisibility(View.VISIBLE);
        } else {
            progressOverlay.setVisibility(View.GONE);
        }
    }

    private void updateSpeedBoostButton(PsiCashDetailsViewState state) {
        speedBoostBtnClicker.setEnabled(!state.psiCashTransactionInFlight());
        Date nextPurchaseExpiryDate = null;
        PsiCashLib.Purchase purchase = state.purchase();
        if (purchase != null) {
            nextPurchaseExpiryDate = purchase.expiry;
        }
        if (nextPurchaseExpiryDate != null && new Date().before(nextPurchaseExpiryDate)) {
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
        } else {
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            speedBoostBtnClickerLabel.setText(R.string.speed_boost_button_caption);
        }
    }

    private void updateUiBalanceLabel(PsiCashDetailsViewState state) {
        if (state.psiCashModel() == null) {
            return;
        }
        if (currentUiBalance == null) {
            final NumberFormat nf = NumberFormat.getInstance();
            balanceLabel.setText(nf.format(state.uiBalance()));
        } else {
            if (currentUiBalance != state.uiBalance()) {
                balanceAnimationRelay.accept(new Pair<>(currentUiBalance, state.uiBalance()));
            }
        }
        currentUiBalance = state.uiBalance();
    }

    private void updateUiBalanceIcon(PsiCashDetailsViewState state) {
        if (!state.pendingRefresh()) {
            if (state.psiCashModel() != null &&
                    state.psiCashModel().isAccount() &&
                    !state.psiCashModel().hasTokens()) {
                // logged out account, show red alert icon
                balanceIcon.setImageLevel(1);
            } else {
                balanceIcon.setImageLevel(0);
            }
        } else {
            // balance out of date, show red alert icon
            balanceIcon.setImageLevel(1);
        }
    }

    private void updatePsiCashBalanceLayout(PsiCashDetailsViewState state) {
        if (state.pendingRefresh()) {
            View.OnClickListener clickListener = v -> {
                final Activity activity = requireActivity();
                if (activity.isFinishing()) {
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setIcon(R.drawable.psicash_coin)
                        .setTitle(activity.getString(R.string.psicash_generic_title))
                        .setMessage(activity.getString(R.string.psicash_out_of_date_dialog_message))
                        .setNeutralButton(R.string.label_ok, (dialog, which) -> {
                        })
                        .setCancelable(true)
                        .create()
                        .show();
            };
            balanceLayout.setOnClickListener(clickListener);
        }
    }

    private void updatePsiCashAccountButton(PsiCashDetailsViewState state) {
        // if account and logged in then hide the account sign in button
        if (state.psiCashModel() != null &&
                state.psiCashModel().isAccount() &&
                state.psiCashModel().hasTokens()) {
            psiCashAccountBtn.setVisibility(View.GONE);
        } else {
            psiCashAccountBtn.setVisibility(View.VISIBLE);
        }
    }

    private void startActiveSpeedBoostCountDown(long millisDiff) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        final long interval;
        if (TimeUnit.MILLISECONDS.toMinutes(millisDiff) < 6) {
            interval = FAST_TIMER_INTERVAL_MILLIS;
        } else {
            interval = SLOW_TIMER_INTERVAL_MILLIS;
        }
        countDownTimer = new SpeedBoostCountDownTimer(millisDiff, interval, this);
        countDownTimer.start();
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        Context ctx = getContext();
        if (ctx != null) {
            LocalBroadcastManager.getInstance(ctx).unregisterReceiver(broadcastReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onCountDownTick(long currentInterval, long l) {
        long d = TimeUnit.MILLISECONDS.toDays(l);
        long h = TimeUnit.MILLISECONDS.toHours(l) - TimeUnit.DAYS.toHours(TimeUnit.MILLISECONDS.toDays(l));
        long m = TimeUnit.MILLISECONDS.toMinutes(l) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(l));
        long s = TimeUnit.MILLISECONDS.toSeconds(l) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(l));

        // Check if remaining time is less than 6 minutes and we need to switch to fast timer
        if (TimeUnit.MILLISECONDS.toMinutes(l) < 6 && currentInterval == SLOW_TIMER_INTERVAL_MILLIS) {
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            countDownTimer = new SpeedBoostCountDownTimer(l, FAST_TIMER_INTERVAL_MILLIS, this);
            countDownTimer.start();
        }

        String countdownButtonText;
        if (d > 0) {
            // Use '11 d 12 hr' format if more than 1 day left, otherwise use XX:YY, see below for details.
            String daysLeft = getResources().getQuantityString(R.plurals.speed_boost_left_days, (int) d, (int) d);
            String hoursLeft = getResources().getQuantityString(R.plurals.speed_boost_left_hours, (int) h, (int) h);
            countdownButtonText = getString(R.string.speed_boost_left_day_hour_ordered, daysLeft, hoursLeft);
        } else {
            if (TimeUnit.MILLISECONDS.toMinutes(l) >= 5) {
                // If remaining time is more than 5 minutes show HH:MM
                countdownButtonText = String.format(Locale.getDefault(), "%02d:%02d", h, m);
            } else {
                // If remaining time is less than 5 minutes then show MM:SS
                countdownButtonText = String.format(Locale.getDefault(), "%02d:%02d", m, s);
            }
        }
        speedBoostBtnClickerLabel.setText(String.format(Locale.getDefault(), "%s %s",
                getString(R.string.speed_boost_active_label), countdownButtonText));

    }

    @Override
    public void onCountDownFinish() {
        // Update state when finished
        intentsPublishRelay.accept(PsiCashDetailsIntent.GetPsiCash.create(tunnelStateFlowable));
    }

    public void setVisibilityChangeListener(Consumer<Boolean> listener) {
        this.visibilityChangeListener = listener;
    }

    private static class SpeedBoostCountDownTimer extends CountDownTimer {
        private final CountDownListener countDownListener;
        private final long countDownInterval;

        public SpeedBoostCountDownTimer(long millisInFuture, long interval, CountDownListener listener) {
            super(millisInFuture, interval);
            this.countDownListener = listener;
            this.countDownInterval = interval;
        }

        @Override
        public void onTick(long l) {
            if (countDownListener != null) {
                countDownListener.onCountDownTick(countDownInterval, l);
            }
        }

        @Override
        public void onFinish() {
            if (countDownListener != null) {
                countDownListener.onCountDownFinish();
            }
        }
    }
}
