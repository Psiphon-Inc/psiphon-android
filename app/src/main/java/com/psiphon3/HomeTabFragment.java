package com.psiphon3;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.PsiCashFragment;
import com.psiphon3.psicash.PsiCashSubscribedFragment;
import com.psiphon3.psicash.PsiCashViewModel;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.subscription.R;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;

public class HomeTabFragment extends Fragment {
    private static boolean seenHandshake = false;
    private MainActivityViewModel viewModel;
    private View mainView;
    private SponsorHomePage sponsorHomePage;
    private boolean isWebViewLoaded = false;
    private View rateLimitedTextSection;
    private TextView rateLimitedText;
    private TextView rateUnlimitedText;
    private Button rateLimitUpgradeButton;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private BroadcastReceiver broadcastReceiver;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.home_tab_layout, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mainView = view;

        rateLimitedTextSection = view.findViewById(R.id.rateLimitedTextSection);
        rateLimitedText = view.findViewById(R.id.rateLimitedText);
        rateUnlimitedText = view.findViewById(R.id.rateUnlimitedText);
        rateLimitUpgradeButton = view.findViewById(R.id.rateLimitUpgradeButton);

        viewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(MainActivityViewModel.class);

        // Observe tunnel state for CONNECTED status and load sponsor home pages in the embedded web view if needed
        compositeDisposable.add(viewModel.tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                // Check for URLs to be opened in the embedded web view.
                .doOnNext(tunnelState -> {
                    // If the tunnel is either stopped or running but not connected
                    // then stop loading the sponsor page and flip to status view.
                    if (tunnelState.isStopped() ||
                            (tunnelState.isRunning() && !tunnelState.connectionData().isConnected())) {
                        if (sponsorHomePage != null) {
                            sponsorHomePage.stop();
                        }
                        // Also reset isWebViewLoaded
                        isWebViewLoaded = false;
                    }
                })
                // Only pass through if tunnel is running and connected
                .filter(tunnelState -> tunnelState.isRunning() && tunnelState.connectionData().isConnected())
                .flatMap(tunnelState -> {
                    ArrayList<String> homePages = tunnelState.connectionData().homePages();
                    if (homePages == null || homePages.size() == 0) {
                        // There's no URL to load
                        return Flowable.empty();
                    }
                    String url = homePages.get(0);

                    // Unlike non-Pro clients we want to load the home page ONLY when the handshake completes
                    // because the embedded web view is shown not as a part of the tab but in a popup.
                    // Showing the popup every time when the main activity is created by clicking running tunnel
                    // service notification would be a bad UX.
                    if (!seenHandshake ||
                            isWebViewLoaded ||
                            !MainActivity.shouldLoadInEmbeddedWebView(url)) {
                        // There either was no handshake or the embedded view has loaded the URL
                        // already or the URL should not be loaded in the embedded view
                        return Flowable.empty();
                    }
                    setSeenHandshake(false);
                    // Pass the URL downstream to be loaded in the embedded web view
                    return Flowable.just(url);
                })
                .doOnNext(this::loadEmbeddedWebView)
                .subscribe());

        PsiCashViewModel psiCashViewModel = new ViewModelProvider(requireActivity(),
                new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                .get(PsiCashViewModel.class);

        GooglePlayBillingHelper googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(requireContext());

        // Observe subscription and speed boost states and update rate limit badge and 'Subscribe' button UI
        compositeDisposable.add(Observable.combineLatest(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .distinctUntilChanged()
                        .toObservable(),
                psiCashViewModel.booleanActiveSpeedBoostObservable(),
                ((BiFunction<SubscriptionState, Boolean, Pair>) Pair::new))
                .distinctUntilChanged()
                .map(pair -> {
                    SubscriptionState subscriptionState = (SubscriptionState) pair.first;
                    Boolean hasActiveSpeedBoost = (Boolean) pair.second;
                    switch (subscriptionState.status()) {
                        case HAS_UNLIMITED_SUBSCRIPTION:
                        case HAS_TIME_PASS:
                            return RateLimitMode.UNLIMITED_SUBSCRIPTION;
                        case HAS_LIMITED_SUBSCRIPTION:
                            return RateLimitMode.LIMITED_SUBSCRIPTION;
                        default:
                            return hasActiveSpeedBoost ?
                                    RateLimitMode.SPEED_BOOST : RateLimitMode.AD_MODE_LIMITED;
                    }
                })
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::setRateLimitUI)
                .subscribe());

        // Observe subscription state and set ad container layout visibility,
        // also set the appropriate PsiCash fragment
        compositeDisposable.add(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .distinctUntilChanged()
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(this::setPsiCashFragment)
                        .subscribe());

        // Listen to GOT_NEW_EXPIRING_PURCHASE intent from PsiCash module
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equals(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE)) {
                        viewModel.restartTunnelService(false);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(broadcastReceiver);
        if (sponsorHomePage != null) {
            sponsorHomePage.stop();
        }
    }

    enum RateLimitMode {AD_MODE_LIMITED, LIMITED_SUBSCRIPTION, UNLIMITED_SUBSCRIPTION, SPEED_BOOST}

    private void setRateLimitUI(RateLimitMode rateLimitMode) {
        // Update UI elements showing the current speed.
        if (rateLimitMode == RateLimitMode.UNLIMITED_SUBSCRIPTION) {
            rateLimitedText.setVisibility(View.GONE);
            rateUnlimitedText.setVisibility(View.VISIBLE);
            rateLimitUpgradeButton.setVisibility(View.GONE);
            rateLimitedTextSection.setVisibility(View.VISIBLE);
        } else{
            if(rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
            } else if (rateLimitMode == RateLimitMode.LIMITED_SUBSCRIPTION) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
            } else if (rateLimitMode == RateLimitMode.SPEED_BOOST) {
                rateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
            }
            rateLimitedText.setVisibility(View.VISIBLE);
            rateUnlimitedText.setVisibility(View.GONE);
            rateLimitUpgradeButton.setVisibility(View.VISIBLE);
            rateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }

    private void setPsiCashFragment(SubscriptionState subscriptionState) {
        // Do nothing if host activity is finishing or destroyed
        if (requireActivity().isFinishing() ||
                // isDestroyed() is API 17+
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && requireActivity().isDestroyed())) {
            return;
        }
        // Do nothing if not added to activity, otherwise getParentFragmentManager will
        // throw IllegalStateException
        if (!isAdded()) {
            return;
        }
        FragmentTransaction transaction = getParentFragmentManager()
                .beginTransaction()
                .setCustomAnimations(android.R.animator.fade_in, android.R.animator.fade_out)
                .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        if (subscriptionState.hasValidPurchase()) {
            transaction.replace(R.id.psicash_fragment_container, new PsiCashSubscribedFragment());
        } else {
            transaction.replace(R.id.psicash_fragment_container, new PsiCashFragment(), "PsiCashFragment");
        }
        // Allow transaction to be committed even after FragmentManager has saved its state.
        // In case the host activity is killed and re-created this function will be called again
        // with the most up to date subscription state data.
        transaction.commitAllowingStateLoss();
    }

    private void loadEmbeddedWebView(String url) {
        isWebViewLoaded = true;

        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View webViewContainer = inflater.inflate(R.layout.embedded_webview_layout, null);
        final WebView webView = webViewContainer.findViewById(R.id.sponsorWebView);
        final ProgressBar progressBar = webViewContainer.findViewById(R.id.sponsorWebViewProgressBar);

        sponsorHomePage = new SponsorHomePage(webView, progressBar);
        sponsorHomePage.load(url);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setView(webViewContainer);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            dialog.dismiss();
        });

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialogInterface -> {
            webView.loadUrl("about:blank");
            ((ViewGroup)webViewContainer.getParent()).removeView(webViewContainer);
        });

        alertDialog.show();
    }

    public static void setSeenHandshake(boolean b) {
        HomeTabFragment.seenHandshake = b;
    }

    protected class SponsorHomePage {
        private class SponsorWebChromeClient extends WebChromeClient {
            private final ProgressBar mProgressBar;

            public SponsorWebChromeClient(ProgressBar progressBar) {
                super();
                mProgressBar = progressBar;
            }

            private boolean mStopped = false;

            public void stop() {
                mStopped = true;
            }

            @Override
            public void onProgressChanged(WebView webView, int progress) {
                if (mStopped) {
                    return;
                }

                mProgressBar.setProgress(progress);
                mProgressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
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
                    viewModel.signalExternalBrowserUrl(url);
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
            }
        }

        private final WebView mWebView;
        private final SponsorWebViewClient mWebViewClient;
        private final SponsorWebChromeClient mWebChromeClient;
        private final ProgressBar mProgressBar;

        @TargetApi(Build.VERSION_CODES.HONEYCOMB)
        public SponsorHomePage(WebView webView, ProgressBar progressBar) {
            mWebView = webView;
            mProgressBar = progressBar;
            mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
            mWebViewClient = new SponsorWebViewClient();

            mWebView.setWebChromeClient(mWebChromeClient);
            mWebView.setWebViewClient(mWebViewClient);

            WebSettings webSettings = mWebView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setUseWideViewPort(true);
        }

        public void stop() {
            mWebViewClient.stop();
            mWebChromeClient.stop();
        }

        public void load(String url) {
            mProgressBar.setVisibility(View.VISIBLE);
            mWebView.loadUrl(url);
        }
    }
}
