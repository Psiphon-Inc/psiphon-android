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

package com.psiphon3.psicash.account;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.net.http.SslError;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.psiphon3.TunnelState;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;

public class PsiCashAccountWebViewDialog {
    private final WebView webView;
    private final Dialog alertDialog;
    private Disposable tunnelStateDisposable;

    @SuppressLint("SetJavaScriptEnabled")
    public PsiCashAccountWebViewDialog(Context context, Flowable<TunnelState> tunnelStateFlowable) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View contentView = inflater.inflate(R.layout.psicash_acount_webview_layout, null);

        View progressOverlay = contentView.findViewById(R.id.progress_overlay);
        FloatingActionButton floatingActionButton = contentView.findViewById(R.id.close_btn);


        alertDialog = new Dialog(context, R.style.Theme_AppCompat_Dialog) {
            // Hook up minimal web view navigation
            @Override
            public void onBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    super.onBackPressed();
                }
            }
        };
        alertDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        alertDialog.setCancelable(false);

        webView = new WebView(context) {
            @Override
            public boolean onCheckIsTextEditor() {
                return true;
            }
        };

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            // hook up progress and 'close' button visibility
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressOverlay.setVisibility(View.GONE);
                floatingActionButton.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                showErrorClosePrompt(view.getContext());
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                showErrorClosePrompt(view.getContext());
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                super.onReceivedSslError(view, handler, error);
                showErrorClosePrompt(view.getContext());
            }
        });

        // hook up window.close()
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                super.onCloseWindow(window);
                alertDialog.dismiss();
            }
        });

        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ((LinearLayout) contentView.findViewById(R.id.webview_container_layout)).addView(webView);

        alertDialog.addContentView(contentView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        alertDialog.setOnShowListener(dialog ->
                // Start observing tunnel state and close the dialog if
                // the tunnel goes offline
                tunnelStateDisposable = tunnelStateFlowable
                        .filter(tunnelState -> !tunnelState.isUnknown())
                        .doOnNext(tunnelState -> {
                            if (!tunnelState.isRunning() ||
                                    !tunnelState.connectionData().isConnected()) {
                                dialog.dismiss();
                            }
                        })
                        .subscribe());

        alertDialog.setOnDismissListener(dialog -> {
            webView.stopLoading();
            if (tunnelStateDisposable != null) {
                tunnelStateDisposable.dispose();
            }
        });


        floatingActionButton.setOnClickListener(view -> {
            try {
                new AlertDialog.Builder(context)
                        .setIcon(R.drawable.psicash_coin)
                        .setTitle(R.string.psicash_webview_close_title)
                        .setCancelable(true)
                        .setMessage(R.string.psicash_webview_close_message)
                        .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                        })
                        .setPositiveButton(R.string.lbl_yes, (dialog, which) ->
                                alertDialog.dismiss())
                        .show();
            } catch (RuntimeException ignored) {
            }
        });

    }

    private void showErrorClosePrompt(Context context) {
        new AlertDialog.Builder(context)
                .setIcon(R.drawable.psicash_coin)
                .setTitle(R.string.psicahs_webview_error_alert_title)
                .setMessage(R.string.psicash_webview_error_alert_message)
                .setPositiveButton(R.string.Commons_Ok, (dialog, which) -> alertDialog.dismiss())
                .show();
    }

    public void load(String url) {
        webView.loadUrl(url);
        alertDialog.show();

        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(alertDialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        alertDialog.getWindow().setAttributes(lp);
    }
}
