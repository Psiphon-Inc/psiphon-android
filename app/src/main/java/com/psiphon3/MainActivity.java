package com.psiphon3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
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
import com.psiphon3.psicash.PsiCashFragment;
import com.psiphon3.psicash.PsiCashStoreActivity;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.LoggingObserver;
import com.psiphon3.psiphonlibrary.LoggingProvider;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.VpnAppsUtils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class MainActivity extends LocalizedActivities.AppCompatActivity {
    public MainActivity() {
        Utils.initializeSecureRandom();
    }

    public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainActivity.PREVENT_AUTO_START";
    private static final String ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION = "askedToAccessCoarseLocationPermission";
    private static final String CURRENT_TAB = "currentTab";
    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    private static final int REQUEST_CODE_PREPARE_VPN = 100;
    private static final int REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 101;

    private LoggingObserver loggingObserver;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button toggleButton;
    protected ProgressBar connectionProgressBar;
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
    protected Disposable startUpInterstitialDisposable;


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

        // TODO: verify if we actually need this?
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        googlePlayBillingHelper.startIab();

        viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);

        // The LoggingObserver will run in a separate thread
        HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
        loggingObserverThread.start();
        loggingObserver = new LoggingObserver(getApplicationContext(),
                new Handler(loggingObserverThread.getLooper()));

        toggleButton = findViewById(R.id.toggleButton);
        connectionProgressBar = findViewById(R.id.connectionProgressBar);
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
        psiphonAdManager = new PsiphonAdManager(this, findViewById(R.id.largeAdSlot),
                () -> onSubscribeButtonClick(null), viewModel.tunnelStateFlowable());
        psiphonAdManager.startLoadingAds();

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

        HandleCurrentIntent(getIntent());
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        if (startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        if (autoStartDisposable != null) {
            autoStartDisposable.dispose();
        }
        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(loggingObserver);
        cancelInvalidProxySettingsToast();
        compositeDisposable.clear();
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

        // Observe tunnel state changes to update UI
        compositeDisposable.add(viewModel.tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::updateServiceStateUI)
                .subscribe());

        // Observe custom proxy validation results to show a toast for invalid ones
        compositeDisposable.add(viewModel.customProxyValidationResultFlowable()
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
        compositeDisposable.add(viewModel.externalBrowserUrlFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(url -> displayBrowser(this, url, false))
                .subscribe());

        // Observe subscription state and set ad container layout visibility
        compositeDisposable.add(
                googlePlayBillingHelper.subscriptionStateFlowable()
                        .distinctUntilChanged()
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnNext(this::setAdBannerPlaceholderVisibility)
                        .subscribe()
        );

        boolean shouldAutoStart = shouldAutoStart();
        preventAutoStart();

        // Check if user previously ran in browser-only mode
        boolean wantVPN = multiProcessPreferences
                .getBoolean(getString(R.string.tunnelWholeDevicePreference),
                        true);
        if (wantVPN) {
            if (autoStartDisposable == null || autoStartDisposable.isDisposed()) {
                autoStartDisposable = autoStartMaybe(shouldAutoStart)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doOnSuccess(__ -> doStartUp())
                        .subscribe();
            }
        } else {
            // Legacy case: do not auto-start if last preference was BOM
            // Instead switch to the options tab and display a dialog with the help information
            selectTabByTag("settings");
            LayoutInflater inflater = this.getLayoutInflater();
            View dialogView = inflater.inflate(R.layout.legacy_bom_alert_view_layout, null);
            TextView tv = dialogView.findViewById(R.id.legacy_mode_alert_tv);
            String text = getString(R.string.legacy_bom_alert_message, getString(R.string.app_name_psiphon_pro));
            String formattedText = text.replaceAll("\n", "\n\n");
            SpannableString spannableString = new SpannableString(formattedText);

            Matcher matcher = Pattern.compile("\n\n").matcher(formattedText);
            while (matcher.find()) {
                spannableString.setSpan(new AbsoluteSizeSpan(10, true), matcher.start() + 1, matcher.end(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(spannableString);
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setView(dialogView)
                    // We are displaying important information to the user, so make sure the dialog
                    // is not dismissed accidentally as it won't be shown again.
                    .setCancelable(false)
                    .setPositiveButton(R.string.label_ok, null)
                    .setOnDismissListener(dialog ->
                            multiProcessPreferences.remove(getString(R.string.tunnelWholeDevicePreference)));
            // Add 'VPN settings' button if VPN exclusions are supported
            if (Utils.supportsVpnExclusions()) {
                builder.setNegativeButton(R.string.label_vpn_settings, (dialog, which) ->
                        viewModel.signalOpenVpnSettings());
            }
            builder.show();
        }
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
                    googlePlayBillingHelper.launchFlow(this, oldSkuString, oldPurchaseToken, skuDetails)
                            .doOnError(err -> {
                                // Show "Subscription options not available" toast.
                                showToast(R.string.subscription_options_currently_not_available);
                            })
                            .onErrorComplete()
                            .subscribe();
                } catch (JSONException | IllegalArgumentException e) {
                    Utils.MyLog.g("MainActivity::onActivityResult purchase SKU error: " + e);
                    // Show "Subscription options not available" toast.
                    showToast(R.string.subscription_options_currently_not_available);
                }
            } else {
                Utils.MyLog.g("MainActivity::onActivityResult: PaymentChooserActivity: canceled");
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
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION) {
            proceedStartTunnel();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        HandleCurrentIntent(intent);
    }

    public void onSubscribeButtonClick(View v) {
        Intent paymentChooserActivityIntent = new Intent(this, PaymentChooserActivity.class);
        paymentChooserActivityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivityForResult(paymentChooserActivityIntent, PAYMENT_CHOOSER_ACTIVITY);
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
            }
        } else {
            // Service not running
            toggleButton.setText(getText(R.string.start));
            toggleButton.setEnabled(true);
            openBrowserButton.setEnabled(false);
            connectionProgressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void displayBrowser(Context context, String urlString) {
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
        // Handle special case - external Android App Link intent which opens PsiCashStoreActivity
        // when the user navigates to psiphon://psicash
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            if (isPsiCashIntentUri(intent.getData())) {
                PsiCashFragment.openPsiCashStoreActivity(this,
                        getResources().getInteger(R.integer.psiCashTabIndex));
                return;
            }
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
        }
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
        // Tunnel core needs this dangerous permission to obtain the WiFi BSSID, which is used
        // as a key for applying tactics
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
            proceedStartTunnel();
        } else {
            AppPreferences mpPreferences = new AppPreferences(this);
            if (mpPreferences.getBoolean(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, false)) {
                proceedStartTunnel();
            } else if (!this.isFinishing()) {
                final Context context = this;
                runOnUiThread(() -> new AlertDialog.Builder(context)
                        .setCancelable(false)
                        .setOnKeyListener(
                                new DialogInterface.OnKeyListener() {
                                    @Override
                                    public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                        // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                        return keyCode == KeyEvent.KEYCODE_SEARCH;
                                    }
                                })
                        .setTitle(R.string.MainBase_AccessCoarseLocationPermissionPromptTitle)
                        .setMessage(R.string.MainBase_AccessCoarseLocationPermissionPromptMessage)
                        .setPositiveButton(R.string.MainBase_AccessCoarseLocationPermissionPositiveButton,
                                (dialog, whichButton) -> {
                                    multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                    ActivityCompat.requestPermissions(MainActivity.this,
                                            new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                            REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);
                                })
                        .setNegativeButton(R.string.MainBase_AccessCoarseLocationPermissionNegativeButton,
                                (dialog, whichButton) -> {
                                    multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                    proceedStartTunnel();
                                })
                        .setOnCancelListener(
                                dialog -> {
                                    // Do nothing (this prompt may reappear)
                                })
                        .show());
            }
        }
    }

    private void proceedStartTunnel() {
        // Don't start if custom proxy settings is selected and values are invalid
        if (!viewModel.validateCustomProxySettings()) {
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
                        .doOnNext(t -> toggleButton.setText(String.format(Locale.US, "%d", t)))
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
    }

    private void doStartUp() {
        // cancel any ongoing startUp subscription
        if (startUpInterstitialDisposable != null) {
            startUpInterstitialDisposable.dispose();
        }
        startTunnel();
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
