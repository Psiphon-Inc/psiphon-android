/*
 * Copyright (c) 2014, Psiphon Inc.
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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TabHost;
import android.widget.TextView;

import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubInterstitial.InterstitialAdListener;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.FreeTrialTimer;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.SupersonicRewardedVideoWrapper;
import com.psiphon3.subscription.R;
import com.psiphon3.util.IabHelper;
import com.psiphon3.util.IabResult;
import com.psiphon3.util.Inventory;
import com.psiphon3.util.Purchase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    public static final String BANNER_FILE_NAME = "bannerImage";

    private ImageView m_banner;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private IabHelper m_iabHelper = null;
    private MoPubInterstitial m_moPubInterstitial = null;
    private boolean m_moPubInterstitialShowWhenLoaded = false;
    private SupersonicRewardedVideoWrapper m_supersonicWrapper;

    public StatusActivity()
    {
        super();
        m_eventsInterface = new Events();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        setContentView(R.layout.main);

        m_banner = (ImageView)findViewById(R.id.banner);
        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_toggleButton = (Button)findViewById(R.id.toggleButton);



        // NOTE: super class assumes m_tabHost is initialized in its onCreate

        super.onCreate(savedInstanceState);

        if (m_firstRun)
        {
            EmbeddedValues.initialize(this);
        }

        // Play Store Build instances should use existing banner from previously installed APK
        // (if present). To enable this, non-Play Store Build instances write their banner to
        // a private file.
        try
        {
            if (EmbeddedValues.IS_PLAY_STORE_BUILD)
            {
                File bannerImageFile = new File(getFilesDir(), BANNER_FILE_NAME);
                if (bannerImageFile.exists())
                {
                    Bitmap bitmap = BitmapFactory.decodeFile(bannerImageFile.getAbsolutePath());
                    m_banner.setImageBitmap(bitmap);
                }
            }
            else
            {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.banner);
                if (bitmap != null)
                {
                    FileOutputStream out = openFileOutput(BANNER_FILE_NAME, Context.MODE_PRIVATE);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                }
            }
        }
        catch (IOException e)
        {
            // Ignore failure
        }

        m_loadedSponsorTab = false;
        HandleCurrentIntent();
        
        // HandleCurrentIntent() may have already loaded the sponsor tab
        if (PsiphonData.getPsiphonData().getDataTransferStats().isConnected() &&
                !m_loadedSponsorTab)
        {
            loadSponsorTab(false);
        }
    }

    @Override
    protected void onResume()
    {
        startIab();
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

    protected void HandleCurrentIntent()
    {
        Intent intent = getIntent();

        if (intent == null || intent.getAction() == null)
        {
            return;
        }

        if (0 == intent.getAction().compareTo(HANDSHAKE_SUCCESS))
        {
            if (!PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(this))
            {
                startIab();
            }
            
            // Show the home page. Always do this in browser-only mode, even
            // after an automated reconnect -- since the status activity was
            // brought to the front after an unexpected disconnect. In whole
            // device mode, after an automated reconnect, we don't re-invoke
            // the browser.
            if (!PsiphonData.getPsiphonData().getTunnelWholeDevice()
                || !intent.getBooleanExtra(HANDSHAKE_SUCCESS_IS_RECONNECT, false))
            {
                m_tabHost.setCurrentTabByTag("home");
                loadSponsorTab(true);
                m_loadedSponsorTab = true;

                //m_eventsInterface.displayBrowser(this);
            }

            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
        }

        // No explicit action for UNEXPECTED_DISCONNECT, just show the activity
    }
    
    @Override
    protected void onPause()
    {
        if (PsiphonData.getPsiphonData().getDataTransferStats().isConnected() &&
                !PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(this))
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
        m_eventsInterface.displayBrowser(this);
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
        if (PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(this))
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
        // Abort any outstanding ad requests
        deInitAds();

        // Reset the FreeTrialTimerCachingWrapper because the tunnel service might modify the free trial timer independently
        FreeTrialTimer.getFreeTrialTimerCachingWrapper().reset();
        
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)

        boolean hasPreference = PreferenceManager.getDefaultSharedPreferences(this).contains(TUNNEL_WHOLE_DEVICE_PREFERENCE);

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
                                    startTunnel(context);
                                }})
                    .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        // Turn off and persist the "off" setting
                                        m_tunnelWholeDeviceToggle.setChecked(false);
                                        updateWholeDevicePreference(false);
                                        startTunnel(context);
                                    }})
                    .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                @Override
                                public void onCancel(DialogInterface dialog) {
                                    // Don't change or persist preference (this prompt may reappear)
                                    startTunnel(context);
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

            startTunnel(this);
        }
    }

    static final String IAB_PUBLIC_KEY = "";
    static final String IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU = "";
    static final String[] OTHER_VALID_IAB_SUBSCRIPTION_SKUS = {};
    static final int IAB_REQUEST_CODE = 10001;

    synchronized
    private void startIab()
    {
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
                handleIabFailure(result);
            }
            else
            {
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
                handleIabFailure(result);
                return;
            }
            
            List<String> validSubscriptionSkus = new ArrayList<String>(Arrays.asList(OTHER_VALID_IAB_SUBSCRIPTION_SKUS));
            validSubscriptionSkus.add(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU);
            for (String validSku : validSubscriptionSkus)
            {
                if (inventory.hasPurchase(validSku))
                {
                    proceedWithValidSubscription();
                    return;
                }
            }

            PsiphonData.getPsiphonData().setHasValidSubscription(false);

            updateEgressRegionPreference(PsiphonConstants.REGION_CODE_ANY);

            if (PsiphonData.getPsiphonData().getDataTransferStats().isConnected() &&
                    !PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(StatusActivity.this))
            {
                // Stop the tunnel
                doToggle();
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
                handleIabFailure(result);
            }      
            else if (purchase.getSku().equals(IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU))
            {
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
                m_iabHelper.queryInventoryAsync(m_iabQueryInventoryFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
    }
    
    private void launchSubscriptionPurchaseFlow()
    {
        try
        {
            if (m_iabHelper != null)
            {
                m_iabHelper.launchSubscriptionPurchaseFlow(this, IAB_BASIC_MONTHLY_SUBSCRIPTION_SKU,
                        IAB_REQUEST_CODE, m_iabPurchaseFinishedListener);
            }
        }
        catch (IllegalStateException ex)
        {
            handleIabFailure(null);
        }
    }
    
    private void proceedWithValidSubscription()
    {
        PsiphonData.getPsiphonData().setHasValidSubscription(true);

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
        
        if (PsiphonData.getPsiphonData().getDataTransferStats().isConnected() &&
                !PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(this))
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
                PsiphonData.getPsiphonData().startFreeTrial(StatusActivity.this, INTERSTITIAL_REWARD_MINUTES);
            }
        }
    };
    private int freeTrialCountdown;

    // updateSubscriptionAndAdOptions() gets called once in onCreate().
    // Don't show these options during the first few calls, to allow time for IAB to check
    // for a valid subscription.
    private int updateSubscriptionAndAdOptionsFlickerHackCountdown = 3;

    @Override
    protected void updateSubscriptionAndAdOptions(boolean show)
    {
        if (updateSubscriptionAndAdOptionsFlickerHackCountdown > 0)
        {
            show = false;
            updateSubscriptionAndAdOptionsFlickerHackCountdown--;
        }

        if (PsiphonData.getPsiphonData().getHasValidSubscription())
        {
            show = false;
        }

        if (show && !PsiphonData.getPsiphonData().getHasValidSubscriptionOrFreeTime(this) &&
                m_moPubInterstitial == null)
        {
            loadFullScreenAd();
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
                m_supersonicWrapper = new SupersonicRewardedVideoWrapper(this, "PsiphonProVideoPlacement");
            }
        }

        findViewById(R.id.subscriptionPromptMessage).setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.subscribeButton).setVisibility(show ? View.VISIBLE : View.GONE);
        findViewById(R.id.watchRewardedVideoButton).setVisibility(show && m_supersonicWrapper.isRewardedVideoAvailable() ? View.VISIBLE : View.GONE);

        textViewRemainingMinutes.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onSubscribeButtonClick(View v)
    {
        launchSubscriptionPurchaseFlow();
    }

    @Override
    public void onWatchRewardedVideoButtonClick(View v)
    {
        if(m_supersonicWrapper != null) {
            m_supersonicWrapper.playVideo();
        }
    }

    synchronized
    private void deInitIab()
    {
        if (m_iabHelper != null)
        {
            m_iabHelper.dispose();
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
        else
        {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    static final String MOPUB_INTERSTITIAL_PROPERTY_ID = "";

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
                PsiphonData.getPsiphonData().startFreeTrial(StatusActivity.this, INTERSTITIAL_REWARD_MINUTES);
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
        if (m_moPubInterstitial != null)
        {
            m_moPubInterstitial.destroy();
        }
        m_moPubInterstitial = null;
    }
}
