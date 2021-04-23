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
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.psiphon3.TunnelState;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;

public class PsiCashAccountWebViewDialog {
    private static final String customUserAgent = "customUserAgent";

    private final WebView webView;
    private final Dialog alertDialog;
    private Disposable tunnelStateDisposable;

    @SuppressLint("SetJavaScriptEnabled")
    public PsiCashAccountWebViewDialog(Context context, Flowable<TunnelState> tunnelStateFlowable) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View contentView = inflater.inflate(R.layout.psicash_acount_webview_layout, null);

        View progressOverlay = contentView.findViewById(R.id.progress_overlay);

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
        webView.getSettings().setUserAgentString(customUserAgent);

        // hook up progress visibility
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressOverlay.setVisibility(View.GONE);
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
