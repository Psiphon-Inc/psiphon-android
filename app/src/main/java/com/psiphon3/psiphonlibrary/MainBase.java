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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
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
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.psiphon3.psiphonlibrary.PsiphonData.DataTransferStats;
import com.psiphon3.psiphonlibrary.PsiphonData.StatusEntry;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.psiphonlibrary.MoreOptionsPreferenceActivity;

public abstract class MainBase {
    public static abstract class SupportFragmentActivity extends FragmentActivity implements MyLog.ILogger {
        public SupportFragmentActivity() {
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

    public static abstract class Activity extends android.app.Activity implements MyLog.ILogger {
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
        public static final String HANDSHAKE_SUCCESS = "com.psiphon3.PsiphonAndroidActivity.HANDSHAKE_SUCCESS";
        public static final String HANDSHAKE_SUCCESS_IS_RECONNECT = "com.psiphon3.PsiphonAndroidActivity.HANDSHAKE_SUCCESS_IS_RECONNECT";
        public static final String UNEXPECTED_DISCONNECT = "com.psiphon3.PsiphonAndroidActivity.UNEXPECTED_DISCONNECT";
        public static final String TUNNEL_STARTING = "com.psiphon3.PsiphonAndroidActivity.TUNNEL_STARTING";
        public static final String TUNNEL_STOPPING = "com.psiphon3.PsiphonAndroidActivity.TUNNEL_STOPPING";
        public static final String STATUS_ENTRY_AVAILABLE = "com.psiphon3.PsiphonAndroidActivity.STATUS_ENTRY_AVAILABLE";
        public static final String EGRESS_REGION_PREFERENCE = "egressRegionPreference";
        public static final String TUNNEL_WHOLE_DEVICE_PREFERENCE = "tunnelWholeDevicePreference";
        public static final String WDM_FORCE_IPTABLES_PREFERENCE = "wdmForceIptablesPreference";
        public static final String SHARE_PROXIES_PREFERENCE = "shareProxiesPreference";

        protected static final int REQUEST_CODE_PREPARE_VPN = 100;
        protected static final int REQUEST_CODE_PREFERENCE = 101;

        protected static boolean m_firstRun = true;
        private boolean m_canWholeDevice = false;

        protected IEvents m_eventsInterface = null;
        protected Button m_toggleButton;
        private StatusListViewManager m_statusListManager = null;
        private SharedPreferences m_preferences;
        private ViewFlipper m_sponsorViewFlipper;
        private LinearLayout m_statusLayout;
        private TextView m_statusTabLogLine;
        private TextView m_statusTabVersionLine;
        private SponsorHomePage m_sponsorHomePage;
        private LocalBroadcastManager m_localBroadcastManager;
        private Timer m_updateHeaderTimer;
        private Timer m_updateServiceStateTimer;
        private boolean m_restartTunnel = false;
        private TextView m_elapsedConnectionTimeView;
        private TextView m_totalSentView;
        private TextView m_totalReceivedView;
        private DataTransferGraph m_slowSentGraph;
        private DataTransferGraph m_slowReceivedGraph;
        private DataTransferGraph m_fastSentGraph;
        private DataTransferGraph m_fastReceivedGraph;
        private RegionAdapter m_regionAdapter;
        private SpinnerHelper m_regionSelector;
        protected CheckBox m_tunnelWholeDeviceToggle;
        private Toast m_invalidProxySettingsToast;
        private Button m_moreOptionsButton;

        /*
         * private CheckBox m_shareProxiesToggle; private TextView
         * m_statusTabSocksPortLine; private TextView
         * m_statusTabHttpProxyPortLine;
         */

        public TabbedActivityBase() {
            Utils.initializeSecureRandom();
        }

        // Avoid calling m_statusTabToggleButton.setImageResource() every 250 ms
        // when it is set to the connected image
        private ImageButton m_statusViewImage;
        private boolean m_statusIconSetToConnected = false;

        private void setStatusState(int resId) {
            boolean statusShowing = m_sponsorViewFlipper.getCurrentView() == m_statusLayout;

            if (R.drawable.status_icon_connected == resId) {
                if (!m_statusIconSetToConnected) {
                    m_statusViewImage.setImageResource(resId);
                    m_statusIconSetToConnected = true;
                }

                // Show the sponsor web view, but only if there's a home page to
                // show and it's isn't excluded from being embedded.
                boolean showHomePage = false;
                ArrayList<String> homepages = PsiphonData.getPsiphonData().getHomePages();
                if (homepages.size() > 0) {
                    showHomePage = !Arrays.asList(EmbeddedValues.HOME_TAB_URL_EXCLUSIONS).contains(homepages.get(0));
                }

                if (showHomePage && statusShowing) {
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
                    int newTab = 0;
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

            SharedPreferences.Editor preferencesEditor = m_preferences.edit();
            preferencesEditor.putInt("currentTab", m_currentTab);
            preferencesEditor.commit();
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

        private void updateProxySettingsFromPreferences() {
            boolean useSystemProxySettingsPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    getString(R.string.useSystemProxySettingsPreference), false);

            // Backwards compatibility: if useSystemProxySettingsPreference is
            // set and (the new) useProxySettingsPreference is not,
            // then set it
            if (useSystemProxySettingsPreference
                    && !PreferenceManager.getDefaultSharedPreferences(this).contains(getString(R.string.useProxySettingsPreference))) {
                Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
                editor.putBoolean(getString(R.string.useProxySettingsPreference), true);
                editor.commit();
            }

            boolean useProxySettingsPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.useProxySettingsPreference),
                    false);

            boolean useCustomProxySettingsPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    getString(R.string.useCustomProxySettingsPreference), false);

            String customProxyHostPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(
                    getString(R.string.useCustomProxySettingsHostPreference), "");

            String customProxyPortPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(
                    getString(R.string.useCustomProxySettingsPortPreference), "");

            boolean useProxyAuthenticationPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    getString(R.string.useProxyAuthenticationPreference), false);

            String proxyUsernamePreference = PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyUsernamePreference), "");

            String proxyPasswordPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyPasswordPreference), "");

            String proxyDomainPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyDomainPreference), "");

            PsiphonData.getPsiphonData().setUseSystemProxySettings(useSystemProxySettingsPreference);
            PsiphonData.getPsiphonData().setUseHTTPProxy(useProxySettingsPreference);
            PsiphonData.getPsiphonData().setUseCustomProxySettings(useCustomProxySettingsPreference);
            PsiphonData.getPsiphonData().setCustomProxyHost(customProxyHostPreference);
            PsiphonData.getPsiphonData().setCustomProxyPort(customProxyPortPreference);
            PsiphonData.getPsiphonData().setUseProxyAuthentication(useProxyAuthenticationPreference);
            PsiphonData.getPsiphonData().setProxyUsername(proxyUsernamePreference);
            PsiphonData.getPsiphonData().setProxyPassword(proxyPasswordPreference);
            PsiphonData.getPsiphonData().setProxyDomain(proxyDomainPreference);
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            m_preferences = PreferenceManager.getDefaultSharedPreferences(this);

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

                    if (m_gestureDetector.onTouchEvent(event)) {
                        return false;
                    } else {
                        return true;
                    }
                }
            };

            m_tabHost.setOnTouchListener(onTouchListener);
            m_statusLayout = (LinearLayout) findViewById(R.id.statusLayout);
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

            int currentTab = m_preferences.getInt("currentTab", 0);
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
            m_moreOptionsButton = (Button) findViewById(R.id.moreOptionsButton);

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            PsiphonData.getPsiphonData().setCurrentEventsInterface(m_eventsInterface);

            // Listen for new messages
            // Using local broad cast
            // (http://developer.android.com/reference/android/support/v4/content/LocalBroadcastManager.html)

            m_localBroadcastManager = LocalBroadcastManager.getInstance(TabbedActivityBase.this);

            m_localBroadcastManager.registerReceiver(new TunnelStartingReceiver(), new IntentFilter(TUNNEL_STARTING));

            m_localBroadcastManager.registerReceiver(new TunnelStoppingReceiver(), new IntentFilter(TUNNEL_STOPPING));

            m_localBroadcastManager.registerReceiver(new UnexpectedDisconnect(), new IntentFilter(UNEXPECTED_DISCONNECT));

            m_localBroadcastManager.registerReceiver(new StatusEntryAvailable(), new IntentFilter(STATUS_ENTRY_AVAILABLE));

            updateServiceStateUI();

            PsiphonData.getPsiphonData().setDisplayDataTransferStats(true);
            
            if (m_firstRun)
            {
                RegionAdapter.initialize(this);
            }
            m_regionAdapter = new RegionAdapter(this);
            m_regionSelector.setAdapter(m_regionAdapter);
            String egressRegionPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(EGRESS_REGION_PREFERENCE,
                    PsiphonConstants.REGION_CODE_ANY);
            PsiphonData.getPsiphonData().setEgressRegion(egressRegionPreference);
            int position = m_regionAdapter.getPositionForRegionCode(egressRegionPreference);
            m_regionSelector.setSelection(position);

            m_regionSelector.setOnItemSelectedListener(regionSpinnerOnItemSelected);
            // Re-populate the spinner when it is expanded -- the underlying
            // region list could change
            // due to background server discovery or remote server list fetch.
            m_regionSelector.getSpinner().setOnTouchListener(regionSpinnerOnTouch);
            m_regionSelector.getSpinner().setOnKeyListener(regionSpinnerOnKey);

            m_canWholeDevice = Utils.hasVpnService();

            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            boolean tunnelWholeDevicePreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE,
                    m_canWholeDevice);
            m_tunnelWholeDeviceToggle.setChecked(tunnelWholeDevicePreference);

            PsiphonData.getPsiphonData().setTunnelWholeDevice(m_canWholeDevice && tunnelWholeDevicePreference);

            updateProxySettingsFromPreferences();

            // Note that this must come after the above lines, or else the
            // activity
            // will not be sufficiently initialized for isDebugMode to succeed.
            // (Voodoo.)
            PsiphonConstants.DEBUG = Utils.isDebugMode(this);

            String msg = getContext().getString(R.string.client_version, EmbeddedValues.CLIENT_VERSION);
            m_statusTabVersionLine.setText(msg);

            // Restore messages previously posted by the service.
            MyLog.restoreLogHistory();
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            if (m_sponsorHomePage != null) {
                m_sponsorHomePage.stop();
                m_sponsorHomePage = null;
            }
        }

        /**
         * Show the sponsor home page, either in the embedded view web view or
         * in the external browser.
         * 
         * @param freshConnect
         *            If false, the home page will not be opened in an external
         *            browser. This is to prevent the page from opening every
         *            time the activity is created.
         */
        protected void resetSponsorHomePage(boolean freshConnect) {
            String url = null;
            ArrayList<String> homepages = PsiphonData.getPsiphonData().getHomePages();
            if (homepages.size() > 0) {
                url = homepages.get(0);
            } else {
                return;
            }

            // Some URLs are excluded from being embedded as home pages.
            if (Arrays.asList(EmbeddedValues.HOME_TAB_URL_EXCLUSIONS).contains(url))
            {
                if (freshConnect)
                {
                    m_eventsInterface.displayBrowser(getContext(), Uri.parse(url));
                }
                return;
            }

            // At this point we're showing the URL in the embedded webview.
            m_sponsorHomePage = new SponsorHomePage((WebView) findViewById(R.id.sponsorWebView), (ProgressBar) findViewById(R.id.sponsorWebViewProgressBar),
                    m_eventsInterface);
            m_sponsorHomePage.load(url);
        }

        @Override
        protected void onResume() {
            super.onResume();
            updateProxySettingsFromPreferences();
            
            // From: http://steve.odyfamily.com/?p=12
            m_updateHeaderTimer = new Timer();
            m_updateHeaderTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    TabbedActivityBase.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateHeaderCallback();
                        }
                    });
                }
            }, 0, 1000);

            m_updateServiceStateTimer = new Timer();
            m_updateServiceStateTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    TabbedActivityBase.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updateServiceStateUI();
                            checkRestartTunnel();
                        }
                    });
                }
            }, 0, 250);

            PsiphonData.getPsiphonData().setStatusActivityForeground(true);
            
            // Don't show the keyboard until edit selected
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }

        @Override
        protected void onPause() {
            super.onPause();

            cancelInvalidProxySettingsToast();

            m_updateHeaderTimer.cancel();
            m_updateServiceStateTimer.cancel();

            PsiphonData.getPsiphonData().setStatusActivityForeground(false);
        }

        public class TunnelStartingReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {                
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateServiceStateUI();
                    }
                });
            }
        }

        public class TunnelStoppingReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateServiceStateUI();
                    }
                });
            }
        }

        public class UnexpectedDisconnect extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateServiceStateUI();
                    }
                });
            }
        }

        public class StatusEntryAvailable extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        StatusEntry statusEntry = PsiphonData.getPsiphonData().getLastStatusEntryForDisplay();
                        if (statusEntry != null) {
                            String msg = getContext().getString(statusEntry.id(), statusEntry.formatArgs());
                            m_statusTabLogLine.setText(msg);
                        }
                    }
                });
            }
        }

        protected void doToggle() {
            if (!isServiceRunning()) {
                startUp();
            } else {
                stopTunnelService();
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
                onRegionSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView parent) {
            }
        };

        private final View.OnTouchListener regionSpinnerOnTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    m_regionAdapter.populate();
                }
                return false;
            }
        };

        private final View.OnKeyListener regionSpinnerOnKey = new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    m_regionAdapter.populate();
                    return true;
                } else {
                    return false;
                }
            }
        };

        public void onRegionSelected(int position) {
            // Just in case an OnItemSelected message is in transit before
            // setEnabled is processed...(?)
            if (!m_regionSelector.isEnabled()) {
                return;
            }

            String selectedRegionCode = m_regionAdapter.getSelectedRegionCode(position);

            String egressRegionPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(EGRESS_REGION_PREFERENCE,
                    PsiphonConstants.REGION_CODE_ANY);
            if (selectedRegionCode.equals(egressRegionPreference) && selectedRegionCode.equals(PsiphonData.getPsiphonData().getEgressRegion())) {
                return;
            }

            updateEgressRegionPreference(selectedRegionCode);

            // NOTE: reconnects even when Any is selected: we could select a
            // faster server
            if (isServiceRunning()) {
                m_restartTunnel = true; 
                stopTunnelService();
                // The tunnel will get restarted in m_updateServiceStateTimer
            }
        }

        protected void updateEgressRegionPreference(String egressRegionPreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putString(EGRESS_REGION_PREFERENCE, egressRegionPreference);
            editor.commit();

            PsiphonData.getPsiphonData().setEgressRegion(egressRegionPreference);
        }

        public void onTunnelWholeDeviceToggle(View v) {
            // Just in case an OnClick message is in transit before setEnabled
            // is processed...(?)
            if (!m_tunnelWholeDeviceToggle.isEnabled()) {
                return;
            }

            boolean tunnelWholeDevicePreference = m_tunnelWholeDeviceToggle.isChecked();
            updateWholeDevicePreference(tunnelWholeDevicePreference);

            if (isServiceRunning()) {
                m_restartTunnel = true; 
                stopTunnelService();
                // The tunnel will get restarted in m_updateServiceStateTimer
            }
        }

        protected void updateWholeDevicePreference(boolean tunnelWholeDevicePreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE, tunnelWholeDevicePreference);
            editor.commit();

            PsiphonData.getPsiphonData().setTunnelWholeDevice(tunnelWholeDevicePreference);
        }

        // Basic check that the values are populated
        private boolean customProxySettingsValuesValid() {
            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(this);
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

        private void updateHeaderCallback() {
            DataTransferStats dataTransferStats = PsiphonData.getPsiphonData().getDataTransferStats();
            m_elapsedConnectionTimeView.setText(dataTransferStats.isConnected() ? getString(R.string.connected_elapsed_time,
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
            TunnelManager tunnelManager = PsiphonData.getPsiphonData().getCurrentTunnelManager();
            
            if (tunnelManager == null) {
                setStatusState(R.drawable.status_icon_disconnected);
                m_toggleButton.setText(getText(R.string.start));
                enableToggleServiceUI();
                
            } else if (tunnelManager.signalledStop()) {
                setStatusState(R.drawable.status_icon_disconnected);
                m_toggleButton.setText(getText(R.string.waiting));
                disableToggleServiceUI();
                
            } else {
                if (PsiphonData.getPsiphonData().getDataTransferStats().isConnected()) {
                    setStatusState(R.drawable.status_icon_connected);
                } else {
                    setStatusState(R.drawable.status_icon_connecting);
                }
                m_toggleButton.setText(getText(R.string.stop));
                enableToggleServiceUI();
                
            }
        }
        
        protected void enableToggleServiceUI() {
            m_toggleButton.setEnabled(true);
            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            m_regionSelector.setEnabled(true);
            m_moreOptionsButton.setEnabled(true);
        }

        protected void disableToggleServiceUI() {
            m_toggleButton.setEnabled(false);
            m_tunnelWholeDeviceToggle.setEnabled(false);
            m_regionSelector.setEnabled(false);
            m_moreOptionsButton.setEnabled(false);
        }

        private void checkRestartTunnel() {
            if (m_restartTunnel &&
                    !isServiceRunning()) {
                m_restartTunnel = false;
                startTunnel(this);
            }
        }
        
        protected void startTunnel(Context context) {
            // Don't start if custom proxy settings is selected and values are
            // invalid
            boolean useHTTPProxyPreference = PsiphonData.getPsiphonData().getUseHTTPProxy();
            boolean useCustomProxySettingsPreference = PsiphonData.getPsiphonData().getUseCustomProxySettings();

            if (useHTTPProxyPreference && useCustomProxySettingsPreference && !customProxySettingsValuesValid()) {
                cancelInvalidProxySettingsToast();
                m_invalidProxySettingsToast = Toast.makeText(context, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                m_invalidProxySettingsToast.show();
                return;
            }

            boolean waitingForPrompt = false;

            if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService()) {
                // VpnService backwards compatibility: for lazy class loading
                // the VpnService
                // class reference has to be in another function (doVpnPrepare),
                // not just
                // in a conditional branch.
                waitingForPrompt = doVpnPrepare();
            }
            if (!waitingForPrompt) {
                startTunnelService();
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

                // bindAndStartTunnelService will be called in onActivityResult
                return true;
            }

            return false;
        }

        private boolean isProxySettingsRestartRequired() {
            // check if "use proxy" has changed
            boolean useHTTPProxyPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.useProxySettingsPreference),
                    false);
            if (useHTTPProxyPreference != PsiphonData.getPsiphonData().getUseHTTPProxy()) {
                return true;
            }

            // no further checking if "use proxy" is off and has not
            // changed
            if (!useHTTPProxyPreference) {
                return false;
            }

            // check if "use custom proxy settings"
            // radio has changed
            boolean useCustomProxySettingsPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    getString(R.string.useCustomProxySettingsPreference), false);
            if (useCustomProxySettingsPreference != PsiphonData.getPsiphonData().getUseCustomProxySettings()) {
                return true;
            }

            // no further checking if "use custom proxy" is off and has
            // not changed
            if (!useCustomProxySettingsPreference) {
                return false;
            }

            // "use custom proxy" is selected, check if
            // host || port have changed
            if (!PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useCustomProxySettingsHostPreference), "")
                    .equals(PsiphonData.getPsiphonData().getCustomProxyHost())
                    || !PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useCustomProxySettingsPortPreference), "")
                            .equals(PsiphonData.getPsiphonData().getCustomProxyPort())) {
                return true;
            }

            // check if "use proxy authentication" has changed
            boolean useProxyAuthenticationPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                    getString(R.string.useProxyAuthenticationPreference), false);
            if (useProxyAuthenticationPreference != PsiphonData.getPsiphonData().getUseProxyAuthentication()) {
                return true;
            }

            // no further checking if "use proxy authentication" is off
            // and has not changed
            if (!useProxyAuthenticationPreference) {
                return false;
            }

            // "use proxy authentication" is checked, check if
            // username || password || domain have changed
            if (!PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyUsernamePreference), "")
                    .equals(PsiphonData.getPsiphonData().getProxyUsername())
                    || !PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyPasswordPreference), "")
                            .equals(PsiphonData.getPsiphonData().getProxyPassword())
                    || !PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.useProxyDomainPreference), "")
                            .equals(PsiphonData.getPsiphonData().getProxyDomain())) {
                return true;
            }

            return false;
        }

        @Override
        protected void onActivityResult(int request, int result, Intent data) {
            if (request == REQUEST_CODE_PREPARE_VPN && result == RESULT_OK) {
                startTunnelService();
            } else if (request == REQUEST_CODE_PREFERENCE) {
                // Don't call updateProxySettingsFromPreferences() first, because
                // isProxySettingsRestartRequired() looks at the stored preferences.
                // But, it should be called before stopping the tunnel, since the tunnel
                // gets asyncronously restarted, and we want it to be restarted with
                // the new settings.
                if (isProxySettingsRestartRequired() && isServiceRunning()) {
                    updateProxySettingsFromPreferences();
                    m_restartTunnel = true;
                    stopTunnelService();
                    // The tunnel will get restarted in m_updateServiceStateTimer
                } else {
                    updateProxySettingsFromPreferences();
                }
            }
        }

        protected void startTunnelService() {

            if (isServiceRunning()) {
                MyLog.g("startTunnelService(): already running service");
                return;
            }

            // Disable service-toggling controls while service is starting up
            // (i.e., while isServiceRunning can't be relied upon)
            disableToggleServiceUI();

            if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService()) {
                doStartTunnelVpnService(this);
            } else {
                if (null == startService(new Intent(this, TunnelService.class))) {
                    PsiphonData.getPsiphonData().setStartingTunnelManager();
                }
            }
        }

        private void doStartTunnelVpnService(Context context) {
            if (null == startService(new Intent(context, TunnelVpnService.class))) {
                PsiphonData.getPsiphonData().setStartingTunnelManager();
            }
        }

        private void stopTunnelService() {

            // Use signalStopService to asynchronously stop the service.
            // 1. VpnService doesn't respond to stopService calls
            // 2. The UI will not block while waiting for stopService to return
            // This scheme assumes that the UI will monitor that the service is
            // running while the Activity is not bound to it. This is the state
            // while the tunnel is shutting down.
            TunnelManager currentTunnelManager = PsiphonData.getPsiphonData().getCurrentTunnelManager();
            if (currentTunnelManager != null) {
                currentTunnelManager.signalStopService();
            }
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

            // getStartingTunnelManager is set to true by the UI thread as soon
            // as it knows startService will start the service. It remains set
            // until the service starts (asynchronously, which is why we have
            // the starting flag) after which point the service onCreate will
            // populate setCurrentTunnelManager.
            if (PsiphonData.getPsiphonData().getStartingTunnelManager() ||
                    PsiphonData.getPsiphonData().getCurrentTunnelManager() != null) {
                return true;
            }

            // TODO: the getRunningServices logic is likely no longer necessary.

            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (service.pid == android.os.Process.myPid() &&
                        (TunnelService.class.getName().equals(service.service.getClassName())
                        || (Utils.hasVpnService() && isVpnService(service.service.getClassName())))) {
                    return true;
                }
            }
            return false;
        }

        private boolean isVpnService(String className) {
            return TunnelVpnService.class.getName().equals(className);
        }

        /*
         * MyLog.ILogger implementation
         */

        /**
         * @see com.psiphon3.psiphonlibrary.Utils.MyLog.ILogger#statusEntryAdded()
         */
        @Override
        public void statusEntryAdded() {
            if (m_statusListManager != null) {
                m_statusListManager.notifyStatusAdded();
            }

            if (m_localBroadcastManager != null) {
                m_localBroadcastManager.sendBroadcast(new Intent(STATUS_ENTRY_AVAILABLE));
            }
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
                private final IEvents mEventsInterface;
                private Timer mTimer;
                private boolean mWebViewLoaded = false;

                public SponsorWebViewClient(IEvents eventsInterface) {
                    super();
                    mEventsInterface = eventsInterface;
                }

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

                    if (!PsiphonData.getPsiphonData().getDataTransferStats().isConnected()) {
                        return true;
                    }

                    if (mWebViewLoaded) {
                        mEventsInterface.displayBrowser(getContext(), Uri.parse(url));
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

            @TargetApi(Build.VERSION_CODES.HONEYCOMB) public SponsorHomePage(WebView webView, ProgressBar progressBar, IEvents eventsInterface) {
                mWebView = webView;
                mProgressBar = progressBar;
                mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
                mWebViewClient = new SponsorWebViewClient(eventsInterface);

                mWebView.setWebChromeClient(mWebChromeClient);
                mWebView.setWebViewClient(mWebViewClient);
                
                
                //UI glitch fix attempt
                //possibly similar to the following
                //https://stackoverflow.com/questions/27172217/android-systemui-glitches
                //https://stackoverflow.com/questions/27224394/android-lollipop-activity-screen-corrupted
                //https://stackoverflow.com/questions/27139494/android-5-screen-glitch-static-with-google-maps-fragment-inside-a-viewpager
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
                    mWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                }                
                

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
                WebViewProxySettings.setLocalProxy(mWebView.getContext(), PsiphonData.getPsiphonData().getListeningLocalHttpProxyPort());
                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }
    }
}
