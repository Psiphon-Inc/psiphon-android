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

package com.psiphon3;

import android.annotation.SuppressLint;
import android.app.UiAutomation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.util.Pair;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.android.billingclient.api.SkuDetails;
import com.google.android.material.tabs.TabLayout;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.account.PsiCashAccountActivity;
import com.psiphon3.psicash.details.PsiCashDetailsViewModel;
import com.psiphon3.psicash.store.PsiCashStoreActivity;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.LoggingObserver;
import com.psiphon3.psiphonlibrary.LoggingProvider;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.VpnAppsUtils;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class MainActivity extends LocalizedActivities.AppCompatActivity {
    private AlertDialog unsafeTrafficAlertsDialog;

    public MainActivity() {
        Utils.initializeSecureRandom();
    }

    public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainActivity.PREVENT_AUTO_START";
    public static final String PSICASH_CONNECT_PSIPHON_INTENT_ACTION = "com.psiphon3.MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION";

    private static final String CURRENT_TAB = "currentTab";
    private static final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    private static final int REQUEST_CODE_PREPARE_VPN = 100;

    private LoggingObserver loggingObserver;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private CompositeDisposable uiUpdatesCompositeDisposable;
    private Button toggleButton;
    private ProgressBar connectionProgressBar;
    private Drawable defaultProgressBarDrawable;
    private Button openBrowserButton;
    private MainActivityViewModel viewModel;
    private Toast invalidProxySettingsToast;
    private AppPreferences multiProcessPreferences;
    private ViewPager viewPager;
    private PsiphonTabLayout tabLayout;
    private Disposable autoStartDisposable;
    private GooglePlayBillingHelper googlePlayBillingHelper;
    // Ads
    private PsiphonAdManager psiphonAdManager;
    private boolean disableInterstitialOnNextTabChange;
    private InterstitialAdViewModel interstitialAdViewModel;
    private Observable<Boolean> hasBoostOrSubscriptionObservable;


    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Add version label to the right side of action bar
        ActionBar actionBar = getSupportActionBar();
        ActionBar.LayoutParams lp = new ActionBar.LayoutParams(ActionBar.LayoutParams.WRAP_CONTENT, ActionBar.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        View customView = LayoutInflater.from(this).inflate(R.layout.toolbar_version_layout, null);
        TextView versionLabel = customView.findViewById(R.id.toolbar_version_label);
        versionLabel.setText(String.format(Locale.US, "v. %s", EmbeddedValues.CLIENT_VERSION));
        actionBar.setCustomView(customView, lp);
        actionBar.setDisplayShowCustomEnabled(true);

        EmbeddedValues.initialize(getApplicationContext());
        multiProcessPreferences = new AppPreferences(this);

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        googlePlayBillingHelper.startIab();

        viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);

        // On first run remove logs from previous sessions if tunnel service is not running.
        if (viewModel.isFirstRun() && !viewModel.isServiceRunning(getApplication())) {
            LoggingProvider.LogDatabaseHelper.truncateLogs(getApplication(), true);
        }

        // The LoggingObserver will run in a separate thread
        HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
        loggingObserverThread.start();
        loggingObserver = new LoggingObserver(getApplicationContext(),
                new Handler(loggingObserverThread.getLooper()));

        toggleButton = findViewById(R.id.toggleButton);
        connectionProgressBar = findViewById(R.id.connectionProgressBar);
        defaultProgressBarDrawable = connectionProgressBar.getIndeterminateDrawable();
        openBrowserButton = findViewById(R.id.openBrowserButton);
        toggleButton.setOnClickListener(v ->
                compositeDisposable.add(viewModel.tunnelStateFlowable()
                        .take(1)
                        .doOnNext(state -> {
                            if (state.isRunning()) {
                                viewModel.stopTunnelService();
                            } else {
                                startUp();
                            }
                        })
                        .subscribe()));

        // ads
        PsiCashDetailsViewModel psiCashDetailsViewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(PsiCashDetailsViewModel.class);

        // This helper observable emits true if the user has a subscription or an active speed boost
        // and false if none of the above.
        hasBoostOrSubscriptionObservable = Observable.combineLatest(psiCashDetailsViewModel.hasActiveSpeedBoostObservable(),
                googlePlayBillingHelper.subscriptionStateFlowable().toObservable(),
                Pair::new)
                .switchMap(pair -> {
                    boolean hasActiveSpeedBoost = pair.first;
                    SubscriptionState subscriptionState = pair.second;

                    return Observable.just(hasActiveSpeedBoost ||
                            subscriptionState.hasValidPurchase());
                })
                .distinctUntilChanged();

        psiphonAdManager = new PsiphonAdManager(getApplicationContext(),
                this,
                findViewById(R.id.largeAdSlot),
                hasBoostOrSubscriptionObservable,
                viewModel.tunnelStateFlowable());

        psiphonAdManager.startLoadingAds();

        interstitialAdViewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(InterstitialAdViewModel.class);
        getLifecycle().addObserver(interstitialAdViewModel);

        interstitialAdViewModel.setActivityWeakReference(this);
        interstitialAdViewModel.setCountDownTextView(toggleButton);

        // Observe start tunnel signals from the InterstitialAdViewModel
        compositeDisposable.add(interstitialAdViewModel.startSignalFlowable()
                .doOnNext(consumableEvent -> consumableEvent.consume(__ -> doStartUp()))
                .subscribe());

        // Preload an interstitial iff all of the below is true
        // 1. The activity is not being recreated due to an orientation change.
        // 2. We are not in "no-ads" mode
        // 3. The tunnel is not running
        if (savedInstanceState == null) {
            compositeDisposable.add(hasBoostOrSubscriptionObservable
                    .firstOrError()
                    .flatMapCompletable(noAds -> noAds ?
                            Completable.complete() :
                            viewModel.tunnelStateFlowable()
                                    .filter(tunnelState -> !tunnelState.isUnknown())
                                    .firstOrError()
                                    .doOnSuccess(state -> {
                                        if (state.isStopped()) {
                                            interstitialAdViewModel.preloadInterstitial();
                                        }
                                    })
                                    .ignoreElement())
                    .subscribe());
        }

        tabLayout = findViewById(R.id.main_activity_tablayout);
        tabLayout.addTab(tabLayout.newTab().setTag("home").setText(R.string.home_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("statistics").setText(R.string.statistics_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("settings").setText(R.string.settings_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("logs").setText(R.string.logs_tab_name));
        PageAdapter pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount());

        viewPager = findViewById(R.id.tabs_view_pager);
        // Try and keep all pages of the view pager loaded. For 4 tabs in total the off screen pages
        // max is 3.
        viewPager.setOffscreenPageLimit(3);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int tabPosition = tab.getPosition();
                viewPager.setCurrentItem(tab.getPosition());
                multiProcessPreferences.put(CURRENT_TAB, tabPosition);

                // ads - trigger interstitial after a few tab changes
                if (!disableInterstitialOnNextTabChange) {
                    psiphonAdManager.onTabChanged();
                }
                disableInterstitialOnNextTabChange = false;
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // Switch to last tab when view pager is ready
        viewPager.post(() ->
                viewPager.setCurrentItem(multiProcessPreferences.getInt(CURRENT_TAB, 0), false));

        // Schedule handling current intent when the main view is fully inflated
        getWindow().getDecorView().post(() -> HandleCurrentIntent(getIntent()));
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(loggingObserver);
        cancelInvalidProxySettingsToast();
        uiUpdatesCompositeDisposable.dispose();
    }

    @Override
    protected void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllPurchases();
        googlePlayBillingHelper.queryAllSkuDetails();

        // Notify tunnel service if it is running so it may trigger purchase check and
        // upgrade current connection if there is a new valid subscription purchase.
        viewModel.notifyTunnelServiceResume();

        // Load new logs from the logging provider now
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            loggingObserver.dispatchChange(false, LoggingProvider.INSERT_URI);
        } else {
            loggingObserver.dispatchChange(false);
        }
        // Load new logs from the logging provider when it changes
        getContentResolver().registerContentObserver(LoggingProvider.INSERT_URI, true, loggingObserver);

        uiUpdatesCompositeDisposable = new CompositeDisposable();
        // Observe tunnel state changes to update UI
        uiUpdatesCompositeDisposable.add(viewModel.tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::updateServiceStateUI)
                .subscribe());

        // Observe custom proxy validation results to show a toast for invalid ones
        uiUpdatesCompositeDisposable.add(viewModel.customProxyValidationResultFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(isValidResult -> {
                    if (!isValidResult) {
                        cancelInvalidProxySettingsToast();
                        invalidProxySettingsToast = Toast.makeText(this,
                                R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                        invalidProxySettingsToast.show();
                    }
                })
                .subscribe());

        // Observe link clicks in the embedded web view to open in the external browser
        // NOTE: do not PsiCash modify links clicked from the embedded view
        uiUpdatesCompositeDisposable.add(viewModel.externalBrowserUrlFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(url -> displayBrowser(this, url, false))
                .subscribe());

        // Observe subscription state and set ad container layout visibility
        uiUpdatesCompositeDisposable.add(googlePlayBillingHelper.subscriptionStateFlowable()
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::setAdBannerPlaceholderVisibility)
                .subscribe());

        boolean shouldAutoStart = shouldAutoStart();
        preventAutoStart();

        if (unsafeTrafficAlertsPrompt(shouldAutoStart)) {
            shouldAutoStart = false;
        }

        autoStartIfNecessary(shouldAutoStart);
    }

    private void autoStartIfNecessary(boolean shouldAutoStart) {
        if (autoStartDisposable == null || autoStartDisposable.isDisposed()) {
            autoStartDisposable = autoStartMaybe(shouldAutoStart)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(__ -> doStartUp())
                    .subscribe();
            compositeDisposable.add(autoStartDisposable);
        }
    }

    private boolean unsafeTrafficAlertsPrompt(boolean startTunnelAfterPrompt) {
        // Prompt the user only once to choose to enable unsafe traffic alerts.
        // Returns true if the prompt is shown.
        try {
            multiProcessPreferences.getBoolean(getString(R.string.unsafeTrafficAlertsPreference));
            return false;
        } catch (ItemNotFoundException e) {
            // Do not show multiple alerts.
            if(unsafeTrafficAlertsDialog != null && unsafeTrafficAlertsDialog.isShowing()) {
                return true;
            }
            LayoutInflater inflater = this.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.unsafe_traffic_alert_prompt_layout, null);
            TextView tv = dialogView.findViewById(R.id.textViewMore);
            tv.append(String.format(Locale.US, "\n%s", getString(R.string.AboutMalAwareLink)));
            Linkify.addLinks(tv, Linkify.WEB_URLS);

            unsafeTrafficAlertsDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.unsafe_traffic_alert_prompt_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.lbl_yes,
                            (dialog, whichButton) -> {
                                multiProcessPreferences.put(getString(R.string.unsafeTrafficAlertsPreference), true);
                            })
                    .setNegativeButton(R.string.lbl_no,
                            (dialog, whichButton) -> {
                                multiProcessPreferences.put(getString(R.string.unsafeTrafficAlertsPreference), false);
                            })
                    .setOnDismissListener(
                            dialog -> {
                                autoStartIfNecessary(startTunnelAfterPrompt);
                            })
                    .show();
        }
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_PREPARE_VPN) {
            if (resultCode == RESULT_OK) {
                viewModel.startTunnelService();
            } else if (resultCode == RESULT_CANCELED) {
                showVpnAlertDialog(R.string.StatusActivity_VpnPromptCancelledTitle,
                        R.string.StatusActivity_VpnPromptCancelledMessage);
            }
        } else if (requestCode == PAYMENT_CHOOSER_ACTIVITY) {
            if (resultCode == RESULT_OK) {
                String skuString = data.getStringExtra(PaymentChooserActivity.USER_PICKED_SKU_DETAILS_EXTRA);
                String oldSkuString = data.getStringExtra(PaymentChooserActivity.USER_OLD_SKU_EXTRA);
                String oldPurchaseToken = data.getStringExtra(PaymentChooserActivity.USER_OLD_PURCHASE_TOKEN_EXTRA);
                try {
                    if (TextUtils.isEmpty(skuString)) {
                        throw new IllegalArgumentException("SKU is empty.");
                    }
                    SkuDetails skuDetails = new SkuDetails(skuString);
                    compositeDisposable.add(googlePlayBillingHelper.launchFlow(this, oldSkuString, oldPurchaseToken, skuDetails)
                            .doOnError(err -> {
                                // Show "Subscription options not available" toast.
                                showToast(R.string.subscription_options_currently_not_available);
                            })
                            .onErrorComplete()
                            .subscribe());
                } catch (JSONException | IllegalArgumentException e) {
                    Utils.MyLog.g("MainActivity::onActivityResult purchase SKU error: " + e);
                    // Show "Subscription options not available" toast.
                    showToast(R.string.subscription_options_currently_not_available);
                }
            } else {
                Utils.MyLog.g("MainActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        } else if (requestCode == PsiCashStoreActivity.ACTIVITY_REQUEST_CODE ||
                requestCode == PsiCashAccountActivity.ACTIVITY_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                if (data != null && PSICASH_CONNECT_PSIPHON_INTENT_ACTION.equals(data.getAction())) {
                    startUpIfNotRunning();
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void startUpIfNotRunning() {
        compositeDisposable.add(viewModel.tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .doOnSuccess(tunnelState -> {
                    if (tunnelState.isStopped()) {
                        startUp();
                    }
                })
                .subscribe());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        HandleCurrentIntent(intent);
    }

    public static void openPaymentChooserActivity(FragmentActivity activity, int tabIndex) {
        try {
            Intent intent = new Intent(activity, PaymentChooserActivity.class);
            intent.putExtra("tabIndex", tabIndex);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivityForResult(intent, PAYMENT_CHOOSER_ACTIVITY);
        } catch(RuntimeException ignored) {
        }
    }

    private void updateServiceStateUI(final TunnelState tunnelState) {
        if (tunnelState.isUnknown()) {
            openBrowserButton.setEnabled(false);
            toggleButton.setEnabled(false);
            toggleButton.setText(getText(R.string.waiting));
            connectionProgressBar.setVisibility(View.INVISIBLE);
        } else if (tunnelState.isRunning()) {
            toggleButton.setEnabled(true);
            toggleButton.setText(getText(R.string.stop));
            if (tunnelState.connectionData().isConnected()) {
                openBrowserButton.setEnabled(true);
                connectionProgressBar.setVisibility(View.INVISIBLE);

                ArrayList<String> homePages = tunnelState.connectionData().homePages();
                final String url;
                if (homePages != null && homePages.size() > 0) {
                    url = homePages.get(0);
                } else {
                    url = null;
                }
                openBrowserButton.setOnClickListener(view -> displayBrowser(this, url));
            } else {
                openBrowserButton.setEnabled(false);
                connectionProgressBar.setVisibility(View.VISIBLE);
                connectionProgressBar.setIndeterminate(false);
                Rect bounds = connectionProgressBar.getIndeterminateDrawable().getBounds();
                Drawable drawable =
                        (tunnelState.connectionData().networkConnectionState() == TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK) ?
                                ContextCompat.getDrawable(this, R.drawable.connection_progress_bar_animation) :
                                defaultProgressBarDrawable;
                connectionProgressBar.setIndeterminateDrawable(drawable);
                connectionProgressBar.getIndeterminateDrawable().setBounds(bounds);
                connectionProgressBar.setIndeterminate(true);
            }
        } else {
            // Service not running
            if (!interstitialAdViewModel.inProgress()) {
                // Update only if the interstitial startup is not in progress
                toggleButton.setText(getText(R.string.start));
                toggleButton.setEnabled(true);
            }
            openBrowserButton.setEnabled(false);
            connectionProgressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void displayBrowser(Context context, String urlString) {
        // Override landing page URL if there is a static landing page URL in the build config
        if (BuildConfig.STATIC_LANDING_PAGE_URL != null) {
            urlString  = BuildConfig.STATIC_LANDING_PAGE_URL;
        }
        // PsiCash modify URLs by default
        displayBrowser(context, urlString, true);
    }

    private void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
            urlString = PsiCashModifyUrl(urlString);
        }

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
    }

    private void HandleCurrentIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // Handle external deep links first
        // Examples:
        // psiphon://settings
        // psiphon://settings/vpn
        // psiphon://psicash
        // psiphon://psicash/
        // psiphon://psicash/buy
        // psiphon://psicash/speedboost
        // psiphon://psicash/speedboost/extras
        // psiphon://subscribe
        // psiphon://subscribe/timepass
        // psiphon://subscribe/subscription
        if (handleDeepLinkIntent(intent)) {
            return;
        }

        // MainActivity is exposed to other apps because it is declared as an entry point activity of the app in the manifest.
        // For the purpose of handling internal intents, such as handshake, etc., from the tunnel service we have declared a not
        // exported activity alias 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler' that should act as a proxy for MainActivity.
        // We expect our own intents have a component set to 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler', all other intents
        // should be ignored.
        ComponentName tunnelIntentsActivityComponentName =
                new ComponentName(this, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        if (!tunnelIntentsActivityComponentName.equals(intent.getComponent())) {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
            Bundle data = intent.getExtras();
            if (data != null) {
                ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
                if (homePages != null && homePages.size() > 0) {
                    String url = homePages.get(0);
                    // If the URL should not be open in the embedded web view then try and open it
                    // in an external browser. The home tab fragment will make a decision to open
                    // the URL in an embedded web view independently, if needed.
                    HomeTabFragment.setSeenHandshake(true);
                    if (!shouldLoadInEmbeddedWebView(url)) {
                        displayBrowser(this, url);
                    } else {
                        disableInterstitialOnNextTabChange = true;
                        selectTabByTag("home");
                    }
                }
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // At this point the service should be stopped and the persisted region selection set
            // to PsiphonConstants.REGION_CODE_ANY by TunnelManager, so we only need to update the
            // region selection UI.

            // Switch to settings tab
            disableInterstitialOnNextTabChange = true;
            selectTabByTag("settings");
            // Signal Rx subscription in the options tab to update available regions list
            viewModel.signalAvailableRegionsUpdate();

            // Show "Selected region unavailable" toast
            Toast toast = Toast.makeText(this, R.string.selected_region_currently_not_available, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
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
                            MainActivity.openPaymentChooserActivity(MainActivity.this,
                                    getResources().getInteger(R.integer.subscriptionTabIndex));
                            dialog.dismiss();
                        });
                builder.setNegativeButton(R.string.btn_get_speed_boost, (dialog, which) -> {
                    UiHelpers.openPsiCashStoreActivity(this,
                            getResources().getInteger(R.integer.speedBoostTabIndex));
                    dialog.dismiss();
                });
                builder.show();
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_UNSAFE_TRAFFIC)) {
            // Unsafe traffic intent from service notification
            if (!isFinishing()) {
                // Get subject and action URLs from the intent
                Bundle extras = intent.getExtras();
                ArrayList<String> unsafeTrafficSubjects = null;
                ArrayList<String> unsafeTrafficActionUrls = null;
                if (extras != null ) {
                    unsafeTrafficSubjects = extras.getStringArrayList(TunnelManager.DATA_UNSAFE_TRAFFIC_SUBJECTS_LIST);
                    unsafeTrafficActionUrls = extras.getStringArrayList(TunnelManager.DATA_UNSAFE_TRAFFIC_ACTION_URLS_LIST);
                }

                LayoutInflater inflater = this.getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.unsafe_traffic_alert_layout, null);
                TextView tv = dialogView.findViewById(R.id.textView);
                if (unsafeTrafficSubjects != null) {
                    tv.append(String.format(Locale.US, "\n"));
                    for (String unsafeTrafficSubject : unsafeTrafficSubjects) {
                        tv.append(String.format(Locale.US, "%s\n", unsafeTrafficSubject));
                    }
                }
                if (unsafeTrafficActionUrls != null) {
                    for (String unsafeTrafficActionUrl : unsafeTrafficActionUrls) {
                        tv.append(String.format(Locale.US, "\n%s", unsafeTrafficActionUrl));
                    }
                }
                Linkify.addLinks(tv, Linkify.WEB_URLS);

                new AlertDialog.Builder(this)
                        .setCancelable(true)
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(R.string.unsafe_traffic_alert_dialog_title)
                        .setView(dialogView)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    private boolean handleDeepLinkIntent(@NonNull Intent intent) {
        final String FWD_SLASH = "/";

        final String PSIPHON_SCHEME = "psiphon";

        final String PSICASH_HOST = "psicash";
        final String PSICASH_PATH_BUY = "/buy";
        final String PSICASH_PATH_SPEEDBOOST = "/speedboost";

        final String SETTINGS_HOST = "settings";
        final String SETTINGS_PATH_VPN = "/vpn";
        final String SETTINGS_PATH_PROXY = "/proxy";
        final String SETTINGS_PATH_MORE_OPTIONS = "/more-options";

        final String SUBSCRIBE_HOST = "subscribe";
        final String SUBSCRIPTION_PATH_SUBSCRIPTION = "/subscription";
        final String SUBSCRIPTION_PATH_TIMEPASS = "/timepass";

        Uri intentUri = intent.getData();
        // Check if this is a deep link intent we can handle
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) ||
                intentUri == null ||
                !PSIPHON_SCHEME.equals(intentUri.getScheme())) {
            // Intent not handled
            return false;
        }

        String path = intentUri.getPath();

        switch (intentUri.getHost()) {
            case PSICASH_HOST:
                // Default tab is 'Add PsiCash'
                int tabIndex = getResources().getInteger(R.integer.psiCashTabIndex);

                if (path != null) {
                    if (path.equals(PSICASH_PATH_BUY) || path.startsWith(PSICASH_PATH_BUY + FWD_SLASH)) {
                        // If the uri path is "/buy" or "/buy/.*" then navigate to Add PsiCash tab,
                        tabIndex = getResources().getInteger(R.integer.psiCashTabIndex);
                    } else if (path.equals(PSICASH_PATH_SPEEDBOOST) || path.startsWith(PSICASH_PATH_SPEEDBOOST + FWD_SLASH)) {
                        // The path is "/speedboost" or "/speedboost/.*" - navigate to SpeedBoost tab
                        tabIndex = getResources().getInteger(R.integer.speedBoostTabIndex);
                    }
                }

                UiHelpers.openPsiCashStoreActivity(this, tabIndex);
                // intent handled
                return true;

            case SUBSCRIBE_HOST:
                // Default tab is 'Subscription'
                tabIndex = getResources().getInteger(R.integer.subscriptionTabIndex);

                if (path != null) {
                    if (path.equals(SUBSCRIPTION_PATH_SUBSCRIPTION) || path.startsWith(SUBSCRIPTION_PATH_SUBSCRIPTION + FWD_SLASH)) {
                        // If the uri path is "/subscription" or "/subscription/.*" then navigate to the Subscription tab,
                        tabIndex = getResources().getInteger(R.integer.subscriptionTabIndex);
                    } else if (path.equals(SUBSCRIPTION_PATH_TIMEPASS) || path.startsWith(SUBSCRIPTION_PATH_TIMEPASS + FWD_SLASH)) {
                        // The path is "/timepass" or "/timepass/.*" - navigate to the Time Pass tab
                        tabIndex = getResources().getInteger(R.integer.timePassTabIndex);
                    }
                }

                MainActivity.openPaymentChooserActivity(this, tabIndex);
                // intent handled
                return true;

            case SETTINGS_HOST:
                selectTabByTag("settings");
                if (path != null) {
                    // If uri path is "/vpn" or "/vpn/.*" then signal to navigate to VPN settings screen.
                    // If the path is "/proxy" or "/proxy/.*" then signal to navigate to Proxy settings screen.
                    // If the path is "/more-options" or "/more-options/.*" then signal to navigate to More Options screen.
                    if (path.equals(SETTINGS_PATH_VPN) || path.startsWith(SETTINGS_PATH_VPN + FWD_SLASH)) {
                        viewModel.signalOpenVpnSettings();
                    } else if (path.equals(SETTINGS_PATH_PROXY) || path.startsWith(SETTINGS_PATH_PROXY + FWD_SLASH)) {
                        viewModel.signalOpenProxySettings();
                    } else if (path.equals(SETTINGS_PATH_MORE_OPTIONS) || path.startsWith(SETTINGS_PATH_MORE_OPTIONS)) {
                        viewModel.signalOpenMoreOptions();
                    }
                }
                // intent handled
                return true;
        }
        // intent not handled
        return false;
    }

    private void showVpnAlertDialog(int titleId, int messageId) {
        new AlertDialog.Builder(this)
                .setCancelable(true)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void startTunnel() {
        // Don't start if custom proxy settings is selected and values are invalid
        if (!viewModel.validateCustomProxySettings()) {
            // Update the main UI with the latest tunnel state once explicitly as the app will not
            // go through the pause / resume cycle at this point and the toggle button may still be
            // showing the last number of the startup countdown. This is only relevant to GP and Pro.
            compositeDisposable.add(viewModel.tunnelStateFlowable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .firstOrError()
                    .doOnSuccess(this::updateServiceStateUI)
                    .subscribe());
            return;
        }
        boolean waitingForPrompt = doVpnPrepare();
        if (!waitingForPrompt) {
            viewModel.startTunnelService();
        }
    }

    private boolean doVpnPrepare() {
        // Devices without VpnService support throw various undocumented
        // exceptions, including ActivityNotFoundException and ActivityNotFoundException.
        // For example: http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
        try {
            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                // TODO: can we disable the mode before we reach this this
                // failure point with
                // resolveActivity()? We'll need the intent from prepare() or
                // we'll have to mimic it.
                // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29

                startActivityForResult(intent, REQUEST_CODE_PREPARE_VPN);

                // start service will be called in onActivityResult
                return true;
            }
            return false;
        } catch (Exception e) {
            Utils.MyLog.e(R.string.tunnel_whole_device_exception, Utils.MyLog.Sensitivity.NOT_SENSITIVE);
            // true = waiting for prompt, although we can't start the
            // activity so onActivityResult won't be called
            return true;
        }
    }

    private void cancelInvalidProxySettingsToast() {
        if (invalidProxySettingsToast != null) {
            View toastView = invalidProxySettingsToast.getView();
            if (toastView != null) {
                if (toastView.isShown()) {
                    invalidProxySettingsToast.cancel();
                }
            }
        }
    }

    private void preventAutoStart() {
        viewModel.setFirstRun(false);
    }

    private boolean shouldAutoStart() {
        return viewModel.isFirstRun() &&
                !getIntent().getBooleanExtra(INTENT_EXTRA_PREVENT_AUTO_START, false);
    }

    // Returns an object only if tunnel should be auto-started,
    // completes with no value otherwise.
    private Maybe<Object> autoStartMaybe(boolean isFirstRun) {
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

    public static boolean shouldLoadInEmbeddedWebView(String url) {
        for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
            if (url.contains(homeTabUrlExclusion)) {
                return false;
            }
        }
        return true;
    }

    private void startUp() {
        if (interstitialAdViewModel.inProgress()) {
            // already in progress, do nothing
            return;
        }
        compositeDisposable.add(hasBoostOrSubscriptionObservable
                .firstOrError()
                .doOnSuccess(noAds -> {
                    interstitialAdViewModel.setCountdownSeconds(10);
                    interstitialAdViewModel.startUp(noAds);
                })
                .subscribe());
    }

    private void doStartUp() {
        // Check if the tunnel is not running already
        compositeDisposable.add(viewModel.tunnelStateFlowable()
                .filter(tunnelState -> !tunnelState.isUnknown())
                .firstOrError()
                .doOnSuccess(state -> {
                    if (state.isStopped()) {
                        startTunnel();
                    }
                })
                .subscribe());
    }

    private void setAdBannerPlaceholderVisibility(SubscriptionState subscriptionState) {
        findViewById(R.id.largeAdSlotContainer)
                .setVisibility(subscriptionState.hasValidPurchase() ?
                        View.GONE : View.VISIBLE);
    }

    private void showToast(int stringResId) {
        Toast toast = Toast.makeText(this, stringResId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    private boolean isPsiCashIntentUri(Uri intentUri) {
        if (intentUri != null) {
            // Handle psiphon://psicash
            if ("psiphon".equals(intentUri.getScheme()) &&
                    "psicash".equals(intentUri.getHost())) {
                return true;
            }
        }
        return false;
    }

    private String PsiCashModifyUrl(String originalUrlString) {
        if (TextUtils.isEmpty(originalUrlString)) {
            return originalUrlString;
        }

        try {
            return PsiCashClient.getInstance(getApplicationContext()).modifiedHomePageURL(originalUrlString);
        } catch (PsiCashException e) {
            Utils.MyLog.g("PsiCash: error modifying home page: " + e);
        }
        return originalUrlString;
    }

    public void selectTabByTag(@NonNull Object tag) {
        viewPager.post(() -> {
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null) {
                    Object tabTag = tabLayout.getTabAt(i).getTag();
                    if (tag.equals(tabTag)) {
                        viewPager.setCurrentItem(i, true);
                    }
                }
            }
        });
    }


    static class PageAdapter extends FragmentPagerAdapter {
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new HomeTabFragment();
                case 1:
                    return new StatisticsTabFragment();
                case 2:
                    return new OptionsTabFragment();
                case 3:
                    return new LogsTabFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }
}
