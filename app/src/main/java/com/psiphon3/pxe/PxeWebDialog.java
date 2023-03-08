package com.psiphon3.pxe;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.psiphon3.R;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.FeedbackWorker;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelManager;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Locale;

import io.reactivex.disposables.Disposable;

public class PxeWebDialog {
    private final WebView webView;
    private final Dialog dialog;
    private Disposable tunnelStateDisposable;

    private static final long NEXT_PXE_PERIOD_MILLIS = 7 * 24 * 60 * 60 * 1000L; // 1 week

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public PxeWebDialog(LocalizedActivities.AppCompatActivity activity, ArrayList<String> homePages) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View contentView = inflater.inflate(R.layout.pxe_webview_dialog_layout, null);

        View progressOverlay = contentView.findViewById(R.id.progress_overlay);
        // Give progress overlay an opaque background matching the web page's
        progressOverlay.setBackgroundColor(Color.parseColor("#F8F8F8"));
        progressOverlay.setAlpha(1.0f);

        View psiphonConnectingBlockingOverlay = contentView.findViewById(R.id.psiphon_connecting_blocking_overlay);
        // Give "Wait while connecting" blocking overlay dark gray background with 10% transparency
        psiphonConnectingBlockingOverlay.setBackgroundColor(Color.DKGRAY);
        psiphonConnectingBlockingOverlay.setAlpha(0.9f);

        webView = new WebView(activity) {
            @Override
            public boolean onCheckIsTextEditor() {
                return true;
            }
        };
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        final PxeJavascriptInterface pxeJavascriptInterface =
                new PxeJavascriptInterface(activity);
        webView.addJavascriptInterface(pxeJavascriptInterface, "Pxe");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            // hook up progress visibility
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressOverlay.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(view.getUrl())) {
                    close();
                }
            }

            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    close();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (activity == null || activity.isFinishing()) {
                    return false;
                }
                WebView newWebView = new WebView(activity);
                newWebView.setWebViewClient(new WebViewClient() {
                    // Try and open new url in an external browser
                    void handleUri(Uri uri) {
                        try {
                            final Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            activity.startActivity(intent);
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

        dialog = new Dialog(activity, R.style.Theme_NoTitleDialog_Transparent);
        dialog.setCancelable(false);
        dialog.addContentView(contentView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setOnShowListener(dialog ->
                // Start observing tunnel state and close the dialog if
                // the tunnel goes offline
                tunnelStateDisposable = activity.getTunnelServiceInteractor().tunnelStateFlowable()
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

        // Hook up close btn
        contentView.findViewById(R.id.close_btn).setOnClickListener(view -> {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            try {
                new AlertDialog.Builder(activity)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.close_alert_title)
                        .setCancelable(false)
                        .setMessage(R.string.close_alert_message)
                        .setNegativeButton(R.string.lbl_no, null)
                        .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                            activity.getTunnelServiceInteractor()
                                    .tunnelStateFlowable()
                                    .firstOrError()
                                    .doOnSuccess(tunnelState -> {
                                        if (homePages != null &&
                                                homePages.size() > 0 &&
                                                tunnelState.isRunning() &&
                                                tunnelState.connectionData().isConnected()) {
                                            sendHomePagesIntent(activity, homePages);
                                        }
                                        close();
                                    })
                                    .subscribe();
                        })
                        .show();
            } catch (RuntimeException ignored) {
            }
        });
    }

    public void load(String url, String clientRegion) {
        // add `lang` and `dataCollectionInfoURL` query parameters
        Locale locale = Locale.getDefault();
        Uri.Builder builder = Uri.parse(url)
                .buildUpon()
                .appendQueryParameter("clientRegion", clientRegion)
                .appendQueryParameter("lang", toBcp47Language(locale))
                .appendQueryParameter("dataCollectionInfoURL", EmbeddedValues.DATA_COLLECTION_INFO_URL);

        webView.loadUrl(builder.toString());
        dialog.show();

        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    private void sendHomePagesIntent(Context ctx, ArrayList<String> homePages) {
        if (homePages == null || homePages.size() == 0) {
            return;
        }
        Intent intent = new Intent();
        ComponentName intentComponentName = new ComponentName(ctx, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        intent.setComponent(intentComponentName);
        intent.setAction(TunnelManager.INTENT_ACTION_HANDSHAKE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Bundle data = new Bundle();
        data.putStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES, homePages);
        intent.putExtras(data);
        ctx.startActivity(intent);
    }

    private void close() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    private static String toBcp47Language(@NonNull Locale loc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return loc.toLanguageTag();
        }

        final char SEP = '-';       // we will use a dash as per BCP 47
        String language = loc.getLanguage();
        String region = loc.getCountry();
        String variant = loc.getVariant();

        // special case for Norwegian Nynorsk since "NY" cannot be a variant as per BCP 47
        // this goes before the string matching since "NY" wont pass the variant checks
        if (language.equals("no") && region.equals("NO") && variant.equals("NY")) {
            language = "nn";
            region = "NO";
            variant = "";
        }

        if (language.isEmpty() || !language.matches("\\p{Alpha}{2,8}")) {
            language = "und";       // Follow the Locale#toLanguageTag() implementation
            // which says to return "und" for Undetermined
        } else if (language.equals("iw")) {
            language = "he";        // correct deprecated "Hebrew"
        } else if (language.equals("in")) {
            language = "id";        // correct deprecated "Indonesian"
        } else if (language.equals("ji")) {
            language = "yi";        // correct deprecated "Yiddish"
        }

        // ensure valid country code, if not well formed, it's omitted
        if (!region.matches("\\p{Alpha}{2}|\\p{Digit}{3}")) {
            region = "";
        }

        // variant subtags that begin with a letter must be at least 5 characters long
        if (!variant.matches("\\p{Alnum}{5,8}|\\p{Digit}\\p{Alnum}{3}")) {
            variant = "";
        }

        StringBuilder bcp47Tag = new StringBuilder(language);
        if (!region.isEmpty()) {
            bcp47Tag.append(SEP).append(region);
        }
        if (!variant.isEmpty()) {
            bcp47Tag.append(SEP).append(variant);
        }

        return bcp47Tag.toString();
    }

    private class PxeJavascriptInterface {
        private final Context ctx;

        public PxeJavascriptInterface(Context ctx) {
            this.ctx = ctx;
        }

        @JavascriptInterface
        public void submitResult(String url, String resultsJson) {
            final AppPreferences mp = new AppPreferences(ctx.getApplicationContext());
            mp.put(TunnelManager.NEXT_ALLOW_PXE_SHOW_TIME_MILLIS, System.currentTimeMillis() + NEXT_PXE_PERIOD_MILLIS);
            Data inputData = FeedbackWorker.generatePxeInputData(url, resultsJson);
            Constraints.Builder constraintsBuilder = new Constraints.Builder();
            constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED);

            OneTimeWorkRequest experimentResultsUpload =
                    new OneTimeWorkRequest.Builder(FeedbackWorker.class)
                            .setInputData(
                                    inputData
                            )
                            .setConstraints(
                                    constraintsBuilder.build()
                            )
                            .addTag("pxe_experiment_results_upload")
                            .build();

            MyLog.i("PxeWebDialog: user submitted experiment results");

            WorkManager.getInstance(ctx.getApplicationContext())
                    .enqueueUniqueWork("pxe_upload",
                            // Do not schedule a new upload if there's a pending(uncompleted)
                            // experiment results upload.
                            ExistingWorkPolicy.KEEP,
                            experimentResultsUpload);

            close();
            Toast.makeText(ctx, R.string.pxe_submit_thank_you, Toast.LENGTH_LONG).show();
        }
    }
}
