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

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psicash.account.PsiCashAccountActivity;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.text.NumberFormat;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class StoreTabHostFragment extends Fragment
        implements MviView<PsiCashStoreIntent, PsiCashStoreViewState> {
    private final PublishRelay<PsiCashStoreIntent> intentsPublishRelay = PublishRelay.create();
    private final PublishRelay<Pair<Long, Long>> balanceAnimationRelay = PublishRelay.create();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private BroadcastReceiver broadcastReceiver;

    private ViewPager viewPager;
    private Long currentUiBalance;
    private TextView balanceLabel;
    private ImageView balanceIcon;
    private ViewGroup balanceLayout;
    private ViewGroup noAccountSignUpView;
    private TextView accountUsernameTextView;

    public StoreTabHostFragment() {
        super(R.layout.psicash_store_tab_host_fragment);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((PsiCashStoreActivity) requireActivity()).hideProgress();

        PsiCashStoreViewModel psiCashStoreViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashStoreViewModel.class);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT);
        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT.equals(action)) {
                    GooglePlayBillingHelper.getInstance(context).queryAllPurchases();
                    psiCashStoreViewModel.processIntents(Observable.just(PsiCashStoreIntent.GetPsiCash.create(
                            ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable())));
                }
            }
        };
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(broadcastReceiver, intentFilter);

        balanceLabel = view.findViewById(R.id.psicash_balance_label);
        balanceIcon = view.findViewById(R.id.psicash_balance_icon);
        balanceLayout = view.findViewById(R.id.psicash_balance_layout);
        noAccountSignUpView = view.findViewById(R.id.psicash_sign_up_card_layout);
        accountUsernameTextView = view.findViewById(R.id.psicash_account_username_textview);

        // Pass the UI's intents to the view model
        psiCashStoreViewModel.processIntents(intents());

        // Balance label increase animations, executed sequentially
        compositeDisposable.add(balanceAnimationRelay
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .concatMap(pair ->
                        UiHelpers.balanceLabelAnimationObservable(pair.first, pair.second, balanceLabel)
                )
                .subscribe(ValueAnimator::start, err -> {
                    Utils.MyLog.g("Balance label increase animation error: " + err);
                }));

        TabLayout tabLayout = view.findViewById(R.id.psicash_store_tablayout);

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

        PageAdapter pageAdapter = new PageAdapter(getChildFragmentManager(), tabLayout.getTabCount());
        viewPager = view.findViewById(R.id.psicash_store_viewpager);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));

        // Go to the tab specified in the opening intent extra
        int tabIndex = requireActivity().getIntent().getIntExtra("tabIndex", 0);
        if (tabIndex < pageAdapter.getCount()) {
            viewPager.setCurrentItem(tabIndex);
        }

        compositeDisposable.add(psiCashStoreViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render));
    }

    @Override
    public Observable<PsiCashStoreIntent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashStoreViewState state) {
        if (!state.hasTokens() && !state.isAccount()) {
            // finish activity
            return;
        }
        updateUiBalanceIcon(state);
        updateUiBalanceLabel(state);
        updateUiAccount(state);
    }

    private void updateUiBalanceIcon(PsiCashStoreViewState state) {
        if (state.pendingRefresh()) {
            balanceIcon.setImageLevel(1);
            balanceLayout.setClickable(true);
            balanceLayout.setOnClickListener(view -> {
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
            });
        } else {
            balanceIcon.setImageLevel(0);
            balanceLayout.setClickable(false);
        }
    }

    void updateUiBalanceLabel(PsiCashStoreViewState state) {
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

    void updateUiAccount(PsiCashStoreViewState state) {
        if (state.psiCashModel() == null) {
            return;
        }
        if (state.hasTokens()) {
            if (state.isAccount()) {
                // logged in
                // hide create account button
                noAccountSignUpView.findViewById(R.id.sign_up_clicker).setOnClickListener(null);
                noAccountSignUpView.setVisibility(View.GONE);
                // show username text label
                accountUsernameTextView.setText(state.accountUsername());
                accountUsernameTextView.setVisibility(View.VISIBLE);
            } else {
                // doesn't have an account
                // show create account button
                noAccountSignUpView.findViewById(R.id.sign_up_clicker).setOnClickListener(v -> {
                    try {
                        UiHelpers.openPsiCashAccountActivity(requireActivity(),
                                PsiCashAccountActivity.CallerActivity.PSICASH_STORE);
                    } catch (RuntimeException ignored) {
                    }
                });
                noAccountSignUpView.setVisibility(View.VISIBLE);
                // hide username text label
                accountUsernameTextView.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        final AppPreferences mp = new AppPreferences(requireContext());
        String psiCashCustomData = mp.getString(requireContext().getString(R.string.persistentPsiCashCustomData), "");
        if (TextUtils.isEmpty(psiCashCustomData)) {
            Utils.MyLog.g("PsiCashStoreActivity error: PsiCash custom data is empty.");
            requireActivity().finish();
            return;
        }
        GooglePlayBillingHelper.getInstance(requireContext())
                .queryAllPurchases();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(broadcastReceiver);
        compositeDisposable.dispose();
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private final int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new AddPsiCashTabFragment();
                case 1:
                    return new SpeedBoostTabFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }
}
