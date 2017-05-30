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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TabHost;

import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubInterstitial.InterstitialAdListener;
import com.mopub.mobileads.MoPubView;
import com.mopub.mobileads.MoPubView.BannerAdListener;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    public static final String BANNER_FILE_NAME = "bannerImage";

    private ImageView m_banner;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private MoPubView m_moPubUntunneledBannerAdView = null;
    private MoPubInterstitial m_moPubUntunneledInterstitial = null;
    private boolean m_moPubUntunneledInterstitialFailed = false;
    private boolean m_moPubUntunneledInterstitialShowWhenLoaded = false;
    private static boolean m_startupPending = false;
    private MoPubView m_moPubTunneledBannerAdView = null;
    private MoPubInterstitial m_moPubTunneledInterstitial = null;
    private boolean m_moPubTunneledInterstitialShowWhenLoaded = false;
    private int m_tunneledFullScreenAdCounter = 0;
    private boolean m_temporarilyDisableTunneledInterstitial = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.main);

        m_banner = (ImageView) findViewById(R.id.banner);
        m_tabHost = (TabHost) findViewById(R.id.tabHost);
        m_toggleButton = (Button) findViewById(R.id.toggleButton);

        // NOTE: super class assumes m_tabHost is initialized in its onCreate

        // Don't let this tab change trigger an interstitial ad
        // OnResume() will reset this flag
        m_temporarilyDisableTunneledInterstitial = true;
        
        super.onCreate(savedInstanceState);

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

    private Handler delayHandler = new Handler();
    private Runnable enableAdMode = new Runnable()
    {
        @Override
        public void run()
        {
            if (adModeCountdown > 0 && !m_moPubUntunneledInterstitialFailed)
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

    static final String MOPUB_UNTUNNELED_BANNER_PROPERTY_ID = "3e7b44ad12be4c3b935abdfb7f1dbce7";
    static final String MOPUB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID = "97b7033b9dc14e9cab29605922ae9451";
    static final String MOPUB_UNTUNNELED_INTERSTITIAL_PROPERTY_ID = "4126820fe551437ab468a8f8186e1267";
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
                Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO;
    }

    private boolean shouldShowUntunneledAds()
    {
        return getShowAds() && !isServiceRunning();
    }

    private void initUntunneledAds() {
        if (shouldShowUntunneledAds()) {
            initUntunneledBanners();

            if (m_moPubUntunneledInterstitial == null)
            {
                loadUntunneledFullScreenAd();
            }
        }
    }

    private void initUntunneledBanners()
    {
        if (m_moPubUntunneledBannerAdView == null)
        {
            m_moPubUntunneledBannerAdView = new MoPubView(this);
            m_moPubUntunneledBannerAdView.setAdUnitId(
                    getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ?
                    MOPUB_UNTUNNELED_BANNER_PROPERTY_ID :
                    MOPUB_UNTUNNELED_LARGE_BANNER_PROPERTY_ID);

            m_moPubUntunneledBannerAdView.setBannerAdListener(new MoPubView.BannerAdListener() {
                @Override
                public void onBannerLoaded(MoPubView banner)
                {
                    if (m_moPubUntunneledBannerAdView.getParent() == null)
                    {
                        LinearLayout layout = (LinearLayout)findViewById(R.id.bannerLayout);
                        layout.removeAllViewsInLayout();
                        layout.addView(m_moPubUntunneledBannerAdView);
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

            m_moPubUntunneledBannerAdView.loadAd();
            m_moPubUntunneledBannerAdView.setAutorefreshEnabled(true);
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
                m_moPubUntunneledInterstitialFailed = true;
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

        m_moPubUntunneledInterstitialFailed = false;
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
                if (m_moPubUntunneledInterstitialFailed)
                {
                    loadUntunneledFullScreenAd();
                }
                m_moPubUntunneledInterstitialShowWhenLoaded = true;
            }
        }
    }

    synchronized
    private void deInitUntunneledAds()
    {
        if (m_moPubUntunneledBannerAdView != null)
        {
            if (m_moPubUntunneledBannerAdView.getParent() != null) {
                LinearLayout layout = (LinearLayout) findViewById(R.id.bannerLayout);
                layout.removeAllViewsInLayout();
                layout.addView(m_banner);
            }

            m_moPubUntunneledBannerAdView.destroy();
        }
        m_moPubUntunneledBannerAdView = null;

        if (m_moPubUntunneledInterstitial != null)
        {
            m_moPubUntunneledInterstitial.destroy();
        }
        m_moPubUntunneledInterstitial = null;
    }

    private boolean shouldShowTunneledAds()
    {
        return getShowAds() && isTunnelConnected();
    }

    private void initTunneledAds(boolean initFullScreenAd)
    {
        if (shouldShowTunneledAds() && m_multiProcessPreferences.getBoolean(getString(R.string.status_activity_foreground), false))
        {
            // make sure WebView proxy settings are up to date
            WebViewProxySettings.setLocalProxy(this, getListeningLocalHttpProxyPort());

            initTunneledBanners();
            if (initFullScreenAd) {
                loadTunneledFullScreenAd();
            }
        }
    }

    private void initTunneledBanners()
    {
        if (shouldShowTunneledAds())
        {
            if (m_moPubTunneledBannerAdView == null)
            {
                m_moPubTunneledBannerAdView = new MoPubView(this);
                m_moPubTunneledBannerAdView.setAdUnitId(
                        getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ?
                                MOPUB_TUNNELED_BANNER_PROPERTY_ID :
                                MOPUB_TUNNELED_LARGE_BANNER_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubTunneledBannerAdView.setKeywords("client_region:" + getClientRegion());
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
}
