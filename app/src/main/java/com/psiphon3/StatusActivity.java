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
import android.text.format.DateUtils;
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
import com.psiphon3.psiphonlibrary.FreeTrialTimer;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.SupersonicRewardedVideoWrapper;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.Utils;
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
    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private IabHelper m_iabHelper = null;
    private boolean m_startIabInFlight = false;
    private MoPubView m_moPubBannerLargeAdView = null;
    private MoPubInterstitial m_moPubInterstitial = null;
    private boolean m_moPubInterstitialShowWhenLoaded = false;
    private SupersonicRewardedVideoWrapper m_supersonicWrapper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.main);

        m_tabHost = (TabHost) findViewById(R.id.tabHost);
        m_toggleButton = (Button) findViewById(R.id.toggleButton);

        // NOTE: super class assumes m_tabHost is initialized in its onCreate

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

        // Reset the FreeTrialTimerCachingWrapper because the tunnel service might modify the free trial timer independently
        FreeTrialTimer.getFreeTrialTimerCachingWrapper().reset();

        super.onResume();

        if(m_supersonicWrapper != null) {
            m_supersonicWrapper.onResume();
        }
    }

    private void loadSponsorTab(boolean freshConnect)
    {
        resetSponsorHomePage(freshConnect);
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

            if (!Utils.getHasValidSubscriptionOrFreeTime(this))
            {
                startIab();
            }
            
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
        if (isTunnelConnected() &&
                !Utils.getHasValidSubscriptionOrFreeTime(this))
        {
            doToggle();
        }
        if(m_supersonicWrapper != null) {
            m_supersonicWrapper.onPause();
        }
        super.onPause();
    }
    
    @Override
    public void onDestroy()
    {
        deInitAds();
        delayHandler.removeCallbacks(enableFreeTrial);
        if(m_supersonicWrapper != null) {
            m_supersonicWrapper.onDestroy();
        }
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
        if (Utils.getHasValidSubscriptionOrFreeTime(this))
        {
            doStartUp();
        }
        else
        {
            pauseServiceStateUI();
            freeTrialCountdown = 10;
            delayHandler.postDelayed(enableFreeTrial, 1000);
            showFullScreenAd();
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

    static final String IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU = "";
    static final String[] OTHER_VALID_IAB_SUBSCRIPTION_SKUS = {};

    static final String IAB_BASIC_30DAY_TIMEPASS_SKU = "";
    static final Map<String, Long> IAB_TIMEPASS_SKUS_TO_TIME;
    static {
        Map<String, Long> m = new HashMap<>();
        m.put(IAB_BASIC_30DAY_TIMEPASS_SKU, 30l * 24 * 60 * 60 * 1000);
        IAB_TIMEPASS_SKUS_TO_TIME = Collections.unmodifiableMap(m);
    }

    Inventory mInventory;

    synchronized
    private void startIab()
    {
        if (m_startIabInFlight)
        {
            return;
        }

        m_startIabInFlight = true;

        if (m_iabHelper == null)
        {
            m_iabHelper = new IabHelper(this, IAB_PUBLIC_KEY);
            m_iabHelper.startSetup(m_iabSetupFinishedListener);
        }
        else
        {
            queryInventory();
        }
    }
    
    private IabHelper.OnIabSetupFinishedListener m_iabSetupFinishedListener =
            new IabHelper.OnIabSetupFinishedListener()
    {
        @Override
        public void onIabSetupFinished(IabResult result)
        {
            if (result.isFailure())
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabSetupFinished: failure: %s", result));
                handleIabFailure(result);
            }
            else
            {
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

            m_startIabInFlight = false;

            mInventory = inventory;

            //
            // Check if the user has a subscription.
            //

            List<String> validSubscriptionSkus = new ArrayList<>(Arrays.asList(OTHER_VALID_IAB_SUBSCRIPTION_SKUS));
            validSubscriptionSkus.add(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU);
            for (String validSku : validSubscriptionSkus)
            {
                if (inventory.hasPurchase(validSku))
                {
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: has valid subscription: %s", validSku));
                    proceedWithValidSubscription();
                    return;
                }
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
                    proceedWithValidSubscription();
                    return;
                }
                else
                {
                    // This time pass is no longer valid. Consider it invalid and consume it below
                    // (unless a valid time-pass is found first and we early-exit).
                    Utils.MyLog.g(String.format("StatusActivity::onQueryInventoryFinished: consuming old time pass: %s", sku));
                    timepassesToConsume.add(purchase);
                }
            }

            if (timepassesToConsume.size() > 0)
            {
                // We're not passing a callback, because we're not going to take any special action
                // when it completes (or if it fails).
                //
                // We are consuming purchases for two reasons:
                //   1. So that they can be re-purchased (IAB prevents purchasing the same
                //      un-consumed product twice).
                //   2. So that the list of purchases to check doesn't just get longer and longer.
                //
                // Note: As implied by #1, if the consume fails the user may be left in a state
                // where they can't buy another time pass. We think this is improbable and accept it
                // (especially since whatever prevented the consume from succeeding would likely
                // also prevent a new purchase from succeeding).
                consumePurchases(timepassesToConsume, null);
            }

            //
            // There is no valid subscription or time pass for this user.
            //

            Utils.setHasValidSubscription(StatusActivity.this, false);

            updateEgressRegionPreference(PsiphonConstants.REGION_CODE_ANY);

            if (isTunnelConnected() &&
                !Utils.getHasValidSubscriptionOrFreeTime(StatusActivity.this))
            {
                // Stop the tunnel
                doToggle();
            }

            Utils.MyLog.g("StatusActivity::onQueryInventoryFinished: no valid subscription or time pass");
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
            else if (purchase.getSku().equals(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));
                proceedWithValidSubscription();
            }
            else if (IAB_TIMEPASS_SKUS_TO_TIME.containsKey(purchase.getSku()))
            {
                Utils.MyLog.g(String.format("StatusActivity::onIabPurchaseFinished: success: %s", purchase.getSku()));

                // We're not going to check the validity time here -- assume no time-pass is so
                // short that it's already expired right after it's purchased.
                proceedWithValidSubscription();
            }
        }
    };
    
    private void queryInventory()
    {
        try
        {
            if (m_iabHelper != null)
            {
                List<String> timepassSkus = new ArrayList<>();
                timepassSkus.addAll(IAB_TIMEPASS_SKUS_TO_TIME.keySet());

                List<String> subscriptionSkus = new ArrayList<>();
                subscriptionSkus.add(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU);

                m_iabHelper.queryInventoryAsync(
                        true,
                        timepassSkus,
                        subscriptionSkus,
                        m_iabQueryInventoryFinishedListener);
            }
        }
        catch (IllegalStateException|IabHelper.IabAsyncInProgressException ex)
        {
            handleIabFailure(null);
        }
    }

    private void consumePurchases(List<Purchase> purchases, IabHelper.OnConsumeMultiFinishedListener listener)
    {
        try
        {
            if (m_iabHelper != null)
            {
                m_iabHelper.consumeAsync(purchases, listener);
            }
        }
        catch (IllegalStateException|IabHelper.IabAsyncInProgressException ex)
        {
            handleIabFailure(null);
        }
    }

    /**
     * Begin the flow for subscribing to premium access.
     */
    private void launchSubscriptionPurchaseFlow()
    {
        try
        {
            if (m_iabHelper != null && !m_startIabInFlight)
            {
                m_iabHelper.launchSubscriptionPurchaseFlow(this, IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException|IabHelper.IabAsyncInProgressException ex)
        {
            handleIabFailure(null);
        }
    }

    /**
     * Begin the flow for making a one-time purchase of time-limited premium access.
     */
    private void launchTimePassPurchaseFlow(String sku)
    {
        try
        {
            if (m_iabHelper != null && !m_startIabInFlight)
            {
                m_iabHelper.launchPurchaseFlow(this, sku,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException|IabHelper.IabAsyncInProgressException ex)
        {
            handleIabFailure(null);
        }
    }

    private void proceedWithValidSubscription()
    {
        Utils.setHasValidSubscription(this, true);

        // Auto-start on app first run
        if (m_firstRun)
        {
            m_firstRun = false;
            doStartUp();
        }
    }
    
    // NOTE: result may be null
    private void handleIabFailure(IabResult result)
    {
        // try again next time
        deInitIab();
        m_startIabInFlight = false;

        if (isTunnelConnected() &&
                !Utils.getHasValidSubscriptionOrFreeTime(this))
        {
            // Stop the tunnel
            doToggle();
        }
        else
        {
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
    }

    static final int INTERSTITIAL_REWARD_MINUTES = 60;
    private Handler delayHandler = new Handler();
    private Runnable enableFreeTrial = new Runnable()
    {
        @Override
        public void run()
        {
            if (freeTrialCountdown > 0)
            {
                m_toggleButton.setText(String.valueOf(freeTrialCountdown));
                freeTrialCountdown--;
                delayHandler.postDelayed(this, 1000);
            }
            else
            {
                resumeServiceStateUI();
                Utils.startFreeTrial(StatusActivity.this, INTERSTITIAL_REWARD_MINUTES);
            }
        }
    };
    private int freeTrialCountdown;

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

        TextView textViewRemainingMinutes = (TextView) findViewById(R.id.timeRemaining);
        if (show)
        {
            long freeTrialRemainingSeconds = FreeTrialTimer.getFreeTrialTimerCachingWrapper().getRemainingTimeSeconds(this);
            textViewRemainingMinutes.setText(String.format(
                    getResources().getString(R.string.FreeTrialRemainingTime),
                    DateUtils.formatElapsedTime(
                            freeTrialRemainingSeconds)));

            // Initialize Supersonic video ads
            if (m_supersonicWrapper == null) {
                m_supersonicWrapper = new SupersonicRewardedVideoWrapper(this);
            }
            findViewById(R.id.watchRewardedVideoButton).setEnabled(m_supersonicWrapper.isRewardedVideoAvailable());

            initBanner();

            if (!Utils.getHasValidSubscriptionOrFreeTime(this) &&
                    m_moPubInterstitial == null)
            {
                loadFullScreenAd();
            }
        }
        else
        {
            // Abort any outstanding ad requests
            deInitAds();
        }

        boolean showSubscribe = show && (mInventory != null);

        findViewById(R.id.subscriptionPromptMessage).setVisibility(showSubscribe ? View.VISIBLE : View.GONE);
        findViewById(R.id.subscribeButton).setVisibility(showSubscribe ? View.VISIBLE : View.GONE);
        findViewById(R.id.watchRewardedVideoButton).setVisibility(show ? View.VISIBLE : View.GONE);
        textViewRemainingMinutes.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    @Override
    public void onSubscribeButtonClick(View v)
    {
        Utils.MyLog.g("StatusActivity::onSubscribeButtonClick");

        // The button should not have been enabled if there's no inventory (yet).
        assert(mInventory != null);

        // User has clicked the Subscribe button, now let them choose the payment method.

        Intent feedbackIntent = new Intent(this, PaymentChooserActivity.class);

        // Pass price and SKU info to payment chooser activity.
        PaymentChooserActivity.SkuInfo skuInfo = new PaymentChooserActivity.SkuInfo();

        SkuDetails subscriptionSkuDetails = mInventory.getSkuDetails(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU);

        skuInfo.mSubscriptionInfo.sku = subscriptionSkuDetails.getSku();
        skuInfo.mSubscriptionInfo.price = subscriptionSkuDetails.getPrice();
        skuInfo.mSubscriptionInfo.priceMicros = subscriptionSkuDetails.getPriceAmountMicros();
        skuInfo.mSubscriptionInfo.priceCurrency = subscriptionSkuDetails.getPriceCurrencyCode();
        // This is a subscription, so lifetime doesn't really apply. However, to keep things sane
        // we'll set it to 30 days.
        skuInfo.mSubscriptionInfo.lifetime = 30l * 24 * 60 * 60 * 1000;

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

    @Override
    public void onWatchRewardedVideoButtonClick(View v)
    {
        Utils.MyLog.g("StatusActivity::onWatchRewardedVideoButtonClick");

        if(m_supersonicWrapper != null) {
            m_supersonicWrapper.playVideo();
        }
    }

    synchronized
    private void deInitIab()
    {
        if (m_iabHelper != null)
        {
            try {
                m_iabHelper.dispose();
            }
            catch (IabHelper.IabAsyncInProgressException ex)
            {
                // Nothing can help at this point. Continue to de-init.
            }

            m_iabHelper = null;
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        if (requestCode == IAB_REQUEST_CODE)
        {
            if (m_iabHelper != null)
            {
                m_iabHelper.handleActivityResult(requestCode, resultCode, data);
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
                    launchSubscriptionPurchaseFlow();
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

    static final String MOPUB_LARGE_BANNER_PROPERTY_ID = "6490d145426a418db838f640c26edb77";
    static final String MOPUB_INTERSTITIAL_PROPERTY_ID = "0d4cf70da6504af5878f0b3592808852";

    private void initBanner()
    {
        if (m_moPubBannerLargeAdView == null)
        {
            m_moPubBannerLargeAdView = new MoPubView(this);
            m_moPubBannerLargeAdView.setAdUnitId(MOPUB_LARGE_BANNER_PROPERTY_ID);

            m_moPubBannerLargeAdView.setBannerAdListener(new MoPubView.BannerAdListener() {
                @Override
                public void onBannerLoaded(MoPubView banner)
                {
                    if (m_moPubBannerLargeAdView.getParent() == null)
                    {
                        LinearLayout layout = (LinearLayout)findViewById(R.id.largeAdSlot);
                        layout.removeAllViewsInLayout();
                        layout.addView(m_moPubBannerLargeAdView);
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
                    LinearLayout layout = (LinearLayout)findViewById(R.id.largeAdSlot);
                    layout.removeAllViewsInLayout();
                }
            });

            m_moPubBannerLargeAdView.loadAd();
            m_moPubBannerLargeAdView.setAutorefreshEnabled(true);
        }
    }

    synchronized
    private void loadFullScreenAd()
    {
        if (m_moPubInterstitial != null)
        {
            m_moPubInterstitial.destroy();
        }
        m_moPubInterstitial = new MoPubInterstitial(this, MOPUB_INTERSTITIAL_PROPERTY_ID);
        
        m_moPubInterstitial.setInterstitialAdListener(new InterstitialAdListener() {

            @Override
            public void onInterstitialClicked(MoPubInterstitial arg0) {
            }
            @Override
            public void onInterstitialDismissed(MoPubInterstitial arg0) {
                startUp();
            }
            @Override
            public void onInterstitialFailed(MoPubInterstitial interstitial,
                    MoPubErrorCode errorCode) {
            }
            @Override
            public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                if (interstitial != null && interstitial.isReady() &&
                        m_moPubInterstitialShowWhenLoaded)
                {
                    interstitial.show();
                }
            }
            @Override
            public void onInterstitialShown(MoPubInterstitial arg0) {
                // Enable the free trial right away
                delayHandler.removeCallbacks(enableFreeTrial);
                resumeServiceStateUI();
                Utils.startFreeTrial(StatusActivity.this, INTERSTITIAL_REWARD_MINUTES);
            }
        });

        m_moPubInterstitialShowWhenLoaded = false;
        m_moPubInterstitial.load();
    }

    private void showFullScreenAd()
    {
        if (m_moPubInterstitial != null)
        {
            if (m_moPubInterstitial.isReady())
            {
                m_moPubInterstitial.show();
            }
            else
            {
                m_moPubInterstitialShowWhenLoaded = true;
            }
        }
    }

    synchronized
    private void deInitAds()
    {
        LinearLayout layout = (LinearLayout)findViewById(R.id.largeAdSlot);
        layout.removeAllViewsInLayout();

        if (m_moPubBannerLargeAdView != null)
        {
            m_moPubBannerLargeAdView.destroy();
        }
        m_moPubBannerLargeAdView = null;

        if (m_moPubInterstitial != null)
        {
            m_moPubInterstitial.destroy();
        }
        m_moPubInterstitial = null;
    }
}
