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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TabHost;
import android.widget.TextView;

import com.mopub.mobileads.MoPubErrorCode;
import com.mopub.mobileads.MoPubInterstitial;
import com.mopub.mobileads.MoPubInterstitial.InterstitialAdListener;
import com.mopub.mobileads.MoPubView;
import com.mopub.mobileads.MoPubView.BannerAdListener;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;


public class StatusActivity
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    public static final String BANNER_FILE_NAME = "bannerImage";

    private ImageView m_banner;
    private ImageButton m_statusImage;
    private TextView m_versionLine;
    private TextView m_logLine;
    private MoPubView m_moPubBannerAdView = null;
    private MoPubView m_moPubBannerLargeAdView = null;
    private MoPubInterstitial m_moPubInterstitial = null;
    private int m_fullScreenAdCounter = 0;
    private boolean m_fullScreenAdPending = false;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private boolean m_loadedSponsorTab = false;
    private boolean m_temporarilyDisableInterstitial = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setContentView(R.layout.main);

        m_banner = (ImageView) findViewById(R.id.banner);
        m_statusImage = (ImageButton)findViewById(R.id.statusViewImage);
        m_versionLine = (TextView)findViewById(R.id.versionline);
        m_logLine = (TextView)findViewById(R.id.lastlogline);
        m_tabHost = (TabHost) findViewById(R.id.tabHost);
        m_toggleButton = (Button) findViewById(R.id.toggleButton);

        // NOTE: super class assumes m_tabHost is initialized in its onCreate

        // Don't let this tab change trigger an interstitial ad
        // OnResume() will reset this flag
        m_temporarilyDisableInterstitial = true;
        
        super.onCreate(savedInstanceState);

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
        if (m_firstRun) {
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
    public void onResume()
    {
        super.onResume();
        m_temporarilyDisableInterstitial = false;
        initAds();
    }
    
    @Override
    public void onDestroy()
    {
        deInitAds();
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
        showFullScreenAd();
        super.onTabChanged(tabId);
    }

    @Override
    protected void onTunnelDisconnected() {
        deInitAds();
    }

    static final String MOPUB_BANNER_PROPERTY_ID = "";
    static final String MOPUB_LARGE_BANNER_PROPERTY_ID = "";
    static final String MOPUB_INTERSTITIAL_PROPERTY_ID = "";

    private boolean getShowAds() {
        for (String homepage : getHomePages()) {
            if (homepage.contains("psiphon_show_ads")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldShowAds()
    {
        // For now, only show ads when the tunnel is connected, since WebViewProxySettings are
        // probably set and webviews won't load successfully when the tunnel is not connected
        return getShowAds() &&
                isTunnelConnected() &&
                Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO;
    }
    
    private void showFullScreenAd()
    {
        if (shouldShowAds() && !m_temporarilyDisableInterstitial)
        {
            m_fullScreenAdCounter++;

            if (m_fullScreenAdCounter % 3 == 1)
            {
                if (m_moPubInterstitial != null)
                {
                    m_moPubInterstitial.destroy();
                }
                m_moPubInterstitial = new MoPubInterstitial(this, MOPUB_INTERSTITIAL_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubInterstitial.setKeywords("client_region:" + getClientRegion());
                }
                
                m_moPubInterstitial.setInterstitialAdListener(new InterstitialAdListener() {
                    @Override
                    public void onInterstitialClicked(MoPubInterstitial arg0) {
                    }
                    @Override
                    public void onInterstitialDismissed(MoPubInterstitial arg0) {
                    }
                    @Override
                    public void onInterstitialFailed(MoPubInterstitial arg0,
                            MoPubErrorCode arg1) {
                    }
                    @Override
                    public void onInterstitialLoaded(MoPubInterstitial interstitial) {
                        if (interstitial != null && interstitial.isReady())
                        {
                            interstitial.show();
                        }
                    }
                    @Override
                    public void onInterstitialShown(MoPubInterstitial arg0) {
                    }
                });
                
                m_moPubInterstitial.load();
            }
        }
    }
    
    private void initBanners()
    {
        if (shouldShowAds())
        {
            if (m_moPubBannerAdView == null)
            {
                m_moPubBannerAdView = new MoPubView(this);
                m_moPubBannerAdView.setAdUnitId(MOPUB_BANNER_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubBannerAdView.setKeywords("client_region:" + getClientRegion());
                }
                
                m_moPubBannerAdView.setBannerAdListener(new BannerAdListener() {
                    @Override
                    public void onBannerLoaded(MoPubView banner)
                    {
                        if (m_moPubBannerAdView.getParent() == null)
                        {
                            LinearLayout layout = (LinearLayout)findViewById(R.id.bannerLayout);
                            layout.removeAllViewsInLayout();
                            layout.addView(m_moPubBannerAdView);
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
                
                m_moPubBannerAdView.loadAd();
                m_moPubBannerAdView.setAutorefreshEnabled(true);
            }
            
            if (!showFirstHomePageInApp() && m_moPubBannerLargeAdView == null)
            {
                m_moPubBannerLargeAdView = new MoPubView(this);
                m_moPubBannerLargeAdView.setAdUnitId(MOPUB_LARGE_BANNER_PROPERTY_ID);
                if (isTunnelConnected()) {
                    m_moPubBannerLargeAdView.setKeywords("client_region:" + getClientRegion());
                }
                
                m_moPubBannerLargeAdView.setBannerAdListener(new BannerAdListener() {
                    @Override
                    public void onBannerLoaded(MoPubView banner)
                    {
                        if (m_moPubBannerLargeAdView.getParent() == null)
                        {
                            LinearLayout layout = (LinearLayout)findViewById(R.id.statusLayout);
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
                    }
                });
                
                m_moPubBannerLargeAdView.loadAd();
                m_moPubBannerLargeAdView.setAutorefreshEnabled(true);
            }
        }
    }
    
    private void deInitAds()
    {
        LinearLayout layout = (LinearLayout)findViewById(R.id.bannerLayout);
        layout.removeAllViewsInLayout();
        layout.addView(m_banner);

        LinearLayout statusLayout = (LinearLayout)findViewById(R.id.statusLayout);
        statusLayout.removeAllViewsInLayout();
        statusLayout.addView(m_statusImage);
        statusLayout.addView(m_versionLine);
        statusLayout.addView(m_logLine);

        if (m_moPubBannerAdView != null)
        {
            m_moPubBannerAdView.destroy();
        }
        m_moPubBannerAdView = null;

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
    
    private void initAds()
    {
        if (getShowAds())
        {
            // make sure WebView proxy settings are up to date
            WebViewProxySettings.setLocalProxy(this, getListeningLocalHttpProxyPort());
            
            initBanners();
            
            if (m_fullScreenAdPending)
            {
                showFullScreenAd();
                m_fullScreenAdPending = false;
            }
        }
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

            // Show the home page. Always do this in browser-only mode, even
            // after an automated reconnect -- since the status activity was
            // brought to the front after an unexpected disconnect. In whole
            // device mode, after an automated reconnect, we don't re-invoke
            // the browser.
            if (!getTunnelConfigWholeDevice()
                || !intent.getBooleanExtra(TunnelManager.DATA_HANDSHAKE_IS_RECONNECT, false))
            {
                // Don't let this tab change trigger an interstitial ad
                // OnResume() will reset this flag
                m_temporarilyDisableInterstitial = true;

                // Show the full screen ad after OnResume() has initialized ads
                m_fullScreenAdPending = true;
                
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

        // No explicit action for UNEXPECTED_DISCONNECT, just show the activity
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
}
