/*
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

package com.psiphon3;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBar.LayoutParams;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.PsiCashFragment;
import com.psiphon3.psicash.PsiCashStoreActivity;
import com.psiphon3.psicash.PsiCashSubscribedFragment;
import com.psiphon3.psicash.PsiCashViewModel;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.VpnAppsUtils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;


public class StatusActivity extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase {
    public static final String ACTION_SHOW_GET_HELP_DIALOG = "com.psiphon3.StatusActivity.SHOW_GET_HELP_CONNECTING_DIALOG";

    private View mRateLimitedTextSection;
    private TextView mRateLimitedText;
    private TextView mRateUnlimitedText;
    private Button mRateLimitSubscribeButton;

    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_firstRun = true;
    private PsiphonAdManager psiphonAdManager;
    private boolean disableInterstitialOnNextTabChange;
    private Disposable autoStartDisposable;

    private GooglePlayBillingHelper googlePlayBillingHelper;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    private View embeddedWebView;


    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        googlePlayBillingHelper.startIab();

        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        // Add version label to the right side of action bar
        ActionBar actionBar = getSupportActionBar();
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        View customView = LayoutInflater.from(this).inflate(R.layout.toolbar_version_layout, null);
        TextView versionLabel = customView.findViewById(R.id.toolbar_version_label);
        versionLabel.setText(String.format(Locale.US, "v. %s", EmbeddedValues.CLIENT_VERSION));
        actionBar.setCustomView(customView, lp);
        actionBar.setDisplayShowCustomEnabled(true);

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_tabsScrollView = (HorizontalScrollView) findViewById(R.id.tabsScrollView);
        m_tabSpecsList = new ArrayList<>();
        m_toggleButton = (Button)findViewById(R.id.toggleButton);
        m_connectionProgressBar = findViewById(R.id.connectionProgressBar);

        mRateLimitedTextSection = findViewById(R.id.rateLimitedTextSection);
        mRateLimitedText = (TextView)findViewById(R.id.rateLimitedText);
        mRateUnlimitedText = (TextView)findViewById(R.id.rateUnlimitedText);
        mRateLimitSubscribeButton = (Button)findViewById(R.id.rateLimitUpgradeButton);

        LayoutInflater inflater = (LayoutInflater)getBaseContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        embeddedWebView = inflater.inflate(R.layout.embedded_webview_layout, null);

        // Update rate limit badge and 'Subscribe' button UI
        PsiCashViewModel psiCashViewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(PsiCashViewModel.class);
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
                .subscribe()
        );

        // Set ad container layout visibility and set the appropriate PsiCash fragment
        compositeDisposable.add(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .distinctUntilChanged()
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(this::setAdBannerPlaceholderVisibility)
                        .doOnNext(this::setPsiCashFragment)
                        .subscribe()
        );

        // ads
        psiphonAdManager = new PsiphonAdManager(this, findViewById(R.id.largeAdSlot),
                () -> onSubscribeButtonClick(null), tunnelServiceInteractor.tunnelStateFlowable());
        psiphonAdManager.startLoadingAds();

        setupActivityLayout();

        HandleCurrentIntent();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("isFirstRun", m_firstRun);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        m_firstRun = savedInstanceState.getBoolean("isFirstRun");
    }

    private void preventAutoStart() {
        m_firstRun = false;
    }

    private boolean shouldAutoStart() {
        return m_firstRun &&
                !getIntent().getBooleanExtra(INTENT_EXTRA_PREVENT_AUTO_START, false);
    }

    // Returns an object only if tunnel should be auto-started,
    // completes with no value otherwise.
    private Maybe<Object> autoStartMaybe(boolean isFirstRun) {
        // If service is running we shouldn't auto-start, complete immediately
        if (tunnelServiceInteractor.isServiceRunning(getApplicationContext())) {
            return Maybe.empty();
        }

        // If not the first app run then do not auto-start
        if (!isFirstRun) {
            return Maybe.empty();
        }

        // If this is a first app run then check subscription state and
        // return a value if user has a valid purchase or if IAB check failed,
        // the IAB status check will be triggered again in onResume
        return googlePlayBillingHelper.subscriptionStateFlowable()
                .firstOrError()
                .flatMapMaybe(subscriptionState -> {
                    if (subscriptionState.hasValidPurchase()
                            || subscriptionState.status() == SubscriptionState.Status.IAB_FAILURE) {
                        return Maybe.just(new Object());
                    }
                    return Maybe.empty();
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllPurchases();
        googlePlayBillingHelper.queryAllSkuDetails();

        // Notify tunnel service if it is running so it may trigger purchase check and
        // upgrade current connection if there is a new valid subscription purchase.
        tunnelServiceInteractor.onResume();

        boolean isFirstRun = shouldAutoStart();
        preventAutoStart();

        if (autoStartDisposable == null || autoStartDisposable.isDisposed()) {
            autoStartDisposable = autoStartMaybe(isFirstRun)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(__ -> doStartUp())
                    .subscribe();
            compositeDisposable.add(autoStartDisposable);
        }
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // If the app is already foreground (so onNewIntent is being called),
        // the incoming intent is not automatically set as the activity's intent
        // (i.e., the intent returned by getIntent()). We want this behaviour,
        // so we'll set it explicitly.
        setIntent(intent);

        // Handle explicit intent that is received when activity is already running
        HandleCurrentIntent();
    }

    @Override
    public void onTabChanged(String tabId) {
        if(mayTriggerInterstitial(tabId)) {
            psiphonAdManager.onTabChanged();
        }
        super.onTabChanged(tabId);
    }

    private boolean mayTriggerInterstitial(String tabId) {
        if(disableInterstitialOnNextTabChange) {
            disableInterstitialOnNextTabChange = false;
            return false;
        }
        if(tabId.equals(PSICASH_TAB_TAG)) {
            return false;
        }
        return true;
    }

    protected void HandleCurrentIntent() {
        Intent intent = getIntent();
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // Handle special case - external Android App Link intent which opens PsiCashStoreActivity
        // when the user navigates to https://mobile.psi.cash/android or psiphon://psicash
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            if (isPsiCashIntentUri(intent.getData())) {
                PsiCashFragment.openPsiCashStoreActivity(this,
                        getResources().getInteger(R.integer.psiCashTabIndex));
                return;
            }
        }

        // StatusActivity is exposed to other apps because it is declared as an entry point activity of the app in the manifest.
        // For the purpose of handling internal intents, such as handshake, etc., from the tunnel service we have declared a not
        // exported activity alias 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler' that should act as a proxy for StatusActivity.
        // We expect our own intents have a component set to 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler', all other intents
        // should be ignored.
        ComponentName tunnelIntentsActivityComponentName = new ComponentName(this, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        if (!tunnelIntentsActivityComponentName.equals(intent.getComponent())) {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
            Bundle data = intent.getExtras();
            if(data != null) {
                ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
                if (homePages != null && homePages.size() > 0) {
                    String url = homePages.get(0);
                    // At this point we're showing the URL in either the embedded webview or in a browser.
                    // Some URLs are excluded from being embedded as home pages.
                    if (shouldLoadInEmbeddedWebView(url)) {
                        boolean isVpn = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_VPN, false);
                        int httpProxyPort = isVpn ? 0 : data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT, 0);
                        loadInEmbeddedWebView(url, httpProxyPort);
                    } else {
                        displayBrowser(this, url);
                    }
                }
            }

            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // Switch to settings tab
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(SETTINGS_TAB_TAG);

            // Set egress region preference to 'Best Performance'
            updateEgressRegionPreference(PsiphonConstants.REGION_CODE_ANY);

            // Set region selection to 'Best Performance' too
            m_regionSelector.setSelectionByValue(PsiphonConstants.REGION_CODE_ANY);

            // Show "Selected region unavailable" toast
            showToast(R.string.selected_region_currently_not_available);

            // We only want to respond to the INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
        } else if (0 == intent.getAction().compareTo(ACTION_SHOW_GET_HELP_DIALOG)) {
            // OK to be null because we don't use it
            onGetHelpConnectingClick(null);
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_DISALLOWED_TRAFFIC)) {
            if (!isFinishing()) {
                LayoutInflater inflater = this.getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.disallowed_traffic_alert_layout, null);
                AlertDialog.Builder builder = new AlertDialog.Builder(this)
                        .setCancelable(true)
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(R.string.disallowed_traffic_alert_notification_title)
                        .setView(dialogView)
                        .setNeutralButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.btn_get_subscription, (dialog, which) -> {
                            onSubscribeButtonClick(null);
                            dialog.dismiss();
                        });
                builder.setNegativeButton(R.string.btn_get_speed_boost, (dialog, which) -> {
                    PsiCashFragment.openPsiCashStoreActivity(this,
                            getResources().getInteger(R.integer.speedBoostTabIndex));
                    dialog.dismiss();
                });
                builder.show();
            }
        }
    }

    private void loadInEmbeddedWebView(String url, int httpProxyPort) {
        final WebView webView = embeddedWebView.findViewById(R.id.sponsorWebView);
        final ProgressBar progressBar = embeddedWebView.findViewById(R.id.sponsorWebViewProgressBar);

        m_sponsorHomePage = new SponsorHomePage(webView, progressBar);
        m_sponsorHomePage.load(url, httpProxyPort);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(embeddedWebView);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            dialog.dismiss();
        });

        AlertDialog alertDialog = builder.create();
        alertDialog.setOnDismissListener(dialogInterface -> {
            webView.loadUrl("about:blank");
            ((ViewGroup)embeddedWebView.getParent()).removeView(embeddedWebView);
        });

        alertDialog.show();
    }

    public void onToggleClick(View v) {
        doToggle();
    }

    public void onGetHelpConnectingClick(View v) {
        showConnectionHelpDialog(this, R.layout.dialog_get_help_connecting);
    }

    public void onHowToHelpClick(View view) {
        showConnectionHelpDialog(this, R.layout.dialog_how_to_help_connect);
    }

    @Override
    public void onFeedbackClick(View v)
    {
        Intent feedbackIntent = new Intent(this, FeedbackActivity.class);
        startActivity(feedbackIntent);
    }

    @Override
    protected void startUp() {
        if (startUpInterstitialDisposable != null && !startUpInterstitialDisposable.isDisposed()) {
            // already in progress, do nothing
            return;
        }

        int countdownSeconds = 10;

        // Updates start/stop button from countdownSeconds to 0 every second and then completes,
        // does not emit any items downstream, only sends onComplete notification when done.
        Observable<Object> countdown =
                Observable.intervalRange(0, countdownSeconds + 1, 0, 1, TimeUnit.SECONDS)
                        .map(t -> countdownSeconds - t)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(t -> m_toggleButton.setText(String.format(Locale.US, "%d", t)))
                        .ignoreElements()
                        .toObservable();

        // Attempts to load an interstitial within countdownSeconds or emits an error if ad fails
        // to load or a timeout occurs.
        Observable<PsiphonAdManager.InterstitialResult> interstitial =
                psiphonAdManager.getCurrentAdTypeObservable()
                        .filter(adResult -> adResult.type() != PsiphonAdManager.AdResult.Type.UNKNOWN)
                        .firstOrError()
                        .flatMapObservable(adResult -> {
                            if (adResult.type() != PsiphonAdManager.AdResult.Type.UNTUNNELED) {
                                return Observable.error(new RuntimeException("Start immediately with ad result: " + adResult));
                            }
                            return Observable.just(adResult)
                                    .compose(psiphonAdManager
                                            .getInterstitialWithTimeoutForAdType(countdownSeconds, TimeUnit.SECONDS))
                                    // If we have a READY interstitial then try and show it.
                                    .doOnNext(interstitialResult -> {
                                        if (interstitialResult.state() == PsiphonAdManager.InterstitialResult.State.READY) {
                                            interstitialResult.show();
                                        }
                                    })
                                    // Emit downstream only when the ad is shown because sometimes
                                    // calling interstitialResult.show() doesn't result in ad presented.
                                    // In such a case let the countdown win the race.
                                    .filter(interstitialResult ->
                                                    interstitialResult.state() == PsiphonAdManager.InterstitialResult.State.SHOWING);
                        });

        startUpInterstitialDisposable = countdown
                // ambWith mirrors the ObservableSource that first either emits an
                // item or sends a termination notification.
                .ambWith(interstitial)
                .observeOn(AndroidSchedulers.mainThread())
                // On error just complete this subscription which then will start the service.
                .onErrorResumeNext(err -> {
                    return Observable.empty();
                })
                // This subscription completes due to one of the following reasons:
                // 1. Countdown completed before interstitial observable emitted anything.
                // 2. There was an error emission from interstitial observable.
                // 3. Interstitial observable completed because it was closed.
                // Now we should attempt to start the service.
                .doOnComplete(this::doStartUp)
                .subscribe();

        compositeDisposable.add(startUpInterstitialDisposable);
    }

    private void doStartUp() {
        // cancel any ongoing startUp subscription
        if(startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)
        boolean hasPreference;
        try {
            m_multiProcessPreferences.getBoolean(getString(R.string.tunnelWholeDevicePreference));
            hasPreference = true;
        } catch (ItemNotFoundException e) {
            hasPreference = false;
        }
        if (Utils.hasVpnService() && !hasPreference) {
            if (!m_tunnelWholeDevicePromptShown && !this.isFinishing()) {
                final Context context = this;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        AlertDialog dialog = new AlertDialog.Builder(context)
                                .setCancelable(false)
                                .setOnKeyListener(
                                        new DialogInterface.OnKeyListener() {
                                            @Override
                                            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                return keyCode == KeyEvent.KEYCODE_SEARCH;
                                            }
                                        })
                                .setTitle(R.string.StatusActivity_WholeDeviceTunnelPromptTitle)
                                .setMessage(R.string.StatusActivity_WholeDeviceTunnelPromptMessage)
                                .setPositiveButton(R.string.StatusActivity_WholeDeviceTunnelPositiveButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Persist the "on" setting
                                                updateWholeDevicePreference(true);
                                                startTunnel();
                                            }
                                        })
                                .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int whichButton) {
                                                // Turn off and persist the "off" setting
                                                m_tunnelWholeDeviceToggle.setChecked(false);
                                                updateWholeDevicePreference(false);
                                                startTunnel();
                                            }
                                        })
                                .setOnCancelListener(
                                        new DialogInterface.OnCancelListener() {
                                            @Override
                                            public void onCancel(DialogInterface dialog) {
                                                // Don't change or persist preference (this prompt may reappear)
                                                startTunnel();
                                            }
                                        })
                                .show();
                        // Our text no longer fits in the AlertDialog buttons on Lollipop, so force the
                        // font size (on older versions, the text seemed to be scaled down to fit).
                        // TODO: custom layout
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                        }
                    }
                });

                m_tunnelWholeDevicePromptShown = true;
            } else {
                // ...there's a prompt already showing (e.g., user hit Home with the
                // prompt up, then resumed Psiphon)
            }
            // ...wait and let onClick handlers will start tunnel
        } else {
            // No prompt, just start the tunnel (if not already running)
            startTunnel();
        }
    }

    @Override
    public void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
            urlString = PsiCashModifyUrl(urlString);
        }

        boolean wantVPN = m_multiProcessPreferences
                .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                        false);

        if (wantVPN && Utils.hasVpnService()) {
            // TODO: support multiple home pages in whole device mode. This is
            // disabled due to the case where users haven't set a default browser
            // and will get the prompt once per home page.

            // If URL is not empty we will try to load in an external browser, otherwise we will
            // try our best to open an external browser instance without specifying URL to load
            // or will load "about:blank" URL if that fails.

            // Prepare browser starting intent.
            Intent browserIntent;
            if (TextUtils.isEmpty(urlString)) {
                // If URL is empty, just start the app.
                browserIntent = new Intent(Intent.ACTION_MAIN);
            } else {
                // If URL is not empty, start the app with URL load intent.
                browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
            }
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // LinkedHashSet maintains FIFO order and does not allow duplicates.
            Set<String> browserIdsSet = new LinkedHashSet<>();

            // Put Brave first in the ordered set.
            browserIdsSet.add("com.brave.browser");

            // Add all resolved browsers to the set preserving the order.
            browserIdsSet.addAll(VpnAppsUtils.getInstalledWebBrowserPackageIds(getPackageManager()));

            // Put Chrome at the end if it is not already in the set.
            browserIdsSet.add("com.android.chrome");

            // If we have a candidate then set the app package ID for the browser intent and try to
            // start the app with the intent right away.
            for (String id : browserIdsSet) {
                if (VpnAppsUtils.isTunneledAppId(context, id)) {
                    browserIntent.setPackage(id);
                    try {
                        context.startActivity(browserIntent);
                        // Return immediately if success.
                        return;
                    } catch (ActivityNotFoundException | SecurityException ignored) {
                        // Continue looping if error.
                    }
                }
            }

            // Last effort - let the system handle it.
            // Note that the browser picked by the system will be most likely not tunneled.
            try {
                // We don't have explicit package ID for the browser intent, so the URL cannot be empty.
                // In this case try loading a special URL 'about:blank'.
                if (TextUtils.isEmpty(urlString)) {
                    browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                context.startActivity(browserIntent);
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Fail silently.
            }
        } else {
            // BOM, try to open Zirco
            Uri uri = null;
            if (!TextUtils.isEmpty(urlString)) {
                uri = Uri.parse(urlString);
            }

            Intent intent = new Intent(
                    "ACTION_VIEW",
                    uri,
                    context,
                    org.zirco.ui.activities.MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // This intent displays the Zirco browser.
            // We use "extras" to communicate Psiphon settings to Zirco.
            // When Zirco is first created, it will use the homePages
            // extras to open tabs for each home page, respectively. When the intent
            // triggers an existing Zirco instance (and it's a singleton) this extra
            // is ignored and the browser is displayed as-is.
            // When a uri is specified, it will open as a new tab. This is
            // independent of the home pages.
            // Note: Zirco now directly accesses PsiphonData to get the current
            // local HTTP proxy port for WebView tunneling.

            if (urlString != null) {
                if(shouldPsiCashModifyUrls) {
                    // Add PsiCash parameters
                    urlString = PsiCashModifyUrl(urlString);
                }
                intent.putExtra("homePages", new ArrayList<>(Collections.singletonList(urlString)));
            }
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
    }

    enum RateLimitMode {AD_MODE_LIMITED, LIMITED_SUBSCRIPTION, UNLIMITED_SUBSCRIPTION, SPEED_BOOST}

    private void setRateLimitUI(RateLimitMode rateLimitMode) {
        // Update UI elements showing the current speed.
        if (rateLimitMode == RateLimitMode.UNLIMITED_SUBSCRIPTION) {
            mRateLimitedText.setVisibility(View.GONE);
            mRateUnlimitedText.setVisibility(View.VISIBLE);
            mRateLimitSubscribeButton.setVisibility(View.GONE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        } else{
            if(rateLimitMode == RateLimitMode.AD_MODE_LIMITED) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 2));
            } else if (rateLimitMode == RateLimitMode.LIMITED_SUBSCRIPTION) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, 5));
            } else if (rateLimitMode == RateLimitMode.SPEED_BOOST) {
                mRateLimitedText.setText(getString(R.string.rate_limit_text_speed_boost));
            }
            mRateLimitedText.setVisibility(View.VISIBLE);
            mRateUnlimitedText.setVisibility(View.GONE);
            mRateLimitSubscribeButton.setVisibility(View.VISIBLE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }

    private void setAdBannerPlaceholderVisibility(SubscriptionState subscriptionState) {
        findViewById(R.id.largeAdSlotContainer)
                .setVisibility(subscriptionState.hasValidPurchase() ?
                        View.GONE : View.VISIBLE);
    }

    private void setPsiCashFragment(SubscriptionState subscriptionState) {
        // Do nothing if host activity is finishing or destroyed
        if (isFinishing() ||
                // isDestroyed() is API 17+
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        FragmentTransaction transaction = getSupportFragmentManager()
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

    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    public void onSubscribeButtonClick(View v) {
        Utils.MyLog.g("StatusActivity::onSubscribeButtonClick");
        Intent paymentChooserActivityIntent = new Intent(this, PaymentChooserActivity.class);
        paymentChooserActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivityForResult(paymentChooserActivityIntent, PAYMENT_CHOOSER_ACTIVITY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PAYMENT_CHOOSER_ACTIVITY) {
            if (resultCode == RESULT_OK) {
                String skuString = data.getStringExtra(PaymentChooserActivity.USER_PICKED_SKU_DETAILS_EXTRA);
                String oldSkuString = data.getStringExtra(PaymentChooserActivity.USER_OLD_SKU_EXTRA);
                String oldPurchaseToken = data.getStringExtra(PaymentChooserActivity.USER_OLD_PURCHASE_TOKEN_EXTRA);
                try {
                    if (TextUtils.isEmpty(skuString)) {
                        throw new IllegalArgumentException("SKU is empty.");
                    }
                    SkuDetails skuDetails = new SkuDetails(skuString);
                    googlePlayBillingHelper.launchFlow(this, oldSkuString, oldPurchaseToken, skuDetails)
                            .doOnError(err -> {
                                // Show "Subscription options not available" toast.
                                showToast(R.string.subscription_options_currently_not_available);
                            })
                            .onErrorComplete()
                            .subscribe();
                } catch (JSONException | IllegalArgumentException e) {
                    Utils.MyLog.g("StatusActivity::onActivityResult purchase SKU error: " + e);
                    // Show "Subscription options not available" toast.
                    showToast(R.string.subscription_options_currently_not_available);
                }
            } else {
                Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        } else if (requestCode == PsiCashFragment.PSICASH_STORE_ACTIVITY) {
            if(resultCode == RESULT_OK) {
                if (data != null && PsiCashStoreActivity.PSICASH_CONNECT_PSIPHON_INTENT.equals(data.getAction())) {
                    startUp();
                }
            }
            PsiCashFragment psiCashFragment = (PsiCashFragment) getSupportFragmentManager().findFragmentByTag("PsiCashFragment");
            if (psiCashFragment != null) {
                psiCashFragment.onActivityResult(requestCode, resultCode, data);
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void showToast(int stringResId) {
        Toast toast = Toast.makeText(this, stringResId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    @Override
    protected void onVpnPromptCancelled() {
        showVpnAlertDialog(R.string.StatusActivity_VpnPromptCancelledTitle, R.string.StatusActivity_VpnPromptCancelledMessage);
    }

    private void showVpnAlertDialog(int titleId, int messageId) {
        new AlertDialog.Builder(getContext())
                .setCancelable(true)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private boolean isPsiCashIntentUri(Uri intentUri) {
        if (intentUri != null) {
            // Handle https://mobile.psi.cash/android
            if ("https".equals(intentUri.getScheme()) &&
                    "mobile.psi.cash".equals(intentUri.getHost()) &&
                    "/android".equals(intentUri.getPath())) {
                return true;
            }
            // Handle psiphon://psicash
            if ("psiphon".equals(intentUri.getScheme()) &&
                    "psicash".equals(intentUri.getHost())) {
                return true;
            }
        }
        return false;
    }
}
