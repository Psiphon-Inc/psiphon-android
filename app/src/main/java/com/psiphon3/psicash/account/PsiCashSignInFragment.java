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
 */

package com.psiphon3.psicash.account;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.SingleViewEvent;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashSignInFragment extends Fragment
        implements MviView<PsiCashAccountIntent, PsiCashAccountViewState> {
    private final Relay<PsiCashAccountIntent> intentsPublishRelay = PublishRelay.create();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable psiCashUpdatesDisposable;
    private BroadcastReceiver broadcastReceiver;
    private boolean isStopped;
    private View progressOverlay;
    private String createAccountUrl;
    private PsiCashAccountSignInDialog psiCashAccountSignInDialog;


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.psicash_sign_in_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((PsiCashAccountActivity) requireActivity()).hideProgress();

        progressOverlay = view.findViewById(R.id.progress_overlay);
        Button loginBtn = view.findViewById(R.id.psicash_login_account_btn);
        Button createAccountBtn = view.findViewById(R.id.psicash_create_account_btn);


        PsiCashAccountViewModel psiCashAccountViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashAccountViewModel.class);

        Flowable<TunnelState> tunnelStateFlowable =
                ((PsiCashAccountActivity) requireActivity()).tunnelStateFlowable();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT);
        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null && !isStopped) {
                    if (TunnelServiceInteractor.PSICASH_PURCHASE_REDEEMED_BROADCAST_INTENT.equals(action)) {
                        GooglePlayBillingHelper.getInstance(context).queryAllPurchases();
                        intentsPublishRelay.accept(PsiCashAccountIntent.GetPsiCash.create(
                                tunnelStateFlowable));
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(requireActivity()).registerReceiver(broadcastReceiver, intentFilter);

        // Subscribe and render PsiCash view states
        compositeDisposable.add(
                psiCashAccountViewModel.states()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::render));

        psiCashAccountViewModel.processIntents(intents());

        // Linkify "create account" explanation text
        TextView tv = view.findViewById(R.id.psicash_create_account_explanation_text_id);
        Linkify.addLinks(tv, Linkify.WEB_URLS);

        // Hook up log into existing account click action
        loginBtn.setOnClickListener(v -> {
            psiCashAccountSignInDialog = new PsiCashAccountSignInDialog(requireActivity(), tunnelStateFlowable);
            psiCashAccountSignInDialog.show();
        });

        // Hook up create account click action
        createAccountBtn.setOnClickListener(v -> {
            if (createAccountUrl != null) {
                new PsiCashAccountWebViewDialog(requireActivity(), tunnelStateFlowable)
                        .load(createAccountUrl);
            }
        });
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

    @Override
    public void onResume() {
        super.onResume();
        // Get PsiCash updates when foregrounded and on tunnel state changes after
        Flowable<TunnelState> tunnelStateFlowable =
                ((PsiCashAccountActivity) requireActivity()).tunnelStateFlowable();
        psiCashUpdatesDisposable = tunnelStateFlowable
                .filter(tunnelState -> !tunnelState.isUnknown())
                .distinctUntilChanged()
                .doOnNext(__ -> intentsPublishRelay.accept(PsiCashAccountIntent.GetPsiCash.create(
                        tunnelStateFlowable)))
                .subscribe();

        compositeDisposable.add(psiCashUpdatesDisposable);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Stop getting PsiCash updates
        if (psiCashUpdatesDisposable != null) {
            psiCashUpdatesDisposable.dispose();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(requireActivity()).unregisterReceiver(broadcastReceiver);
        compositeDisposable.dispose();
    }

    @Override
    public Observable<PsiCashAccountIntent> intents() {
        return intentsPublishRelay
                .hide();
    }

    @Override
    public void render(PsiCashAccountViewState state) {
        FragmentActivity activity = getActivity();
        if (activity == null || activity.isFinishing() || !isAdded()) {
            return;
        }
        createAccountUrl = state.psiCashModel() == null ? null : state.psiCashModel().accountSignupUrl();

        updateUiProgressView(state);
        updateUiPsiCashError(state);

        UiHelpers.setEnabledControls(!state.psiCashTransactionInFlight(),
                requireView().findViewById(R.id.psicash_account_sign_in_layout));

        if (state.psiCashModel() == null) {
            return;
        }

        if (state.psiCashModel().hasTokens()) {
            if (state.psiCashModel().isAccount()) {
                if (psiCashAccountSignInDialog != null && psiCashAccountSignInDialog.isShowing()) {
                    psiCashAccountSignInDialog.close();
                }
                SingleViewEvent<String> notificationViewEvent = state.notificationViewEvent();
                if (notificationViewEvent != null) {
                    notificationViewEvent.consume((notificationMsg) -> {
                        try {
                            new AlertDialog.Builder(requireActivity())
                                    .setIcon(R.drawable.psicash_coin)
                                    .setTitle(requireContext().getString(R.string.psicash_generic_title))
                                    .setMessage(requireContext().getString(R.string.psicash_last_tracker_merge_notification))
                                    .setPositiveButton(R.string.label_ok, (dialog, which) -> {
                                    })
                                    .setCancelable(true)
                                    .setOnDismissListener(dialog -> dismissActivity())
                                    .show();
                        } catch (RuntimeException ignored) {
                        }
                    });
                } else {
                    dismissActivity();
                }
            }
        } else {
            if (!state.psiCashModel().isAccount()) {
                // PsiCash state is not initialized, we shouldn't be here, log and close the activity
                MyLog.w("PsiCashAccountFragment: PsiCash state is not initialized, closing the activity.");
                requireActivity().finish();
            }
        }
    }

    private void dismissActivity() {
        try {
            requireActivity().setResult(Activity.RESULT_OK);
            requireActivity().finish();
            requireActivity().overridePendingTransition(0, android.R.anim.fade_out);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateUiPsiCashError(PsiCashAccountViewState state) {
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
                MyLog.e("Unexpected PsiCash error: " + error.toString());
                errorMessage = getString(R.string.unexpected_error_occured_send_feedback_message);
            }
            try {
                new AlertDialog.Builder(requireActivity())
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(requireContext().getString(R.string.psicash_account_login_failed_title))
                        .setMessage(errorMessage)
                        .setPositiveButton(R.string.psicash_login_error_alert_dismiss, (dialog, which) -> {
                        })
                        .setOnDismissListener(__ -> clearPasswordField())
                        .setCancelable(true)
                        .show();
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void clearPasswordField() {
        if (psiCashAccountSignInDialog != null && psiCashAccountSignInDialog.isShowing()) {
            psiCashAccountSignInDialog.clearPasswordField();
        }
    }

    private void updateUiProgressView(PsiCashAccountViewState state) {
        if (state.psiCashTransactionInFlight()) {
            progressOverlay.setVisibility(View.VISIBLE);
        } else {
            progressOverlay.setVisibility(View.GONE);
        }
    }
}
