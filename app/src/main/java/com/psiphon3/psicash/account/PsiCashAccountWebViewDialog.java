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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.psiphon3.TunnelState;
import com.psiphon3.subscription.R;

import java.util.Vector;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;

public class PsiCashAccountWebViewDialog {
    private final WebView webView;
    private final Dialog dialog;
    private final Vector<AlertDialog> alertDialogs = new Vector<>();
    private Disposable tunnelStateDisposable;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public PsiCashAccountWebViewDialog(FragmentActivity fragmentActivity, Flowable<TunnelState> tunnelStateFlowable) {
        LayoutInflater inflater = LayoutInflater.from(fragmentActivity);
        View contentView = inflater.inflate(R.layout.psicash_account_webview_dialog_layout, null);

        View progressOverlay = contentView.findViewById(R.id.progress_overlay);
        // Give progress overlay an opaque background matching the accounts web page's
        progressOverlay.setBackgroundColor(Color.parseColor("#F8F8F8"));
        progressOverlay.setAlpha(1.0f);

        FloatingActionButton floatingActionButton = contentView.findViewById(R.id.close_btn);

        View psiphonConnectingBlockingOverlay = contentView.findViewById(R.id.psiphon_connecting_blocking_overlay);
        // Give "Wait while connecting" blocking overlay dark gray background with 10% transparency
        psiphonConnectingBlockingOverlay.setBackgroundColor(Color.DKGRAY);
        psiphonConnectingBlockingOverlay.setAlpha(0.9f);

        dialog = new Dialog(fragmentActivity, R.style.Theme_NoTitleDialog_Transparent) {
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
        dialog.setCancelable(false);

        webView = new WebView(fragmentActivity) {
            @Override
            public boolean onCheckIsTextEditor() {
                return true;
            }
        };

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportMultipleWindows(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        final PsiCashLocalStorageJavascriptInterface psiCashLocalStorageJavascriptInterface =
                new PsiCashLocalStorageJavascriptInterface(fragmentActivity);
        webView.addJavascriptInterface(psiCashLocalStorageJavascriptInterface, "PsiCashLocalStorage");

        // Android's WebView doesn't give us a per origin control over the DOM storage, so we are
        // overriding native storage with custom implementation for better control over clearing our
        // own data when the user logs out of PsiCash account. The other alternative is to use global
        // WebViewStorage.deleteAllData() which is much less desirable since it will potentially
        // affect all app's WebViews, which may be bad for ads performance, etc.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                webView.loadUrl(
                        "javascript:(function() {" +
                                "Object.defineProperty(PsiCashLocalStorage, 'length', {get: function(){return this.getLength()}});" +
                                "Object.defineProperty(window, 'localStorage', {value: PsiCashLocalStorage,configurable: false});" +
                                "})()"
                );
            }

            @Override
            // hook up progress visibility
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                    showErrorClosePrompt();
                }
            }

            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showErrorClosePrompt();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // hook up window.close()
            @Override
            public void onCloseWindow(WebView window) {
                super.onCloseWindow(window);
                close();
            }

            // hook up open in new window
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (fragmentActivity == null || fragmentActivity.isFinishing()) {
                    return false;
                }
                WebView newWebView = new WebView(fragmentActivity);
                newWebView.setWebViewClient(new WebViewClient() {
                    // Try and open new url in an external browser
                    void handleUri(Uri uri) {
                        try {
                            final Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            fragmentActivity.startActivity(intent);
                        } catch (ActivityNotFoundException ignored) {
                        }
                    }

                    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        handleUri(request.getUrl());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, String url) {
                        // avoid potentially calling this twice
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                            final Uri uri = Uri.parse(url);
                            handleUri(uri);
                        }
                        return true;
                    }
                });

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newWebView);
                resultMsg.sendToTarget();

                return true;
            }
        });

        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        ((LinearLayout) contentView.findViewById(R.id.webview_container_layout)).addView(webView);

        dialog.addContentView(contentView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setOnShowListener(dialog ->
                // Start observing tunnel state and close the dialog if
                // the tunnel goes offline
                tunnelStateDisposable = tunnelStateFlowable
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

        dialog.setOnDismissListener(dialog -> {
            webView.stopLoading();
            webView.destroy();
            if (tunnelStateDisposable != null) {
                tunnelStateDisposable.dispose();
            }
        });


        floatingActionButton.setOnClickListener(view -> {
            if (fragmentActivity == null || fragmentActivity.isFinishing()) {
                return;
            }
            try {
                alertDialogs.add(new AlertDialog.Builder(fragmentActivity)
                        .setIcon(R.drawable.psicash_coin)
                        .setTitle(R.string.psicash_webview_close_alert_title)
                        .setCancelable(true)
                        .setMessage(R.string.psicash_webview_close_alert_message)
                        .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                        })
                        .setPositiveButton(R.string.lbl_yes, (dialog, which) -> close())
                        .show());
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void showErrorClosePrompt() {
        Activity activity = dialog.getOwnerActivity();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            alertDialogs.add(new AlertDialog.Builder(activity)
                    .setIcon(R.drawable.psicash_coin)
                    .setTitle(R.string.psicash_webview_error_alert_title)
                    .setMessage(R.string.psicash_webview_error_alert_message)
                    .setPositiveButton(R.string.label_ok, (dialog, which) -> {
                    })
                    .setOnDismissListener(dialog -> close())
                    .show());
        } catch (RuntimeException ignored) {
        }
    }

    public void load(String url) {
        webView.loadUrl(url);
        dialog.show();

        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    private void close() {
        for (AlertDialog alertDialog : alertDialogs) {
            if (alertDialog.isShowing()) {
                alertDialog.dismiss();
            }
        }
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static class PsiCashLocalStorageJavascriptInterface {
        SharedPreferences sharedPreferences;

        public PsiCashLocalStorageJavascriptInterface(Context context) {
            sharedPreferences = context.getSharedPreferences(
                    context.getString(R.string.psicashWebStorage), Context.MODE_PRIVATE);
        }

        @JavascriptInterface
        public String getItem(String key) {
            if (key == null) {
                return "";
            }
            String value = sharedPreferences.getString(key, "");
            return value;
        }

        @JavascriptInterface
        public void setItem(String key, String value) {
            if (key == null) {
                return;
            }
            sharedPreferences.edit().putString(key, value).apply();
        }

        @JavascriptInterface
        public void removeItem(String key) {
            if (key == null) {
                return;
            }
            sharedPreferences.edit().remove(key).apply();
        }

        @JavascriptInterface
        public void clear() {
            sharedPreferences.edit().clear().apply();
        }

        @JavascriptInterface
        public int getLength() {
            return sharedPreferences.getAll().size();
        }
    }
}
