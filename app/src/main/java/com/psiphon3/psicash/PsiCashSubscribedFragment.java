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

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.psiphon3.subscription.R;

import java.text.NumberFormat;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashSubscribedFragment extends Fragment {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable viewStatesDisposable;
    private PsiCashViewModel psiCashViewModel;

    private TextView balanceLabel;
    private ImageView balanceIcon;
    private ViewGroup balanceLayout;

    private View fragmentView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.psicash_fragment_subscribed, container, false);
        return fragmentView;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        FragmentActivity fragmentActivity = getActivity();
        psiCashViewModel = new ViewModelProvider(fragmentActivity,
                new ViewModelProvider.AndroidViewModelFactory(fragmentActivity.getApplication()))
                .get(PsiCashViewModel.class);

        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);

        balanceIcon = getActivity().findViewById(R.id.psicash_balance_icon);
        balanceIcon.setImageLevel(0);

        balanceLayout = getActivity().findViewById(R.id.psicash_balance_layout);
        balanceLayout.setClickable(true);
        balanceLayout.setOnClickListener(view -> onLabelClick());
    }

    public void onLabelClick() {
        Intent intent = new Intent(getActivity(), PsiCashStoreActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        getActivity().startActivity(intent);
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

    public void render(PsiCashViewState state) {
        if (!state.hasValidTokens()) {
            if (fragmentView != null) {
                fragmentView.setVisibility(View.GONE);
            }
            return;
        } else {
            fragmentView.setVisibility(View.VISIBLE);
        }
        updateUiBalanceLabel(state);
    }

    private void updateUiBalanceLabel(PsiCashViewState state) {
        if (state.uiBalance() == PsiCashViewState.PSICASH_IDLE_BALANCE) {
            return;
        }
        final NumberFormat nf = NumberFormat.getInstance();
        balanceLabel.setText(nf.format(state.uiBalance()));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
        unbindViewState();
    }
}