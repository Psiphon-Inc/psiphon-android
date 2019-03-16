/*
 * Copyright (c) 2016, Psiphon Inc.
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

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.Toast;

import com.psiphon3.psicash.psicash.PsiCashClient;
import com.psiphon3.psicash.psicash.PsiCashException;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.TunnelState;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;
import com.psiphon3.util.IabHelper;
import com.psiphon3.util.IabResult;
import com.psiphon3.util.Inventory;
import com.psiphon3.util.Purchase;
import com.psiphon3.util.SkuDetails;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    private View mRateLimitedTextSection;
    private TextView mRateLimitedText;
    private TextView mRateUnlimitedText;
    private Button mRateLimitSubscribeButton;

    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private static boolean m_startupPending = false;
    private IabHelper mIabHelper = null;
    private boolean mStartIabInProgress = false;
    private boolean mIabHelperIsInitialized = false;

    private PsiCashFragment psiCashFragment;
    private RewardedVideoFragment rewardedVideoFragment;

    private PsiphonAdManager psiphonAdManager;
    private Disposable startUpInterstitialDisposable;
    private boolean disableInterstitialOnNextTabChange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.main);

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_tabSpecsList = new ArrayList<>();
        m_toggleButton = (Button)findViewById(R.id.toggleButton);

        mRateLimitedTextSection = findViewById(R.id.rateLimitedTextSection);
        mRateLimitedText = (TextView)findViewById(R.id.rateLimitedText);
        mRateUnlimitedText = (TextView)findViewById(R.id.rateUnlimitedText);
        mRateLimitSubscribeButton = (Button)findViewById(R.id.rateLimitUpgradeButton);

        // PsiCash and rewarded video modules
        FragmentManager fm = getSupportFragmentManager();
        psiCashFragment = (PsiCashFragment) fm.findFragmentById(R.id.psicash_fragment_container);
        rewardedVideoFragment = (RewardedVideoFragment) fm.findFragmentById(R.id.rewarded_fragment_container);

        // ads
        psiphonAdManager = new PsiphonAdManager(this, findViewById(R.id.largeAdSlot),
                () -> onSubscribeButtonClick(null), true);

        psiphonAdManager.startLoadingAds();

        setupActivityLayout();

        // Listen to GOT_NEW_EXPIRING_PURCHASE intent from psicash module
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

        if (isServiceRunning()) {
            // m_firstRun indicates if we should automatically start the tunnel. If the service is
            // already running, we can reset this flag.
            // This mitigates the scenario where the user starts the Activity while the tunnel is
            // running and presses Stop before the IAB flow has completed, causing handleIabFailure
            // to immediately restart the tunnel.
            m_firstRun = false;
        }

        // Initialize WebView proxy settings before attempting to load any URLs
        WebViewProxySettings.initialize(this);

        m_loadedSponsorTab = false;
        HandleCurrentIntent();
    }

    @Override
    protected void restoreSponsorTab() {
        // HandleCurrentIntent() may have already loaded the sponsor tab
        if (isTunnelConnected() && !m_loadedSponsorTab)
        {
            loadSponsorTab(false);
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
    }

    @Override
    protected void onResume()
    {
        startIab();
        super.onResume();
        if (m_startupPending) {
            m_startupPending = false;
            doStartUp();
        }
    }

    @Override
    public void onDestroy()
    {
        psiphonAdManager.onDestroy();
        super.onDestroy();
    }

    private void hidePsiCashTab() {
        for (int i = 0; i < m_tabSpecsList.size(); i++) {
            TabHost.TabSpec tabSpec = m_tabSpecsList.get(i);
            if (tabSpec != null && tabSpec.getTag().equals(PSICASH_TAB_TAG))
                m_tabHost.getTabWidget().getChildTabViewAt(i).setVisibility(View.GONE);
        }
    }

    private void showPsiCashTabIfHasValidToken() {
        // Hide or show the PsiCash tab depending on presence of valid PsiCash tokens
        try {
            if (PsiCashClient.getInstance(this).hasValidTokens()) {
                for (int i = 0; i < m_tabSpecsList.size(); i++) {
                    TabHost.TabSpec tabSpec = m_tabSpecsList.get(i);
                    if (tabSpec != null && tabSpec.getTag().equals(PSICASH_TAB_TAG))
                        m_tabHost.getTabWidget().getChildTabViewAt(i).setVisibility(View.VISIBLE);
                }
            } else {
                hidePsiCashTab();
            }
        } catch (PsiCashException e) {
            MyLog.g("Error showing or hiding PsiCash tab: " + e);
        }
    }

    private void loadSponsorTab(boolean freshConnect)
    {
        if (!getSkipHomePage())
        {
            resetSponsorHomePage(freshConnect);
        }
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
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
    protected PendingIntent getHandshakePendingIntent() {
        Intent intent = new Intent(
                TunnelManager.INTENT_ACTION_HANDSHAKE,
                null,
                this,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected PendingIntent getServiceNotificationPendingIntent() {
        Intent intent = new Intent(
                "ACTION_VIEW",
                null,
                this,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected PendingIntent getRegionNotAvailablePendingIntent() {
        Intent intent = new Intent(
                TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE,
                null,
                this,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    protected PendingIntent getVpnRevokedPendingIntent() {
        Intent intent = new Intent(
                TunnelManager.INTENT_ACTION_VPN_REVOKED,
                null,
                this,
                com.psiphon3.StatusActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    protected void doToggle()
    {
        super.doToggle();
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

    @Override
    protected void onTunnelConnectionState(@NonNull TunnelManager.State state) {
        super.onTunnelConnectionState(state);
        TunnelState tunnelState;
        if (state.isRunning) {
            TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                    .setIsConnected(state.isConnected)
                    .setClientRegion(state.clientRegion)
                    .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                    .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                    .setSponsorId(state.sponsorId)
                    .setHttpPort(state.listeningLocalHttpProxyPort)
                    .setVpnMode(state.isVPN)
                    .build();
            tunnelState = TunnelState.running(connectionData);
        } else {
            tunnelState = TunnelState.stopped();
        }

        psiCashFragment.onTunnelConnectionState(tunnelState);
        rewardedVideoFragment.onTunnelConnectionState(tunnelState);
        psiphonAdManager.onTunnelConnectionState(tunnelState);
    }

    protected void HandleCurrentIntent()
    {
        Intent intent = getIntent();

        if (intent == null || intent.getAction() == null)
        {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE))
        {
            onTunnelConnectionState(getTunnelStateFromBundle(intent.getExtras()));

            // OLD COMMENT:
            // Show the home page. Always do this in browser-only mode, even
            // after an automated reconnect -- since the status activity was
            // brought to the front after an unexpected disconnect. In whole
            // device mode, after an automated reconnect, we don't re-invoke
            // the browser.
            // UPDATED:
            // We don't bring the status activity to the front after an
            // unexpected disconnect in browser-only mode any more.
            // Show the home page, unless this was an automatic reconnect,
            // since the homepage should already be showing.
            // UPDATED #2:
            // This intent is only sent when there was a commanded service start or a restart
            // such as when there's a new subscription, or a speed-boost. It is not sent
            // on automated reconnects or when the app binds to a an already running service.
            // In later case the embedded web view gets updated via MSG_TUNNEL_CONNECTION_STATE
            // messages from a bound service.

            // TODO: only switch to home tab when there's an in app home page to show?
            disableInterstitialOnNextTabChange = true;
            m_tabHost.setCurrentTabByTag(HOME_TAB_TAG);
            loadSponsorTab(true);
            m_loadedSponsorTab = true;

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
            Toast toast = Toast.makeText(this, R.string.selected_region_currently_not_available, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();

            // We only want to respond to the INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                    "ACTION_VIEW",
                    null,
                    this,
                    this.getClass()));
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
        }
    }
    
    public void onToggleClick(View v)
    {
        doToggle();
    }

    public void onOpenBrowserClick(View v)
    {
        displayBrowser(this, null);
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
        startUpInterstitialDisposable = psiphonAdManager.getCurrentAdTypeObservable()
                .take(1)
                .flatMap(adResult -> {
                    if (adResult.type() == PsiphonAdManager.AdResult.Type.NONE) {
                        m_startupPending = true;
                        return Observable.empty();
                    }
                    else if (adResult.type() == PsiphonAdManager.AdResult.Type.TUNNELED) {
                        Log.w(PsiphonAdManager.TAG, "startUp interstitial ad bad type: " + adResult.type());
                        return Observable.empty();
                    }

                    Observable<PsiphonAdManager.InterstitialResult> interstitial =
                            Observable.just(adResult)
                                    .compose(psiphonAdManager.getInterstitialWithTimeoutForAdType(countdownSeconds, TimeUnit.SECONDS))
                                    .share();

                    Observable<Long> countdown =
                            Observable.intervalRange(0, countdownSeconds, 0, 1, TimeUnit.SECONDS)
                                    .map(t -> countdownSeconds - t)
                                    .concatWith(Observable.error(new TimeoutException("Ad countdown timeout.")))
                                    .takeUntil(interstitial)
                                    .doOnNext(t -> m_toggleButton.setText(String.format(Locale.US, "%d", t)));

                    return Observable.mergeDelayError(countdown, interstitial)
                            .doOnNext(o -> {
                                if (!(o instanceof PsiphonAdManager.InterstitialResult)) {
                                    return;
                                }
                                PsiphonAdManager.InterstitialResult interstitialResult = (PsiphonAdManager.InterstitialResult) o;
                                if (interstitialResult.state() == PsiphonAdManager.InterstitialResult.State.READY) {
                                    m_startupPending = true;
                                    interstitialResult.show();
                                }
                            })
                            .doOnError(__ -> doStartUp());
                })
                .doOnSubscribe(__ -> disableToggleServiceUI())
                .doOnComplete(() -> {
                    if(m_startupPending) {
                        m_startupPending = false;
                        doStartUp();
                    }
                })
                .onErrorResumeNext(Observable.empty())
                .subscribe();
    }
    
    private void doStartUp()
    {
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)

        boolean hasPreference;
        AppPreferences mpPreferences = new AppPreferences(this);
        try {
            mpPreferences.getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE);
            hasPreference = true;
        } catch (ItemNotFoundException e) {
            hasPreference = false;
        }

        if (m_tunnelWholeDeviceToggle.isEnabled() &&
            !hasPreference &&
            !isServiceRunning())
        {
            if (!m_tunnelWholeDevicePromptShown && !this.isFinishing())
            {
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        {
                            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                        }
                    }
                });

                m_tunnelWholeDevicePromptShown = true;
            }
            else
            {
                // ...there's a prompt already showing (e.g., user hit Home with the
                // prompt up, then resumed Psiphon)
            }

            // ...wait and let onClick handlers will start tunnel
        }
        else
        {
            // No prompt, just start the tunnel (if not already running)
            startTunnel();
        }
    }

    @Override
    public void displayBrowser(Context context, Uri uri) {
        if (uri == null) {
            for (String homePage : getHomePages()) {
                uri = Uri.parse(homePage);
                break;
            }
        }

        // No URI to display - do nothing
        if (uri == null) {
            return;
        }

        try {
            if (getTunnelConfigWholeDevice()) {
                // TODO: support multiple home pages in whole device mode. This is
                // disabled due to the case where users haven't set a default browser
                // and will get the prompt once per home page.

                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ResolveInfo resolveInfo = getPackageManager().resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY);
                if (resolveInfo == null || resolveInfo.activityInfo == null ||
                        resolveInfo.activityInfo.name == null || resolveInfo.activityInfo.name.toLowerCase().contains("resolver")) {
                    // No default web browser is set, so try opening in Chrome
                    browserIntent.setPackage("com.android.chrome");
                    try {
                        context.startActivity(browserIntent);
                    } catch (ActivityNotFoundException ex) {
                        // We tried to open Chrome and it is not installed,
                        // so reinvoke with the default behaviour
                        browserIntent.setPackage(null);
                        context.startActivity(browserIntent);
                    }
                } else {
                    context.startActivity(browserIntent);
                }
            } else {
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

                intent.putExtra("localProxyPort", getListeningLocalHttpProxyPort());
                intent.putExtra("homePages", getHomePages());

                context.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }

    static final String IAB_PUBLIC_KEY = BuildConfig.IAB_PUBLIC_KEY;
    static final int IAB_REQUEST_CODE = 10001;

    static final String IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU = "speed_limited_ad_free_subscription";
    static final String[] IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS = {IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU};
    static final String IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU = "basic_ad_free_subscription_5";
    static final String[] IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS = {IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU,
            "basic_ad_free_subscription", "basic_ad_free_subscription_2", "basic_ad_free_subscription_3", "basic_ad_free_subscription_4"};

    static final String IAB_BASIC_7DAY_TIMEPASS_SKU = "basic_ad_free_7_day_timepass";
    static final String IAB_BASIC_30DAY_TIMEPASS_SKU = "basic_ad_free_30_day_timepass";
    static final String IAB_BASIC_360DAY_TIMEPASS_SKU = "basic_ad_free_360_day_timepass";
    static final Map<String, Long> IAB_TIMEPASS_SKUS_TO_TIME;
    static {
        Map<String, Long> m = new HashMap<>();
        m.put(IAB_BASIC_7DAY_TIMEPASS_SKU, 7L * 24 * 60 * 60 * 1000);
        m.put(IAB_BASIC_30DAY_TIMEPASS_SKU, 30L * 24 * 60 * 60 * 1000);
        m.put(IAB_BASIC_360DAY_TIMEPASS_SKU, 360L * 24 * 60 * 60 * 1000);
        IAB_TIMEPASS_SKUS_TO_TIME = Collections.unmodifiableMap(m);
    }

    static int AD_MODE_RATE_LIMIT_MBPS = 2;
    static int LIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS = 5;
    static int UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS = 0;

    Inventory mInventory;

    synchronized
    private void startIab()
    {
        if (mStartIabInProgress)
        {
            return;
        }

        if (mIabHelper == null)
        {
            mStartIabInProgress = true;
            mIabHelper = new IabHelper(this, IAB_PUBLIC_KEY);
            mIabHelper.startSetup(m_iabSetupFinishedListener);
        }
        else
        {
            queryInventory();
        }
    }

    private boolean isIabInitialized() {
        return mIabHelper != null && mIabHelperIsInitialized;
    }
    
    private IabHelper.OnIabSetupFinishedListener m_iabSetupFinishedListener =
            new IabHelper.OnIabSetupFinishedListener()
    {
        @Override
        public void onIabSetupFinished(IabResult result)
        {
            mStartIabInProgress = false;

            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabSetupFinished: failure: %s", result));
                handleIabFailure(result);
            }
            else
            {
                mIabHelperIsInitialized = true;
                Utils.MyLog.g(String.format("StatusActivity::onIabSetupFinished: success: %s", result));
                queryInventory();
            }
        }
    };

    private IabHelper.QueryInventoryFinishedListener m_iabQueryInventoryFinishedListener =
            new IabHelper.QueryInventoryFinishedListener()
    {
        @Override
        public void onQueryInventoryFinished(IabResult result, Inventory inventory)
        {
            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: failure: %s", result));
                handleIabFailure(result);
                return;
            }

            mInventory = inventory;

            boolean hasValidSubscription = false;

            //
            // Check if the user has a subscription.
            //

            int rateLimit = AD_MODE_RATE_LIMIT_MBPS;
            Purchase purchase = null;

            for (String limitedMonthlySubscriptionSku : IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(limitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid limited subscription: %s", limitedMonthlySubscriptionSku));
                    purchase = inventory.getPurchase(limitedMonthlySubscriptionSku);
                    rateLimit = LIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                    break;
                }
            }

            for (String unlimitedMonthlySubscriptionSku : IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(unlimitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid unlimited subscription: %s", unlimitedMonthlySubscriptionSku));
                    purchase = inventory.getPurchase(unlimitedMonthlySubscriptionSku);
                    rateLimit = UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                    break;
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(purchase, rateLimit);
                return;
            }

            //
            // Check if the user has purchased a (30-day) time pass.
            //

            long now = System.currentTimeMillis();
            List<Purchase> timepassesToConsume = new ArrayList<>();
            for (Map.Entry<String, Long> timepass : IAB_TIMEPASS_SKUS_TO_TIME.entrySet())
            {
                String sku = timepass.getKey();
                long lifetime = timepass.getValue();

                // DEBUG: This line will convert days to minutes. Useful for testing.
                //lifetime = lifetime / 24 / 60;

                Purchase tempPurchase = inventory.getPurchase(sku);
                if (tempPurchase == null)
                {
                    continue;
                }

                long timepassExpiry = tempPurchase.getPurchaseTime() + lifetime;
                if (now < timepassExpiry)
                {
                    // This time pass is still valid.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid time pass: %s", sku));
                    rateLimit = UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                    purchase = tempPurchase;
                }
                else
                {
                    // This time pass is no longer valid. Consider it invalid and consume it below.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: consuming old time pass: %s", sku));
                    timepassesToConsume.add(tempPurchase);
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(purchase, rateLimit);
            }
            else
            {
                // There is no valid subscription or time pass for this user.
                Utils.MyLog.g("StatusActivity::onQueryInventoryFinished: no valid subscription or time pass");
                proceedWithoutValidSubscription();
            }

            if (timepassesToConsume.size() > 0)
            {
                consumePurchases(timepassesToConsume);
            }
        }
    };
    
    private IabHelper.OnIabPurchaseFinishedListener m_iabPurchaseFinishedListener = 
            new IabHelper.OnIabPurchaseFinishedListener()
    {
        @Override
        public void onIabPurchaseFinished(IabResult result, Purchase purchase) 
        {
            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: failure: %s", result));
                handleIabFailure(result);
            }
            else if (purchase.getSku().equals(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                proceedWithValidSubscription(purchase, LIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
            }
            else if (purchase.getSku().equals(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                proceedWithValidSubscription(purchase, UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
            }
            else if (IAB_TIMEPASS_SKUS_TO_TIME.containsKey(purchase.getSku()))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));

                // We're not going to check the validity time here -- assume no time-pass is so
                // short that it's already expired right after it's purchased.
                proceedWithValidSubscription(purchase, UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
            }
        }
    };

    private IabHelper.OnConsumeMultiFinishedListener m_iabConsumeFinishedListener =
            new IabHelper.OnConsumeMultiFinishedListener()
    {
        @Override
        public void onConsumeMultiFinished(List<Purchase> purchases, List<IabResult> results)
        {
            boolean failed = false;
            for (IabResult result : results)
            {
                if (result.isFailure())
                {
                    Utils.MyLog.g(String.format("StatusActivity::onConsumeMultiFinished: failure: %s", result));
                    failed = true;
                }
                else
                {
                    Utils.MyLog.g("StatusActivity::onConsumeMultiFinished: success");
                }
            }

            if (failed)
            {
                handleIabFailure(null);
            }
        }
    };
    
    private void queryInventory()
    {
        try
        {
            if (isIabInitialized())
            {
                List<String> timepassSkus = new ArrayList<>();
                timepassSkus.addAll(IAB_TIMEPASS_SKUS_TO_TIME.keySet());

                List<String> subscriptionSkus = new ArrayList<>();
                subscriptionSkus.add(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
                subscriptionSkus.add(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);

                mIabHelper.queryInventoryAsync(
                        true,
                        timepassSkus,
                        subscriptionSkus,
                        m_iabQueryInventoryFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    private void consumePurchases(List<Purchase> purchases)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.consumeAsync(purchases, m_iabConsumeFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    /**
     * Begin the flow for subscribing to premium access.
     */
    private void launchSubscriptionPurchaseFlow(String sku)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.launchSubscriptionPurchaseFlow(
                        this,
                        sku,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    /**
     * Begin the flow for making a one-time purchase of time-limited premium access.
     */
    private void launchTimePassPurchaseFlow(String sku)
    {
        try
        {
            if (isIabInitialized())
            {
                mIabHelper.launchPurchaseFlow(this, sku,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
        catch (IabHelper.IabAsyncInProgressException ex)
        {
            // Allow outstanding IAB request to finish.
        }
    }

    private void proceedWithValidSubscription(Purchase purchase, int rateLimitMbps)
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIBER);
        Utils.setHasValidSubscription(this, true);
        setRateLimitUI(rateLimitMbps);
        this.m_retainedDataFragment.setCurrentPurchase(purchase);
        hidePsiCashTab();

        // Pass the most current purchase data to the service if it is running so the tunnel has a
        // chance to update authorization and restart if the purchase is new.
        // NOTE: we assume there can be only one valid purchase and authorization at a time
        if (isTunnelConnected()) {
                startAndBindTunnelService();
        }

        // Auto-start on app first run
        if (m_firstRun) {
            m_firstRun = false;
            doStartUp();
        }
    }

    private void proceedWithoutValidSubscription()
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.NOT_SUBSCRIBER);
        Utils.setHasValidSubscription(this, false);
        setRateLimitUI(AD_MODE_RATE_LIMIT_MBPS);
        this.m_retainedDataFragment.setCurrentPurchase(null);
        showPsiCashTabIfHasValidToken();
    }

    private void setRateLimitUI(int rateLimitMbps) {
        // Update UI elements showing the current speed.^M
        if (rateLimitMbps > 0) {
            mRateLimitedText.setText(getString(R.string.rate_limit_text_limited, rateLimitMbps));
            mRateLimitedText.setVisibility(View.VISIBLE);
            mRateUnlimitedText.setVisibility(View.GONE);
            mRateLimitSubscribeButton.setVisibility(View.VISIBLE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        } else {
            mRateLimitedText.setVisibility(View.GONE);
            mRateUnlimitedText.setVisibility(View.VISIBLE);
            mRateLimitSubscribeButton.setVisibility(View.GONE);
            mRateLimitedTextSection.setVisibility(View.VISIBLE);
        }
    }

    // NOTE: result param may be null
    private void handleIabFailure(IabResult result)
    {
        psiphonAdManager.onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus.SUBSCRIPTION_CHECK_FAILED);
        // try again next time
        deInitIab();

        if (result != null &&
            result.getResponse() == IabHelper.IABHELPER_USER_CANCELLED)
        {
            // do nothing, onResume() calls startIAB()
        }
        else
        {
            // Start the tunnel anyway, IAB will get checked again once the tunnel is connected
            if (m_firstRun)
            {
                m_firstRun = false;
                doStartUp();
            }
        }
    }

    public void onRateLimitUpgradeButtonClick(View v) {
        if (!Utils.getHasValidSubscription(this)) {
            onSubscribeButtonClick(v);
            return;
        }

        try {
            if (isIabInitialized()) {
                // Replace any subscribed limited monthly subscription SKUs with the unlimited SKU
                mIabHelper.launchPurchaseFlow(
                        this,
                        IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU,
                        IabHelper.ITEM_TYPE_SUBS,
                        Arrays.asList(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS),
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener, "");
            }
        } catch (IllegalStateException ex) {
            handleIabFailure(null);
        } catch (IabHelper.IabAsyncInProgressException ex) {
            // Allow outstanding IAB request to finish.
        }
    }

    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    @Override
    public void onSubscribeButtonClick(View v)
    {
        Utils.MyLog.g("StatusActivity::onSubscribeButtonClick");

        // Do nothing in this case (instead of crashing).
        if (mInventory == null)
        {
            return;
        }

        // User has clicked the Subscribe button, now let them choose the payment method.

        Intent feedbackIntent = new Intent(this, PaymentChooserActivity.class);

        // Pass price and SKU info to payment chooser activity.
        PaymentChooserActivity.SkuInfo skuInfo = new PaymentChooserActivity.SkuInfo();

        SkuDetails limitedSubscriptionSkuDetails = mInventory.getSkuDetails(IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKU);
        skuInfo.mLimitedSubscriptionInfo.sku = limitedSubscriptionSkuDetails.getSku();
        skuInfo.mLimitedSubscriptionInfo.price = limitedSubscriptionSkuDetails.getPrice();
        skuInfo.mLimitedSubscriptionInfo.priceMicros = limitedSubscriptionSkuDetails.getPriceAmountMicros();
        skuInfo.mLimitedSubscriptionInfo.priceCurrency = limitedSubscriptionSkuDetails.getPriceCurrencyCode();
        // This is a subscription, so lifetime doesn't really apply. However, to keep things sane
        // we'll set it to 30 days.
        skuInfo.mLimitedSubscriptionInfo.lifetime = 30L * 24 * 60 * 60 * 1000;

        SkuDetails unlimitedSubscriptionSkuDetails = mInventory.getSkuDetails(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU);
        skuInfo.mUnlimitedSubscriptionInfo.sku = unlimitedSubscriptionSkuDetails.getSku();
        skuInfo.mUnlimitedSubscriptionInfo.price = unlimitedSubscriptionSkuDetails.getPrice();
        skuInfo.mUnlimitedSubscriptionInfo.priceMicros = unlimitedSubscriptionSkuDetails.getPriceAmountMicros();
        skuInfo.mUnlimitedSubscriptionInfo.priceCurrency = unlimitedSubscriptionSkuDetails.getPriceCurrencyCode();
        // This is a subscription, so lifetime doesn't really apply. However, to keep things sane
        // we'll set it to 30 days.
        skuInfo.mUnlimitedSubscriptionInfo.lifetime = 30L * 24 * 60 * 60 * 1000;

        for (Map.Entry<String, Long> timepassSku : IAB_TIMEPASS_SKUS_TO_TIME.entrySet())
        {
            SkuDetails timepassSkuDetails = mInventory.getSkuDetails(timepassSku.getKey());
            PaymentChooserActivity.SkuInfo.Info info = new PaymentChooserActivity.SkuInfo.Info();

            info.sku = timepassSkuDetails.getSku();
            info.price = timepassSkuDetails.getPrice();
            info.priceMicros = timepassSkuDetails.getPriceAmountMicros();
            info.priceCurrency = timepassSkuDetails.getPriceCurrencyCode();
            info.lifetime = timepassSku.getValue();

            skuInfo.mTimePassSkuToInfo.put(info.sku, info);
        }

        feedbackIntent.putExtra(PaymentChooserActivity.SKU_INFO_EXTRA, skuInfo.toString());

        startActivityForResult(feedbackIntent, PAYMENT_CHOOSER_ACTIVITY);
    }

    synchronized
    private void deInitIab()
    {
        mInventory = null;
        mIabHelperIsInitialized = false;
        if (mIabHelper != null)
        {
            try {
                mIabHelper.dispose();
            }
            catch (IabHelper.IabAsyncInProgressException ex)
            {
                // Nothing can help at this point. Continue to de-init.
            }

            mIabHelper = null;
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == IAB_REQUEST_CODE)
        {
            if (isIabInitialized())
            {
                mIabHelper.handleActivityResult(requestCode, resultCode, data);
            }
        }
        else if (requestCode == PAYMENT_CHOOSER_ACTIVITY)
        {
            if (resultCode == RESULT_OK)
            {
                int buyType = data.getIntExtra(PaymentChooserActivity.BUY_TYPE_EXTRA, -1);
                if (buyType == PaymentChooserActivity.BUY_SUBSCRIPTION)
                {
                    Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: subscription");
                    String sku = data.getStringExtra(PaymentChooserActivity.SKU_INFO_EXTRA);
                    launchSubscriptionPurchaseFlow(sku);
                }
                else if (buyType == PaymentChooserActivity.BUY_TIMEPASS)
                {
                    Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: time pass");
                    String sku = data.getStringExtra(PaymentChooserActivity.SKU_INFO_EXTRA);
                    launchTimePassPurchaseFlow(sku);
                }
            }
            else
            {
                Utils.MyLog.g("StatusActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        }
        else
        {
            super.onActivityResult(requestCode, resultCode, data);
        }
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

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE)) {
                    scheduleRunningTunnelServiceRestart();
                }
            }
        }
    };
}
