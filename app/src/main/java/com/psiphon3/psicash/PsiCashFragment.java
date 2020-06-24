/*
 *
 * Copyright (c) 2019, Psiphon Inc.
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

package com.psiphon3.psicash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.behavior.SwipeDismissBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psiphonlibrary.Authorization;
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

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static android.app.Activity.RESULT_OK;

public class PsiCashFragment extends Fragment implements MviView<PsiCashIntent, PsiCashViewState>, CountDownListener {
    private static final String TAG = "PsiCashFragment";
    public static final int PSICASH_STORE_ACTIVITY = 20002;
    private static final long SLOW_TIMER_INTERVAL_MILLIS = 1000L * 60;
    private static final long FAST_TIMER_INTERVAL_MILLIS = 1000L;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable viewStatesDisposable;
    private PsiCashViewModel psiCashViewModel;

    private Relay<PsiCashIntent> intentsPublishRelay = PublishRelay.<PsiCashIntent>create().toSerialized();
    private PublishRelay<Pair<Integer, Integer>> balanceAnimationRelay = PublishRelay.create();

    private View fragmentView;
    private TextView balanceLabel;
    private View speedBoostBtnClicker;
    private TextView speedBoostBtnClickerLabel;
    private SpeedBoostCountDownTimer countDownTimer;
    private View progressOverlay;
    private ImageView balanceIcon;
    private ViewGroup balanceLayout;

    private int currentUiBalance = PsiCashViewState.PSICASH_IDLE_BALANCE;

    private BroadcastReceiver broadcastReceiver;
    private boolean isStopped;
    private List<Throwable> renderedErrorList = new ArrayList<>();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.psicash_fragment, container, false);
        return fragmentView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        psiCashViewModel = new ViewModelProvider(getActivity()).get(PsiCashViewModel.class);
        getLifecycle().addObserver(psiCashViewModel);

        // Pass the UI's intents to the view model
        psiCashViewModel.processIntents(intents());

        progressOverlay = getActivity().findViewById(R.id.progress_overlay);
        speedBoostBtnClicker = getActivity().findViewById(R.id.purchase_speedboost_clicker);
        speedBoostBtnClicker.setOnClickListener(v ->
                openPsiCashStoreActivity(getActivity(),
                        getResources().getInteger(R.integer.speedBoostTabIndex)));
        speedBoostBtnClickerLabel = getActivity().findViewById(R.id.purchase_speedboost_clicker_label);
        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);
        balanceIcon = getActivity().findViewById(R.id.psicash_balance_icon);
        balanceLayout = getActivity().findViewById(R.id.psicash_balance_layout);

        // Balance label increase animations, executed sequentially
        compositeDisposable.add(balanceAnimationRelay
                .distinctUntilChanged()
                .concatMap(pair ->
                        balanceLabelAnimationObservable(pair.first, pair.second)
                )
                .subscribe(ValueAnimator::start, err -> {
                    Utils.MyLog.g("Balance label increase animation error: " + err);
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
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(broadcastReceiver, intentFilter);
    }

    public static void openPsiCashStoreActivity(final FragmentActivity activity, int tabIndex) {
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, PsiCashStoreActivity.class);
        intent.putExtra("tabIndex", tabIndex);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivityForResult(intent, PSICASH_STORE_ACTIVITY);
    }

    @Override
    public void onPause() {
        super.onPause();
        unbindViewState();
    }

    @Override
    public void onResume() {
        super.onResume();
        bindViewState();
        // Check if there are SpeedBoost purchases to be removed when app is foregrounded
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

    private void bindViewState() {
        // Subscribe to the RewardedVideoViewModel and render every emitted state
        viewStatesDisposable = viewStatesDisposable();
    }

    private void unbindViewState() {
        if (viewStatesDisposable != null) {
            viewStatesDisposable.dispose();
        }
    }

    private Disposable viewStatesDisposable() {
        return psiCashViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render);
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
                    Utils.MyLog.g("PsiCash: will remove purchase of transactionClass: " +
                            purchase.transactionClass + ", auth expires: " +
                            Utils.getISO8601String(purchase.authorization.expires)
                    );
                }
            }
            if (purchasesToRemove.size() > 0) {
                intentsPublishRelay.accept(PsiCashIntent.RemovePurchases.create(purchasesToRemove));
            }
        } catch (PsiCashException e) {
            Utils.MyLog.g("PsiCash: error removing expired purchases: " + e);
        }
    }

    @Override
    public Observable<PsiCashIntent> intents() {
        return intentsPublishRelay
                .hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        if (!state.hasValidTokens()) {
            if (fragmentView != null) {
                fragmentView.setVisibility(View.GONE);
            }
            return;
        } else {
            fragmentView.setVisibility(View.VISIBLE);
        }

        Throwable psiCashStateError = state.error();
        if (psiCashStateError == null) {
            renderedErrorList.clear();
            updateUiBalanceIcon(state);
            updateUiBalanceLabel(state);
            updateSpeedBoostButton(state);
            updateUiProgressView(state);
        } else {
            // Do not show same error twice
            if (!renderedErrorList.contains(psiCashStateError)) {
                renderedErrorList.add(psiCashStateError);
                updateUiPsiCashError(psiCashStateError);
            }
        }
    }

    private void updateUiPsiCashError(Throwable error) {
        // Clear view state error immediately.
        intentsPublishRelay.accept(PsiCashIntent.ClearErrorState.create());

        String errorMessage;
        if (error instanceof PsiCashException) {
            PsiCashException e = (PsiCashException) error;
            errorMessage = e.getUIMessage(getActivity());
        } else {
            Utils.MyLog.g("Unexpected PsiCash error: " + error.toString());
            errorMessage = getString(R.string.unexpected_error_occured_send_feedback_message);
        }

        // Custom snackbar dismiss timeout in milliseconds.
        int snackBarTimeousMs = 4000;
        Snackbar snackbar = Snackbar.make(getActivity().findViewById(R.id.psicash_coordinator_layout), errorMessage, snackBarTimeousMs);

        // Center the message in the text view.
        TextView tv = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        tv.setMaxLines(5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        } else {
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        // Add 'Ok' dismiss action button.
        snackbar.setAction(R.string.psicash_snackbar_action_ok, (View.OnClickListener) view -> {
        });

        // Add swipe dismiss behaviour.
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar sb) {
                super.onShown(sb);
                View snackBarView = sb.getView();
                final ViewGroup.LayoutParams lp = snackBarView.getLayoutParams();
                if (lp instanceof CoordinatorLayout.LayoutParams) {
                    final CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) lp;
                    CoordinatorLayout.Behavior behavior = layoutParams.getBehavior();
                    if (behavior instanceof SwipeDismissBehavior) {
                        ((SwipeDismissBehavior) behavior).setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY);
                    }
                    layoutParams.setBehavior(behavior);
                }
            }
        });

        snackbar.show();
    }

    private void updateUiProgressView(PsiCashViewState state) {
        if (state.psiCashTransactionInFlight() || state.videoIsLoading()) {
            progressOverlay.setVisibility(View.VISIBLE);
        } else {
            progressOverlay.setVisibility(View.GONE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PSICASH_STORE_ACTIVITY) {
            if (resultCode == RESULT_OK) {
                if (data != null && PsiCashStoreActivity.PURCHASE_SPEEDBOOST_INTENT.equals(data.getAction())) {
                    String distinguisher = data.getStringExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_DISTINGUISHER_EXTRA);
                    String transactionClass = data.getStringExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_TRANSACTION_CLASS_EXTRA);
                    long expectedPrice = data.getLongExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_EXPECTED_PRICE_EXTRA, 0L);
                    intentsPublishRelay.accept(PsiCashIntent.PurchaseSpeedBoost.create(distinguisher, transactionClass, expectedPrice));
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void updateSpeedBoostButton(PsiCashViewState state) {
        speedBoostBtnClicker.setEnabled(!state.psiCashTransactionInFlight());
        Date nextPurchaseExpiryDate = null;
        PsiCashLib.Purchase purchase = state.purchase();
        if(purchase != null)  {
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

    private void updateUiBalanceLabel(PsiCashViewState state) {
        if (state.uiBalance() == PsiCashViewState.PSICASH_IDLE_BALANCE) {
            return;
        }
        if (currentUiBalance == PsiCashViewState.PSICASH_IDLE_BALANCE) {
            final NumberFormat nf = NumberFormat.getInstance();
            balanceLabel.setText(nf.format(state.uiBalance()));
        } else {
            balanceAnimationRelay.accept(new Pair<>(currentUiBalance, state.uiBalance()));
        }
        currentUiBalance = state.uiBalance();
    }

    private void updateUiBalanceIcon(PsiCashViewState state) {
        if (state.pendingRefresh()) {
            balanceIcon.setImageLevel(1);
            balanceLayout.setOnClickListener(view -> {
                final Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) {
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
            });
        } else {
            balanceIcon.setImageLevel(0);
            balanceLayout.setOnClickListener(v ->
                    openPsiCashStoreActivity(getActivity(),
                            getResources().getInteger(R.integer.psiCashTabIndex)));
        }
    }

    private Observable<ValueAnimator> balanceLabelAnimationObservable(int fromVal, int toVal) {
        return Observable.create(emitter -> {
            ValueAnimator valueAnimator = ValueAnimator.ofInt(fromVal, toVal);
            valueAnimator.setDuration(1000);
            final NumberFormat nf = NumberFormat.getInstance();

            valueAnimator.addUpdateListener(va ->
                    balanceLabel.setText(nf.format(va.getAnimatedValue())));
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            });

            if (!emitter.isDisposed()) {
                emitter.onNext(valueAnimator);
            }
        });
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
        unbindViewState();
        Context ctx = getContext();
        if (ctx != null) {
            LocalBroadcastManager.getInstance(ctx).unregisterReceiver(broadcastReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onCountDownTick(long currentInterval, long l) {
        long h = TimeUnit.MILLISECONDS.toHours(l);
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
        if (TimeUnit.MILLISECONDS.toMinutes(l) >= 5) {
            // If remaining time is more than 5 minutes show HH:MM
            countdownButtonText = String.format(Locale.US, "%02d:%02d", h, m);
        } else {
            // If remaining time is less than 5 minutes then show MM::SS
            countdownButtonText = String.format(Locale.US, "%02d:%02d", m, s);
        }
        speedBoostBtnClickerLabel.setText(String.format(Locale.US, "%s - %s",
                getString(R.string.speed_boost_active_label), countdownButtonText));

    }

    @Override
    public void onCountDownFinish() {
        // Update state when finished
        intentsPublishRelay.accept(PsiCashIntent.GetPsiCash.create());
    }

    private static class SpeedBoostCountDownTimer extends CountDownTimer {
        private final CountDownListener countDownListener;
        private long countDownInterval;

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