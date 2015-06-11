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

package com.psiphon3.psiphonlibrary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Pair;
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
        private boolean m_isRooted = false;
        private boolean m_canWholeDevice = false;

        protected IEvents m_eventsInterface = null;
        protected Button m_toggleButton;
        protected List<Pair<String, String>> m_extraAuthParams = new ArrayList<Pair<String, String>>();
        private StatusListViewManager m_statusListManager = null;
        private SharedPreferences m_preferences;
        private ViewFlipper m_sponsorViewFlipper;
        private LinearLayout m_statusLayout;
        private TextView m_statusTabLogLine;
        private TextView m_statusTabVersionLine;
        private SponsorHomePage m_sponsorHomePage;
        private LocalBroadcastManager m_localBroadcastManager;
        private Timer m_updateHeaderTimer;
        private Timer m_updateStatusTimer;
        private TextView m_elapsedConnectionTimeView;
        private TextView m_totalSentView;
        private TextView m_totalReceivedView;
        private TextView m_compressionRatioSentView;
        private TextView m_compressionRatioReceivedView;
        private TextView m_compressionSavingsSentView;
        private TextView m_compressionSavingsReceivedView;
        private DataTransferGraph m_slowSentGraph;
        private DataTransferGraph m_slowReceivedGraph;
        private DataTransferGraph m_fastSentGraph;
        private DataTransferGraph m_fastReceivedGraph;
        private RegionAdapter m_regionAdapter;
        private SpinnerHelper m_regionSelector;
        protected CheckBox m_tunnelWholeDeviceToggle;
        private CheckBox m_wdmForceIptablesToggle;
        private Toast m_invalidProxySettingsToast;

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

        private boolean m_boundToTunnelService = false;
        private final ServiceConnection m_tunnelServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                onPostStartService();
                TunnelService.LocalBinder binder = (TunnelService.LocalBinder) service;
                TunnelService tunnelService = binder.getService();
                m_boundToTunnelService = true;
                tunnelService.setEventsInterface(m_eventsInterface);
                tunnelService.setExtraAuthParams(m_extraAuthParams);
                startService(new Intent(TabbedActivityBase.this, TunnelService.class));
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                m_boundToTunnelService = false;
            }
        };

        private boolean m_boundToTunnelVpnService = false;
        private final ServiceConnection m_tunnelVpnServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                onPostStartService();

                // VpnService backwards compatibility: this has sufficient lazy
                // class loading
                // as onServiceConnected is only called on bind.
                TunnelVpnService.LocalBinder binder = (TunnelVpnService.LocalBinder) service;
                TunnelVpnService tunnelVpnService = binder.getService();
                m_boundToTunnelVpnService = true;
                tunnelVpnService.setEventsInterface(m_eventsInterface);
                tunnelVpnService.setExtraAuthParams(m_extraAuthParams);
                startService(new Intent(TabbedActivityBase.this, TunnelVpnService.class));
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                m_boundToTunnelVpnService = false;
            }
        };

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
            m_compressionRatioSentView = (TextView) findViewById(R.id.compressionRatioSent);
            m_compressionRatioReceivedView = (TextView) findViewById(R.id.compressionRatioReceived);
            m_compressionSavingsSentView = (TextView) findViewById(R.id.compressionSavingsSent);
            m_compressionSavingsReceivedView = (TextView) findViewById(R.id.compressionSavingsReceived);
            m_regionSelector = new SpinnerHelper(findViewById(R.id.regionSelector));
            m_tunnelWholeDeviceToggle = (CheckBox) findViewById(R.id.tunnelWholeDeviceToggle);
            m_wdmForceIptablesToggle = (CheckBox) findViewById(R.id.WdmForceIptablesToggle);
            /*
             * m_shareProxiesToggle =
             * (CheckBox)findViewById(R.id.shareProxiesToggle);
             * m_statusTabSocksPortLine =
             * (TextView)findViewById(R.id.socksportline);
             * m_statusTabHttpProxyPortLine =
             * (TextView)findViewById(R.id.httpproxyportline);
             */

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            // Listen for new messages
            // Using local broad cast
            // (http://developer.android.com/reference/android/support/v4/content/LocalBroadcastManager.html)

            m_localBroadcastManager = LocalBroadcastManager.getInstance(TabbedActivityBase.this);

            m_localBroadcastManager.registerReceiver(new TunnelStartingReceiver(), new IntentFilter(TUNNEL_STARTING));

            m_localBroadcastManager.registerReceiver(new TunnelStoppingReceiver(), new IntentFilter(TUNNEL_STOPPING));

            m_localBroadcastManager.registerReceiver(new UnexpectedDisconnect(), new IntentFilter(UNEXPECTED_DISCONNECT));

            m_localBroadcastManager.registerReceiver(new StatusEntryAvailable(), new IntentFilter(STATUS_ENTRY_AVAILABLE));

            initToggleResources();

            PsiphonData.getPsiphonData().setDisplayDataTransferStats(true);

            if (m_firstRun) {
                RegionAdapter.initialize(this);
            }

            m_regionAdapter = new RegionAdapter(this);
            m_regionSelector.setAdapter(m_regionAdapter);
            String egressRegionPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(EGRESS_REGION_PREFERENCE,
                    ServerInterface.ServerEntry.REGION_CODE_ANY);
            PsiphonData.getPsiphonData().setEgressRegion(egressRegionPreference);
            int position = m_regionAdapter.getPositionForRegionCode(egressRegionPreference);
            m_regionSelector.setSelection(position);

            m_regionSelector.setOnItemSelectedListener(regionSpinnerOnItemSelected);
            // Re-populate the spinner when it is expanded -- the underlying
            // region list could change
            // due to background server discovery or remote server list fetch.
            m_regionSelector.getSpinner().setOnTouchListener(regionSpinnerOnTouch);
            m_regionSelector.getSpinner().setOnKeyListener(regionSpinnerOnKey);

            // Transparent proxy-based "Tunnel Whole Device" option is only
            // available on rooted devices and
            // defaults to true on rooted devices.
            // On Android 4+, we offer "Whole Device" via the VpnService
            // facility, which does not require root.
            // We prefer VpnService when available, even when the device is
            // rooted.
            m_isRooted = Utils.isRooted();
            boolean canRunVpnService = Utils.hasVpnService() && !PsiphonData.getPsiphonData().getVpnServiceUnavailable();
            m_canWholeDevice = m_isRooted || canRunVpnService;

            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            boolean tunnelWholeDevicePreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE,
                    m_canWholeDevice);
            m_tunnelWholeDeviceToggle.setChecked(tunnelWholeDevicePreference);

            // Use PsiphonData to communicate the setting to the TunnelService
            // so it doesn't need to
            // repeat the isRooted check. The preference is retained even if the
            // device becomes "unrooted"
            // and that's why setTunnelWholeDevice !=
            // tunnelWholeDevicePreference.
            PsiphonData.getPsiphonData().setTunnelWholeDevice(m_canWholeDevice && tunnelWholeDevicePreference);

            m_wdmForceIptablesToggle.setEnabled(m_isRooted && PsiphonData.getPsiphonData().getTunnelWholeDevice());
            boolean wdmForceIptablesPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(WDM_FORCE_IPTABLES_PREFERENCE, false);
            m_wdmForceIptablesToggle.setChecked(wdmForceIptablesPreference);

            // The preference is retained even if the device becomes "unrooted"
            // and that's why setWdmForceIptables != wdmForceIptablesPreference.
            PsiphonData.getPsiphonData().setWdmForceIptables(m_isRooted && wdmForceIptablesPreference);

            updateProxySettingsFromPreferences();

            boolean shareProxiesPreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SHARE_PROXIES_PREFERENCE, false);
            PsiphonData.getPsiphonData().setShareProxies(shareProxiesPreference);
            /* m_shareProxiesToggle.setChecked(shareProxiesPreference); */

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
                    updateHeaderCallback();
                }
            }, 0, 1000);

            m_updateStatusTimer = new Timer();
            m_updateStatusTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    updateStatusCallback();
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
            m_updateStatusTimer.cancel();

            unbindTunnelService();

            PsiphonData.getPsiphonData().setStatusActivityForeground(false);
        }

        public class TunnelStartingReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                m_toggleButton.setText(getText(R.string.stop));
                setStatusState(R.drawable.status_icon_connecting);
            }
        }

        public class TunnelStoppingReceiver extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                // When the tunnel self-stops, we also need to unbind to ensure
                // the service is destroyed
                unbindTunnelService();
                m_toggleButton.setText(getText(R.string.start));
                setStatusState(R.drawable.status_icon_disconnected);
            }
        }

        public class UnexpectedDisconnect extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                setStatusState(R.drawable.status_icon_connecting);
            }
        }

        public class StatusEntryAvailable extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                StatusEntry statusEntry = PsiphonData.getPsiphonData().getLastStatusEntryForDisplay();

                if (statusEntry != null) {
                    String msg = getContext().getString(statusEntry.id(), statusEntry.formatArgs());
                    m_statusTabLogLine.setText(msg);
                }
            }
        }

        private void initToggleResources() {
            // Only use this in onCreate. For updating the text when the
            // activity
            // is showing and the service is stopping, it's more reliable to
            // use TunnelStoppingReceiver.
            m_toggleButton.setText(isServiceRunning() ? getText(R.string.stop) : getText(R.string.start));
            setStatusState(isServiceRunning() ? R.drawable.status_icon_connecting : R.drawable.status_icon_disconnected);
        }

        protected void doToggle() {
            // TODO: use TunnelStartingReceiver/TunnelStoppingReceiver to track
            // state?
            if (!isServiceRunning()) {
                startUp();
            } else {
                stopTunnel(this);
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
                    ServerInterface.ServerEntry.REGION_CODE_ANY);
            if (selectedRegionCode.equals(egressRegionPreference) && selectedRegionCode.equals(PsiphonData.getPsiphonData().getEgressRegion())) {
                return;
            }

            boolean restart = false;

            // NOTE: reconnects even when Any is selected: we could select a
            // faster server
            if (isServiceRunning()) {
                doToggle();
                restart = true;
            }

            updateEgressRegionPreference(selectedRegionCode);

            if (restart && !isServiceRunning()) {
                startTunnel(this);
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

            boolean restart = false;

            if (isServiceRunning()) {
                doToggle();
                restart = true;
            }

            boolean tunnelWholeDevicePreference = m_tunnelWholeDeviceToggle.isChecked();
            updateWholeDevicePreference(tunnelWholeDevicePreference);

            if (restart && !isServiceRunning()) {
                startTunnel(this);
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

            m_wdmForceIptablesToggle.setEnabled(m_isRooted && PsiphonData.getPsiphonData().getTunnelWholeDevice());
        }

        public void onWdmForceIptablesToggle(View v) {
            // Just in case an OnClick message is in transit before setEnabled
            // is processed...(?)
            if (!m_wdmForceIptablesToggle.isEnabled()) {
                return;
            }

            boolean restart = false;

            if (isServiceRunning()) {
                doToggle();
                restart = true;
            }

            boolean wdmForceIptablesPreference = m_wdmForceIptablesToggle.isChecked();
            updateWdmForceIptablesPreference(wdmForceIptablesPreference);

            if (restart && !isServiceRunning()) {
                startTunnel(this);
            }
        }

        protected void updateWdmForceIptablesPreference(boolean wdmForceIptablesPreference) {
            // No isRooted check: the user can specify whatever preference they
            // wish. Also, CheckBox enabling should cover this (but isn't
            // required to).
            Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
            editor.putBoolean(WDM_FORCE_IPTABLES_PREFERENCE, wdmForceIptablesPreference);
            editor.commit();

            PsiphonData.getPsiphonData().setWdmForceIptables(wdmForceIptablesPreference);
        }

        // Basic check that the values are populated
        private boolean customProxySettingsValuesValid() {
            PsiphonData.ProxySettings proxySettings = PsiphonData.getPsiphonData().getProxySettings(this);
            return proxySettings != null && proxySettings.proxyHost.length() > 0 && proxySettings.proxyPort >= 1 && proxySettings.proxyPort <= 65535;
        }

        /*
         * public void onShareProxiesToggle(View v) { new
         * AlertDialog.Builder(this) .setOnKeyListener( new
         * DialogInterface.OnKeyListener() { public boolean
         * onKey(DialogInterface dialog, int keyCode, KeyEvent event) { // Don't
         * dismiss when hardware search button is clicked (Android 2.3 and
         * earlier) return keyCode == KeyEvent.KEYCODE_SEARCH; }})
         * .setTitle(R.string.share_proxies_prompt_title)
         * .setMessage(R.string.share_proxies_prompt_message)
         * .setPositiveButton(R.string.share_proxies_prompt_positive, new
         * DialogInterface.OnClickListener() { public void
         * onClick(DialogInterface dialog, int whichButton) {
         * applyShareProxies(); }})
         * .setNegativeButton(R.string.share_proxies_prompt_negative, new
         * DialogInterface.OnClickListener() { public void
         * onClick(DialogInterface dialog, int whichButton) {
         * m_shareProxiesToggle.setChecked(!m_shareProxiesToggle.isChecked());
         * }}) .setOnCancelListener( new DialogInterface.OnCancelListener() {
         * 
         * @Override public void onCancel(DialogInterface dialog) {
         * m_shareProxiesToggle.setChecked(!m_shareProxiesToggle.isChecked());
         * }}) .show(); }
         * 
         * private void applyShareProxies() { boolean shareProxies =
         * m_shareProxiesToggle.isChecked();
         * 
         * PsiphonData.getPsiphonData().setShareProxies(shareProxies);
         * 
         * Editor editor =
         * PreferenceManager.getDefaultSharedPreferences(this).edit();
         * editor.putBoolean(SHARE_PROXIES_PREFERENCE, shareProxies);
         * editor.commit();
         * 
         * stopTunnel(this);
         * 
         * AlarmManager alm = (AlarmManager)
         * this.getSystemService(Context.ALARM_SERVICE);
         * alm.set(AlarmManager.RTC, System.currentTimeMillis() + 1000,
         * PendingIntent.getActivity(this, 0, new Intent(this, this.getClass()),
         * 0));
         * 
         * android.os.Process.killProcess(android.os.Process.myPid()); }
         */

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
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    DataTransferStats dataTransferStats = PsiphonData.getPsiphonData().getDataTransferStats();
                    m_elapsedConnectionTimeView.setText(dataTransferStats.isConnected() ? getString(R.string.connected_elapsed_time,
                            Utils.elapsedTimeToDisplay(dataTransferStats.getElapsedTime())) : getString(R.string.disconnected));
                    m_totalSentView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesSent() + dataTransferStats.getTotalOverheadBytesSent(),
                            false));
                    m_compressionRatioSentView.setText(getString(R.string.compression_ratio, dataTransferStats.getTotalSentCompressionRatio()));
                    m_compressionSavingsSentView.setText(getString(R.string.compression_savings,
                            Utils.byteCountToDisplaySize(dataTransferStats.getTotalSentSaved(), false)));
                    m_totalReceivedView.setText(Utils.byteCountToDisplaySize(
                            dataTransferStats.getTotalBytesReceived() + dataTransferStats.getTotalOverheadBytesReceived(), false));
                    m_compressionRatioReceivedView.setText(getString(R.string.compression_ratio, dataTransferStats.getTotalReceivedCompressionRatio()));
                    m_compressionSavingsReceivedView.setText(getString(R.string.compression_savings,
                            Utils.byteCountToDisplaySize(dataTransferStats.getTotalReceivedSaved(), false)));

                    m_slowSentGraph.update(dataTransferStats.getSlowSentSeries());
                    m_slowReceivedGraph.update(dataTransferStats.getSlowReceivedSeries());
                    m_fastSentGraph.update(dataTransferStats.getFastSentSeries());
                    m_fastReceivedGraph.update(dataTransferStats.getFastReceivedSeries());
                }
            });
        }

        /* private boolean proxyInfoDisplayed = false; */
        private void updateStatusCallback() {
            this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    DataTransferStats dataTransferStats = PsiphonData.getPsiphonData().getDataTransferStats();
                    if (dataTransferStats.isConnected()) {
                        setStatusState(R.drawable.status_icon_connected);
                        /*
                         * if (!proxyInfoDisplayed) {
                         * m_statusTabSocksPortLine.setText(
                         * getContext().getString(R.string.socks_proxy_address,
                         * (PsiphonData.getPsiphonData().getShareProxies() ?
                         * Utils.getIPv4Address() : "127.0.0.1") + ":" +
                         * PsiphonData.getPsiphonData().getSocksPort()));
                         * m_statusTabHttpProxyPortLine.setText(
                         * getContext().getString(R.string.http_proxy_address,
                         * (PsiphonData.getPsiphonData().getShareProxies() ?
                         * Utils.getIPv4Address() : "127.0.0.1") + ":" +
                         * PsiphonData.getPsiphonData().getHttpProxyPort()));
                         * proxyInfoDisplayed = true; }
                         */
                    } else {
                        /*
                         * if (proxyInfoDisplayed) {
                         * m_statusTabSocksPortLine.setText("");
                         * m_statusTabHttpProxyPortLine.setText("");
                         * proxyInfoDisplayed = false; }
                         */
                    }
                }
            });
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

            if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService() && !PsiphonData.getPsiphonData().getVpnServiceUnavailable()
                    && !PsiphonData.getPsiphonData().getWdmForceIptables()) {
                // VpnService backwards compatibility: for lazy class loading
                // the VpnService
                // class reference has to be in another function (doVpnPrepare),
                // not just
                // in a conditional branch.
                waitingForPrompt = doVpnPrepare();
            }
            if (!waitingForPrompt) {
                startTunnelService(this);
            }
        }

        protected boolean doVpnPrepare() {
            try {
                return vpnPrepare();
            } catch (ActivityNotFoundException e) {
                MyLog.e(R.string.tunnel_whole_device_exception, MyLog.Sensitivity.NOT_SENSITIVE);

                // VpnService is broken. For rooted devices, proceed with
                // starting Whole Device in root mode.

                if (Utils.isRooted()) {
                    PsiphonData.getPsiphonData().setVpnServiceUnavailable(true);

                    // false = not waiting for prompt, so service will be
                    // started immediately
                    return false;
                }

                // For non-rooted devices, turn off the option and abort.

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
                // Catching ActivityNotFoundException as per:
                // http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
                //
                // TODO: can we disable the mode before we reach this this
                // failure point with
                // resolveActivity()? We'll need the intent from prepare() or
                // we'll have to mimic it.
                // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29

                startActivityForResult(intent, REQUEST_CODE_PREPARE_VPN);

                // startTunnelService will be called in onActivityResult
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
                startTunnelService(this);
            } else if (request == REQUEST_CODE_PREFERENCE) {
                // detect if a restart needed due to proxy settings change
                boolean restart = isProxySettingsRestartRequired() && isServiceRunning();
                
                if (restart) {
                    stopTunnel(this);
                }
                updateProxySettingsFromPreferences();
                if (restart && !isServiceRunning()) {
                    startTunnel(this);
                }
            }
        }

        protected void onPreStartService() {
            // Disable service-toggling controls while service is starting up
            // (i.e., while isServiceRunning can't be relied upon)
            m_tunnelWholeDeviceToggle.setEnabled(false);
            m_wdmForceIptablesToggle.setEnabled(false);
            m_regionSelector.setEnabled(false);
        }

        protected void onPostStartService() {
            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            m_wdmForceIptablesToggle.setEnabled(m_isRooted && PsiphonData.getPsiphonData().getTunnelWholeDevice());
            m_regionSelector.setEnabled(true);
        }

        protected void startTunnelService(Context context) {
            // TODO: onResume calls this and when there was only one kind of
            // service
            // it was safe to call through to bindService, which would start
            // that
            // service if it was not already running. Now we have two types of
            // services,
            // can we rely on blindly rebinding? What if the
            // getTunnelWholeDevice()
            // value changed, can we end up with two running services? For now,
            // we have some asserts.

            if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService()) {
                if (m_boundToTunnelService != false) {
                    MyLog.g("already bound to TunnelService");
                    return;
                }

                onPreStartService();
                // VpnService backwards compatibility: doStartTunnelVpnService
                // is a wrapper
                // function so we don't reference the undefined class when this
                // function
                // is loaded.
                if (!doStartTunnelVpnService(context)) {
                    // Service won't start, so allow handler to clean up
                    onPostStartService();
                }
            } else {
                if (m_boundToTunnelVpnService != false) {
                    MyLog.g("already bound to TunnelVpnService");
                    return;
                }

                onPreStartService();
                Intent intent = new Intent(context, TunnelService.class);
                if (!bindService(intent, m_tunnelServiceConnection, Context.BIND_AUTO_CREATE)) {
                    // Service won't start, so allow handler to clean up
                    onPostStartService();
                }
            }
        }

        private boolean doStartTunnelVpnService(Context context) {
            Intent intent = new Intent(context, TunnelVpnService.class);
            return bindService(intent, m_tunnelVpnServiceConnection, Context.BIND_AUTO_CREATE);
        }

        private void stopTunnel(Context context) {
            unbindTunnelService();
            if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService()) {
                doStopVpnTunnel(context);
            } else {
                stopService(new Intent(context, TunnelService.class));
            }

            // Wait up to 5 seconds for the service to stop running before
            // returning
            for (int i = 0; i < 50; i++) {
                if (!isServiceRunning()) {
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        }

        private void doStopVpnTunnel(Context context) {
            TunnelCore currentTunnelCore = PsiphonData.getPsiphonData().getCurrentTunnelCore();

            if (currentTunnelCore != null) {
                // See comments in stopVpnServiceHelper about stopService.
                currentTunnelCore.stopVpnServiceHelper();
                stopService(new Intent(context, TunnelVpnService.class));
            }
        }

        private void unbindTunnelService() {
            if (m_boundToTunnelService) {
                try {
                    unbindService(m_tunnelServiceConnection);
                }
                // Ignore
                // "java.lang.IllegalArgumentException: Service not registered"
                catch (java.lang.IllegalArgumentException e) {
                }
                m_boundToTunnelService = false;
            }
            if (m_boundToTunnelVpnService) {
                try {
                    unbindService(m_tunnelVpnServiceConnection);
                } catch (java.lang.IllegalArgumentException e) {
                }
                m_boundToTunnelVpnService = false;
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
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (TunnelService.class.getName().equals(service.service.getClassName())
                        || (Utils.hasVpnService() && isVpnService(service.service.getClassName()))) {
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
                        }, 1000);
                    }
                }
            }

            private final WebView mWebView;
            private final SponsorWebViewClient mWebViewClient;
            private final SponsorWebChromeClient mWebChromeClient;
            private final ProgressBar mProgressBar;

            public SponsorHomePage(WebView webView, ProgressBar progressBar, IEvents eventsInterface) {
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
                WebViewProxySettings.setLocalProxy(mWebView.getContext(), PsiphonData.getPsiphonData().getHttpProxyPort());
                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }
    }
}