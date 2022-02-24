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

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class PsiCashAccountSignInDialog {
    private final Dialog dialog;
    private final Relay<PsiCashAccountIntent> intentsPublishRelay = PublishRelay.create();
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final View progressOverlay;
    private final View contentView;
    private String forgetAccountUrl;
    private TextInputEditText loginUsernameTv;
    private TextInputEditText loginPasswordTv;

    public PsiCashAccountSignInDialog(FragmentActivity fragmentActivity, Flowable<TunnelState> tunnelStateFlowable) {
        LayoutInflater inflater = LayoutInflater.from(fragmentActivity);
        contentView = inflater.inflate(R.layout.psicash_account_sign_in_dialog_layout, null);

        progressOverlay = contentView.findViewById(R.id.progress_overlay);

        FloatingActionButton floatingActionButton = contentView.findViewById(R.id.close_btn);

        View psiphonConnectingBlockingOverlay = contentView.findViewById(R.id.psiphon_connecting_blocking_overlay);
        // Give "Wait while connecting" blocking overlay dark gray background with 10% transparency
        psiphonConnectingBlockingOverlay.setBackgroundColor(Color.DKGRAY);
        psiphonConnectingBlockingOverlay.setAlpha(0.9f);

        dialog = new Dialog(fragmentActivity, R.style.Theme_NoTitleDialog);
        dialog.setCancelable(false);

        dialog.setContentView(contentView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setOnShowListener(dialogInterface -> {
            // Start observing tunnel state and close the dialog if
            // the tunnel goes offline
            compositeDisposable.add(tunnelStateFlowable
                    .filter(tunnelState -> !tunnelState.isUnknown())
                    .doOnNext(tunnelState -> {
                        if (!tunnelState.isRunning()) {
                            close();
                        } else {
                            if (tunnelState.connectionData().isConnected()) {
                                psiphonConnectingBlockingOverlay.setVisibility(View.GONE);
                            } else {
                                psiphonConnectingBlockingOverlay.setVisibility(View.VISIBLE);
                            }
                        }
                    })
                    .subscribe());

            PsiCashAccountViewModel psiCashAccountViewModel = new ViewModelProvider(fragmentActivity.getViewModelStore(),
                    new ViewModelProvider.AndroidViewModelFactory(fragmentActivity.getApplication()))
                    .get(PsiCashAccountViewModel.class);

            compositeDisposable.add(tunnelStateFlowable
                    .filter(tunnelState -> !tunnelState.isUnknown())
                    .distinctUntilChanged()
                    .doOnNext(__ -> intentsPublishRelay.accept(PsiCashAccountIntent.GetPsiCash.create(
                            tunnelStateFlowable)))
                    .subscribe());

            Button loginBtn = contentView.findViewById(R.id.psicash_account_login_btn);
            loginUsernameTv = contentView.findViewById(R.id.psicash_account_username_textview);
            loginPasswordTv = contentView.findViewById(R.id.psicash_account_password_textview);

            // Hook up login button
            loginBtn.setOnClickListener(v -> {
                submitForm(tunnelStateFlowable);
                InputMethodManager imm = (InputMethodManager) fragmentActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            });

            // Hook up keyboard "done" action
            loginPasswordTv.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitForm(tunnelStateFlowable);
                }
                return false;
            });

            // Subscribe and render PsiCash view states
            compositeDisposable.add(
                    psiCashAccountViewModel.states()
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(this::render));

            psiCashAccountViewModel.processIntents(intentsPublishRelay);
        });

        dialog.setOnDismissListener(dialog -> {
            compositeDisposable.dispose();
        });


        floatingActionButton.setOnClickListener(view -> close());


        TextView forgotAccountTv = contentView.findViewById(R.id.psicash_account_forgot_account_tv);
        // Make forgot credentials text look like an HTML link
        CharSequence charSequence = forgotAccountTv.getText();
        SpannableString spannableString = new SpannableString(charSequence);
        spannableString.setSpan(new URLSpan(""), 0, spannableString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        forgotAccountTv.setText(spannableString, TextView.BufferType.SPANNABLE);

        // Hook up forgot credentials click action
        forgotAccountTv.setOnClickListener(v -> {
            if (forgetAccountUrl != null) {
                try {
                    new PsiCashAccountWebViewDialog(fragmentActivity, tunnelStateFlowable)
                            .load(forgetAccountUrl);
                } catch (RuntimeException ignored) {
                }
            }
        });
    }

    private void render(PsiCashAccountViewState state) {
        if (state == null) {
            return;
        }
        forgetAccountUrl = state.psiCashModel() == null ? null : state.psiCashModel().accountSignupUrl();

        progressOverlay.setVisibility(state.psiCashTransactionInFlight() ?
                View.VISIBLE :
                View.GONE);

        UiHelpers.setEnabledControls(!state.psiCashTransactionInFlight(),
                contentView.findViewById(R.id.psicash_login_layout));
    }

    private void submitForm(Flowable<TunnelState> tunnelStateFlowable) {
        intentsPublishRelay.accept(PsiCashAccountIntent.LoginAccount.create(
                tunnelStateFlowable,
                loginUsernameTv.getText().toString(),
                loginPasswordTv.getText().toString()));
        loginUsernameTv.clearFocus();
        loginPasswordTv.clearFocus();
    }


    public void show() {
        dialog.show();

        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    void close() {
        if (dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void clearPasswordField() {
        loginPasswordTv.getText().clear();
    }
}
