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

package com.psiphon3.psicash.store;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.MainActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.SingleViewEvent;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.subscription.R;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;


public class SpeedBoostTabFragment extends Fragment {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private View progressOverlay;
    private PsiCashStoreViewModel psiCashStoreViewModel;
    private final Handler handler = new Handler();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.psicash_store_tab_fragment, container, false);
        progressOverlay = view.findViewById(R.id.progress_overlay);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        psiCashStoreViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashStoreViewModel.class);

        Flowable<TunnelState> tunnelStateFlowable = ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable();

        compositeDisposable.add(Observable.combineLatest(
                        tunnelStateFlowable
                                .toObservable()
                                .filter(state -> !state.isUnknown())
                                .distinctUntilChanged(),
                        psiCashStoreViewModel.states()
                                .map(psiCashViewState -> {
                                    if (psiCashViewState.purchase() == null) {
                                        return false;
                                    }
                                    Date expiryDate = psiCashViewState.purchase().expiry;
                                    if (expiryDate != null) {
                                        long millisDiff = expiryDate.getTime() - new Date().getTime();
                                        if (millisDiff > 0) {
                                            // (Re)schedule state refresh after expiry
                                            handler.removeCallbacksAndMessages(null);
                                            handler.postDelayed(() -> psiCashStoreViewModel
                                                            .processIntents(
                                                                    Observable.just(PsiCashStoreIntent.GetPsiCash.create(
                                                                            tunnelStateFlowable))),
                                                    millisDiff);
                                            return true;
                                        }
                                    }
                                    return false;
                                })
                                .distinctUntilChanged(),
                        ((BiFunction<TunnelState, Boolean, Pair<TunnelState, Boolean>>) Pair::new))
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(pair -> {
                    FragmentActivity activity = getActivity();
                    if (activity == null || activity.isFinishing() || !isAdded()) {
                        return;
                    }

                    TunnelState state = pair.first;
                    boolean hasActiveSpeedBoost = pair.second;

                    FragmentTransaction transaction = getChildFragmentManager()
                            .beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);

                    if (hasActiveSpeedBoost) {
                        transaction.replace(R.id.root_fragment_container, new SpeedBoostActiveFragment());
                    } else if (state.isStopped()) {
                        transaction.replace(R.id.root_fragment_container, new ConnectToBuySpeedBoostFragment());
                    } else {
                        transaction.replace(R.id.root_fragment_container, new SpeedBoostPurchaseFragment());
                    }

                    transaction.commitAllowingStateLoss();
                })
                .subscribe());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private void showProgress() {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }

    public static class SpeedBoostPurchaseFragment extends Fragment
            implements MviView<PsiCashStoreIntent, PsiCashStoreViewState> {
        private final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private final PublishRelay<PsiCashStoreIntent> intentsPublishRelay = PublishRelay.create();
        private Long currentUiBalance;
        private List<PsiCashLib.PurchasePrice> currentPurchasePrices = new ArrayList<>();

        public SpeedBoostPurchaseFragment() {
            super(R.layout.psicash_speed_boost_purchase_fragment);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            SpeedBoostTabFragment speedBoostTabFragment =
                    (SpeedBoostTabFragment) getParentFragment();
            if (speedBoostTabFragment != null) {
                speedBoostTabFragment.hideProgress();
            }

            PsiCashStoreViewModel psiCashStoreViewModel = new ViewModelProvider(requireActivity(),
                    new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                    .get(PsiCashStoreViewModel.class);
            psiCashStoreViewModel.processIntents(intents());

            // Render view states
            compositeDisposable.add(psiCashStoreViewModel.states()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::render));
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            compositeDisposable.dispose();
        }

        @Override
        public Observable<PsiCashStoreIntent> intents() {
            return intentsPublishRelay
                    .hide();
        }

        @Override
        public void render(PsiCashStoreViewState state) {
            updateUiPsiCashError(state);
            updateUiSpeedBoostPurchaseView(state);
        }

        private enum SpeedBoostDistinguisher {
            SPEEDBOOST_1_HR,
            SPEEDBOOST_1_DAY,
            SPEEDBOOST_1_WEEK,
            SPEEDBOOST_1_MONTH,
        }

        private int getSpeedBoostPurchaseDrawableResId(SpeedBoostDistinguisher distinguisher) {
            switch (distinguisher) {
                case SPEEDBOOST_1_HR:
                    return R.drawable.speedboost_background_orange;
                case SPEEDBOOST_1_DAY:
                    return R.drawable.speedboost_background_pink;
                case SPEEDBOOST_1_WEEK:
                    return R.drawable.speedboost_background_purple;
                case SPEEDBOOST_1_MONTH:
                    return R.drawable.speedboost_background_blue;
                default:
                    return 0;
            }
        }

        private String getSpeedBoostDistinguisherValue(SpeedBoostDistinguisher distinguisher) {
            switch (distinguisher) {
                case SPEEDBOOST_1_HR:
                    return "1hr";
                case SPEEDBOOST_1_DAY:
                    return "24hr";
                case SPEEDBOOST_1_WEEK:
                    return "7day";
                case SPEEDBOOST_1_MONTH:
                    return "31day";
                default:
                    return null;
            }
        }

        private int getDurationStringResId(SpeedBoostDistinguisher distinguisher) {
            switch (distinguisher) {
                case SPEEDBOOST_1_HR:
                    return R.string.speed_boost_product_1_hr;
                case SPEEDBOOST_1_DAY:
                    return R.string.speed_boost_product_1_day;
                case SPEEDBOOST_1_WEEK:
                    return R.string.speed_boost_product_1_week;
                case SPEEDBOOST_1_MONTH:
                    return R.string.speed_boost_product_1_month;
                default:
                    return 0;
            }
        }

        private void populateSpeedBoostPurchases(View view, long balance, @NonNull List<PsiCashLib.PurchasePrice> purchasePriceList) {
            if (view == null) {
                return;
            }
            final int columnCount;
            int orientation = getResources().getConfiguration().orientation;

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                columnCount = 4;
            } else {
                columnCount = 2;
            }

            TableLayout containerTableLayout = view.findViewById(R.id.speedboost_purchases_table);
            containerTableLayout.removeAllViews();

            int itemsCount = 0;
            TableRow tableRow = null;

            for (SpeedBoostDistinguisher distinguisher : SpeedBoostDistinguisher.values()) {
                for (PsiCashLib.PurchasePrice price : purchasePriceList) {
                    if (price == null || !price.distinguisher.equals(getSpeedBoostDistinguisherValue(distinguisher))) {
                        continue;
                    }

                    final int durationStringResId = getDurationStringResId(distinguisher);
                    if (durationStringResId == 0) {
                        // Skip if the distinguisher is not in the hardcoded set of PsiCash SKUs
                        continue;
                    }
                    LinearLayout speedboostItemLayout = (LinearLayout) requireActivity().getLayoutInflater().inflate(R.layout.speedboost_button_template, null);
                    RelativeLayout relativeLayout = speedboostItemLayout.findViewById(R.id.speedboost_relative_layout);

                    DisplayMetrics metrics = new DisplayMetrics();
                    requireActivity().getWindow().getWindowManager().getDefaultDisplay().getMetrics(metrics);

                    float ratio = 248.0f / 185.0f;
                    LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(0, 0);
                    layoutParams.width = metrics.widthPixels / 3;
                    layoutParams.height = (int) (layoutParams.width * ratio);
                    relativeLayout.setLayoutParams(layoutParams);

                    final int priceInteger = (int) (Math.floor((long) (price.price / 1e9)));
                    int drawableResId = getSpeedBoostPurchaseDrawableResId(distinguisher);
                    relativeLayout.setBackgroundResource(drawableResId);

                    TextView durationLabel = speedboostItemLayout.findViewById(R.id.speedboost_purchase_label);
                    durationLabel.setText(durationStringResId);

                    Button button = speedboostItemLayout.findViewById(R.id.speedboost_purchase_button);

                    String priceTag = String.format(Locale.getDefault(), "%d", priceInteger);
                    button.setText(priceTag);

                    if (balance >= priceInteger) {
                        button.setEnabled(true);
                        speedboostItemLayout.setOnClickListener(v -> intentsPublishRelay.accept(
                                PsiCashStoreIntent.PurchaseSpeedBoost.create(
                                        ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable(),
                                        price.distinguisher,
                                        price.transactionClass,
                                        price.price)));
                    } else {
                        button.setEnabled(false);
                        speedboostItemLayout.setOnClickListener(v -> new AlertDialog.Builder(requireActivity())
                                .setIcon(R.drawable.psicash_coin)
                                .setTitle(requireActivity().getString(R.string.speed_boost_button_caption))
                                .setMessage(requireActivity().getString(R.string.speed_boost_insufficient_balance_alert))
                                .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                                })
                                .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                                    FragmentActivity activity = getActivity();
                                    if (activity == null || activity.isFinishing()) {
                                        return;
                                    }
                                    final ViewPager viewPager = activity.findViewById(R.id.psicash_store_viewpager);
                                    viewPager.setCurrentItem(getResources().getInteger(R.integer.psiCashTabIndex));
                                })
                                .setCancelable(true)
                                .create()
                                .show());
                    }

                    if (itemsCount % columnCount == 0) {
                        tableRow = new TableRow(requireActivity());
                        tableRow.setPadding(0, 16, 0, 16);
                        containerTableLayout.addView(tableRow);
                    }

                    TableRow.LayoutParams params = new TableRow.LayoutParams();
                    params.width = 0;
                    params.weight = 1f;
                    params.gravity = Gravity.CENTER;
                    speedboostItemLayout.setLayoutParams(params);
                    tableRow.addView(speedboostItemLayout);
                    itemsCount++;
                }
            }
        }

        private void updateUiPsiCashError(PsiCashStoreViewState state) {
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
                UiHelpers.getSnackbar(errorMessage,
                                requireActivity().findViewById(R.id.snackbar_anchor_layout))
                        .show();
            });
        }

        private void updateUiSpeedBoostPurchaseView(PsiCashStoreViewState state) {
            if (state.psiCashModel() == null || state.purchasePrices() == null) {
                return;
            }

            SpeedBoostTabFragment speedBoostTabFragment =
                    (SpeedBoostTabFragment) getParentFragment();
            View view = getView();
            if (speedBoostTabFragment == null || view == null) {
                return;
            }

            if (state.psiCashTransactionInFlight()) {
                view.setAlpha(.5f);
                speedBoostTabFragment.showProgress();
            } else {
                view.setAlpha(1f);
                speedBoostTabFragment.hideProgress();
            }

            // Redraw if either the balance or the purchase prices size have changed
            if (currentUiBalance == null ||
                    state.uiBalance() != currentUiBalance ||
                    currentPurchasePrices.size() != state.purchasePrices().size()) {
                currentUiBalance = state.uiBalance();
                currentPurchasePrices = state.purchasePrices();
                populateSpeedBoostPurchases(getView(), state.uiBalance(), state.purchasePrices());
            }
        }
    }

    public static class ConnectToBuySpeedBoostFragment extends Fragment {
        public ConnectToBuySpeedBoostFragment() {
            super(R.layout.psicash_speed_boost_connect_to_buy_fragment);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            SpeedBoostTabFragment speedBoostTabFragment =
                    (SpeedBoostTabFragment) getParentFragment();
            if (speedBoostTabFragment != null) {
                speedBoostTabFragment.hideProgress();
            }

            Button connectBtn = view.findViewById(R.id.continue_button);
            connectBtn.setOnClickListener(v -> {
                try {
                    Intent data = new Intent(MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION);
                    requireActivity().setResult(Activity.RESULT_OK, data);
                    requireActivity().finish();
                } catch (RuntimeException ignored) {
                }
            });
        }
    }

    public static class SpeedBoostActiveFragment extends Fragment {
        public SpeedBoostActiveFragment() {
            super(R.layout.psicash_speed_boost_active_fragment);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            SpeedBoostTabFragment speedBoostTabFragment =
                    (SpeedBoostTabFragment) getParentFragment();
            if (speedBoostTabFragment != null) {
                speedBoostTabFragment.hideProgress();
            }
        }
    }
}
