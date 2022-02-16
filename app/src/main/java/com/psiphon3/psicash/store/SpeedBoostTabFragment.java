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
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.gridlayout.widget.GridLayout;
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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

        private final int[] backgrounds = new int[]{
                R.drawable.speedboost_background_orange,
                R.drawable.speedboost_background_pink,
                R.drawable.speedboost_background_purple,
                R.drawable.speedboost_background_blue,
                R.drawable.speedboost_background_light_blue,
                R.drawable.speedboost_background_mint,
                R.drawable.speedboost_background_orange_2,
                R.drawable.speedboost_background_yellow,
                R.drawable.speedboost_background_fluoro_green,
        };

        static public final Map<String, Integer> PSICASH_SKUS_TO_HOURS;

        static {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("1hr", 1);
            m.put("2hr", 2);
            m.put("3hr", 3);
            m.put("4hr", 4);
            m.put("5hr", 5);
            m.put("6hr", 6);
            m.put("7hr", 7);
            m.put("8hr", 8);
            m.put("9hr", 9);
            PSICASH_SKUS_TO_HOURS = Collections.unmodifiableMap(m);
        }

        private void populateSpeedBoostPurchases(View view, long balance, @NonNull List<PsiCashLib.PurchasePrice> purchasePriceList) {
            if (view == null) {
                return;
            }
            final int columnCount;
            int orientation = getResources().getConfiguration().orientation;

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                columnCount = 5;
            } else {
                columnCount = 3;
            }

            GridLayout containerLayout = view.findViewById(R.id.purchase_speedboost_grid);
            containerLayout.removeAllViews();
            containerLayout.setColumnCount(columnCount);

            Drawable buttonDrawable = ResourcesCompat.getDrawable(requireActivity().getResources(), R.drawable.psicash_coin, null);
            buttonDrawable.setBounds(0,
                    0,
                    (int) (buttonDrawable.getIntrinsicWidth() * 0.7),
                    (int) (buttonDrawable.getIntrinsicHeight() * 0.7));

            for (String distinguisher : PSICASH_SKUS_TO_HOURS.keySet()) {
                for (PsiCashLib.PurchasePrice price : purchasePriceList) {
                    if (price == null || !distinguisher.equals(price.distinguisher)) {
                        continue;
                    }

                    final String durationString = getDurationString(price.distinguisher, requireActivity().getResources());
                    if (durationString == null) {
                        // Skip if we the distinguisher is not in the hardcoded set of PsiCash SKUs
                        continue;
                    }
                    LinearLayout speedboostItemLayout = (LinearLayout) requireActivity().getLayoutInflater().inflate(R.layout.speedboost_button_template, null);
                    RelativeLayout relativeLayout = speedboostItemLayout.findViewById(R.id.speedboost_relative_layout);

                    final int priceInteger = (int) (Math.floor((long) (price.price / 1e9)));
                    int drawableResId = getSpeedBoostPurchaseDrawableResId(priceInteger);
                    relativeLayout.setBackgroundResource(drawableResId);

                    TextView durationLabel = speedboostItemLayout.findViewById(R.id.speedboost_purchase_label);
                    durationLabel.setText(durationString);

                    Button button = speedboostItemLayout.findViewById(R.id.speedboost_purchase_button);

                    button.setCompoundDrawables(buttonDrawable, null, null, null);

                    String priceTag = String.format(Locale.getDefault(), "%d", priceInteger);
                    button.setText(priceTag);

                    if (balance >= priceInteger) {
                        button.setEnabled(true);
                        speedboostItemLayout.setOnClickListener(v -> {
                            String confirmationMessage = String.format(
                                    requireActivity().getString(R.string.confirm_speedboost_purchase_alert),
                                    durationString,
                                    priceInteger
                            );
                            Flowable<TunnelState> tunnelStateFlowable = ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable();
                            new AlertDialog.Builder(requireActivity())
                                    .setIcon(R.drawable.psicash_coin)
                                    .setTitle(requireActivity().getString(R.string.speed_boost_button_caption))
                                    .setMessage(confirmationMessage)
                                    .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                                    })
                                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                                        intentsPublishRelay.accept(PsiCashStoreIntent.PurchaseSpeedBoost.create(
                                                tunnelStateFlowable,
                                                price.distinguisher,
                                                price.transactionClass,
                                                price.price));
                                        dialog.dismiss();
                                    })
                                    .setCancelable(true)
                                    .create()
                                    .show();
                        });
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
                    DisplayMetrics metrics = new DisplayMetrics();
                    requireActivity().getWindow().getWindowManager().getDefaultDisplay().getMetrics(metrics);

                    float ratio = 248.0f / 185.0f;
                    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                    params.width = metrics.widthPixels / columnCount;
                    params.height = (int) (params.width * ratio);
                    speedboostItemLayout.setLayoutParams(params);
                    containerLayout.addView(speedboostItemLayout);
                }
            }
        }

        private String getDurationString(String distinguisher, Resources resources) {
            if (TextUtils.isEmpty(distinguisher)) {
                return null;
            }
            Integer duration = PSICASH_SKUS_TO_HOURS.get(distinguisher);
            if (duration == null) {
                return null;
            }
            return resources.getQuantityString(R.plurals.hours_of_speed_boost, duration, duration);
        }

        private int getSpeedBoostPurchaseDrawableResId(int priceValue) {
            int index = ((priceValue / 100) - 1) % backgrounds.length;
            return backgrounds[index];
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
