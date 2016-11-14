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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TabHost;
import android.widget.TextView;

import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubInterstitial.InterstitialAdListener;
import com.mopub.mobileads.MoPubView;
import com.mopub.mobileads.MoPubView.BannerAdListener;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;
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
import java.util.Map;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    private View mRateLimitedTextSection;
    private TextView mRateLimitedText;
    private TextView mRateUnlimitedText;
    private Button mRateLimitSubscribeButton;

    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private IabHelper mIabHelper = null;
    private boolean mStartIabInProgress = false;
    private boolean mIabHelperIsInitialized = false;
    private MoPubView m_moPubUntunneledBannerLargeAdView = null;
    private MoPubInterstitial m_moPubUntunneledInterstitial = null;
    private boolean m_moPubUntunneledInterstitialShowWhenLoaded = false;
    private static boolean m_startupPending = false;
    private MoPubView m_moPubTunneledBannerLargeAdView = null;
    private MoPubInterstitial m_moPubTunneledInterstitial = null;
    private int m_tunneledFullScreenAdCounter = 0;
    private boolean m_temporarilyDisableTunneledInterstitial = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.main);

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_toggleButton = (Button)findViewById(R.id.toggleButton);

        mRateLimitedTextSection = findViewById(R.id.rateLimitedTextSection);
        mRateLimitedText = (TextView)findViewById(R.id.rateLimitedText);
        mRateUnlimitedText = (TextView)findViewById(R.id.rateUnlimitedText);
        mRateLimitSubscribeButton = (Button)findViewById(R.id.rateLimitUpgradeButton);

        // NOTE: super class assumes m_tabHost is initialized in its onCreate

        // Don't let this tab change trigger an interstitial ad
        // OnResume() will reset this flag
        m_temporarilyDisableTunneledInterstitial = true;
        
        super.onCreate(savedInstanceState);

        // EmbeddedValues.initialize(this); is called in MainBase.OnCreate

        m_loadedSponsorTab = false;
        HandleCurrentIntent();

        restoreSponsorTab();
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
    protected void onResume()
    {
        startIab();
        super.onResume();
        if (m_startupPending) {
            m_startupPending = false;
            resumeServiceStateUI();
            doStartUp();
        }
    }

    @Override
    protected void onTunnelStateReceived() {
        m_temporarilyDisableTunneledInterstitial = false;
        initTunneledAds();
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

    protected void doToggle()
    {
        super.doToggle();
    }
    
    @Override
    public void onTabChanged(String tabId)
    {
        showTunneledFullScreenAd();
        super.onTabChanged(tabId);
    }

    @Override
    protected void onTunnelDisconnected() {
        deInitTunneledAds();
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
            getTunnelStateFromHandshakeIntent(intent);

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
            if (!intent.getBooleanExtra(TunnelManager.DATA_HANDSHAKE_IS_RECONNECT, false))
            {
                // Don't let this tab change trigger an interstitial ad
                // OnResume() will reset this flag
                m_temporarilyDisableTunneledInterstitial = true;
                m_tunneledFullScreenAdCounter = 0;

                m_tabHost.setCurrentTabByTag("home");
                loadSponsorTab(true);
                m_loadedSponsorTab = true;
            }

            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
        }
    }
    
    @Override
    protected void onPause()
    {
        super.onPause();
    }
    
    @Override
    public void onDestroy()
    {
        deInitAllAds();
        delayHandler.removeCallbacks(enableAdMode);
        super.onDestroy();
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
    protected void startUp()
    {
        if (Utils.getHasValidSubscription(this))
        {
            doStartUp();
        }
        else
        {
            pauseServiceStateUI();
            adModeCountdown = 10;
            delayHandler.postDelayed(enableAdMode, 1000);
            showUntunneledFullScreenAd();
        }
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
            if (!m_tunnelWholeDevicePromptShown)
            {
                final Context context = this;

                AlertDialog dialog = new AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setOnKeyListener(
                            new DialogInterface.OnKeyListener() {
                                @Override
                                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                    // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                    return keyCode == KeyEvent.KEYCODE_SEARCH;
                                }})
                    .setTitle(R.string.StatusActivity_WholeDeviceTunnelPromptTitle)
                    .setMessage(R.string.StatusActivity_WholeDeviceTunnelPromptMessage)
                    .setPositiveButton(R.string.StatusActivity_WholeDeviceTunnelPositiveButton,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    // Persist the "on" setting
                                    updateWholeDevicePreference(true);
                                    startTunnel();
                                }})
                    .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        // Turn off and persist the "off" setting
                                        m_tunnelWholeDeviceToggle.setChecked(false);
                                        updateWholeDevicePreference(false);
                                        startTunnel();
                                    }})
                    .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    // Don't change or persist preference (this prompt may reappear)
                                    startTunnel();
                                }})
                    .show();
                
                // Our text no longer fits in the AlertDialog buttons on Lollipop, so force the
                // font size (on older versions, the text seemed to be scaled down to fit).
                // TODO: custom layout
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                }
                
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
    public void displayBrowser(Context context, Uri uri)
    {
        try
        {
            if (getTunnelConfigWholeDevice())
            {
                // TODO: support multiple home pages in whole device mode. This is
                // disabled due to the case where users haven't set a default browser
                // and will get the prompt once per home page.

                if (uri == null)
                {
                    for (String homePage : getHomePages())
                    {
                        uri = Uri.parse(homePage);
                        break;
                    }
                }

                if (uri != null)
                {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                    context.startActivity(browserIntent);
                }
            }
            else
            {
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
                intent.putExtra("serviceClassName", TunnelService.class.getName());
                intent.putExtra("statusActivityClassName", StatusActivity.class.getName());
                intent.putExtra("feedbackActivityClassName", FeedbackActivity.class.getName());

                context.startActivity(intent);
            }
        }
        catch (ActivityNotFoundException e)
        {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }

    static final String IAB_PUBLIC_KEY = "";
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

            for (String limitedMonthlySubscriptionSku : IAB_LIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(limitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid limited subscription: %s", limitedMonthlySubscriptionSku));
                    rateLimit = LIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                    break;
                }
            }

            for (String unlimitedMonthlySubscriptionSku : IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKUS) {
                if (inventory.hasPurchase(unlimitedMonthlySubscriptionSku)) {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid unlimited subscription: %s", unlimitedMonthlySubscriptionSku));
                    rateLimit = UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                    break;
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(rateLimit);
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

                Purchase purchase = inventory.getPurchase(sku);
                if (purchase == null)
                {
                    continue;
                }

                long timepassExpiry = purchase.getPurchaseTime() + lifetime;
                if (now < timepassExpiry)
                {
                    // This time pass is still valid.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid time pass: %s", sku));
                    rateLimit = UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS;
                    hasValidSubscription = true;
                }
                else
                {
                    // This time pass is no longer valid. Consider it invalid and consume it below.
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: consuming old time pass: %s", sku));
                    timepassesToConsume.add(purchase);
                }
            }

            if (hasValidSubscription)
            {
                proceedWithValidSubscription(rateLimit);
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
                proceedWithValidSubscription(LIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
            }
            else if (purchase.getSku().equals(IAB_UNLIMITED_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                proceedWithValidSubscription(UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
            }
            else if (IAB_TIMEPASS_SKUS_TO_TIME.containsKey(purchase.getSku()))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));

                // We're not going to check the validity time here -- assume no time-pass is so
                // short that it's already expired right after it's purchased.
                proceedWithValidSubscription(UNLIMITED_SUBSCRIPTION_RATE_LIMIT_MBPS);
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

    private void proceedWithValidSubscription(int rateLimitMbps)
    {
        Utils.setHasValidSubscription(this, true);

        deInitAllAds();

        // Tunnel throughput is limited depending on the subscription.
        setRateLimit(rateLimitMbps);

        // Auto-start on app first run
        if (m_firstRun) {
            m_firstRun = false;
            doStartUp();
        }

        // Note: There is a possible race condition here because startIab() and binding to the
        // tunnel service are both asynchronously called from onResume(). If we get here before
        // having bound to the tunnel service, we will not perform the following restart to upgrade
        // the tunnel to unthrottled mode.
        if (isTunnelConnected()) {
            // If we're already connected, make sure we're using a tunnel with
            // the correct capabilities for the subscription.
            boolean restartRequired = getRateLimitMbps() != rateLimitMbps;
            if (restartRequired &&
                    // If the activity isn't foreground, the service won't get restarted
                    m_multiProcessPreferences.getBoolean(getString(R.string.status_activity_foreground), false)) {
                Utils.MyLog.g("StatusActivity::proceedWithValidSubscription: restarting tunnel");
                scheduleRunningTunnelServiceRestart();
            }
        }
    }

    private void proceedWithoutValidSubscription()
    {
        Utils.setHasValidSubscription(this, false);

        // Tunnel throughput is limited without a valid subscription.
        setRateLimit(AD_MODE_RATE_LIMIT_MBPS);

        if (isTunnelConnected()) {
            // If we're already connected, make sure we're using a tunnel with
            // no-valid-subscription capabilities.
            boolean restartRequired = getRateLimitMbps() != AD_MODE_RATE_LIMIT_MBPS;
            if (restartRequired &&
                    // If the activity isn't foreground, the service won't get restarted
                    m_multiProcessPreferences.getBoolean(getString(R.string.status_activity_foreground), false)) {
                Utils.MyLog.g("StatusActivity::proceedWithoutValidSubscription: restarting tunnel");
                scheduleRunningTunnelServiceRestart();
            }
        }
    }

    private void setRateLimit(int rateLimitMbps) {
        setTunnelConfigRateLimit(rateLimitMbps);

        // Update UI elements showing the current speed.
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

    private Handler delayHandler = new Handler();
    private Runnable enableAdMode = new Runnable()
    {
        @Override
        public void run()
        {
            if (adModeCountdown > 0)
            {
                m_toggleButton.setText(String.valueOf(adModeCountdown));
                adModeCountdown--;
                delayHandler.postDelayed(this, 1000);
            }
            else
            {
                resumeServiceStateUI();
                doStartUp();
            }
        }
    };
    private int adModeCountdown;

    // updateSubscriptionAndAdOptions() gets called once in onCreate().
    // Don't show these options during the first few calls, to allow time for IAB to check
    // for a valid subscription.
    private int updateSubscriptionAndAdOptionsFlickerHackCountdown = 4;

    @Override
    protected void updateSubscriptionAndAdOptions(boolean show)
    {
        if (updateSubscriptionAndAdOptionsFlickerHackCountdown > 0)
        {
            show = false;
            updateSubscriptionAndAdOptionsFlickerHackCountdown--;
        }

        if (Utils.getHasValidSubscription(this))
        {
            show = false;
        }

        if (show)
        {
            initUntunneledBanner();

            if (m_moPubUntunneledInterstitial == null)
            {
                loadUntunneledFullScreenAd();
            }
        }
        else
        {
            // Abort any outstanding ad requests
            deInitUntunneledAds();
        }

        boolean showSubscribe = show && (mInventory != null);

        findViewById(R.id.subscribeButton).setVisibility(showSubscribe ? View.VISIBLE : View.GONE);
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

    static final String MOPUB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID = "6490d145426a418db838f640c26edb77";
    static final String MOPUB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID = "0d4cf70da6504af5878f0b3592808852";
    static final String MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID = "6efb5aa4e0d74a679a6219f9b3aa6221";
    static final String MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID = "1f9cb36809f04c8d9feaff5deb9f17ed";

    @Override
    protected void updateAdsForServiceState() {
        if (isServiceRunning()) {
            deInitUntunneledAds();
        } else {
            deInitTunneledAds();
        }
    }

    private void initUntunneledBanner()
    {
        if (m_moPubUntunneledBannerLargeAdView == null)
        {
            m_moPubUntunneledBannerLargeAdView = new MoPubView(this);
            m_moPubUntunneledBannerLargeAdView.setAdUnitId(MOPUB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID);

            m_moPubUntunneledBannerLargeAdView.setBannerAdListener(new MoPubView.BannerAdListener() {
                @Override
                public void onBannerLoaded(MoPubView banner)
                {
                    if (m_moPubUntunneledBannerLargeAdView.getParent() == null)
                    {
                        LinearLayout layout = (LinearLayout)findViewById(R.id.largeAdSlot);
                        layout.removeAllViewsInLayout();
                        layout.addView(m_moPubUntunneledBannerLargeAdView);
                    }
                }
                @Override
                public void onBannerClicked(MoPubView arg0) {
                }
                @Override
                public void onBannerCollapsed(MoPubView arg0) {
                }
                @Override
                public void onBannerExpanded(MoPubView arg0) {
                }
                @Override
                public void onBannerFailed(MoPubView arg0,
                                           MoPubErrorCode arg1) {
                }
            });

            m_moPubUntunneledBannerLargeAdView.loadAd();
            m_moPubUntunneledBannerLargeAdView.setAutorefreshEnabled(true);
        }
    }

    synchronized
    private void loadUntunneledFullScreenAd()
    {
        if (m_moPubUntunneledInterstitial != null)
        {
            m_moPubUntunneledInterstitial.destroy();
        }
        m_moPubUntunneledInterstitial = new MoPubInterstitial(this, MOPUB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID);

        m_moPubUntunneledInterstitial.setInterstitialAdListener(new InterstitialAdListener() {

            @Override
            public void onInterstitialClicked(MoPubInterstitial arg0) {
            }
            @Override
            public void onInterstitialDismissed(MoPubInterstitial arg0) {
            }
            @Override
            public void onInterstitialFailed(MoPubInterstitial interstitial,
                    MoPubErrorCode errorCode) {
            }
            @Override
            public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                if (interstitial != null && interstitial.isReady() &&
                        m_moPubUntunneledInterstitialShowWhenLoaded)
                {
                    interstitial.show();
                }
            }
            @Override
            public void onInterstitialShown(MoPubInterstitial arg0) {
                // Enable the free trial right away
                m_startupPending = true;
                delayHandler.removeCallbacks(enableAdMode);
                resumeServiceStateUI();
            }
        });

        m_moPubUntunneledInterstitialShowWhenLoaded = false;
        m_moPubUntunneledInterstitial.load();
    }

    private void showUntunneledFullScreenAd()
    {
        if (m_moPubUntunneledInterstitial != null)
        {
            if (m_moPubUntunneledInterstitial.isReady())
            {
                m_moPubUntunneledInterstitial.show();
            }
            else
            {
                m_moPubUntunneledInterstitialShowWhenLoaded = true;
            }
        }
    }

    synchronized
    private void deInitUntunneledAds()
    {
        if (m_moPubUntunneledBannerLargeAdView != null)
        {
            m_moPubUntunneledBannerLargeAdView.destroy();
        }
        m_moPubUntunneledBannerLargeAdView = null;

        if (m_moPubUntunneledInterstitial != null)
        {
            m_moPubUntunneledInterstitial.destroy();
        }
        m_moPubUntunneledInterstitial = null;
    }


    private boolean shouldShowTunneledAds()
    {
        return !Utils.getHasValidSubscription(this) &&
                isTunnelConnected() &&
                Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO;
    }

    private void initTunneledAds()
    {
        if (shouldShowTunneledAds())
        {
            // make sure WebView proxy settings are up to date
            WebViewProxySettings.setLocalProxy(this, getListeningLocalHttpProxyPort());

            initTunneledBanner();
            loadTunneledFullScreenAd();
        }
    }

    private void initTunneledBanner()
    {
        if (shouldShowTunneledAds())
        {
            if (!showFirstHomePageInApp() && m_moPubTunneledBannerLargeAdView == null)
            {
                m_moPubTunneledBannerLargeAdView = new MoPubView(this);
                m_moPubTunneledBannerLargeAdView.setAdUnitId(MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubTunneledBannerLargeAdView.setKeywords("client_region:" + getClientRegion());
                }

                m_moPubTunneledBannerLargeAdView.setBannerAdListener(new BannerAdListener() {
                    @Override
                    public void onBannerLoaded(MoPubView banner)
                    {
                        if (m_moPubTunneledBannerLargeAdView.getParent() == null)
                        {
                            LinearLayout layout = (LinearLayout)findViewById(R.id.largeAdSlot);
                            layout.removeAllViewsInLayout();
                            layout.addView(m_moPubTunneledBannerLargeAdView);
                        }
                    }
                    @Override
                    public void onBannerClicked(MoPubView arg0) {
                    }
                    @Override
                    public void onBannerCollapsed(MoPubView arg0) {
                    }
                    @Override
                    public void onBannerExpanded(MoPubView arg0) {
                    }
                    @Override
                    public void onBannerFailed(MoPubView arg0,
                                               MoPubErrorCode arg1) {
                    }
                });

                m_moPubTunneledBannerLargeAdView.loadAd();
                m_moPubTunneledBannerLargeAdView.setAutorefreshEnabled(true);
            }
        }
    }

    synchronized
    private void loadTunneledFullScreenAd()
    {
        if (shouldShowTunneledAds() && m_moPubTunneledInterstitial == null)
        {
            m_moPubTunneledInterstitial = new MoPubInterstitial(this, MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID);
            if (isTunnelConnected()) {
                m_moPubTunneledInterstitial.setKeywords("client_region:" + getClientRegion());
            }

            m_moPubTunneledInterstitial.setInterstitialAdListener(new InterstitialAdListener() {
                @Override
                public void onInterstitialClicked(MoPubInterstitial arg0) {
                }
                @Override
                public void onInterstitialDismissed(MoPubInterstitial arg0) {
                    m_moPubTunneledInterstitial.load();
                }
                @Override
                public void onInterstitialFailed(MoPubInterstitial arg0,
                                                 MoPubErrorCode arg1) {
                    m_moPubTunneledInterstitial.load();
                }
                @Override
                public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                }
                @Override
                public void onInterstitialShown(MoPubInterstitial arg0) {
                }
            });
            m_moPubTunneledInterstitial.load();
        }
    }

    private void showTunneledFullScreenAd()
    {
        if (shouldShowTunneledAds() && !m_temporarilyDisableTunneledInterstitial)
        {
            if (m_tunneledFullScreenAdCounter % 3 == 0)
            {
                if (m_moPubTunneledInterstitial != null && m_moPubTunneledInterstitial.isReady())
                {
                    m_tunneledFullScreenAdCounter++;
                    m_moPubTunneledInterstitial.show();
                }
            }
            else
            {
                m_tunneledFullScreenAdCounter++;
            }
        }
    }

    synchronized
    private void deInitTunneledAds()
    {
        if (m_moPubTunneledBannerLargeAdView != null)
        {
            m_moPubTunneledBannerLargeAdView.destroy();
        }
        m_moPubTunneledBannerLargeAdView = null;

        if (m_moPubTunneledInterstitial != null)
        {
            m_moPubTunneledInterstitial.destroy();
        }
        m_moPubTunneledInterstitial = null;
    }

    synchronized
    private void deInitAllAds()
    {
        deInitUntunneledAds();
        deInitTunneledAds();
    }
}
