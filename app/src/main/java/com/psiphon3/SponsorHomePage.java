package com.psiphon3;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import java.util.Timer;
import java.util.TimerTask;

public class SponsorHomePage {
    public void setOnUrlClickListener(OnUrlClickListener onUrlClickListener) {
        this.onUrlClickListener = onUrlClickListener;
    }

    public void setOnTitleChangedListener(OnTitleChangedListener onTitleChangedListener) {
        this.onTitleChangedListener = onTitleChangedListener;
    }

    public interface OnUrlClickListener {
        void onUrl(String url);
    }

    public interface OnTitleChangedListener {
        void onTitle(String title);
    }

    private static class SponsorWebChromeClient extends WebChromeClient {
        private final ProgressBar progressBar;

        public SponsorWebChromeClient(ProgressBar progressBar) {
            super();
            this.progressBar = progressBar;
        }

        private boolean isStopped = false;

        public void stop() {
            isStopped = true;
        }

        @Override
        public void onProgressChanged(WebView webView, int progress) {
            if (isStopped) {
                return;
            }

            progressBar.setProgress(progress);
            progressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
        }
    }

    private class SponsorWebViewClient extends WebViewClient {
        private Timer mTimer;
        private boolean mWebViewLoaded = false;
        private boolean mStopped = false;

        public void stop() {
            mStopped = true;
            if (mTimer != null) {
                mTimer.cancel();
                mTimer = null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView webView, String url) {
            if (mStopped) {
                return true;
            }

            if (mTimer != null) {
                mTimer.cancel();
                mTimer = null;
            }

            if (mWebViewLoaded) {
                if (onUrlClickListener != null) {
                    onUrlClickListener.onUrl(url);
                }
            }
            return mWebViewLoaded;
        }

        @Override
        public void onPageFinished(WebView webView, String url) {
            if (mStopped) {
                return;
            }

            if (!mWebViewLoaded) {
                mTimer = new Timer();
                mTimer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (mStopped) {
                            return;
                        }
                        mWebViewLoaded = true;
                    }
                }, 2000);
            }
            if (onTitleChangedListener != null) {
                onTitleChangedListener.onTitle(webView.getTitle());
            }
        }
    }

    private final WebView webView;
    private final SponsorWebViewClient webViewClient;
    private final SponsorWebChromeClient webChromeClient;
    private final ProgressBar progressBar;
    private  OnUrlClickListener onUrlClickListener;
    private  OnTitleChangedListener onTitleChangedListener;

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public SponsorHomePage(WebView webView, ProgressBar progressBar) {
        this.webView = webView;
        this.progressBar = progressBar;
        webChromeClient = new SponsorWebChromeClient(this.progressBar);
        webViewClient = new SponsorWebViewClient();

        this.webView.setWebChromeClient(webChromeClient);
        this.webView.setWebViewClient(webViewClient);

        WebSettings webSettings = this.webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    }

    public void stop() {
        webViewClient.stop();
        webChromeClient.stop();
        webView.stopLoading();
    }

    public void load(String url) {
        progressBar.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    public void destroy() {
        webView.stopLoading();
        webView.destroy();
    }
}
