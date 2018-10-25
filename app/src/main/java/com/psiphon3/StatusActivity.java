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
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TabHost;
import android.widget.Toast;

import com.google.ads.consent.ConsentInformation;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.MobileAds;
import com.mopub.common.MoPub;
import com.mopub.common.SdkConfiguration;
import com.mopub.common.SdkInitializationListener;
import com.mopub.common.privacy.ConsentDialogListener;
import com.mopub.common.privacy.ConsentStatus;
import com.mopub.common.privacy.ConsentStatusChangeListener;
import com.mopub.common.privacy.PersonalInfoManager;
import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubInterstitial.InterstitialAdListener;
import com.mopub.mobileads.MoPubView;
import com.mopub.mobileads.MoPubView.BannerAdListener;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    public static final String BANNER_FILE_NAME = "bannerImage";

    private ImageView m_banner;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private AdView m_adMobUntunneledBannerAdView = null;
    private InterstitialAd m_adMobUntunneledInterstitial = null;
    private boolean m_adMobUntunneledInterstitialFailed = false;
    private boolean m_adMobUntunneledInterstitialShowWhenLoaded = false;
    private static boolean m_startupPending = false;
    private MoPubView m_moPubTunneledBannerAdView = null;
    private MoPubInterstitial m_moPubTunneledInterstitial = null;
    private boolean m_moPubTunneledInterstitialShowWhenLoaded = false;
    private int m_tunneledFullScreenAdCounter = 0;
    private boolean m_temporarilyDisableTunneledInterstitial = false;

    private boolean mAdsConsentInitializing;
    private boolean mAdsConsentInitialized;
    private AdMobGDPRHelper mAdMobGDPRHelper;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        m_banner = (ImageView) findViewById(R.id.banner);
        m_tabHost = (TabHost) findViewById(R.id.tabHost);
        m_toggleButton = (Button) findViewById(R.id.toggleButton);

        setupActivityLayout();

        // Don't let this tab change trigger an interstitial ad
        // OnResume() will reset this flag
        m_temporarilyDisableTunneledInterstitial = true;
        
        if (shouldShowUntunneledAds()) {
            // Start at the Home tab if the service isn't running and we want to show ads
            m_tabHost.setCurrentTabByTag("home");
        }

        // EmbeddedValues.initialize(this); is called in MainBase.OnCreate

        // Play Store Build instances should use existing banner from previously installed APK
        // (if present). To enable this, non-Play Store Build instances write their banner to
        // a private file.
        try {
            if (EmbeddedValues.IS_PLAY_STORE_BUILD) {
                File bannerImageFile = new File(getFilesDir(), BANNER_FILE_NAME);
                if (bannerImageFile.exists()) {
                    Bitmap bitmap = BitmapFactory.decodeFile(bannerImageFile.getAbsolutePath());
                    m_banner.setImageBitmap(bitmap);
                }
            } else {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.banner);
                if (bitmap != null) {
                    FileOutputStream out = openFileOutput(BANNER_FILE_NAME, Context.MODE_PRIVATE);
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    out.close();
                }
            }
        } catch (IOException e) {
            // Ignore failure
        }

        // Auto-start on app first run
        if (m_firstRun && !shouldShowUntunneledAds()) {
            m_firstRun = false;
            startUp();
        }

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
    public void onPause()
    {
        super.onPause();
    }
    
    @Override
    public void onResume() {
        // Don't miss a chance to get personalized ads consent if user hasn't set it yet.
        mAdsConsentInitialized = false;

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
        initTunneledAds(false);
    }
    
    @Override
    public void onDestroy()
    {
        deInitAllAds();
        delayHandler.removeCallbacks(enableAdMode);
        super.onDestroy();
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
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // Switch to settings tab
            m_tabHost.setCurrentTabByTag("settings");

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
        if (shouldShowUntunneledAds()) {
            pauseServiceStateUI();
            adModeCountdown = 10;
            delayHandler.postDelayed(enableAdMode, 1000);
            showUntunneledFullScreenAd();
        } else {
            doStartUp();
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
                intent.putExtra("serviceClassName", TunnelService.class.getName());
                intent.putExtra("statusActivityClassName", StatusActivity.class.getName());
                intent.putExtra("feedbackActivityClassName", FeedbackActivity.class.getName());

                context.startActivity(intent);
            }
        } catch (ActivityNotFoundException e) {
            // Thrown by startActivity; in this case, we ignore and the URI isn't opened
        }
    }

    private Handler delayHandler = new Handler();
    private Runnable enableAdMode = new Runnable()
    {
        @Override
        public void run()
        {
            if (adModeCountdown > 0 && !m_adMobUntunneledInterstitialFailed)
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

    static final String ADMOB_APP_ID = "ca-app-pub-1072041961750291~3741619967";
    static final String ADMOB_UNTUNNELED_BANNER_PROPERTY_ID = "ca-app-pub-1072041961750291/7238659523";
    static final String ADMOB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID = "ca-app-pub-1072041961750291/3275363789";
    static final String ADMOB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID = "ca-app-pub-1072041961750291/9298519104";
    static final String MOPUB_TUNNELED_BANNER_PROPERTY_ID = "6848f6c3bce64522b771ea8ce9b5f1cd";
    static final String MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID = "0ad7bcfc9b17444aa80b1c198e5ebda5";
    static final String MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID = "b17a746d77c9436bb805c958f7879342";

    @Override
    protected void updateAdsForServiceState() {
        if (isServiceRunning()) {
            deInitUntunneledAds();
        } else {
            deInitTunneledAds();
            initUntunneledAds();
        }
    }

    private boolean getShowAds() {
        return m_multiProcessPreferences.getBoolean(getString(R.string.persistent_show_ads_setting), false) &&
                !EmbeddedValues.hasEverBeenSideLoaded(this) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    }

    private boolean shouldShowUntunneledAds()
    {
        return getShowAds() && !isServiceRunning();
    }

    private void initUntunneledAds() {
        final Context context = this;

        if (shouldShowUntunneledAds()) {
            Runnable adsRunnable = new Runnable() {
                @Override
                public void run() {
                    MobileAds.initialize(context, ADMOB_APP_ID);
                    initUntunneledBanners();

                    if (m_adMobUntunneledInterstitial == null)
                    {
                        loadUntunneledFullScreenAd();
                    }
                }
            };

            initAdsConsentAndRunAds(adsRunnable);
        }
    }

    private void initUntunneledBanners()
    {
        if (m_adMobUntunneledBannerAdView == null)
        {
            m_adMobUntunneledBannerAdView = new AdView(this);
            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                m_adMobUntunneledBannerAdView.setAdSize(AdSize.MEDIUM_RECTANGLE);
                m_adMobUntunneledBannerAdView.setAdUnitId(ADMOB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID);
            } else {
                m_adMobUntunneledBannerAdView.setAdSize(AdSize.SMART_BANNER);
                m_adMobUntunneledBannerAdView.setAdUnitId(ADMOB_UNTUNNELED_BANNER_PROPERTY_ID);
            }

            m_adMobUntunneledBannerAdView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded()
                {
                    if (m_adMobUntunneledBannerAdView.getParent() == null)
                    {
                        LinearLayout layout = (LinearLayout)findViewById(R.id.bannerLayout);
                        layout.removeAllViewsInLayout();
                        layout.addView(m_adMobUntunneledBannerAdView);
                    }
                }
                @Override
                public void onAdFailedToLoad(int errorCode) {
                }
                @Override
                public void onAdOpened() {
                }
                @Override
                public void onAdLeftApplication() {
                }
                @Override
                public void onAdClosed() {
                }
            });

            Bundle extras = new Bundle();
            if (ConsentInformation.getInstance(this).getConsentStatus() == com.google.ads.consent.ConsentStatus.NON_PERSONALIZED) {
                extras.putString("npa", "1");
            }
            AdRequest adRequest = new AdRequest.Builder()
                    .addNetworkExtrasBundle(AdMobAdapter.class, extras)
                    .build();
            m_adMobUntunneledBannerAdView.loadAd(adRequest);
        }
    }

    synchronized
    private void loadUntunneledFullScreenAd()
    {
        m_adMobUntunneledInterstitial = new InterstitialAd(this);
        m_adMobUntunneledInterstitial.setAdUnitId(ADMOB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID);

        m_adMobUntunneledInterstitial.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                if (m_adMobUntunneledInterstitial != null && m_adMobUntunneledInterstitial.isLoaded() &&
                        m_adMobUntunneledInterstitialShowWhenLoaded)
                {
                    m_adMobUntunneledInterstitial.show();
                }
            }
            @Override
            public void onAdFailedToLoad(int errorCode) {
                m_adMobUntunneledInterstitialFailed = true;
            }
            @Override
            public void onAdOpened() {
            }
            @Override
            public void onAdLeftApplication() {
            }
            @Override
            public void onAdClosed() {
                // Enable the free trial right away
                m_startupPending = true;
                delayHandler.removeCallbacks(enableAdMode);
                resumeServiceStateUI();
            }
        });

        m_adMobUntunneledInterstitialFailed = false;
        m_adMobUntunneledInterstitialShowWhenLoaded = false;

        Bundle extras = new Bundle();
        if (ConsentInformation.getInstance(this).getConsentStatus() == com.google.ads.consent.ConsentStatus.NON_PERSONALIZED) {
            extras.putString("npa", "1");
        }
        AdRequest adRequest = new AdRequest.Builder()
                .addNetworkExtrasBundle(AdMobAdapter.class, extras)
                .build();
        m_adMobUntunneledInterstitial.loadAd(adRequest);
    }

    private void showUntunneledFullScreenAd()
    {
        if (m_adMobUntunneledInterstitial != null)
        {
            if (m_adMobUntunneledInterstitial.isLoaded())
            {
                m_adMobUntunneledInterstitial.show();
            }
            else
            {
                if (m_adMobUntunneledInterstitialFailed)
                {
                    loadUntunneledFullScreenAd();
                }
                m_adMobUntunneledInterstitialShowWhenLoaded = true;
            }
        }
    }

    synchronized
    private void deInitUntunneledAds()
    {
        if (m_adMobUntunneledBannerAdView != null)
        {
            if (m_adMobUntunneledBannerAdView.getParent() != null) {
                LinearLayout layout = (LinearLayout) findViewById(R.id.bannerLayout);
                layout.removeAllViewsInLayout();
                layout.addView(m_banner);
            }

            m_adMobUntunneledBannerAdView.destroy();
        }
        m_adMobUntunneledBannerAdView = null;

        m_adMobUntunneledInterstitial = null;
    }

    private boolean shouldShowTunneledAds()
    {
        return getShowAds() && isTunnelConnected();
    }

    private void initTunneledAds(final boolean initFullScreenAd)
    {
        if (shouldShowTunneledAds() && m_multiProcessPreferences.getBoolean(getString(R.string.status_activity_foreground), false))
        {
            final Context that = this;
            Runnable adsRunnable = new Runnable() {
                @Override
                public void run() {
                    // make sure WebView proxy settings are up to date
                    // Set WebView proxy only if we are not running in WD mode.
                    if (!getTunnelConfigWholeDevice() || !Utils.hasVpnService()) {
                        WebViewProxySettings.setLocalProxy(that, getListeningLocalHttpProxyPort());
                    } else {
                        // We are running in WDM, reset WebView proxy if it has been previously set.
                        if (WebViewProxySettings.isLocalProxySet()){
                            WebViewProxySettings.resetLocalProxy(that);
                        }
                    }

                    initTunneledBanners();
                    if (initFullScreenAd) {
                        loadTunneledFullScreenAd();
                    }
                }
            };

            initAdsConsentAndRunAds(adsRunnable);
        }
    }

    private void initTunneledBanners()
    {
        if (shouldShowTunneledAds())
        {
            if (m_moPubTunneledBannerAdView == null && MoPub.isSdkInitialized())
            {
                m_moPubTunneledBannerAdView = new MoPubView(this);
                m_moPubTunneledBannerAdView.setAdUnitId(
                        getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT &&
                                new Random().nextBoolean() ?
                                MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID :
                                MOPUB_TUNNELED_BANNER_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubTunneledBannerAdView.setKeywords("client_region:" + getClientRegion());
                    Map<String,Object> localExtras = new HashMap<>();
                    localExtras.put("client_region", getClientRegion());
                    m_moPubTunneledBannerAdView.setLocalExtras(localExtras);
                }

                m_moPubTunneledBannerAdView.setBannerAdListener(new BannerAdListener() {
                    @Override
                    public void onBannerLoaded(MoPubView banner)
                    {
                        if (m_moPubTunneledBannerAdView.getParent() == null)
                        {
                            LinearLayout layout = (LinearLayout)findViewById(R.id.bannerLayout);
                            layout.removeAllViewsInLayout();
                            layout.addView(m_moPubTunneledBannerAdView);
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

                m_moPubTunneledBannerAdView.loadAd();
                m_moPubTunneledBannerAdView.setAutorefreshEnabled(true);
            }
        }
    }

    synchronized
    private void loadTunneledFullScreenAd()
    {
        if (shouldShowTunneledAds() && m_moPubTunneledInterstitial == null && MoPub.isSdkInitialized())
        {
            m_moPubTunneledInterstitial = new MoPubInterstitial(this, MOPUB_TUNNELED_INTERSTITIAL_PROPERTY_ID);
            if (isTunnelConnected()) {
                m_moPubTunneledInterstitial.setKeywords("client_region:" + getClientRegion());
                Map<String,Object> localExtras = new HashMap<>();
                localExtras.put("client_region", getClientRegion());
                m_moPubTunneledInterstitial.setLocalExtras(localExtras);
            }

            m_moPubTunneledInterstitial.setInterstitialAdListener(new InterstitialAdListener() {
                @Override
                public void onInterstitialClicked(MoPubInterstitial arg0) {
                }
                @Override
                public void onInterstitialDismissed(MoPubInterstitial arg0) {
                    m_moPubTunneledInterstitialShowWhenLoaded = false;
                    m_moPubTunneledInterstitial.load();
                }
                @Override
                public void onInterstitialFailed(MoPubInterstitial arg0,
                                                 MoPubErrorCode arg1) {
                    m_moPubTunneledInterstitial.destroy();
                    m_moPubTunneledInterstitial = null;
                }
                @Override
                public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                    if (interstitial != null && interstitial.isReady() &&
                            m_moPubTunneledInterstitialShowWhenLoaded &&
                            m_multiProcessPreferences.getBoolean(getString(R.string.status_activity_foreground), false))
                    {
                        m_tunneledFullScreenAdCounter++;
                        interstitial.show();
                    }
                }
                @Override
                public void onInterstitialShown(MoPubInterstitial arg0) {
                }
            });
            m_moPubTunneledInterstitialShowWhenLoaded = false;
            m_moPubTunneledInterstitial.load();
        }
    }

    private void showTunneledFullScreenAd()
    {
        initTunneledAds(true);

        if (shouldShowTunneledAds() && !m_temporarilyDisableTunneledInterstitial)
        {
            if (m_tunneledFullScreenAdCounter % 3 == 0)
            {
                if (m_moPubTunneledInterstitial != null)
                {
                    if (m_moPubTunneledInterstitial.isReady())
                    {
                        m_tunneledFullScreenAdCounter++;
                        m_moPubTunneledInterstitial.show();
                    }
                    else
                    {
                        m_moPubTunneledInterstitialShowWhenLoaded = true;
                    }
                }
            }
            else
            {
                m_tunneledFullScreenAdCounter++;
            }
        }
    }

    private void deInitTunneledAds()
    {
        if (m_moPubTunneledBannerAdView != null)
        {
            if (m_moPubTunneledBannerAdView.getParent() != null) {
                LinearLayout layout = (LinearLayout) findViewById(R.id.bannerLayout);
                layout.removeAllViewsInLayout();
                layout.addView(m_banner);
            }

            m_moPubTunneledBannerAdView.destroy();
        }
        m_moPubTunneledBannerAdView = null;

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

    // New MoPub initializer with GDPR consent dialog
    static class MoPubConsentDialogHelper {
        public static ConsentDialogListener initDialogLoadListener() {
            return new ConsentDialogListener() {

                @Override
                public void onConsentDialogLoaded() {
                    PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                    if (personalInfoManager != null) {
                        personalInfoManager.showConsentDialog();
                    }
                }

                @Override
                public void onConsentDialogLoadFailed(@NonNull MoPubErrorCode moPubErrorCode) {
                    Utils.MyLog.d( "MoPub consent dialog failed to load.");
                }
            };
        }
    }

    synchronized
    private void initAdsConsentAndRunAds(final Runnable runnable) {
        if (mAdsConsentInitialized) {
            runnable.run();
            return;
        }

        if (mAdsConsentInitializing) {
            return;
        }
        mAdsConsentInitializing = true;

        MoPub.setLocationAwareness(MoPub.LocationAwareness.DISABLED);
        final Context context = this;

        // If tunnel is not running run AdMob GDPR check and pass
        // MoPub GDPR consent check as a completion callback.
        // Otherwise just run MoPub GDPR consent check

        AdMobGDPRHelper.AdMobGDPRHelperCallback moPubGDPRCheckCallback = new AdMobGDPRHelper.AdMobGDPRHelperCallback() {
            @Override
            public void onComplete() {
                PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                // initialized MoPub SDK if needed
                if (personalInfoManager == null) {
                    SdkConfiguration.Builder builder = new SdkConfiguration.Builder(MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
                    SdkConfiguration sdkConfiguration = builder.build();

                    MoPub.initializeSdk(context, sdkConfiguration, new SdkInitializationListener() {
                        @Override
                        public void onInitializationFinished() {
                            PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                            if (personalInfoManager != null) {
                                // subscribe to consent change state event
                                personalInfoManager.subscribeConsentStatusChangeListener(new ConsentStatusChangeListener() {

                                    @Override
                                    public void onConsentStateChange(@NonNull ConsentStatus oldConsentStatus,
                                                                     @NonNull ConsentStatus newConsentStatus,
                                                                     boolean canCollectPersonalInformation) {
                                        PersonalInfoManager personalInfoManager = MoPub.getPersonalInformationManager();
                                        if (personalInfoManager != null && personalInfoManager.shouldShowConsentDialog()) {
                                            personalInfoManager.loadConsentDialog(MoPubConsentDialogHelper.initDialogLoadListener());
                                        }
                                    }
                                });

                                // If consent is required load the consent dialog
                                if (personalInfoManager.shouldShowConsentDialog()) {
                                    personalInfoManager.loadConsentDialog(MoPubConsentDialogHelper.initDialogLoadListener());
                                }
                            } else {
                                Utils.MyLog.d( "MoPub SDK has failed to initialize.");
                            }
                            // Don't retry if MoPub SDK fails to initialize.
                            // We don't want this failure case to prevent loading AdMob ads.
                            runnable.run();
                            mAdsConsentInitializing = false;
                            mAdsConsentInitialized = true;
                        }
                    });

                } else {
                    runnable.run();
                    mAdsConsentInitializing = false;
                    mAdsConsentInitialized = true;
                }
            }
        };

        if(!isServiceRunning()  && !this.isFinishing()) {
            if(mAdMobGDPRHelper != null) {
                mAdMobGDPRHelper.destroy();
                mAdMobGDPRHelper = null;
            }
            String[] publisherIds = {"pub-1072041961750291"};
            mAdMobGDPRHelper = new AdMobGDPRHelper(this, publisherIds, moPubGDPRCheckCallback);

            // Do not show 'Upgrade to ad-free" button
            mAdMobGDPRHelper.setShowBuyAdFree(false);
            mAdMobGDPRHelper.presentGDPRConsentDialogIfNeeded();
        } else {
            moPubGDPRCheckCallback.onComplete();
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
}
