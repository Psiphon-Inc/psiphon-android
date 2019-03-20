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
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.PendingIntent;
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
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.text.TextUtilsCompat;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.R;
import com.psiphon3.util.IabHelper;
import com.psiphon3.util.Purchase;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.SharedPreferencesImport;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

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
        protected static final String EGRESS_REGION_PREFERENCE = "egressRegionPreference";
        protected static final String TUNNEL_WHOLE_DEVICE_PREFERENCE = "tunnelWholeDevicePreference";
        protected static final String ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION = "askedToAccessCoarseLocationPermission";
        protected static final String CURRENT_TAB = "currentTab";
        protected static final String CURRENT_PURCHASE = "currentPurchase";

        protected static final int REQUEST_CODE_PREPARE_VPN = 100;
        protected static final int REQUEST_CODE_PREFERENCE = 101;
        protected static final int REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 102;

        public static final String HOME_TAB_TAG = "home_tab_tag";
        public static final String PSICASH_TAB_TAG = "psicash_tab_tag";
        public static final String STATISTICS_TAB_TAG = "statistics_tab_tag";
        public static final String SETTINGS_TAB_TAG = "settings_tab_tag";
        public static final String LOGS_TAB_TAG = "logs_tab_tag";


        protected static boolean m_firstRun = true;
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
        private LoggingObserver m_loggingObserver;

        protected boolean isAppInForeground;

        // This fragment helps retain data across configuration changes
        protected RetainedDataFragment m_retainedDataFragment;
        private static final String TAG_RETAINED_DATA_FRAGMENT = "com.psiphon3.RetainedDataFragment";

        private BehaviorRelay<ServiceConnectionStatus> serviceConnectionStatusBehaviorRelay = BehaviorRelay.create();
        private Disposable restartServiceDisposable = null;

        public static class RetainedDataFragment extends Fragment {
            private final Map<String, Map<Class<?>, Object>> internalMap = new HashMap<>();

            @Override
            public void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                // retain this fragment
                setRetainInstance(true);
            }

            private <T> void put(String key, Class<T> type, T value) {
                if (!internalMap.containsKey(key)) {
                    final Map<Class<?>, Object> typeValueMap = new HashMap<>();
                    typeValueMap.put(type, value);
                    internalMap.put(key, typeValueMap);
                } else {
                    internalMap.get(key).put(type, value);
                }
            }

            private <T> T get(String key, Class<T> type) {
                if (internalMap.containsKey(key))
                    return type.cast(internalMap.get(key).get(type));
                else
                    return null;
            }

            public Purchase getCurrentPurchase() {
                return get(CURRENT_PURCHASE, Purchase.class);
            }

            public void setCurrentPurchase(Purchase value) {
                put(CURRENT_PURCHASE, Purchase.class, value);
            }

            public Boolean getBoolean(String key, Boolean devaultValue) {
                Boolean b = get(key, Boolean.class);
                if(b == null) {
                    return devaultValue;
                } //else
                return b;
            }

            public void putBoolean(String key, Boolean value) {
                put(key, Boolean.class, value);
            }
        }

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
        protected List<TabSpec> m_tabSpecsList;
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

            if (m_firstRun) {
                EmbeddedValues.initialize(this);
            }

            FragmentManager fm = getFragmentManager();
            m_retainedDataFragment = (RetainedDataFragment) fm.findFragmentByTag(TAG_RETAINED_DATA_FRAGMENT);
            if (m_retainedDataFragment == null) {
                m_retainedDataFragment = new RetainedDataFragment();
                fm.beginTransaction().add(m_retainedDataFragment, TAG_RETAINED_DATA_FRAGMENT).commit();
            }
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

            m_tabSpecsList.clear();
            m_tabSpecsList.add(0, m_tabHost.newTabSpec(HOME_TAB_TAG).setContent(R.id.homeTab).setIndicator(getText(R.string.home_tab_name)));
            m_tabSpecsList.add(1, m_tabHost.newTabSpec(PSICASH_TAB_TAG).setContent(R.id.psicashTab).setIndicator(getText(R.string.psicash_tab_name)));
            m_tabSpecsList.add(2, m_tabHost.newTabSpec(STATISTICS_TAB_TAG).setContent(R.id.statisticsView).setIndicator(getText(R.string.statistics_tab_name)));
            m_tabSpecsList.add(3, m_tabHost.newTabSpec(SETTINGS_TAB_TAG).setContent(R.id.settingsView).setIndicator(getText(R.string.settings_tab_name)));
            m_tabSpecsList.add(4, m_tabHost.newTabSpec(LOGS_TAB_TAG).setContent(R.id.logsTab).setIndicator(getText(R.string.logs_tab_name)));

            for (TabSpec tabSpec : m_tabSpecsList) {
                m_tabHost.addTab(tabSpec);
            }

            LinearLayout psiCashTabLayout = (LinearLayout) m_tabHost.getTabWidget().getChildTabViewAt(1);
            decorateWithRedDot(psiCashTabLayout);

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
            findViewById(R.id.psicashTab).setOnTouchListener(onTouchListener);
            ListView statusListView = (ListView) findViewById(R.id.statusList);
            statusListView.setOnTouchListener(onTouchListener);

            int currentTab = m_multiProcessPreferences.getInt(CURRENT_TAB, 0);
            m_tabHost.setCurrentTab(currentTab);

            // Set TabChangedListener after restoring last tab to avoid triggering an interstitial,
            // we only want interstitial to be triggered by user actions
            m_tabHost.setOnTabChangedListener(this);

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

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);
            m_localBroadcastManager.registerReceiver(new StatusEntryAdded(), new IntentFilter(STATUS_ENTRY_AVAILABLE));

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

            // Note that this must come after the above lines, or else the
            // activity
            // will not be sufficiently initialized for isDebugMode to succeed.
            // (Voodoo.)
            PsiphonConstants.DEBUG = Utils.isDebugMode(this);

            String msg = getContext().getString(R.string.client_version, EmbeddedValues.CLIENT_VERSION);
            m_statusTabVersionLine.setText(msg);

            // The LoggingObserver will run in a separate thread than the main UI thread
            HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
            loggingObserverThread.start();
            m_loggingObserver = new LoggingObserver(this, new Handler(loggingObserverThread.getLooper()));

            // Force the UI to display logs already loaded into the StatusList message history
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(STATUS_ENTRY_AVAILABLE));
        }

        private void decorateWithRedDot(LinearLayout psiCashTabLayout) {
            // Get parent and index of the current tab layout. We will need the index later when we
            // wrap and replace the original layout with a wrapper layout.
            ViewGroup parent = (ViewGroup) psiCashTabLayout.getParent();
            final int index = parent.indexOfChild(psiCashTabLayout);

            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setLayoutParams(psiCashTabLayout.getLayoutParams());

            // Remove the tab layout from parent tab widget.
            parent.removeView(psiCashTabLayout);
            // Add a new linear layout in place of original one.
            parent.addView(linearLayout, index);

            // Create a new relative layout to wrap old layout.
            RelativeLayout wrapperRelativeLayout = new RelativeLayout(this);
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            wrapperRelativeLayout.addView(psiCashTabLayout, lp);

            // Add wrapper relative layout to the top tab linear layout.
            linearLayout.addView(wrapperRelativeLayout);

            // Create a frame layout which will hold a red dot image view.
            FrameLayout redDotLayout= new FrameLayout(this);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            redDotLayout.setLayoutParams(flp);

            // Create the red dot image and add it to the holder frame layout
            int redDotSize = 35;
            ImageView redDotImage = new ImageView(getContext());
            ShapeDrawable badge = new ShapeDrawable(new OvalShape());
            badge.setIntrinsicWidth(redDotSize);
            badge.setIntrinsicHeight(redDotSize);
            badge.getPaint().setColor(Color.RED);
            redDotImage.setImageDrawable(badge);
            redDotImage.setLayoutParams(new LinearLayout.LayoutParams(redDotSize, redDotSize));
            redDotLayout.addView(redDotImage);

            // Position and add the red dot layout to the wrapper layout
            lp = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
            lp.addRule(RelativeLayout.CENTER_VERTICAL);

            psiCashTabLayout.setId(R.id.psicash_tab_layout_id);

            // Calculate side margin of the red dot holder layout.
            // Get original tab layout side padding, since it is centered
            // we assume left padding == right padding.
            int paddingSide = psiCashTabLayout.getPaddingLeft();
            int redDotMargin = - (paddingSide + redDotSize) / 2;

            boolean isRtl = ViewCompat.LAYOUT_DIRECTION_RTL == TextUtilsCompat.getLayoutDirectionFromLocale(getResources().getConfiguration().locale);
            if (isRtl) {
                lp.addRule(RelativeLayout.LEFT_OF, psiCashTabLayout.getId());
                lp.rightMargin = redDotMargin;
            } else {
                lp.addRule(RelativeLayout.RIGHT_OF, psiCashTabLayout.getId());
                lp.leftMargin = redDotMargin;
            }
            redDotLayout.setLayoutParams(lp);
            wrapperRelativeLayout.addView(redDotLayout, lp);
        }

        /**
         * Show the sponsor home page, either in the embedded view web view or
         * in the external browser.
         *
         * @param freshConnect If false, the home page will not be opened in an external
         * browser. This is to prevent the page from opening every
         * time the activity is created.
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
                    displayBrowser(getContext(), Uri.parse(url));
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

            isAppInForeground = true;
            
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

            // Don't show the keyboard until edit selected
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

            if (isServiceRunning()) {
                updateServiceStateUI(null);
                startAndBindTunnelService();
            } else {
                // reset the tunnel state
                onTunnelConnectionState(new TunnelManager.State());
            }
        }

        @Override
        protected void onPause() {
            super.onPause();

            isAppInForeground = false;

            getContentResolver().unregisterContentObserver(m_loggingObserver);

            cancelInvalidProxySettingsToast();

            m_updateStatisticsUITimer.cancel();

            unbindTunnelService();
            updateServiceStateUI(null);
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

        public abstract void onSubscribeButtonClick(View v);

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

        protected void updateServiceStateUI(TunnelManager.State state) {
            if(state == null) {
                setStatusState(R.drawable.status_icon_disconnected);
                disableToggleServiceUI();
            } else if(!state.isRunning) {
                setStatusState(R.drawable.status_icon_disconnected);
                enableToggleServiceUI(R.string.start);
            } else {
                if(state.isConnected) {
                    setStatusState(R.drawable.status_icon_connected);
                    enableToggleServiceUI(R.string.stop);
                } else {
                    setStatusState(R.drawable.status_icon_connecting);
                    enableToggleServiceUI(R.string.stop);
                }
            }
        }

        protected void enableToggleServiceUI(int resId) {
            m_toggleButton.setText(getText(resId));
            m_toggleButton.setEnabled(true);
            m_tunnelWholeDeviceToggle.setEnabled(m_canWholeDevice);
            m_disableTimeoutsToggle.setEnabled(true);
            m_regionSelector.setEnabled(true);
            m_moreOptionsButton.setEnabled(true);
        }

        protected void disableToggleServiceUI() {
            m_toggleButton.setText(getText(R.string.waiting));
            m_toggleButton.setEnabled(false);
            m_tunnelWholeDeviceToggle.setEnabled(false);
            m_disableTimeoutsToggle.setEnabled(false);
            m_regionSelector.setEnabled(false);
            m_moreOptionsButton.setEnabled(false);
        }

        protected void scheduleRunningTunnelServiceRestart() {
            if(restartServiceDisposable != null && !restartServiceDisposable.isDisposed()) {
                // call in progress, do nothing
                return;
            }
            if (isServiceRunning()) {
                stopTunnelService();
                // start observing service connection for disconnected message
                restartServiceDisposable = serviceConnectionObservable()
                        .observeOn(Schedulers.computation())
                        .filter(s -> s.equals(ServiceConnectionStatus.SERVICE_DISCONNECTED))
                        .take(1)
                        .doOnComplete(() -> runOnUiThread(this::startTunnel))
                        .subscribe();
            }
        }

        private Observable <ServiceConnectionStatus> serviceConnectionObservable() {
            return serviceConnectionStatusBehaviorRelay.hide();
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

            //check if "add custom headers" checkbox changed
            boolean addCustomHeadersPreference = prefs.getBoolean(
                    getString(R.string.addCustomHeadersPreference), false);
            if (addCustomHeadersPreference != UpstreamProxySettings.getAddCustomHeadersPreference(this)) {
                return true;
            }

            // "add custom headers" is selected, check if
            // upstream headers string has changed
            if (addCustomHeadersPreference) {
                JSONObject newHeaders = new JSONObject();

                for (int position = 1; position <= 6; position++) {
                    int nameID = getResources().getIdentifier("customProxyHeaderName" + position, "string", getPackageName());
                    int valueID = getResources().getIdentifier("customProxyHeaderValue" + position, "string", getPackageName());

                    String namePrefStr = getResources().getString(nameID);
                    String valuePrefStr = getResources().getString(valueID);

                    String name = prefs.getString(namePrefStr, "");
                    String value = prefs.getString(valuePrefStr, "");
                    try {
                        if (!TextUtils.isEmpty(name)) {
                            JSONArray arr = new JSONArray();
                            arr.put(value);
                            newHeaders.put(name, arr);
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }

                JSONObject oldHeaders = UpstreamProxySettings.getUpstreamProxyCustomHeaders(this);

                if (0 != oldHeaders.toString().compareTo(newHeaders.toString())) {
                    return true;
                }
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
                        new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.addCustomHeadersPreference), getString(R.string.addCustomHeadersPreference)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName1), getString(R.string.customProxyHeaderName1)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue1), getString(R.string.customProxyHeaderValue1)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName2), getString(R.string.customProxyHeaderName2)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue2), getString(R.string.customProxyHeaderValue2)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName3), getString(R.string.customProxyHeaderName3)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue3), getString(R.string.customProxyHeaderValue3)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName4), getString(R.string.customProxyHeaderName4)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue4), getString(R.string.customProxyHeaderValue4)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName5), getString(R.string.customProxyHeaderName5)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue5), getString(R.string.customProxyHeaderValue5)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderName6), getString(R.string.customProxyHeaderName6)),
                        new SharedPreferencesImport(this, prefName, getString(R.string.customProxyHeaderValue6), getString(R.string.customProxyHeaderValue6))
                );

                if (bRestartRequired) {
                    if (isServiceRunning()) {
                        startAndBindTunnelService();
                    }
                    scheduleRunningTunnelServiceRestart();
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

        protected PendingIntent getHandshakePendingIntent() {
            return null;
        }

        protected PendingIntent getServiceNotificationPendingIntent() {
            return null;
        }

        protected PendingIntent getRegionNotAvailablePendingIntent() {
            return null;
        }

        protected PendingIntent getVpnRevokedPendingIntent() {
            return null;
        }
        protected void configureServiceIntent(Intent intent) {
            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_HANDSHAKE_PENDING_INTENT,
                    getHandshakePendingIntent());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_NOTIFICATION_PENDING_INTENT,
                    getServiceNotificationPendingIntent());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_REGION_NOT_AVAILABLE_PENDING_INTENT,
                    getRegionNotAvailablePendingIntent());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_VPN_REVOKED_PENDING_INTENT,
                    getVpnRevokedPendingIntent());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_WHOLE_DEVICE,
                    getTunnelConfigWholeDevice());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_EGRESS_REGION,
                    getTunnelConfigEgressRegion());

            intent.putExtra(TunnelManager.DATA_TUNNEL_CONFIG_DISABLE_TIMEOUTS,
                    getTunnelConfigDisableTimeouts());

            intent.putExtra(TunnelManager.CLIENT_MESSENGER, m_incomingMessenger);

            Purchase currentPurchase = m_retainedDataFragment.getCurrentPurchase();
            if(currentPurchase != null) {
                intent.putExtra(TunnelManager.DATA_PURCHASE_ID,
                        currentPurchase.getSku());
                intent.putExtra(TunnelManager.DATA_PURCHASE_TOKEN,
                        currentPurchase.getToken());
                intent.putExtra(TunnelManager.DATA_PURCHASE_IS_SUBSCRIPTION,
                        currentPurchase.getItemType().equals(IabHelper.ITEM_TYPE_SUBS));
            }
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
            // On API >= 26 a service will get started even when the app is in the background
            // as long as the service calls its startForeground() within a reasonable amount of time.
            // On API < 26 the call may throw IllegalStateException in case the app is in the state
            // when services are not allowed, such as not in foreground
            try {
                ContextCompat.startForegroundService(this, intent);
            } catch(IllegalStateException e) {
                // do nothing
            }

            if (bindService(intent, m_tunnelServiceConnection, 0)) {
                m_boundToTunnelService = true;
            }
        }

        private Intent startVpnServiceIntent() {
            return new Intent(this, TunnelVpnService.class);
        }

        // Shared tunnel state, received from service in the HANDSHAKE
        // intent and in various state-related Messages.
        protected TunnelManager.State m_tunnelState;

        protected boolean isTunnelConnected() {
            return m_tunnelState != null && m_tunnelState.isConnected;
        }

        protected ArrayList<String> getHomePages() {
            ArrayList<String> homePages = new ArrayList<>();
            try {
                PsiCashClient psiCashClient = PsiCashClient.getInstance(getContext());
                if (psiCashClient.hasValidTokens()) {
                    for (String homePageUrl : m_tunnelState.homePages) {
                        homePages.add(PsiCashClient.getInstance(getContext()).modifiedHomePageURL(homePageUrl));
                    }
                } else {
                    homePages.addAll(m_tunnelState.homePages);
                }
            } catch (PsiCashException e) {
                MyLog.g("Error modifying home pages: " + e);
                homePages.clear();
                homePages.addAll(m_tunnelState.homePages);
            }
            return homePages;
        }

        protected int getListeningLocalHttpProxyPort() {
            return m_tunnelState.listeningLocalHttpProxyPort;
        }

        protected String getClientRegion() {
            return m_tunnelState.clientRegion;
        }

        @NonNull
        protected TunnelManager.State getTunnelStateFromBundle(Bundle data) {
            TunnelManager.State tunnelState = new TunnelManager.State();
            if (data == null) {
                return tunnelState;
            }
            tunnelState.isRunning = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_RUNNING);
            tunnelState.isVPN = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_VPN);
            tunnelState.isConnected = data.getBoolean(TunnelManager.DATA_TUNNEL_STATE_IS_CONNECTED);
            tunnelState.listeningLocalSocksProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT);
            tunnelState.listeningLocalHttpProxyPort = data.getInt(TunnelManager.DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT);
            tunnelState.clientRegion = data.getString(TunnelManager.DATA_TUNNEL_STATE_CLIENT_REGION);
            tunnelState.sponsorId = data.getString(TunnelManager.DATA_TUNNEL_STATE_SPONSOR_ID);
            ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
            if (homePages != null && tunnelState.isConnected) {
                tunnelState.homePages = homePages;
            }
            return tunnelState;
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
            @Override
            public void handleMessage(Message msg) {
                Bundle data = msg.getData();
                // Only MSG_TUNNEL_CONNECTION_STATE has a tunnel state data bundle
                switch (msg.what) {
                    case TunnelManager.MSG_KNOWN_SERVER_REGIONS:
                        m_regionAdapter.updateRegionsFromPreferences();
                        // Make sure we preserve the selection in case the dataset has changed
                        m_regionSelector.setSelectionByValue(m_tunnelConfig.egressRegion);
                        break;

                    case TunnelManager.MSG_TUNNEL_CONNECTION_STATE:
                        TunnelManager.State state = getTunnelStateFromBundle(data);
                        onTunnelConnectionState(state);

                        // An activity created needs to load a sponsor the tab when tunnel connects
                        // once per its lifecycle. Both conditions are taken care of inside
                        // of restoreSponsorTab function
                        restoreSponsorTab();
                        break;

                    case TunnelManager.MSG_DATA_TRANSFER_STATS:
                        getDataTransferStatsFromBundle(data);
                        break;

                    case TunnelManager.MSG_AUTHORIZATIONS_REMOVED:
                        onAuthorizationsRemoved();
                        break;

                    default:
                        super.handleMessage(msg);
                }
            }
        }

        protected void onAuthorizationsRemoved() {
            final AppPreferences mp = new AppPreferences(getContext());
            mp.put(this.getString(R.string.persistentAuthorizationsRemovedFlag), false);
        }

        private void sendServiceMessage(int what) {
            try {
                Message msg = Message.obtain(null, what);
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
                serviceConnectionStatusBehaviorRelay.accept(ServiceConnectionStatus.SERVICE_CONNECTED);
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                m_outgoingMessenger = null;
                serviceConnectionStatusBehaviorRelay.accept(ServiceConnectionStatus.SERVICE_DISCONNECTED);
                onTunnelConnectionState(new TunnelManager.State());
            }
        };

        private void stopTunnelService() {
            sendServiceMessage(TunnelManager.MSG_STOP_SERVICE);
            // MSG_STOP_SERVICE will cause the Service to stop itself,
            // which will then cause an unbind to occur. Don't call
            // unbindTunnelService() here, as its unnecessary and either
            // the MSG_UNREGISTER or unbindService causes
            // "Exception when unbinding service com.psiphon3/.psiphonlibrary.TunnelVpnService"
        }

        private void unbindTunnelService() {
            if (m_boundToTunnelService) {
                m_boundToTunnelService = false;
                sendServiceMessage(TunnelManager.MSG_UNREGISTER);
                try {
                    unbindService(m_tunnelServiceConnection);
                }
                catch (java.lang.IllegalArgumentException e) {
                    // Ignore
                    // "java.lang.IllegalArgumentException: Service not registered"
                }
            }
        }

        protected void onTunnelConnectionState(@NonNull TunnelManager.State state) {
            // make sure WebView proxy settings are up to date
            // Set WebView proxy only if we are connected and not in WD mode.
            if (state.isConnected && !state.isVPN) {
                WebViewProxySettings.setLocalProxy(this, state.listeningLocalHttpProxyPort);
            }

            // We are not running
            // reset WebView proxy if it has been previously set.
            if(!state.isRunning)
            {
                if (WebViewProxySettings.isLocalProxySet()){
                    WebViewProxySettings.resetLocalProxy(this);
                }
            }
            m_tunnelState = state;
            updateServiceStateUI(state);
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
                if (service.uid == android.os.Process.myUid() &&
                        (TunnelService.class.getName().equals(service.service.getClassName())
                        || (Utils.hasVpnService() && TunnelVpnService.class.getName().equals(service.service.getClassName())))) {
                    return true;
                }
            }
            return false;
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
                        displayBrowser(getContext(), Uri.parse(url));
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
                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }

        protected void displayBrowser(Context context, Uri uri) {

        }

        protected void restoreSponsorTab() {

        }

        private enum ServiceConnectionStatus {
            SERVICE_CONNECTED,
            SERVICE_DISCONNECTED
        }
    }
}
