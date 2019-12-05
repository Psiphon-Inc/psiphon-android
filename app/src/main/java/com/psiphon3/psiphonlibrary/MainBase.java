/*
 * Copyright (c) 2015, Psiphon Inc.
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

package com.psiphon3.psiphonlibrary;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.TranslateAnimation;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.psiphon3.R;
import com.psiphon3.StatusActivity;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.SharedPreferencesImport;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.nfc.NdefRecord.createMime;

public abstract class MainBase {
    public static abstract class Activity extends LocalizedActivities.AppCompatActivity implements MyLog.ILogger {
        public Activity() {
            Utils.initializeSecureRandom();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            MyLog.setLogger(this);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            MyLog.unsetLogger();
        }

        /*
         * Partial MyLog.ILogger implementation
         */

        @Override
        public Context getContext() {
            return this;
        }
    }

    public static abstract class TabbedActivityBase extends Activity implements OnTabChangeListener {
        public static final String STATUS_ENTRY_AVAILABLE = "com.psiphon3.MainBase.TabbedActivityBase.STATUS_ENTRY_AVAILABLE";
        public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainBase.TabbedActivityBase.PREVENT_AUTO_START";
        protected static final String EGRESS_REGION_PREFERENCE = "egressRegionPreference";
        protected static final String TUNNEL_WHOLE_DEVICE_PREFERENCE = "tunnelWholeDevicePreference";
        protected static final String ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION = "askedToAccessCoarseLocationPermission";
        protected static final String CURRENT_TAB = "currentTab";

        protected static final int REQUEST_CODE_PREPARE_VPN = 100;
        protected static final int REQUEST_CODE_PREFERENCE = 101;
        protected static final int REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 102;


        private boolean m_canWholeDevice = false;

        protected Button m_toggleButton;
        private StatusListViewManager m_statusListManager = null;
        protected AppPreferences m_multiProcessPreferences;
        private ViewFlipper m_sponsorViewFlipper;
        private ScrollView m_statusLayout;
        private TextView m_statusTabLogLine;
        private TextView m_statusTabVersionLine;
        private SponsorHomePage m_sponsorHomePage;
        private LocalBroadcastManager m_localBroadcastManager;
        private Timer m_updateStatisticsUITimer;
        private Timer m_updateServiceStateUITimer;
        private boolean m_restartTunnel = false;
        private TextView m_elapsedConnectionTimeView;
        private TextView m_totalSentView;
        private TextView m_totalReceivedView;
        private DataTransferGraph m_slowSentGraph;
        private DataTransferGraph m_slowReceivedGraph;
        private DataTransferGraph m_fastSentGraph;
        private DataTransferGraph m_fastReceivedGraph;
        private RegionAdapter m_regionAdapter;
        protected SpinnerHelper m_regionSelector;
        protected CheckBox m_tunnelWholeDeviceToggle;
        protected CheckBox m_downloadOnWifiOnlyToggle;
        protected CheckBox m_disableTimeoutsToggle;
        private Toast m_invalidProxySettingsToast;
        private Button m_moreOptionsButton;
        private Button m_openBrowserButton;
        private LoggingObserver m_loggingObserver;
        private boolean m_serviceStateUIPaused = false;

        public TabbedActivityBase() {
            Utils.initializeSecureRandom();
        }

        protected boolean getSkipHomePage() {
            for (String homepage : getHomePages()) {
                if (homepage.contains("psiphon_skip_homepage")) {
                    return true;
                }
            }
            return false;
        }

        protected boolean showFirstHomePageInApp() {
            boolean showHomePage = false;
            List<String> homepages = getHomePages();
            if (!getSkipHomePage() && homepages.size() > 0) {
                showHomePage = true;
                for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
                    if (homepages.get(0).contains(homeTabUrlExclusion)) {
                        showHomePage = false;
                        break;
                    }
                }
            }
            return showHomePage;
        }

        // Avoid calling m_statusTabToggleButton.setImageResource() every 250 ms
        // when it is set to the connected image
        private ImageButton m_statusViewImage;
        private boolean m_statusIconSetToConnected = false;

        private View mGetHelpConnectingButton;
        private View mHelpConnectButton;

        private NfcAdapter mNfcAdapter;
        private NfcAdapterCallback mNfcAdapterCallback;
        private String mConnectionInfoPayload = "";

        private CountDownLatch mNfcConnectionInfoExportLatch;

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
        private class NfcAdapterCallback implements NfcAdapter.CreateNdefMessageCallback {
            @Override
            public NdefMessage createNdefMessage(NfcEvent event) {
                // Request the connection info get updated
                sendServiceMessage(TunnelManager.ClientToServiceMessage.NFC_CONNECTION_INFO_EXCHANGE_EXPORT.ordinal());

                // Wait for the service to respond
                try {
                    mNfcConnectionInfoExportLatch = new CountDownLatch(1);
                    if (!mNfcConnectionInfoExportLatch.await(2, TimeUnit.SECONDS)) {
                        // We didn't get a response within two seconds so
                        // set the payload to something invalid so nothing happens to the receiver
                        mConnectionInfoPayload = "";
                    }
                } catch (InterruptedException e) {
                    // Set the payload to something invalid so nothing happens to the receiver
                    mConnectionInfoPayload = "";
                }

                // Decode the payload then clear it so we don't try to import an old payload
                byte[] payload = ConnectionInfoExchangeUtils.decodeConnectionInfo(mConnectionInfoPayload);
                mConnectionInfoPayload = "";

                return new NdefMessage(
                        new NdefRecord[] { createMime(
                                ConnectionInfoExchangeUtils.NFC_MIME_TYPE, payload)
                        });
            }
        }

        private void setStatusState(int resId) {
            boolean statusShowing = m_sponsorViewFlipper.getCurrentView() == m_statusLayout;

            if (R.drawable.status_icon_connected == resId) {
                if (!m_statusIconSetToConnected) {
                    m_statusViewImage.setImageResource(resId);
                    m_statusIconSetToConnected = true;
                }

                // Show the sponsor web view, but only if there's a home page to
                // show and it's isn't excluded from being embedded.
                if (showFirstHomePageInApp() && statusShowing) {
                    m_sponsorViewFlipper.showNext();
                }
            } else {
                m_statusViewImage.setImageResource(resId);
                m_statusIconSetToConnected = false;

                // Show the status view
                if (!statusShowing) {
                    m_sponsorViewFlipper.showNext();
                }
            }
        }

        // Lateral navigation with TabHost:
        // Adapted from here:
        // http://danielkvist.net/code/animated-tabhost-with-slide-gesture-in-android
        private static final int ANIMATION_TIME = 240;
        protected TabHost m_tabHost;
        private int m_currentTab;
        private View m_previousView;
        private View m_currentView;
        private GestureDetector m_gestureDetector;

        /**
         * A gesture listener that listens for a left or right swipe and uses
         * the swip gesture to navigate a TabHost that uses an AnimatedTabHost
         * listener.
         * 
         * @author Daniel Kvist
         * 
         */
        class LateralGestureDetector extends SimpleOnGestureListener {
            private static final int SWIPE_MIN_DISTANCE = 120;
            private static final int SWIPE_MAX_OFF_PATH = 250;
            private static final int SWIPE_THRESHOLD_VELOCITY = 200;
            private final int maxTabs;

            /**
             * An empty constructor that uses the tabhosts content view to
             * decide how many tabs there are.
             */
            public LateralGestureDetector() {
                maxTabs = m_tabHost.getTabContentView().getChildCount();
            }

            /**
             * Listens for the onFling event and performs some calculations
             * between the touch down point and the touch up point. It then uses
             * that information to calculate if the swipe was long enough. It
             * also uses the swiping velocity to decide if it was a "true" swipe
             * or just some random touching.
             */
            @Override
            public boolean onFling(MotionEvent event1, MotionEvent event2, float velocityX, float velocityY) {
                if (event1 != null && event2 != null) {
                    int newTab;
                    if (Math.abs(event1.getY() - event2.getY()) > SWIPE_MAX_OFF_PATH) {
                        return false;
                    }
                    if (event1.getX() - event2.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe right to left
                        newTab = m_currentTab + 1;
                    } else if (event2.getX() - event1.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe left to right
                        newTab = m_currentTab - 1;
                    } else {
                        return false;
                    }
                    if (newTab < 0 || newTab > (maxTabs - 1)) {
                        return false;
                    }
                    m_tabHost.setCurrentTab(newTab);
                }
                return super.onFling(event1, event2, velocityX, velocityY);
            }
        }

        /**
         * When tabs change we fetch the current view that we are animating to
         * and animate it and the previous view in the appropriate directions.
         */
        @Override
        public void onTabChanged(String tabId) {
            m_currentView = m_tabHost.getCurrentView();
            if (m_previousView != null) {
                if (m_tabHost.getCurrentTab() > m_currentTab) {
                    m_previousView.setAnimation(outToLeftAnimation());
                    m_currentView.setAnimation(inFromRightAnimation());
                } else {
                    m_previousView.setAnimation(outToRightAnimation());
                    m_currentView.setAnimation(inFromLeftAnimation());
                }
            }
            m_previousView = m_currentView;
            m_currentTab = m_tabHost.getCurrentTab();

            m_multiProcessPreferences.put(CURRENT_TAB, m_currentTab);
        }

        /**
         * Custom animation that animates in from right
         * 
         * @return Animation the Animation object
         */
        private Animation inFromRightAnimation() {
            Animation inFromRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromRight);
        }

        /**
         * Custom animation that animates out to the right
         * 
         * @return Animation the Animation object
         */
        private Animation outToRightAnimation() {
            Animation outToRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outToRight);
        }

        /**
         * Custom animation that animates in from left
         * 
         * @return Animation the Animation object
         */
        private Animation inFromLeftAnimation() {
            Animation inFromLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromLeft);
        }

        /**
         * Custom animation that animates out to the left
         * 
         * @return Animation the Animation object
         */
        private Animation outToLeftAnimation() {
            Animation outtoLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outtoLeft);
        }

        /**
         * Helper method that sets some common properties
         * 
         * @param animation
         *            the animation to give common properties
         * @return the animation with common properties
         */
        private Animation setProperties(Animation animation) {
            animation.setDuration(ANIMATION_TIME);
            animation.setInterpolator(new AccelerateInterpolator());
            return animation;
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            if (!isServiceRunning()) {
                // remove logs from previous sessions
                LoggingProvider.LogDatabaseHelper.truncateLogs(this, true);
            }

            m_multiProcessPreferences = new AppPreferences(this);
            // Migrate 'More Options' SharedPreferences to tray preferences:
            // The name of the DefaultSharedPreferences is this.getPackageName() + "_preferences"
            // http://stackoverflow.com/questions/5946135/difference-between-getdefaultsharedpreferences-and-getsharedpreferences
            String prefName = this.getPackageName() + "_preferences";
            m_multiProcessPreferences.migrate(
                    // Top level  preferences
                    new SharedPreferencesImport(this, prefName, CURRENT_TAB, CURRENT_TAB),
                    new SharedPreferencesImport(this, prefName, EGRESS_REGION_PREFERENCE, EGRESS_REGION_PREFERENCE),
                    new SharedPreferencesImport(this, prefName, TUNNEL_WHOLE_DEVICE_PREFERENCE, TUNNEL_WHOLE_DEVICE_PREFERENCE),
                    new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference)),
                    // More Options preferences
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceLanguageSelection), getString(R.string.preferenceLanguageSelection))
            );

            EmbeddedValues.initialize(this);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            if (m_sponsorHomePage != null) {
                m_sponsorHomePage.stop();
                m_sponsorHomePage = null;
            }
        }

        protected void setupActivityLayout() {
            // Set up tabs
            m_tabHost.setup();

            TabSpec homeTab = m_tabHost.newTabSpec("home");
            homeTab.setContent(R.id.sponsorViewFlipper);
            homeTab.setIndicator(getText(R.string.home_tab_name));

            TabSpec statisticsTab = m_tabHost.newTabSpec("statistics");
            statisticsTab.setContent(R.id.statisticsView);
            statisticsTab.setIndicator(getText(R.string.statistics_tab_name));

            TabSpec settingsTab = m_tabHost.newTabSpec("settings");
            settingsTab.setContent(R.id.settingsView);
            settingsTab.setIndicator(getText(R.string.settings_tab_name));

            TabSpec logsTab = m_tabHost.newTabSpec("logs");
            logsTab.setContent(R.id.logsTab);
            logsTab.setIndicator(getText(R.string.logs_tab_name));

            m_tabHost.addTab(homeTab);
            m_tabHost.addTab(statisticsTab);
            m_tabHost.addTab(settingsTab);
            m_tabHost.addTab(logsTab);

            m_gestureDetector = new GestureDetector(this, new LateralGestureDetector());
            OnTouchListener onTouchListener = new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // Give the view a chance to handle the event first, ie a
                    // scrollview or listview
                    v.onTouchEvent(event);

                    return !m_gestureDetector.onTouchEvent(event);
                }
            };

            m_tabHost.setOnTouchListener(onTouchListener);
            m_statusLayout = (ScrollView) findViewById(R.id.statusLayout);
            m_statusLayout.setOnTouchListener(onTouchListener);
            m_statusViewImage = (ImageButton) findViewById(R.id.statusViewImage);
            m_statusViewImage.setOnTouchListener(onTouchListener);
            findViewById(R.id.sponsorViewFlipper).setOnTouchListener(onTouchListener);
            findViewById(R.id.sponsorWebView).setOnTouchListener(onTouchListener);
            findViewById(R.id.statisticsView).setOnTouchListener(onTouchListener);
            findViewById(R.id.settingsView).setOnTouchListener(onTouchListener);
            findViewById(R.id.regionSelector).setOnTouchListener(onTouchListener);
            findViewById(R.id.tunnelWholeDeviceToggle).setOnTouchListener(onTouchListener);
            findViewById(R.id.feedbackButton).setOnTouchListener(onTouchListener);
            findViewById(R.id.aboutButton).setOnTouchListener(onTouchListener);
            ListView statusListView = (ListView) findViewById(R.id.statusList);
            statusListView.setOnTouchListener(onTouchListener);

            m_tabHost.setOnTabChangedListener(this);

            int currentTab = m_multiProcessPreferences.getInt(CURRENT_TAB, 0);
            m_currentTab = currentTab;
            m_tabHost.setCurrentTab(currentTab);

            m_sponsorViewFlipper = (ViewFlipper) findViewById(R.id.sponsorViewFlipper);
            m_sponsorViewFlipper.setInAnimation(AnimationUtils.loadAnimation(this, android.R.anim.slide_in_left));
            m_sponsorViewFlipper.setOutAnimation(AnimationUtils.loadAnimation(this, android.R.anim.slide_out_right));

            m_statusTabLogLine = (TextView) findViewById(R.id.lastlogline);
            m_statusTabVersionLine = (TextView) findViewById(R.id.versionline);
            m_elapsedConnectionTimeView = (TextView) findViewById(R.id.elapsedConnectionTime);
            m_totalSentView = (TextView) findViewById(R.id.totalSent);
            m_totalReceivedView = (TextView) findViewById(R.id.totalReceived);
            m_regionSelector = new SpinnerHelper(findViewById(R.id.regionSelector));
            m_tunnelWholeDeviceToggle = (CheckBox) findViewById(R.id.tunnelWholeDeviceToggle);
            m_disableTimeoutsToggle = (CheckBox) findViewById(R.id.disableTimeoutsToggle);
            m_downloadOnWifiOnlyToggle = (CheckBox) findViewById(R.id.downloadOnWifiOnlyToggle);
            m_moreOptionsButton = (Button) findViewById(R.id.moreOptionsButton);
            m_openBrowserButton = (Button) findViewById(R.id.openBrowserButton);

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);
            m_localBroadcastManager.registerReceiver(new StatusEntryAdded(), new IntentFilter(STATUS_ENTRY_AVAILABLE));

            updateServiceStateUI();

            m_regionAdapter = new RegionAdapter(this);
            m_regionSelector.setAdapter(m_regionAdapter);
            String egressRegionPreference = m_multiProcessPreferences.getString(EGRESS_REGION_PREFERENCE,
                    PsiphonConstants.REGION_CODE_ANY);

            m_regionSelector.setSelectionByValue(egressRegionPreference);

            setTunnelConfigEgressRegion(egressRegionPreference);

            m_regionSelector.setOnItemSelectedListener(regionSpinnerOnItemSelected);

            m_canWholeDevice = Utils.hasVpnService();

            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            boolean tunnelWholeDevicePreference = m_multiProcessPreferences.getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE,
                    m_canWholeDevice);
            m_tunnelWholeDeviceToggle.setChecked(tunnelWholeDevicePreference);
            setTunnelConfigWholeDevice(m_canWholeDevice && tunnelWholeDevicePreference);

            // Show download-wifi-only preference only in not Play Store build
            if (!EmbeddedValues.IS_PLAY_STORE_BUILD) {
                boolean downLoadWifiOnlyPreference = m_multiProcessPreferences.getBoolean(
                        getString(R.string.downloadWifiOnlyPreference),
                        PsiphonConstants.DOWNLOAD_WIFI_ONLY_PREFERENCE_DEFAULT);
                m_downloadOnWifiOnlyToggle.setChecked(downLoadWifiOnlyPreference);
            }
            else {
                m_downloadOnWifiOnlyToggle.setEnabled(false);
                m_downloadOnWifiOnlyToggle.setVisibility(View.GONE);
            }

            boolean disableTimeoutsPreference = m_multiProcessPreferences.getBoolean(
                    getString(R.string.disableTimeoutsPreference), false);
            m_disableTimeoutsToggle.setChecked(disableTimeoutsPreference);
            setTunnelConfigDisableTimeouts(disableTimeoutsPreference);

            String msg = getContext().getString(R.string.client_version, EmbeddedValues.CLIENT_VERSION);
            m_statusTabVersionLine.setText(msg);

            // The LoggingObserver will run in a separate thread than the main UI thread
            HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
            loggingObserverThread.start();
            m_loggingObserver = new LoggingObserver(this, new Handler(loggingObserverThread.getLooper()));

            // Force the UI to display logs already loaded into the StatusList message history
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(STATUS_ENTRY_AVAILABLE));

            // Get the connection help buttons
            mGetHelpConnectingButton = findViewById(R.id.getHelpConnectingButton);
            mHelpConnectButton = findViewById(R.id.howToHelpButton);

            // Only handle NFC if the version is sufficient
            if (ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                // Check for available NFC Adapter
                mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
                if (mNfcAdapter != null) {
                    // Register callback
                    mNfcAdapterCallback = new NfcAdapterCallback();

                    // Always enable receiving an NFC tag, and determine what to do with it when we receive it based on the service state.
                    // For example, in the Stopped state, we can receive a tag, and instruct the user to start the tunnel service and try again.
                    PackageManager packageManager = getPackageManager();
                    ComponentName componentName = new ComponentName(getPackageName(), NfcActivity.class.getName());
                    packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                }
            }

            // Always start disabled
            setConnectionHelpState(ConnectionHelpState.DISABLED);
        }

        /**
         * Show the sponsor home page, either in the embedded view web view or
         * in the external browser.
         *
         * @param freshConnect If false, the home page will not be opened in an external
         *                     browser. This is to prevent the page from opening every
         *                     time the activity is created.
         */
        protected void resetSponsorHomePage(boolean freshConnect) {
            if (getSkipHomePage()) {
                return;
            }

            String url;
            List<String> homepages = getHomePages();
            if (homepages.size() > 0) {
                url = homepages.get(0);
            } else {
                return;
            }

            if (!showFirstHomePageInApp()) {
                if (freshConnect) {
                    displayBrowser(getContext(), url);
                }
                return;
            }

            // At this point we're showing the URL in the embedded webview.
            m_sponsorHomePage = new SponsorHomePage((WebView) findViewById(R.id.sponsorWebView), (ProgressBar) findViewById(R.id.sponsorWebViewProgressBar));
            m_sponsorHomePage.load(url);
        }

        @Override
        protected void onResume() {
            super.onResume();

            // Load new logs from the logging provider now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                m_loggingObserver.dispatchChange(false, LoggingProvider.INSERT_URI);
            } else {
                m_loggingObserver.dispatchChange(false);
            }

            // Load new logs from the logging provider when it changes
            getContentResolver().registerContentObserver(LoggingProvider.INSERT_URI, true, m_loggingObserver);

            // From: http://steve.odyfamily.com/?p=12
            m_updateStatisticsUITimer = new Timer();
            m_updateStatisticsUITimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateStatisticsUICallback();
                        }
                    });
                }
            }, 0, 1000);

            m_updateServiceStateUITimer = new Timer();
            m_updateServiceStateUITimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateServiceStateUI();
                            checkRestartTunnel();
                        }
                    });
                }
            }, 0, 250);

            // Don't show the keyboard until edit selected
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

            // Set to foreground before binding to the service. Otherwise there would be a short
            // period of time where we could miss a handshake intent after getting the
            // tunnel state from registering with the service.
            m_multiProcessPreferences.put(getString(R.string.status_activity_foreground), true);

            if (isServiceRunning()) {
                startAndBindTunnelService();
            } else {
                // reset the tunnel state
                m_tunnelState = new TunnelManager.State();
            }

            // Note that handling the NFC Intent will attempt to send a message to the running Tunnel service
            // so call this after binding to the service.
            // Note that there may still be a race condition between the bind, which recreates the outgoing messenger,
            // and the following sendServiceMessage called by handleNfcIntent but in testing it seems that the service
            // binding is fast enough.
            if (ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                Intent intent = getIntent();
                // Check to see that the Activity started due to an Android Beam
                if (ConnectionInfoExchangeUtils.isNfcDiscoveredIntent(intent)) {
                    handleNfcIntent(intent);

                    // We only want to respond to the NFC Intent once,
                    // so we need to clear it (by setting it to a non-special intent).
                    setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
                }
            }
        }

        @Override
        protected void onPause() {
            super.onPause();

            getContentResolver().unregisterContentObserver(m_loggingObserver);

            cancelInvalidProxySettingsToast();

            m_updateStatisticsUITimer.cancel();
            m_updateServiceStateUITimer.cancel();

            unbindTunnelService();

            m_multiProcessPreferences.put(getString(R.string.status_activity_foreground), false);
        }

        protected void doToggle() {
            if (!isServiceRunning()) {
                startUp();
            } else {
                stopTunnelService();
            }
        }

        public class StatusEntryAdded extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (m_statusListManager != null) {
                    m_statusListManager.notifyStatusAdded();
                }

                runOnUiThread(new Runnable() {
                    public void run() {
                        StatusList.StatusEntry statusEntry = StatusList.getLastStatusEntryForDisplay();
                        if (statusEntry != null) {
                            String msg = getContext().getString(statusEntry.stringId(), statusEntry.formatArgs());
                            m_statusTabLogLine.setText(msg);
                        }
                    }
                });
            }
        }

        protected abstract void startUp();

        protected void doAbout() {
            if (URLUtil.isValidUrl(EmbeddedValues.INFO_LINK_URL)) {
                // TODO: if connected, open in Psiphon browser?
                // Events.displayBrowser(this,
                // Uri.parse(PsiphonConstants.INFO_LINK_URL));

                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(EmbeddedValues.INFO_LINK_URL));
                startActivity(browserIntent);
            }
        }

        public void onMoreOptionsClick(View v) {
            startActivityForResult(new Intent(this, MoreOptionsPreferenceActivity.class), REQUEST_CODE_PREFERENCE);
        }

        public abstract void onFeedbackClick(View v);

        public void onAboutClick(View v) {
            doAbout();
        }

        private final AdapterView.OnItemSelectedListener regionSpinnerOnItemSelected = new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String regionCode = parent.getItemAtPosition(position).toString();
                onRegionSelected(regionCode);
            }

            @Override
            public void onNothingSelected(AdapterView parent) {
            }
        };

        public void onRegionSelected(String selectedRegionCode) {
            // Just in case an OnItemSelected message is in transit before
            // setEnabled is processed...(?)
            if (!m_regionSelector.isEnabled()) {
                return;
            }

            String egressRegionPreference = m_multiProcessPreferences.getString(EGRESS_REGION_PREFERENCE,
                    PsiphonConstants.REGION_CODE_ANY);
            if (selectedRegionCode.equals(egressRegionPreference) && selectedRegionCode.equals(getTunnelConfigEgressRegion())) {
                return;
            }

            updateEgressRegionPreference(selectedRegionCode);

            // NOTE: reconnects even when Any is selected: we could select a
            // faster server
            scheduleRunningTunnelServiceRestart();
        }

        protected void updateEgressRegionPreference(String egressRegionPreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            m_multiProcessPreferences.put(EGRESS_REGION_PREFERENCE, egressRegionPreference);

            setTunnelConfigEgressRegion(egressRegionPreference);
        }

        public void onTunnelWholeDeviceToggle(View v) {
            // Just in case an OnClick message is in transit before setEnabled
            // is processed...(?)
            if (!m_tunnelWholeDeviceToggle.isEnabled()) {
                return;
            }

            boolean tunnelWholeDevicePreference = m_tunnelWholeDeviceToggle.isChecked();
            updateWholeDevicePreference(tunnelWholeDevicePreference);
            scheduleRunningTunnelServiceRestart();
        }

        protected void updateWholeDevicePreference(boolean tunnelWholeDevicePreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            m_multiProcessPreferences.put(TUNNEL_WHOLE_DEVICE_PREFERENCE, tunnelWholeDevicePreference);

            // When enabling BOM, we don't use the TunnelVpnService, so we can disable it
            // which prevents the user having Always On turned on.

            PackageManager packageManager = getPackageManager();
            ComponentName componentName = new ComponentName(getPackageName(), TunnelVpnService.class.getName());
            packageManager.setComponentEnabledSetting(componentName,
                    tunnelWholeDevicePreference ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);

            setTunnelConfigWholeDevice(tunnelWholeDevicePreference);
        }

        public void onDisableTimeoutsToggle(View v) {
            boolean disableTimeoutsChecked = m_disableTimeoutsToggle.isChecked();
            updateDisableTimeoutsPreference(disableTimeoutsChecked);
            scheduleRunningTunnelServiceRestart();
        }
        protected void updateDisableTimeoutsPreference(boolean disableTimeoutsPreference) {
            m_multiProcessPreferences.put(getString(R.string.disableTimeoutsPreference), disableTimeoutsPreference);

            setTunnelConfigDisableTimeouts(disableTimeoutsPreference);
        }

        public void onDownloadOnWifiOnlyToggle(View v) {
            boolean downloadWifiOnly = m_downloadOnWifiOnlyToggle.isChecked();

            // There is no need to restart the service if the value of downloadWifiOnly
            // has changed because upgrade downloads happen in a different, temp tunnel

            m_multiProcessPreferences.put(getString(R.string.downloadWifiOnlyPreference), downloadWifiOnly);
        }

        // Basic check of proxy settings values
        private boolean customProxySettingsValuesValid() {
            UpstreamProxySettings.ProxySettings proxySettings = UpstreamProxySettings.getProxySettings(this);
            return proxySettings != null && proxySettings.proxyHost.length() > 0 && proxySettings.proxyPort >= 1 && proxySettings.proxyPort <= 65535;
        }

        private class DataTransferGraph {
            private final Activity m_activity;
            private final LinearLayout m_graphLayout;
            private GraphicalView m_chart;
            private final XYMultipleSeriesDataset m_chartDataset;
            private final XYMultipleSeriesRenderer m_chartRenderer;
            private final XYSeries m_chartCurrentSeries;
            private final XYSeriesRenderer m_chartCurrentRenderer;

            public DataTransferGraph(Activity activity, int layoutId) {
                m_activity = activity;
                m_graphLayout = (LinearLayout) activity.findViewById(layoutId);
                m_chartDataset = new XYMultipleSeriesDataset();
                m_chartRenderer = new XYMultipleSeriesRenderer();
                m_chartRenderer.setGridColor(Color.GRAY);
                m_chartRenderer.setShowGrid(true);
                m_chartRenderer.setShowLabels(false);
                m_chartRenderer.setShowLegend(false);
                m_chartRenderer.setShowAxes(false);
                m_chartRenderer.setPanEnabled(false, false);
                m_chartRenderer.setZoomEnabled(false, false);

                // Make the margins transparent.
                // Note that this value is a bit magical. One would expect
                // android.graphics.Color.TRANSPARENT to work, but it doesn't.
                // Nor does 0x00000000. Ref:
                // http://developer.android.com/reference/android/graphics/Color.html
                m_chartRenderer.setMarginsColor(0x00FFFFFF);

                m_chartCurrentSeries = new XYSeries("");
                m_chartDataset.addSeries(m_chartCurrentSeries);
                m_chartCurrentRenderer = new XYSeriesRenderer();
                m_chartCurrentRenderer.setColor(Color.YELLOW);
                m_chartRenderer.addSeriesRenderer(m_chartCurrentRenderer);
            }

            public void update(ArrayList<Long> data) {
                m_chartCurrentSeries.clear();
                for (int i = 0; i < data.size(); i++) {
                    m_chartCurrentSeries.add(i, data.get(i));
                }
                if (m_chart == null) {
                    m_chart = ChartFactory.getLineChartView(m_activity, m_chartDataset, m_chartRenderer);
                    m_graphLayout.addView(m_chart);
                } else {
                    m_chart.repaint();
                }
            }
        }

        private void updateStatisticsUICallback() {
            DataTransferStats.DataTransferStatsForUI dataTransferStats = DataTransferStats.getDataTransferStatsForUI();
            m_elapsedConnectionTimeView.setText(isTunnelConnected() ? getString(R.string.connected_elapsed_time,
                    Utils.elapsedTimeToDisplay(dataTransferStats.getElapsedTime())) : getString(R.string.disconnected));
            m_totalSentView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesSent(), false));
            m_totalReceivedView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesReceived(), false));
            m_slowSentGraph.update(dataTransferStats.getSlowSentSeries());
            m_slowReceivedGraph.update(dataTransferStats.getSlowReceivedSeries());
            m_fastSentGraph.update(dataTransferStats.getFastSentSeries());
            m_fastReceivedGraph.update(dataTransferStats.getFastReceivedSeries());
        }

        private void cancelInvalidProxySettingsToast() {
            if (m_invalidProxySettingsToast != null) {
                View toastView = m_invalidProxySettingsToast.getView();
                if (toastView != null) {
                    if (toastView.isShown()) {
                        m_invalidProxySettingsToast.cancel();
                    }
                }
            }
        }

        private void updateServiceStateUI() {
            if (m_serviceStateUIPaused) {
                return;
            }

            if (!m_boundToTunnelService) {
                setStatusState(R.drawable.status_icon_disconnected);
                m_openBrowserButton.setEnabled(false);
                if (!isServiceRunning()) {
                    m_toggleButton.setText(getText(R.string.start));
                    enableToggleServiceUI();

                    if (WebViewProxySettings.isLocalProxySet()) {
                        WebViewProxySettings.resetLocalProxy(this);
                    }
                } else {
                    m_toggleButton.setText(getText(R.string.waiting));
                    disableToggleServiceUI();

                    if (WebViewProxySettings.isLocalProxySet()) {
                        WebViewProxySettings.resetLocalProxy(this);
                    }
                }
            } else {
                if (isTunnelConnected()) {
                    setStatusState(R.drawable.status_icon_connected);
                    m_openBrowserButton.setEnabled(true);
                } else {
                    setStatusState(R.drawable.status_icon_connecting);
                    m_openBrowserButton.setEnabled(false);
                }
                m_toggleButton.setText(getText(R.string.stop));
                enableToggleServiceUI();
            }

            updateAdsForServiceState();
        }

        protected abstract void updateAdsForServiceState();

        protected void enableToggleServiceUI() {
            m_toggleButton.setEnabled(true);
            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            m_disableTimeoutsToggle.setEnabled(true);
            m_regionSelector.setEnabled(true);
            m_moreOptionsButton.setEnabled(true);
        }

        protected void disableToggleServiceUI() {
            m_toggleButton.setEnabled(false);
            m_tunnelWholeDeviceToggle.setEnabled(false);
            m_disableTimeoutsToggle.setEnabled(false);
            m_regionSelector.setEnabled(false);
            m_moreOptionsButton.setEnabled(false);
        }

        protected void pauseServiceStateUI() {
            m_serviceStateUIPaused = true;
            disableToggleServiceUI();
        }

        protected void resumeServiceStateUI() {
            m_serviceStateUIPaused = false;
            updateServiceStateUI();
        }

        private void checkRestartTunnel() {
            if (m_restartTunnel &&
                    !m_boundToTunnelService &&
                    !isServiceRunning()) {
                m_restartTunnel = false;
                startTunnel();
            }
        }

        private void scheduleRunningTunnelServiceRestart() {
            String runningService = getRunningService();

            if (runningService == null) {
                // There is no running service, do nothing.
                return;
            }

            // If the running service doesn't need to be changed from WDM to BOM or vice versa we will
            // just message the service a restart command and have it restart Psiphon tunnel (and VPN
            // if in WDM mode) internally via TunnelManager.onRestartCommand without stopping the service.
            // If the WDM preference has changed we will message the service to stop self, wait for it to
            // stop and then start a brand new service via checkRestartTunnel on a timer.
            if ((getTunnelConfigWholeDevice() && Utils.hasVpnService() && isVpnService(runningService))
                    || (!getTunnelConfigWholeDevice() && runningService.equals(TunnelService.class.getName()))) {
                // A dummy intent just used to pass new tunnel config with the service restart command
                Intent tunnelConfigIntent = new Intent();
                configureServiceIntent(tunnelConfigIntent);
                sendServiceMessage(TunnelManager.ClientToServiceMessage.RESTART_SERVICE.ordinal(), tunnelConfigIntent.getExtras());
            } else {
                m_restartTunnel = true;
                stopTunnelService();
                // The tunnel will get restarted in m_updateServiceStateTimer
            }
        }

        protected void startTunnel() {
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
                } else if(!this.isFinishing()){
                    final Context context = this;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(context)
                                    .setCancelable(false)
                                    .setOnKeyListener(
                                            new DialogInterface.OnKeyListener() {
                                                @Override
                                                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                    // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                    return keyCode == KeyEvent.KEYCODE_SEARCH;
                                                }})
                                    .setTitle(R.string.MainBase_AccessCoarseLocationPermissionPromptTitle)
                                    .setMessage(R.string.MainBase_AccessCoarseLocationPermissionPromptMessage)
                                    .setPositiveButton(R.string.MainBase_AccessCoarseLocationPermissionPositiveButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    ActivityCompat.requestPermissions(TabbedActivityBase.this,
                                                            new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                                            REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);
                                                }})
                                    .setNegativeButton(R.string.MainBase_AccessCoarseLocationPermissionNegativeButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    proceedStartTunnel();
                                                }})
                                    .setOnCancelListener(
                                            new DialogInterface.OnCancelListener() {
                                                @Override
                                                public void onCancel(DialogInterface dialog) {
                                                    // Do nothing (this prompt may reappear)
                                                }})
                                    .show();
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode,
                                               String permissions[], int[] grantResults) {
            switch (requestCode) {
                case REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION:
                    proceedStartTunnel();
                    break;

                default:
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }

        private void proceedStartTunnel() {
            // Don't start if custom proxy settings is selected and values are
            // invalid
            boolean useHTTPProxyPreference = UpstreamProxySettings.getUseHTTPProxy(this);
            boolean useCustomProxySettingsPreference = UpstreamProxySettings.getUseCustomProxySettings(this);

            if (useHTTPProxyPreference && useCustomProxySettingsPreference && !customProxySettingsValuesValid()) {
                cancelInvalidProxySettingsToast();
                m_invalidProxySettingsToast = Toast.makeText(this, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                m_invalidProxySettingsToast.show();
                return;
            }

            boolean waitingForPrompt = false;

            if (getTunnelConfigWholeDevice() && Utils.hasVpnService()) {
                // VpnService backwards compatibility: for lazy class loading
                // the VpnService
                // class reference has to be in another function (doVpnPrepare),
                // not just
                // in a conditional branch.
                waitingForPrompt = doVpnPrepare();
            }
            if (!waitingForPrompt) {
                startAndBindTunnelService();
            }
        }

        protected boolean doVpnPrepare() {
            
            // Devices without VpnService support throw various undocumented
            // exceptions, including ActivityNotFoundException and ActivityNotFoundException.
            // For example: http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
            try {
                return vpnPrepare();
            } catch (Exception e) {
                MyLog.e(R.string.tunnel_whole_device_exception, MyLog.Sensitivity.NOT_SENSITIVE);

                // Turn off the option and abort.

                m_tunnelWholeDeviceToggle.setChecked(false);
                m_tunnelWholeDeviceToggle.setEnabled(false);
                updateWholeDevicePreference(false);

                // true = waiting for prompt, although we can't start the
                // activity so onActivityResult won't be called
                return true;
            }
        }

        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        protected boolean vpnPrepare() throws ActivityNotFoundException {
            // VpnService: need to display OS user warning. If whole device
            // option is
            // selected and we expect to use VpnService, so the prompt here in
            // the UI
            // before starting the service.

            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                // TODO: can we disable the mode before we reach this this
                // failure point with
                // resolveActivity()? We'll need the intent from prepare() or
                // we'll have to mimic it.
                // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29

                startActivityForResult(intent, REQUEST_CODE_PREPARE_VPN);

                // startAndBindTunnelService will be called in onActivityResult
                return true;
            }

            return false;
        }

        private boolean isSettingsRestartRequired() {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.moreOptionsPreferencesName), MODE_PRIVATE);

            // check if "excluded apps" list has changed
            String spExcludedAppsString = prefs.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
            if (!spExcludedAppsString.equals(m_multiProcessPreferences.getString(getString(R.string.preferenceExcludeAppsFromVpnString), ""))) {
                return true;
            }


            // check if "use proxy" has changed
            boolean useHTTPProxyPreference = prefs.getBoolean(getString(R.string.useProxySettingsPreference),
                    false);
            if (useHTTPProxyPreference != UpstreamProxySettings.getUseHTTPProxy(this)) {
                return true;
            }

            // no further checking if "use proxy" is off and has not
            // changed
            if (!useHTTPProxyPreference) {
                return false;
            }

            // check if "use custom proxy settings"
            // radio has changed
            boolean useCustomProxySettingsPreference = prefs.getBoolean(
                    getString(R.string.useCustomProxySettingsPreference), false);
            if (useCustomProxySettingsPreference != UpstreamProxySettings.getUseCustomProxySettings(this)) {
                return true;
            }

            // no further checking if "use custom proxy" is off and has
            // not changed
            if (!useCustomProxySettingsPreference) {
                return false;
            }

            // "use custom proxy" is selected, check if
            // host || port have changed
            if (!prefs.getString(getString(R.string.useCustomProxySettingsHostPreference), "")
                    .equals(UpstreamProxySettings.getCustomProxyHost(this))
                    || !prefs.getString(getString(R.string.useCustomProxySettingsPortPreference), "")
                            .equals(UpstreamProxySettings.getCustomProxyPort(this))) {
                return true;
            }

            // check if "use proxy authentication" has changed
            boolean useProxyAuthenticationPreference = prefs.getBoolean(
                    getString(R.string.useProxyAuthenticationPreference), false);
            if (useProxyAuthenticationPreference != UpstreamProxySettings.getUseProxyAuthentication(this)) {
                return true;
            }

            // no further checking if "use proxy authentication" is off
            // and has not changed
            if (!useProxyAuthenticationPreference) {
                return false;
            }

            // "use proxy authentication" is checked, check if
            // username || password || domain have changed
            return !prefs.getString(getString(R.string.useProxyUsernamePreference), "")
                    .equals(UpstreamProxySettings.getProxyUsername(this))
                    || !prefs.getString(getString(R.string.useProxyPasswordPreference), "")
                    .equals(UpstreamProxySettings.getProxyPassword(this))
                    || !prefs.getString(getString(R.string.useProxyDomainPreference), "")
                    .equals(UpstreamProxySettings.getProxyDomain(this));
        }

        @Override
        protected void onActivityResult(int request, int result, Intent data) {
            if (request == REQUEST_CODE_PREPARE_VPN) {
                if(result == RESULT_OK) {
                    startAndBindTunnelService();
                } else if(result == RESULT_CANCELED) {
                    onVpnPromptCancelled();
                }
            } else if (request == REQUEST_CODE_PREFERENCE) {

                // Verify if restart is required before saving new settings
                boolean bRestartRequired = isSettingsRestartRequired();

                // Import 'More Options' values to tray preferences
                String prefName = getString(R.string.moreOptionsPreferencesName);
                m_multiProcessPreferences.migrate(
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference))
                );

                if (bRestartRequired) {
                    if (isServiceRunning()) {
                        startAndBindTunnelService();
                    }
                    scheduleRunningTunnelServiceRestart();
                }

                if (data != null && data.getBooleanExtra(MoreOptionsPreferenceActivity.INTENT_EXTRA_LANGUAGE_CHANGED, false)) {
                    // This is a bit of a weird hack to cause a restart, but it works
                    // Previous attempts to use the alarm manager or others caused a variable amount of wait (up to about a second)
                    // before the activity would relaunch. This *seems* to provide the best functionality across phones.
                    finish();
                    Intent intent = new Intent(this, StatusActivity.class);
                    intent.putExtra(INTENT_EXTRA_PREVENT_AUTO_START, true);
                    startActivity(intent);
                    System.exit(1);
                }
            }
        }

        protected void onVpnPromptCancelled() {}

        // Tunnel config, sent to the service.
        private TunnelManager.Config m_tunnelConfig = new TunnelManager.Config();

        protected void setTunnelConfigEgressRegion(String tunnelConfigEgressRegion) {
            m_tunnelConfig.egressRegion = tunnelConfigEgressRegion;
        }

        protected String getTunnelConfigEgressRegion() {
            return m_tunnelConfig.egressRegion;
        }

        protected void setTunnelConfigWholeDevice(boolean tunnelConfigWholeDevice) {
            m_tunnelConfig.wholeDevice = tunnelConfigWholeDevice;
        }

        protected boolean getTunnelConfigWholeDevice() {
            return m_tunnelConfig.wholeDevice;
        }

        protected void setTunnelConfigDisableTimeouts(boolean disableTimeouts) {
            m_tunnelConfig.disableTimeouts = disableTimeouts;
        }

        protected boolean getTunnelConfigDisableTimeouts() {
            return m_tunnelConfig.disableTimeouts;
        }

        protected void configureServiceIntent(Intent intent) {
            // Indicate that the user triggered this start request
            intent.putExtra(TunnelVpnService.USER_STARTED_INTENT_FLAG, true);

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_WHOLE_DEVICE,
                    getTunnelConfigWholeDevice());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_EGRESS_REGION,
                    getTunnelConfigEgressRegion());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS,
                    getTunnelConfigDisableTimeouts());

            intent.putExtra(TunnelManager.CLIENT_MESSENGER, m_incomingMessenger);

            LocaleManager localeManager = LocaleManager.getInstance(this);
            intent.putExtra(TunnelManager.EXTRA_LANGUAGE_CODE, localeManager.getLanguage());
        }

        protected void startAndBindTunnelService() {

            // Disable service-toggling controls while service is starting up
            // (i.e., while isServiceRunning can't be relied upon)
            disableToggleServiceUI();
            Intent intent;

            if (getTunnelConfigWholeDevice() && Utils.hasVpnService()) {
                // VpnService backwards compatibility: startVpnServiceIntent is a wrapper
                // function so we don't reference the undefined class when this
                // function is loaded.
                intent = startVpnServiceIntent();
            } else {
                intent = new Intent(this, TunnelService.class);
            }
            configureServiceIntent(intent);

            // Use a wrapper to start a service in SDK >= 26
            // which is defined like following
            /*
                public static void startForegroundService(Context context, Intent intent) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        context.startForegroundService(intent);
                    } else {
                        // Pre-O behavior.
                        context.startService(intent);
                    }
                }
             */
            try {
                ContextCompat.startForegroundService(this, intent);
                if (bindService(intent, m_tunnelServiceConnection, 0)) {
                    m_boundToTunnelService = true;
                }
            } catch (IllegalStateException | SecurityException e) {
                // Also log to diagnostics
                MyLog.g("startAndBindTunnelService failed with error: " + e);
            }
        }

        private Intent startVpnServiceIntent() {
            return new Intent(this, TunnelVpnService.class);
        }

        // Shared tunnel state, received from service in the HANDSHAKE
        // intent and in various state-related Messages.
        private TunnelManager.State m_tunnelState = new TunnelManager.State();

        protected boolean isTunnelConnected() {
            return m_tunnelState.isConnected;
        }

        protected ArrayList<String> getHomePages() {
            ArrayList<String> homePages = new ArrayList<>();
            homePages.addAll(m_tunnelState.homePages);
            return homePages;
        }

        protected int getListeningLocalHttpProxyPort() {
            return m_tunnelState.listeningLocalHttpProxyPort;
        }

        protected String getClientRegion() {
            return m_tunnelState.clientRegion;
        }

        protected void getTunnelStateFromHandshakeIntent(Intent intent) {
            if (!intent.getAction().equals(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
                return;
            }
            getTunnelStateFromBundle(intent.getExtras());
        }

        private void getTunnelStateFromBundle(Bundle data) {
            if (data == null) {
                return;
            }

            m_tunnelState.isConnected = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_CONNECTED);
            if (m_tunnelState.isConnected) {
                setStatusState(R.drawable.status_icon_connected);
            } else {
                setStatusState(R.drawable.status_icon_connecting);
            }
            m_tunnelState.listeningLocalSocksProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT);
            m_tunnelState.listeningLocalHttpProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT);
            m_tunnelState.clientRegion = data.getString(TunnelManager.DATA_TUNNEL_STATE_CLIENT_REGION);
            ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
            if (homePages != null) {
                m_tunnelState.homePages = homePages;
            }

            m_tunnelState.needsHelpConnecting = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_NEEDS_HELP_CONNECTING);
            if (m_tunnelState.needsHelpConnecting) {
                setConnectionHelpState(ConnectionHelpState.NEEDS_HELP);
            } else if (m_tunnelState.isConnected) {
                setConnectionHelpState(ConnectionHelpState.CAN_HELP);
            } else {
                setConnectionHelpState(ConnectionHelpState.DISABLED);
            }

            onTunnelStateReceived();
        }

        protected void onTunnelStateReceived() {
            // do nothing
        }

        private void getDataTransferStatsFromBundle(Bundle data) {
            if (data == null) {
                return;
            }

            data.setClassLoader(DataTransferStats.DataTransferStatsBase.Bucket.class.getClassLoader());
            DataTransferStats.getDataTransferStatsForUI().m_connectedTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_CONNECTED_TIME);
            DataTransferStats.getDataTransferStatsForUI().m_totalBytesSent = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_SENT);
            DataTransferStats.getDataTransferStatsForUI().m_totalBytesReceived = data.getLong(TunnelManager.DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED);
            DataTransferStats.getDataTransferStatsForUI().m_slowBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS);
            DataTransferStats.getDataTransferStatsForUI().m_slowBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME);
            DataTransferStats.getDataTransferStatsForUI().m_fastBuckets = data.getParcelableArrayList(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS);
            DataTransferStats.getDataTransferStatsForUI().m_fastBucketsLastStartTime = data.getLong(TunnelManager.DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME);
        }

        private final Messenger m_incomingMessenger = new Messenger(new IncomingMessageHandler());
        private Messenger m_outgoingMessenger = null;
        // queue of client messages that
        // will be sent to Service once client is connected
        private final List<Message> m_queue = new ArrayList<>();

        private class IncomingMessageHandler extends Handler {
            private final TunnelManager.ServiceToClientMessage[] scm = TunnelManager.ServiceToClientMessage.values();
            @Override
            public void handleMessage(Message msg) {
                Bundle data = msg.getData();
                switch (scm[msg.what]) {
                    case REGISTER_RESPONSE:
                        getTunnelStateFromBundle(data);
                        // An activity created while the service is already running will learn
                        // the sponsor home page at this point, so now load it.
                        restoreSponsorTab();
                        updateServiceStateUI();
                        break;

                    case KNOWN_SERVER_REGIONS:
                        m_regionAdapter.updateRegionsFromPreferences();
                        // Make sure we preserve the selection in case the dataset has changed
                        m_regionSelector.setSelectionByValue(m_tunnelConfig.egressRegion);
                        break;

                    case TUNNEL_STARTING:
                        m_tunnelState.isConnected = false;
                        updateServiceStateUI();
                        break;

                    case TUNNEL_STOPPING:
                        m_tunnelState.isConnected = false;
                        onTunnelDisconnected();

                        // Disable help when stopped
                        setConnectionHelpState(ConnectionHelpState.DISABLED);

                        // When the tunnel self-stops, we also need to unbind to ensure
                        // the service is destroyed
                        unbindTunnelService();
                        break;

                    case TUNNEL_CONNECTION_STATE:
                        getTunnelStateFromBundle(data);
                        if (!m_tunnelState.isConnected) {
                            onTunnelDisconnected();
                        }
                        updateServiceStateUI();
                        break;

                    case DATA_TRANSFER_STATS:
                        getDataTransferStatsFromBundle(data);
                        break;

                    case NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT:
                        handleNfcConnectionInfoExchangeResponseExport(data);
                        break;

                    case NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_IMPORT:
                        handleNfcConnectionInfoExchangeResponseImport(data);
                        break;

                    default:
                        super.handleMessage(msg);
                }
            }
        }

        private void handleNfcIntent(Intent intent) {
            if (!isServiceRunning()) {
                Toast.makeText(this, getString(R.string.nfc_connection_info_press_start), Toast.LENGTH_LONG).show();
                return;
            }

            if (isTunnelConnected()) {
                // Discard the NFC tag, we don't need to import anything.
                return;
            }

            String connectionInfoPayload = ConnectionInfoExchangeUtils.getConnectionInfoPayloadFromNfcIntent(intent);

            // If the payload is empty don't try to import just let the user know it failed
            if ("".equals(connectionInfoPayload)) {
                Toast.makeText(this, getString(R.string.nfc_connection_info_import_failure), Toast.LENGTH_LONG).show();
                return;
            }

            // Otherwise, send the received message to the TunnelManager to be imported
            importConnectionInfo(connectionInfoPayload);
        }

        private void importConnectionInfo(String connectionInfoPayload) {
            Bundle data = new Bundle();
            data.putString(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_IMPORT, connectionInfoPayload);
            sendServiceMessage(TunnelManager.ClientToServiceMessage.NFC_CONNECTION_INFO_EXCHANGE_IMPORT.ordinal(), data);
        }

        protected void handleNfcConnectionInfoExchangeResponseExport(Bundle data) {
            // Store the data to be sent on an NFC exchange so we don't have to wait when beaming
            mConnectionInfoPayload = data.getString(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_EXPORT);

            // If the latch exists, let it wake up
            if (mNfcConnectionInfoExportLatch != null) {
                mNfcConnectionInfoExportLatch.countDown();
            }
        }

        protected void handleNfcConnectionInfoExchangeResponseImport(Bundle data) {
            boolean success = data.getBoolean(TunnelManager.DATA_NFC_CONNECTION_INFO_EXCHANGE_RESPONSE_IMPORT, false);
            String message = success ? getString(R.string.nfc_connection_info_import_success) : getString(R.string.nfc_connection_info_import_failure);
            if (success) {
                // Dismiss the get help dialog if it is showing
                if (mConnectionHelpDialog != null && mConnectionHelpDialog.isShowing()) {
                    mConnectionHelpDialog.dismiss();
                }
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }

        private enum ConnectionHelpState {
            UNKNOWN, // This should only be used as the initial state of ConnectionHelp
            DISABLED,
            NEEDS_HELP,
            CAN_HELP,
        }

        private ConnectionHelpState mConnectionHelpState = ConnectionHelpState.UNKNOWN;

        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        private void setConnectionHelpState(ConnectionHelpState state) {
            // Make sure we aren't calling this before everything is set up
            if (mNfcAdapter == null || mConnectionHelpState == state) {
                return;
            }

            // Make sure the activity isn't destroyed (setNdefPushMessageCallback will throw IllegalStateException)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && this.isDestroyed()) {
                return;
            }

            mConnectionHelpState = state;

            switch (state) {
                case DISABLED:
                    hideGetHelpConnectingUI();
                    hideHelpConnectUI();
                    mNfcAdapter.setNdefPushMessageCallback(null, this);
                    break;
                case NEEDS_HELP:
                    showGetHelpConnectingUI();
                    hideHelpConnectUI();
                    mNfcAdapter.setNdefPushMessageCallback(null, this);
                    break;
                case CAN_HELP:
                    hideGetHelpConnectingUI();
                    showHelpConnectUI();
                    mNfcAdapter.setNdefPushMessageCallback(mNfcAdapterCallback, this);
                    break;
            }
        }

        private AlertDialog mConnectionHelpDialog;

        protected void showConnectionHelpDialog(Context context, int id) {
            LayoutInflater layoutInflater = LayoutInflater.from(context);
            // TODO: Determine what the root inflation should be.
            View dialogView = layoutInflater.inflate(id, null);
            mConnectionHelpDialog = new AlertDialog.Builder(context)
                    .setView(dialogView)
                    .setPositiveButton(R.string.label_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .create();
            mConnectionHelpDialog.show();
        }

        private void showGetHelpConnectingUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mGetHelpConnectingButton.setVisibility(View.VISIBLE);
        }

        private void hideGetHelpConnectingUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mGetHelpConnectingButton.setVisibility(View.GONE);
        }

        private void showHelpConnectUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mHelpConnectButton.setVisibility(View.VISIBLE);
        }

        private void hideHelpConnectUI() {
            // Ensure that they have NFC
            if (!ConnectionInfoExchangeUtils.isNfcSupported(getApplicationContext())) {
                return;
            }

            mHelpConnectButton.setVisibility(View.GONE);
        }

        private void sendServiceMessage(int what) {
            sendServiceMessage(what, null);
        }

        private void sendServiceMessage(int what, Bundle data) {
            try {
                Message msg = Message.obtain(null, what);
                if(data != null) {
                    msg.setData(data);
                }
                if (m_outgoingMessenger == null) {
                    synchronized (m_queue) {
                        m_queue.add(msg);
                    }
                } else {
                    m_outgoingMessenger.send(msg);
                }
            } catch (RemoteException e) {
                MyLog.g(String.format("sendServiceMessage failed: %s", e.getMessage()));
            }
        }

        private boolean m_boundToTunnelService = false;
        private final ServiceConnection m_tunnelServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                m_outgoingMessenger = new Messenger(service);
                /** Send all pending messages to the newly created Service. **/
                synchronized (m_queue) {
                    for (Message message : m_queue) {
                        try {
                            m_outgoingMessenger.send(message);
                        } catch (RemoteException e) {

                        }
                    }
                    m_queue.clear();
                }
                updateServiceStateUI();
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                m_outgoingMessenger = null;
                unbindTunnelService();
            }
        };

        private void stopTunnelService() {
            sendServiceMessage(TunnelManager.ClientToServiceMessage.STOP_SERVICE.ordinal());
            // STOP_SERVICE will cause the Service to stop itself,
            // which will then cause an unbind to occur. Don't call
            // unbindTunnelService() here, as its unnecessary and either
            // the UNREGISTER or unbindService causes
            // "Exception when unbinding service com.psiphon3/.psiphonlibrary.TunnelVpnService"
        }

        private void unbindTunnelService() {
            if (m_boundToTunnelService) {
                m_boundToTunnelService = false;
                sendServiceMessage(TunnelManager.ClientToServiceMessage.UNREGISTER.ordinal());
                try {
                    unbindService(m_tunnelServiceConnection);
                }
                catch (java.lang.IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
            updateServiceStateUI();
        }

        protected void onTunnelDisconnected() {
            // do nothing
        }

        /**
         * Determine if the Psiphon local service is currently running.
         * 
         * @see <a href="http://stackoverflow.com/a/5921190/729729">From
         *      StackOverflow answer:
         *      "android: check if a service is running"</a>
         * @return True if the service is already running, false otherwise.
         */
        protected boolean isServiceRunning() {
            return getRunningService() != null;
        }

        private String getRunningService() {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.uid == android.os.Process.myUid() &&
                        (TunnelService.class.getName().equals(service.service.getClassName())
                                || (Utils.hasVpnService() && isVpnService(service.service.getClassName())))) {
                    return service.service.getClassName();
                }
            }
            return null;
        }

        private boolean isVpnService(String className) {
            return TunnelVpnService.class.getName().equals(className);
        }

        private class SponsorHomePage {
            private class SponsorWebChromeClient extends WebChromeClient {
                private final ProgressBar mProgressBar;

                public SponsorWebChromeClient(ProgressBar progressBar) {
                    super();
                    mProgressBar = progressBar;
                }

                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                }

                @Override
                public void onProgressChanged(WebView webView, int progress) {
                    if (mStopped) {
                        return;
                    }

                    mProgressBar.setProgress(progress);
                    mProgressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
                }
            }

            private class SponsorWebViewClient extends WebViewClient {
                private Timer mTimer;
                private boolean mWebViewLoaded = false;
                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                    if (mStopped) {
                        return true;
                    }

                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }

                    if (!isTunnelConnected()) {
                        return true;
                    }

                    if (mWebViewLoaded) {
                        displayBrowser(getContext(), url);
                    }
                    return mWebViewLoaded;
                }

                @Override
                public void onPageFinished(WebView webView, String url) {
                    if (mStopped) {
                        return;
                    }

                    if (!mWebViewLoaded) {
                        mTimer = new Timer();
                        mTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                if (mStopped) {
                                    return;
                                }
                                mWebViewLoaded = true;
                            }
                        }, 2000);
                    }
                }
            }

            private final WebView mWebView;
            private final SponsorWebViewClient mWebViewClient;
            private final SponsorWebChromeClient mWebChromeClient;
            private final ProgressBar mProgressBar;

            @TargetApi(Build.VERSION_CODES.HONEYCOMB)
            public SponsorHomePage(WebView webView, ProgressBar progressBar) {
                mWebView = webView;
                mProgressBar = progressBar;
                mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
                mWebViewClient = new SponsorWebViewClient();

                mWebView.setWebChromeClient(mWebChromeClient);
                mWebView.setWebViewClient(mWebViewClient);
                
                WebSettings webSettings = mWebView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);
            }

            public void stop() {
                mWebViewClient.stop();
                mWebChromeClient.stop();
            }

            public void load(String url) {
                // Set WebView proxy only if we are not running in WD mode.
                if(!getTunnelConfigWholeDevice() || !Utils.hasVpnService()) {
                    WebViewProxySettings.setLocalProxy(mWebView.getContext(), getListeningLocalHttpProxyPort());
                } else {
                    // We are running in WDM, reset WebView proxy if it has been previously set.
                    if(WebViewProxySettings.isLocalProxySet()){
                        WebViewProxySettings.resetLocalProxy(mWebView.getContext());
                    }
                }

                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }

        protected void displayBrowser(Context context, String urlString) {

        }

        protected void restoreSponsorTab() {

        }
    }
}
