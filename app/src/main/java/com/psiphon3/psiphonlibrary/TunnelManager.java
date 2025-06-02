/*
 * Copyright (c) 2022, Psiphon Inc.
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

import static android.os.Build.VERSION_CODES.LOLLIPOP;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.net.VpnService.Builder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.ConduitState;
import com.psiphon3.ConduitStateManager;
import com.psiphon3.Location;
import com.psiphon3.PackageHelper;
import com.psiphon3.PsiphonCrashService;
import com.psiphon3.RateLimitHelper;
import com.psiphon3.TunnelState;
import com.psiphon3.UnlockOptions;
import com.psiphon3.VpnManager;
import com.psiphon3.billing.PurchaseVerifier;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import ca.psiphon.PsiphonTunnel;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;
import ru.ivanarh.jndcrash.NDCrash;

public class TunnelManager implements PsiphonTunnel.HostService, PurchaseVerifier.VerificationResultListener, VpnManager.VpnServiceBuilderProvider {
    // Android IPC messages
    // Client -> Service
    enum ClientToServiceMessage {
        REGISTER,
        UNREGISTER,
        STOP_SERVICE,
        RESTART_TUNNEL,
        CHANGED_LOCALE,
        NFC_CONNECTION_INFO_EXCHANGE_IMPORT,
        NFC_CONNECTION_INFO_EXCHANGE_EXPORT,
    }

    // Service -> Client
    enum ServiceToClientMessage {
        TUNNEL_CONNECTION_STATE,
        DATA_TRANSFER_STATS,
        AUTHORIZATIONS_REMOVED,
        PING,
        NFC_CONNECTION_INFO_EXCHANGE_EXPORT,
    }

    public static final String INTENT_ACTION_VIEW = "ACTION_VIEW";
    public static final String INTENT_ACTION_HANDSHAKE = "com.psiphon3.psiphonlibrary.TunnelManager.HANDSHAKE";
    public static final String INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE = "com.psiphon3.psiphonlibrary.TunnelManager.SELECTED_REGION_NOT_AVAILABLE";
    public static final String INTENT_ACTION_VPN_REVOKED = "com.psiphon3.psiphonlibrary.TunnelManager.INTENT_ACTION_VPN_REVOKED";
    public static final String INTENT_ACTION_STOP_TUNNEL = "com.psiphon3.psiphonlibrary.TunnelManager.ACTION_STOP_TUNNEL";
    public static final String IS_CLIENT_AN_ACTIVITY = "com.psiphon3.psiphonlibrary.TunnelManager.IS_CLIENT_AN_ACTIVITY";
    public static final String INTENT_ACTION_DISALLOWED_TRAFFIC = "com.psiphon3.psiphonlibrary.TunnelManager.INTENT_ACTION_DISALLOWED_TRAFFIC";
    public static final String INTENT_ACTION_UNSAFE_TRAFFIC = "com.psiphon3.psiphonlibrary.TunnelManager.INTENT_ACTION_UNSAFE_TRAFFIC";
    public static final String INTENT_ACTION_UPSTREAM_PROXY_ERROR = "com.psiphon3.psiphonlibrary.TunnelManager.UPSTREAM_PROXY_ERROR";

    // Service -> Client bundle parameter names
    static final String DATA_TUNNEL_STATE_IS_RUNNING = "isRunning";
    static final String DATA_TUNNEL_STATE_NETWORK_CONNECTION_STATE = "networkConnectionState";
    static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT = "listeningLocalSocksProxyPort";
    public static final String DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT = "listeningLocalHttpProxyPort";
    static final String DATA_TUNNEL_STATE_CLIENT_REGION = "clientRegion";
    static final String DATA_TUNNEL_STATE_SPONSOR_ID = "sponsorId";
    public static final String DATA_TUNNEL_STATE_HOME_PAGES = "homePages";
    public static final String DATA_TUNNEL_STATE_UPSTREAM_RATE_LIMIT = "upstreamRateLimit";
    public static final String DATA_TUNNEL_STATE_DOWNSTREAM_RATE_LIMIT = "downstreamRateLimit";
    static final String DATA_TRANSFER_STATS_CONNECTED_TIME = "dataTransferStatsConnectedTime";
    static final String DATA_TRANSFER_STATS_TOTAL_BYTES_SENT = "dataTransferStatsTotalBytesSent";
    static final String DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED = "dataTransferStatsTotalBytesReceived";
    static final String DATA_TRANSFER_STATS_SLOW_BUCKETS = "dataTransferStatsSlowBuckets";
    static final String DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME = "dataTransferStatsSlowBucketsLastStartTime";
    static final String DATA_TRANSFER_STATS_FAST_BUCKETS = "dataTransferStatsFastBuckets";
    static final String DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME = "dataTransferStatsFastBucketsLastStartTime";
    public static final String DATA_UNSAFE_TRAFFIC_SUBJECTS_LIST = "dataUnsafeTrafficSubjects";
    public static final String DATA_UNSAFE_TRAFFIC_ACTION_URLS_LIST = "dataUnsafeTrafficActionUrls";
    public static final String DATA_NFC_CONNECTION_INFO_EXCHANGE = "dataNfcConnectionInfoExchange";

    // a snapshot of all authorizations pulled by getPsiphonConfig
    private List<Authorization> m_tunnelConfigAuthorizations;

    void updateNotifications() {
        postServiceNotification(false, m_tunnelState.networkConnectionState);
    }

    // Shared tunnel state, sent to the client in the HANDSHAKE
    // intent and in the MSG_TUNNEL_CONNECTION_STATE service message.
    public static class State {
        boolean isRunning = false;
        TunnelState.ConnectionData.NetworkConnectionState networkConnectionState =
                TunnelState.ConnectionData.NetworkConnectionState.CONNECTING;
        int listeningLocalSocksProxyPort = 0;
        int listeningLocalHttpProxyPort = 0;
        String clientRegion = "";
        String sponsorId = "";
        ArrayList<String> homePages = new ArrayList<>();
        // Rate limits set to -1 indicate that the rate limit is not set, since 0 is
        // a valid rate limit and means 'no limit'.
        long upstreamRateLimitBytesPerSecond = -1;
        long downstreamRateLimitBytesPerSecond = -1;

        boolean isConnected() {
            return networkConnectionState == TunnelState.ConnectionData.NetworkConnectionState.CONNECTED;
        }
    }

    private State m_tunnelState = new State();

    private NotificationManager mNotificationManager = null;
    private final static String NOTIFICATION_CHANNEL_ID = "psiphon_notification_channel";
    private final static String NOTIFICATION_SERVER_ALERT_CHANNEL_ID_OLD = "psiphon_server_alert_notification_channel";
    private final static String NOTIFICATION_SERVER_ALERT_CHANNEL_ID = "psiphon_server_alert_new_notification_channel";
    private Service m_parentService;

    private Context m_context;
    private boolean m_firstStart = true;
    private CountDownLatch m_tunnelThreadStopSignal;
    private Thread m_tunnelThread;
    private final AtomicBoolean m_isStopping;
    private PsiphonTunnel m_tunnel;
    private VpnManager m_vpnManager = VpnManager.getInstance();
    private String m_lastUpstreamProxyErrorMessage;
    private Handler m_Handler = new Handler();

    private PendingIntent m_notificationPendingIntent;

    private PublishRelay<TunnelState.ConnectionData.NetworkConnectionState> m_networkConnectionStatePublishRelay = PublishRelay.create();
    private final PublishRelay<Boolean> m_isRoutingThroughTunnelPublishRelay = PublishRelay.create();
    private PublishRelay<Object> m_newClientPublishRelay = PublishRelay.create();
    private CompositeDisposable m_compositeDisposable = new CompositeDisposable();
    private Disposable conduitStateObserver;
    private VpnAppsUtils.VpnAppsExclusionSetting vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.ALL_APPS;
    private int vpnAppsExclusionCount = 0;
    private ArrayList<String> unsafeTrafficSubjects;


    private PurchaseVerifier purchaseVerifier;

    private boolean disallowedTrafficNotificationAlreadyShown = false;

    private final CountDownLatch tunnelThreadStartedLock = new CountDownLatch(1);
    private TunnelConfigManager tunnelConfigManager;
    private final UnlockOptions unlockOptions = new UnlockOptions();
    private boolean unlockOptionsProcessed = false;
    private boolean unlockUIDeliveredThisSession = false;


    TunnelManager(Service parentService) {
        m_parentService = parentService;
        m_context = parentService;
        m_isStopping = new AtomicBoolean(false);
        unsafeTrafficSubjects = new ArrayList<>();
    }

    void onCreate() {
        // Defer initialization of the PsiphonTunnel instance to onCreate(). Ensures a valid context
        // passed via hostService is available for potential Context-dependent operations that the
        // PsiphonTunnel may perform internally at any time.
        m_tunnel = PsiphonTunnel.newPsiphonTunnel(this);

        // Register self as a host service for the VPN manager
        m_vpnManager.registerHostService(this);

        m_notificationPendingIntent = getPendingIntent(m_parentService, INTENT_ACTION_VIEW);

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Remove old server alert notification channel if exist
                // since we changed server alert notification priority from DEFAULT to HIGH
                mNotificationManager.deleteNotificationChannel(NOTIFICATION_SERVER_ALERT_CHANNEL_ID_OLD);

                NotificationChannel notificationChannel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID, getContext().getText(R.string.psiphon_service_notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                mNotificationManager.createNotificationChannel(notificationChannel);

                notificationChannel = new NotificationChannel(
                        NOTIFICATION_SERVER_ALERT_CHANNEL_ID, getContext().getText(R.string.psiphon_server_alert_notification_channel_name),
                        NotificationManager.IMPORTANCE_HIGH);

                mNotificationManager.createNotificationChannel(notificationChannel);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            m_parentService.startForeground(R.string.psiphon_service_notification_id,
                    createNotification(false, TunnelState.ConnectionData.NetworkConnectionState.CONNECTING),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            m_parentService.startForeground(R.string.psiphon_service_notification_id,
                    createNotification(false, TunnelState.ConnectionData.NetworkConnectionState.CONNECTING));
        }

        m_tunnelState.isRunning = true;
        // This service runs as a separate process, so it needs to initialize embedded values
        EmbeddedValues.initialize(getContext());

        purchaseVerifier = new PurchaseVerifier(getContext(), this);
        tunnelConfigManager = new TunnelConfigManager(getContext());

        // Start all subscription and purchase verification observers
        purchaseVerifier.start();

        // Load trusted signatures from file
        PackageHelper.configureRuntimeTrustedSignatures(
                PackageHelper.readTrustedSignaturesFromFile(getContext().getApplicationContext())
        );

        m_compositeDisposable.add(connectionStatusUpdaterDisposable());
    }

    // Implementation of android.app.Service.onStartCommand
    int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && INTENT_ACTION_STOP_TUNNEL.equals(intent.getAction())) {
            if (m_tunnelThreadStopSignal == null || m_tunnelThreadStopSignal.getCount() == 0) {
                m_parentService.stopForeground(true);
                m_parentService.stopSelf();
            } else {
                signalStopService();
            }
            return Service.START_NOT_STICKY;
        }

        if (m_firstStart) {
            MyLog.i(R.string.client_version, MyLog.Sensitivity.NOT_SENSITIVE, EmbeddedValues.CLIENT_VERSION);
            m_firstStart = false;
            m_tunnelThreadStopSignal = new CountDownLatch(1);
            m_compositeDisposable.add(
                    tunnelConfigManager.initConfiguration(speedBoostStateSingle(), conduitStateSingle(), deviceLocationSingle(), purchaseVerifier.subscriptionStateSingle())
                            .doOnSuccess(config -> {
                                MyLog.i("TunnelManager: tunnel config initialized");
                                m_tunnelThread = new Thread(this::runTunnel);
                                m_tunnelThread.start();
                                tunnelThreadStartedLock.countDown();
                            })
                            .subscribe());


            // Start a tunnel config observer to restart the tunnel when there is a new tunnel config
            // This is the preferred method to restart the tunnel in all cases
            m_compositeDisposable.add(
                    tunnelConfigManager.observeTunnelConfig()
                            .skip(1) // Skip the initial config value
                            .doOnNext(config -> {
                                // Perform a tunnel restart when a new tunnel config is received and we are not in the process of stopping
                                if (!m_isStopping.get()) {
                                    TunnelConfigManager.RestartType restartType = config.getRestartType();
                                    if (restartType == TunnelConfigManager.RestartType.FULL_RESTART) {
                                        // On full restart reset unlock options and all unlock flags
                                        unlockOptions.setEntries(new ConcurrentHashMap<>());
                                        unlockOptionsProcessed = false;
                                        unlockUIDeliveredThisSession = false;

                                        m_networkConnectionStatePublishRelay.accept(
                                                TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
                                        m_vpnManager.stopRouteThroughTunnel();
                                        m_isRoutingThroughTunnelPublishRelay.accept(Boolean.FALSE);
                                        MyLog.i("TunnelManager: tunnel config observer: full tunnel restart due to new tunnel config");
                                    } else {
                                        MyLog.i("TunnelManager: tunnel config observer: quiet tunnel restart due to new tunnel config");
                                    }
                                    onRestartTunnel();
                                }
                            })
                            .subscribe()
            );

            // Start Conduit state observer
            setupConduitStateObserver();

            // Set the persistent service running flag to true.
            // This flag is used to determine whether the service should be automatically restarted
            // after an app update, upon receiving a package replaced broadcast in the PsiphonUpdateReceiver.
            new AppPreferences(getContext()).put(getContext().getString(R.string.serviceRunningPreference), true);
        }
        return Service.START_REDELIVER_INTENT;
    }

    private void setupConduitStateObserver() {
        if (conduitStateObserver != null && !conduitStateObserver.isDisposed()) {
            conduitStateObserver.dispose();
        }
        // Configure runtime trusted signatures
        PackageHelper.configureRuntimeTrustedSignatures(
                PackageHelper.readTrustedSignaturesFromFile(getContext().getApplicationContext())
        );

        // Observe conduit state changes, then check unlock options for each change
        conduitStateObserver = ConduitStateManager.newManager(getContext()).stateFlowable()
                .doOnNext(state -> MyLog.i("TunnelManager: Conduit state: " + state))
                .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                .map(state -> state.status() == ConduitState.Status.RUNNING)
                .onErrorReturnItem(false) // Should not ever happen but just in case
                .switchMap(isRunning ->
                        unlockOptions.getEntriesSetFlowable()
                                .map(ignored -> unlockOptions.hasConduitEntry())
                                .distinctUntilChanged()
                                .doOnNext(hasConduitUnlockOption -> {
                                    MyLog.i("TunnelManager: Conduit is running: " + isRunning +
                                            ", has conduit unlock option: " + hasConduitUnlockOption +
                                            ", updating tunnel config manager");
                                    tunnelConfigManager.updateConduitStateConditional(isRunning, hasConduitUnlockOption);
                                }))
                .subscribe();

        m_compositeDisposable.add(conduitStateObserver);
    }

    IBinder onBind(Intent intent) {
        return m_incomingMessenger.getBinder();
    }

    // Sends handshake intent and tunnel state updates to the client Activity,
    // Also updates service notification and forwards tunnel state data to purchaseVerifier.
    private Disposable connectionStatusUpdaterDisposable() {
        final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
        return connectionObservable()
                // Combine with latest state of hasPendingPsiCashPurchaseObservable
                .switchMap(pair -> {
                    TunnelState.ConnectionData.NetworkConnectionState networkConnectionState = pair.first;
                    boolean isRoutingThroughTunnel = pair.second;

                    // The tunnel is connected but we are not routing traffic through the tunnel yet,
                    // check in the following order if:
                    // a) we have a pending Speed Boost purchase,
                    // b) or we need to send a "Purchase Required" intent,
                    // c) or we need to send a landing page intent.
                    if (networkConnectionState == TunnelState.ConnectionData.NetworkConnectionState.CONNECTED && !isRoutingThroughTunnel) {
                        if (multiProcessPreferences.getBoolean(getContext().getString(R.string.preferencePendingSpeedBoostPurchase), false)) {
                            // If there is a pending Speed Boost purchase start routing immediately
                            m_vpnManager.routeThroughTunnel(m_tunnel.getLocalSocksProxyPort());
                            m_isRoutingThroughTunnelPublishRelay.accept(Boolean.TRUE);
                            // Do not emit downstream if we just started routing.
                            return Observable.empty();
                        }
                        // Handle unlock required case
                        if (unlockOptions.unlockRequired()) {
                            // If there is an unlock required, deliver the intent to the client Activity
                            // either by sending it directly or showing a notification
                            if (!unlockUIDeliveredThisSession ) {
                                unlockUIDeliveredThisSession = true;
                                deliverUnlockRequiredUI();
                            }
                            // Command the service to stop immediately if unlock is required.
                            signalStopService();
                            // Do not emit downstream
                            return Observable.empty();
                        } else {
                            // If there is no unlock required, we should cancel the unlock required notification
                            // and continue to handling landing pages.
                            cancelUnlockRequiredNotification();
                        }

                        if (m_tunnelState.homePages != null && m_tunnelState.homePages.size() != 0) {
                            if (canSendIntentToActivity()) {
                                m_vpnManager.routeThroughTunnel(m_tunnel.getLocalSocksProxyPort());
                                sendHandshakeIntent();
                                m_isRoutingThroughTunnelPublishRelay.accept(Boolean.TRUE);
                                // Do not emit downstream if we are just started routing.
                                return Observable.empty();
                            }
                            // Emit CONNECTING and start waiting for an activity to bind
                            return waitSendIntentAndRouteThroughTunnelCompletable(this::sendHandshakeIntent)
                                    .<TunnelState.ConnectionData.NetworkConnectionState>toObservable()
                                    .startWith(TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
                        }
                        // No intents to send, just route through tunnel.
                        m_vpnManager.routeThroughTunnel(m_tunnel.getLocalSocksProxyPort());
                        m_isRoutingThroughTunnelPublishRelay.accept(Boolean.TRUE);
                        // Do not emit downstream if we are just started routing.
                        return Observable.empty();
                    }
                    return Observable.just(networkConnectionState);
                })
                .distinctUntilChanged()
                .doOnNext(networkConnectionState -> {
                    m_tunnelState.networkConnectionState = networkConnectionState;
                    sendClientMessage(ServiceToClientMessage.TUNNEL_CONNECTION_STATE.ordinal(), getTunnelStateBundle());
                    // Don't update notification to CONNECTING, etc., when a stop was commanded.
                    if (!m_isStopping.get()) {
                        postServiceNotification(true, networkConnectionState);
                    }

                    // Send tunnel state updates to purchase verifier.
                    TunnelState tunnelState;
                    if (m_tunnelState.isRunning) {
                        TunnelState.ConnectionData connectionData = TunnelState.ConnectionData.builder()
                                .setNetworkConnectionState(m_tunnelState.networkConnectionState)
                                .setClientRegion(m_tunnelState.clientRegion)
                                .setClientVersion(EmbeddedValues.CLIENT_VERSION)
                                .setPropagationChannelId(EmbeddedValues.PROPAGATION_CHANNEL_ID)
                                .setSponsorId(m_tunnelState.sponsorId)
                                .setHttpPort(m_tunnelState.listeningLocalHttpProxyPort)
                                .setHomePages(m_tunnelState.homePages)
                                .build();
                        tunnelState = TunnelState.running(connectionData);
                    } else {
                        tunnelState = TunnelState.stopped();
                    }
                    purchaseVerifier.onTunnelState(tunnelState);
                })
                .subscribe();

    }

    private Completable waitSendIntentAndRouteThroughTunnelCompletable(Runnable runnable) {
        return m_newClientPublishRelay
                // Test the activity client(s) again by pinging, block until there's at least one live client
                .filter(__ -> pingForActivity())
                .take(1)
                .ignoreElements()
                .doOnSubscribe(__ -> showOpenAppToFinishConnectingNotification())
                .doOnComplete(() -> {
                    m_vpnManager.routeThroughTunnel(m_tunnel.getLocalSocksProxyPort());
                    runnable.run();
                    m_isRoutingThroughTunnelPublishRelay.accept(Boolean.TRUE);
                })
                // Cancel "Open Psiphon to keep connecting" when completed or disposed
                .doFinally(this::cancelOpenAppToFinishConnectingNotification);
    }

    private boolean canSendIntentToActivity() {
        return Build.VERSION.SDK_INT < 29 || pingForActivity();
    }

    private void cancelOpenAppToFinishConnectingNotification() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(R.id.notification_id_open_app_to_keep_connecting);
        }
    }

    private void showOpenAppToFinishConnectingNotification() {
        if (mNotificationManager == null) {
            return;
        }

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_SERVER_ALERT_CHANNEL_ID);
        notificationBuilder
                .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                .setGroup(getContext().getString(R.string.alert_notification_group))
                .setContentTitle(getContext().getString(R.string.notification_title_action_required))
                .setContentText(getContext().getString(R.string.notification_text_open_psiphon_to_finish_connecting))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getContext().getString(R.string.notification_text_open_psiphon_to_finish_connecting)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(m_notificationPendingIntent);

        mNotificationManager.notify(R.id.notification_id_open_app_to_keep_connecting, notificationBuilder.build());
    }

    private void deliverUnlockRequiredUI() {
        // persist the unlock options
        String jsonString = unlockOptions.toJsonString();
        UnlockOptions.toFile(getContext(), jsonString);

        if (canSendIntentToActivity()) {
            try {
                m_notificationPendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                showUnlockRequiredNotification();
            }
        } else {
            showUnlockRequiredNotification();
        }
    }

    private void showUnlockRequiredNotification() {
        if (mNotificationManager == null) {
            return;
        }
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_SERVER_ALERT_CHANNEL_ID);
        notificationBuilder
                .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                .setGroup(getContext().getString(R.string.alert_notification_group))
                .setContentTitle(getContext().getString(R.string.notification_title_action_required))
                .setContentText(getContext().getString(R.string.notification_text_unlock_required_short))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(getContext().getString(R.string.notification_text_unlock_required_long)))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(m_notificationPendingIntent);

        mNotificationManager.notify(R.id.notification_id_unlock_required, notificationBuilder.build());
    }

    // Implementation of android.app.Service.onDestroy
    void onDestroy() {
        if (mNotificationManager != null) {
            // Cancel main service notification
            mNotificationManager.cancel(R.string.psiphon_service_notification_id);
            // Cancel upstream proxy error notification
            mNotificationManager.cancel(R.id.notification_id_upstream_proxy_error);
        }
        // Cancel potentially dangling disallowed traffic alert notification, but not the unlock
        // required notification, since it is supposed to be shown even after the tunnel is stopped
        // and service is destroyed.
        cancelDisallowedTrafficAlertNotification();

        stopAndWaitForTunnel();
        m_compositeDisposable.dispose();
        // Unregister host service for the VPN manager
        m_vpnManager.unregisterHostService();
        // Stop and dispose of all observable chains in the purchaseVerifier
        purchaseVerifier.stop();
    }

    private void cancelDisallowedTrafficAlertNotification() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(R.id.notification_id_disallowed_traffic_alert);
        }
    }

    private void cancelUnlockRequiredNotification() {
        if (mNotificationManager != null) {
            mNotificationManager.cancel(R.id.notification_id_unlock_required);
        }
    }

    void onRevoke() {
        MyLog.w(R.string.vpn_service_revoked, MyLog.Sensitivity.NOT_SENSITIVE);

        stopAndWaitForTunnel();
        PendingIntent vpnRevokedPendingIntent = getPendingIntent(m_parentService, INTENT_ACTION_VPN_REVOKED);
        // Try and foreground client activity with the vpnRevokedPendingIntent in order to notify user.
        // If Android < 10 or there is a live activity client then send the intent right away,
        // otherwise show a notification.
        if (Build.VERSION.SDK_INT < 29 || pingForActivity()) {
            try {
                vpnRevokedPendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                MyLog.w("vpnRevokedPendingIntent send failed: " + e);
            }
        } else {
            if (mNotificationManager == null) {
                return;
            }

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_CHANNEL_ID);
            notificationBuilder
                    .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                    .setGroup(getContext().getString(R.string.alert_notification_group))
                    .setContentTitle(getContext().getString(R.string.notification_title_vpn_revoked))
                    .setContentText(getContext().getString(R.string.notification_text_vpn_revoked))
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText(getContext().getString(R.string.notification_text_vpn_revoked)))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setContentIntent(vpnRevokedPendingIntent);
            mNotificationManager.notify(R.id.notification_id_vpn_revoked, notificationBuilder.build());
        }
    }

    private void stopAndWaitForTunnel() {
        if (m_tunnelThread == null) {
            return;
        }

        // The `signalStopService`, which performs the latch countdown, may have already been called.
        // If it has not been called, then manually attempt to count down the latch here.
        // If the countdown hasn't been initiated, the `join` call may block the calling thread, potentially delaying execution.
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }

        try {
            m_tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        m_tunnelThreadStopSignal = null;
        m_tunnelThread = null;
    }

    // signalStopService signals the runTunnel thread to stop. The thread will
    // self-stop the service. This is the preferred method for stopping the
    // Psiphon tunnel service:
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    public void signalStopService() {
        if (m_tunnelThreadStopSignal != null) {
            m_tunnelThreadStopSignal.countDown();
        }
        // Also set the persistent service running flag to false to prevent automatic restart upon app update.
        new AppPreferences(getContext()).put(getContext().getString(R.string.serviceRunningPreference), false);
    }

    private PendingIntent getPendingIntent(Context ctx, final String actionString) {
        return getPendingIntent(ctx, actionString, null);
    }

    private PendingIntent getPendingIntent(Context ctx, final String actionString, final Bundle extras) {
        // This comment is copied from MainActivity::HandleCurrentIntent
        //
        // MainActivity is exposed to other apps because it is declared as an entry point activity of the app in the manifest.
        // For the purpose of handling internal intents, such as handshake, etc., from the tunnel service we have declared a not
        // exported activity alias 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler' that should act as a proxy for MainActivity.
        // We expect our own intents have a component set to 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler', all other intents
        // should be ignored.
        Intent intent = new Intent();
        ComponentName intentComponentName = new ComponentName(m_parentService, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        intent.setComponent(intentComponentName);
        intent.setAction(actionString);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (extras != null) {
            intent.putExtras(extras);
        }

        return PendingIntent.getActivity(
                ctx,
                0,
                intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE :
                        PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification createNotification(
            boolean alert,
            TunnelState.ConnectionData.NetworkConnectionState networkConnectionState) {
        int iconID;
        CharSequence contentText;
        CharSequence ticker = null;
        int defaults = 0;

        if (networkConnectionState == TunnelState.ConnectionData.NetworkConnectionState.CONNECTED) {
            iconID = R.drawable.notification_icon_connected;
            switch (vpnAppsExclusionSetting) {
                case INCLUDE_APPS:
                    contentText = getContext().getResources()
                            .getQuantityString(R.plurals.psiphon_service_notification_message_vpn_include_apps,
                                    vpnAppsExclusionCount, vpnAppsExclusionCount);
                    break;
                case EXCLUDE_APPS:
                    contentText = getContext().getResources()
                            .getQuantityString(R.plurals.psiphon_service_notification_message_vpn_exclude_apps,
                                    vpnAppsExclusionCount, vpnAppsExclusionCount);
                    break;
                case ALL_APPS:
                default:
                    contentText = getContext().getString(R.string.psiphon_service_notification_message_vpn_all_apps);
                    break;
            }
        } else if (networkConnectionState == TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK) {
            iconID = R.drawable.notification_icon_waiting;
            contentText = getContext().getString(R.string.waiting_for_network_connectivity);
            ticker = getContext().getText(R.string.waiting_for_network_connectivity);
        } else {
            iconID = R.drawable.notification_icon_connecting_animation;
            contentText = getContext().getString(R.string.psiphon_service_notification_message_connecting);
            ticker = getContext().getText(R.string.psiphon_service_notification_message_connecting);
        }

        // Only add notification vibration and sound defaults from preferences
        // when user has access to Sound and Vibration in the app's settings.
        if (alert && Utils.supportsNotificationSound()) {
            final AppPreferences multiProcessPreferences = new AppPreferences(getContext());

            if (multiProcessPreferences.getBoolean(
                    getContext().getString(R.string.preferenceNotificationsWithSound), false)) {
                defaults |= Notification.DEFAULT_SOUND;
            }
            if (multiProcessPreferences.getBoolean(
                    getContext().getString(R.string.preferenceNotificationsWithVibrate), false)) {
                defaults |= Notification.DEFAULT_VIBRATE;
            }
        }

        Intent stopTunnelIntent = new Intent(getContext(), m_parentService.getClass());
        stopTunnelIntent.setAction(INTENT_ACTION_STOP_TUNNEL);
        PendingIntent stopTunnelPendingIntent = PendingIntent.getService(
                getContext(),
                0,
                stopTunnelIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : 0);
        NotificationCompat.Action notificationAction = new NotificationCompat.Action.Builder(
                R.drawable.ic_btn_stop,
                getContext().getString(R.string.stop),
                stopTunnelPendingIntent)
                .build();

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_CHANNEL_ID);
        return notificationBuilder
                .setSmallIcon(iconID)
                .setGroup(getContext().getString(R.string.status_notification_group))
                .setContentTitle(getContext().getText(R.string.app_name_psiphon_pro))
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setTicker(ticker)
                .setDefaults(defaults)
                .setContentIntent(m_notificationPendingIntent)
                .addAction(notificationAction)
                .setOngoing(true)
                .build();
    }

    /**
     * Update the context used to get resources with the passed context
     *
     * @param context the new context to use for resources
     */
    void updateContext(Context context) {
        m_context = context;
    }

    private synchronized void postServiceNotification(
            boolean alert,
            TunnelState.ConnectionData.NetworkConnectionState networkConnectionState) {
        if (mNotificationManager != null) {
            m_Handler.post(new Runnable() {
                @Override
                public void run() {
                    Notification notification = createNotification(alert, networkConnectionState);
                    mNotificationManager.notify(
                            R.string.psiphon_service_notification_id,
                            notification);
                }
            });
        }
    }

    private boolean isSelectedEgressRegionAvailable(List<String> availableRegions) {
        String selectedEgressRegion = tunnelConfigManager.getEgressRegion();
        if (selectedEgressRegion == null || selectedEgressRegion.equals(PsiphonConstants.REGION_CODE_ANY)) {
            // User region is either not set or set to 'Best Performance', do nothing
            return true;
        }

        for (String regionCode : availableRegions) {
            if (selectedEgressRegion.equals(regionCode)) {
                return true;
            }
        }
        return false;
    }

    private static class MessengerWrapper {
        @NonNull
        Messenger messenger;
        boolean isActivity;

        MessengerWrapper(@NonNull Messenger messenger, Bundle data) {
            this.messenger = messenger;
            if (data != null) {
                isActivity = data.getBoolean(IS_CLIENT_AN_ACTIVITY, false);
            }
        }

        void send(Message message) throws RemoteException {
            messenger.send(message);
        }
    }

    private final Messenger m_incomingMessenger = new Messenger(
            new IncomingMessageHandler(this));
    private final HashMap<Integer, MessengerWrapper> mClients = new HashMap<>();


    private static class IncomingMessageHandler extends Handler {
        private final WeakReference<TunnelManager> mTunnelManager;
        private final ClientToServiceMessage[] csm = ClientToServiceMessage.values();

        IncomingMessageHandler(TunnelManager manager) {
            mTunnelManager = new WeakReference<>(manager);
        }

        @Override
        public void handleMessage(Message msg) {
            TunnelManager manager = mTunnelManager.get();
            switch (csm[msg.what]) {
                case REGISTER:
                    if (manager != null) {
                        if (msg.replyTo == null) {
                            MyLog.w("Error registering a client: client's messenger is null.");
                            return;
                        }
                        MessengerWrapper client = new MessengerWrapper(msg.replyTo, msg.getData());
                        // Respond immediately to the new client with current connection state and
                        // data stats. All following distinct tunnel connection updates will be provided
                        // by an Rx connectionStatusUpdaterDisposable() subscription to all clients.
                        List<Message> messageList = new ArrayList<>();
                        messageList.add(manager.composeClientMessage(ServiceToClientMessage.TUNNEL_CONNECTION_STATE.ordinal(),
                                manager.getTunnelStateBundle()));
                        messageList.add(manager.composeClientMessage(ServiceToClientMessage.DATA_TRANSFER_STATS.ordinal(),
                                manager.getDataTransferStatsBundle()));
                        for (Message message : messageList) {
                            try {
                                client.send(message);
                            } catch (RemoteException e) {
                                // Client is dead, do not add it to the clients list
                                return;
                            }
                        }
                        manager.mClients.put(msg.replyTo.hashCode(), client);
                        manager.m_newClientPublishRelay.accept(new Object());

                        // Note that we no longer triggering purchaseVerifier.queryAllPurchases here because the purchaseVerifier
                        // listens on purchase updates provided by GooglePlayBillingHelper.startObservePurchasesUpdates()
                        // which should trigger the verification process for new purchases.
                    }
                    break;

                case UNREGISTER:
                    if (manager != null) {
                        manager.mClients.remove(msg.replyTo.hashCode());
                    }
                    break;

                case STOP_SERVICE:
                    if (manager != null) {
                        // Ignore the message if the sender is not registered
                        if (manager.mClients.get(msg.replyTo.hashCode()) == null) {
                            return;
                        }
                        // Do not send any more messages after a stop was commanded.
                        // Client side will receive a ServiceConnection.onServiceDisconnected callback
                        // when the service finally stops.
                        manager.mClients.clear();
                        manager.signalStopService();
                    }
                    break;

                case RESTART_TUNNEL:
                    if (manager != null) {
                        // Ignore the message if the sender is not registered
                        if (manager.mClients.get(msg.replyTo.hashCode()) == null) {
                            return;
                        }

                        // On a restart, a new tunnel configuration is created and emitted via tunnelConfigManager.observeTunnelConfig()
                        // This emission automatically triggers a tunnel restart through the Rx subscription in the onStartCommand() method.
                        MyLog.i("TunnelManager: received restart tunnel message");
                        manager.m_compositeDisposable.add(
                                manager.tunnelConfigManager.initConfiguration(manager.speedBoostStateSingle(),
                                                manager.conduitStateSingle(),
                                                manager.deviceLocationSingle(),
                                                manager.purchaseVerifier.subscriptionStateSingle())
                                        .subscribe());
                    }
                    break;

                case CHANGED_LOCALE:
                    if (manager != null) {
                        // Ignore the message if the sender is not registered
                        if (manager.mClients.get(msg.replyTo.hashCode()) == null) {
                            return;
                        }
                        setLocale(manager);
                    }
                    break;

                case NFC_CONNECTION_INFO_EXCHANGE_EXPORT:
                    if (manager != null) {
                        MessengerWrapper client = manager.mClients.get(msg.replyTo.hashCode());
                        if (client != null) {
                            String exportExchangePayload = manager.m_tunnel.exportExchangePayload();
                            Bundle bundle = new Bundle();
                            bundle.putString(DATA_NFC_CONNECTION_INFO_EXCHANGE, exportExchangePayload);
                            Message message = manager.composeClientMessage(
                                    ServiceToClientMessage.NFC_CONNECTION_INFO_EXCHANGE_EXPORT.ordinal(),
                                    bundle);
                            try {
                                client.send(message);
                            } catch (RemoteException ignored) {
                            }
                        }
                    }
                    break;

                case NFC_CONNECTION_INFO_EXCHANGE_IMPORT:
                    if (manager != null) {
                        Bundle bundle = msg.getData();
                        String importExchangePayload = bundle.getString(DATA_NFC_CONNECTION_INFO_EXCHANGE);
                        manager.m_tunnel.importExchangePayload(importExchangePayload);
                    }
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    }

    // Get the initial conduit state and return a boolean indicating whether the conduit is running
    // Note that we are not using a member variable for the conduit state single because
    // ConduitStateManager.stateFlowable() should not be reused across multiple calls.
    private Single<Boolean> conduitStateSingle() {
        return ConduitStateManager.newManager(getContext()).stateFlowable()
                // Filter out UNKNOWN states
                .filter(state -> state.status() != ConduitState.Status.UNKNOWN)
                // Wait for up to 1 second for the first state, explicit timeout error
                .timeout(1000, java.util.concurrent.TimeUnit.MILLISECONDS,
                        Flowable.error(new TimeoutException("Conduit state timeout")))
                .firstOrError()
                .doOnSuccess(state -> MyLog.i("TunnelManager: initial Conduit state: " + state))
                // Any state other than RUNNING is considered not running
                .map(state -> state.status() == ConduitState.Status.RUNNING ? Boolean.TRUE : Boolean.FALSE)
                // If there is an error, log it and treat it as Conduit not running
                .onErrorReturn(e -> {
                    MyLog.e("TurnelManager: error getting initial Conduit state: " + e);
                    return Boolean.FALSE;
                });
    }

    // Get the initial speed boost by checking if there are any persisted authorizations with
    // the access type SPEED_BOOST
    private Single<Boolean> speedBoostStateSingle() {
        return Single.fromCallable(() -> {
                    List<Authorization> authorizations = Authorization.geAllPersistedAuthorizations(getContext());
                    return authorizations.stream()
                            .anyMatch(auth -> Authorization.ACCESS_TYPE_SPEED_BOOST.equals(auth.accessType()));
                })
                .subscribeOn(Schedulers.io());
    }

    // Get persisted device location precision and return a GeoHash string for the device location
    // with the given precision
    private Single <String> deviceLocationSingle() {
        return Single.fromCallable(() -> {
                    AppPreferences preferences = new AppPreferences(getContext());
                    return preferences.getInt(getContext().getString(R.string.deviceLocationPrecisionParameter), 0);
                })
                .flatMap(deviceLocationPrecision -> Location.getGeoHashSingle(getContext(), deviceLocationPrecision, 1000))
                .doOnError(e -> MyLog.e("Error getting device location: " + e))
                .onErrorReturnItem("");
    }

    private static void setLocale(TunnelManager manager) {
        LocaleManager localeManager = LocaleManager.getInstance(manager.m_parentService);
        String languageCode = localeManager.getLanguage();
        if (localeManager.isSystemLocale(languageCode)) {
            manager.m_context = localeManager.resetToSystemLocale(manager.m_parentService);
        } else {
            manager.m_context = localeManager.setNewLocale(manager.m_parentService, languageCode);
        }
        manager.updateNotifications();
    }

    private Message composeClientMessage(int what, Bundle data) {
        Message msg = Message.obtain(null, what);
        if (data != null) {
            msg.setData(data);
        }
        return msg;
    }

    private void sendClientMessage(int what, Bundle data) {
        Message msg = composeClientMessage(what, data);
        for (Iterator i = mClients.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry pair = (Map.Entry) i.next();
            MessengerWrapper messenger = (MessengerWrapper) pair.getValue();
            try {
                messenger.send(msg);
            } catch (RemoteException e) {
                // The client is dead.  Remove it from the list;
                i.remove();
            }
        }
    }

    private boolean pingForActivity() {
        Message msg = composeClientMessage(ServiceToClientMessage.PING.ordinal(), null);
        for (Map.Entry<Integer, MessengerWrapper> entry : mClients.entrySet()) {
            MessengerWrapper messenger = entry.getValue();
            if (messenger.isActivity) {
                try {
                    messenger.send(msg);
                    return true;
                } catch (RemoteException ignore) {
                }
            }
        }
        return false;
    }

    private void sendHandshakeIntent() {
        PendingIntent handshakePendingIntent = getPendingIntent(m_parentService, INTENT_ACTION_HANDSHAKE, getTunnelStateBundle());
        try {
            handshakePendingIntent.send();
        } catch (PendingIntent.CanceledException e) {
            MyLog.w("handshakePendingIntent send failed: " + e);
        }
    }

    private Bundle getTunnelStateBundle() {
        // Update with the latest sponsorId from the tunnel config
        m_tunnelState.sponsorId = tunnelConfigManager.getSponsorId();

        Bundle data = new Bundle();
        data.putBoolean(DATA_TUNNEL_STATE_IS_RUNNING, m_tunnelState.isRunning);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_SOCKS_PROXY_PORT, m_tunnelState.listeningLocalSocksProxyPort);
        data.putInt(DATA_TUNNEL_STATE_LISTENING_LOCAL_HTTP_PROXY_PORT, m_tunnelState.listeningLocalHttpProxyPort);
        data.putSerializable(DATA_TUNNEL_STATE_NETWORK_CONNECTION_STATE, m_tunnelState.networkConnectionState);
        data.putString(DATA_TUNNEL_STATE_CLIENT_REGION, m_tunnelState.clientRegion);
        data.putString(DATA_TUNNEL_STATE_SPONSOR_ID, m_tunnelState.sponsorId);
        data.putStringArrayList(DATA_TUNNEL_STATE_HOME_PAGES, m_tunnelState.homePages);
        data.putLong(DATA_TUNNEL_STATE_UPSTREAM_RATE_LIMIT, m_tunnelState.upstreamRateLimitBytesPerSecond);
        data.putLong(DATA_TUNNEL_STATE_DOWNSTREAM_RATE_LIMIT, m_tunnelState.downstreamRateLimitBytesPerSecond);
        return data;
    }

    private Bundle getDataTransferStatsBundle() {
        Bundle data = new Bundle();
        data.putLong(DATA_TRANSFER_STATS_CONNECTED_TIME, DataTransferStats.getDataTransferStatsForService().m_connectedTime);
        data.putLong(DATA_TRANSFER_STATS_TOTAL_BYTES_SENT, DataTransferStats.getDataTransferStatsForService().m_totalBytesSent);
        data.putLong(DATA_TRANSFER_STATS_TOTAL_BYTES_RECEIVED, DataTransferStats.getDataTransferStatsForService().m_totalBytesReceived);
        data.putParcelableArrayList(DATA_TRANSFER_STATS_SLOW_BUCKETS, DataTransferStats.getDataTransferStatsForService().m_slowBuckets);
        data.putLong(DATA_TRANSFER_STATS_SLOW_BUCKETS_LAST_START_TIME, DataTransferStats.getDataTransferStatsForService().m_slowBucketsLastStartTime);
        data.putParcelableArrayList(DATA_TRANSFER_STATS_FAST_BUCKETS, DataTransferStats.getDataTransferStatsForService().m_fastBuckets);
        data.putLong(DATA_TRANSFER_STATS_FAST_BUCKETS_LAST_START_TIME, DataTransferStats.getDataTransferStatsForService().m_fastBucketsLastStartTime);
        return data;
    }

    private final static String LEGACY_SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";

    static String getServerEntries(Context context) {
        StringBuilder list = new StringBuilder();

        for (String encodedServerEntry : EmbeddedValues.EMBEDDED_SERVER_LIST) {
            list.append(encodedServerEntry);
            list.append("\n");
        }

        // Delete legacy server entries if they exist
        context.deleteFile(LEGACY_SERVER_ENTRY_FILENAME);

        return list.toString();
    }

    private Handler sendDataTransferStatsHandler = new Handler();
    private final long sendDataTransferStatsIntervalMs = 1000;
    private Runnable sendDataTransferStats = new Runnable() {
        @Override
        public void run() {
            sendClientMessage(ServiceToClientMessage.DATA_TRANSFER_STATS.ordinal(), getDataTransferStatsBundle());
            sendDataTransferStatsHandler.postDelayed(this, sendDataTransferStatsIntervalMs);
        }
    };

    private void runTunnel() {
        Utils.initializeSecureRandom();
        // Also set locale
        setLocale(this);

        final String stdErrRedirectPath = PsiphonCrashService.getStdRedirectPath(m_parentService);
        NDCrash.nativeInitializeStdErrRedirect(stdErrRedirectPath);

        m_isStopping.set(false);
        m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
        m_isRoutingThroughTunnelPublishRelay.accept(Boolean.FALSE);

        MyLog.i(R.string.starting_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

        m_tunnelState.homePages.clear();

        DataTransferStats.getDataTransferStatsForService().startSession();
        sendDataTransferStatsHandler.postDelayed(sendDataTransferStats, sendDataTransferStatsIntervalMs);

        try {
            m_vpnManager.vpnEstablish();
            MyLog.i(R.string.vpn_service_running, MyLog.Sensitivity.NOT_SENSITIVE);

            m_tunnel.setVpnMode(true);
            m_tunnel.startTunneling(getServerEntries(m_parentService));
            try {
                m_tunnelThreadStopSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IllegalStateException |
                 IllegalArgumentException |
                 SecurityException |
                 PsiphonTunnel.Exception e) {
            String errorMessage = e.getMessage();
            MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, errorMessage);
            if ((errorMessage.startsWith("get package uid:") || errorMessage.startsWith("getPackageUid:"))
                    && errorMessage.endsWith("android.permission.INTERACT_ACROSS_USERS.")) {
                MyLog.i(R.string.vpn_exclusions_conflict, MyLog.Sensitivity.NOT_SENSITIVE);
            }
        } finally {
            MyLog.i(R.string.stopping_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            m_isStopping.set(true);
            m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
            m_isRoutingThroughTunnelPublishRelay.accept(false);
            m_vpnManager.vpnTeardown();
            m_tunnel.stop();

            sendDataTransferStatsHandler.removeCallbacks(sendDataTransferStats);
            DataTransferStats.getDataTransferStatsForService().stop();

            MyLog.i(R.string.stopped_tunnel, MyLog.Sensitivity.NOT_SENSITIVE);

            // Stop service
            m_parentService.stopForeground(true);
            m_parentService.stopSelf();
        }
    }

    private void onRestartTunnel() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    m_tunnel.restartPsiphon();
                } catch (PsiphonTunnel.Exception e) {
                    MyLog.e(R.string.start_tunnel_failed, MyLog.Sensitivity.NOT_SENSITIVE, e.getMessage());
                }
            }
        });
    }

    @Override
    public Context getContext() {
        return m_context;
    }

    @Override
    public void bindToDevice(long fileDescriptor) {
        if (m_parentService instanceof VpnService) {
            if (!((VpnService) m_parentService).protect((int) fileDescriptor)) {
                throw new RuntimeException("VpnService.protect() failed");
            }
        }
    }

    @Override
    public Builder vpnServiceBuilder() {
        // Create a new VpnService.Builder instance and set the session name to the app name^M
        Builder vpnBuilder = ((VpnService) m_parentService)
                .new Builder()
                .setSession(getContext().getString(R.string.app_name));
        // only can control tunneling post lollipop
        if (Build.VERSION.SDK_INT < LOLLIPOP) {
            return vpnBuilder;
        }

//        Added on API 29:
//        Marks the VPN network as metered. A VPN network is classified as metered when the user is
//        sensitive to heavy data usage due to monetary costs and/or data limitations. In such cases,
//        you should set this to true so that apps on the system can avoid doing large data transfers.
//        Otherwise, set this to false. Doing so would cause VPN network to inherit its meteredness
//        from its underlying networks.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vpnBuilder.setMetered(false);
        }

        Context context = getContext();
        PackageManager pm = context.getPackageManager();

        switch (VpnAppsUtils.getVpnAppsExclusionMode(context)) {
            case ALL_APPS:
                vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.ALL_APPS;
                vpnAppsExclusionCount = 0;
                break;

            case INCLUDE_APPS:
                Set<String> includedApps = VpnAppsUtils.getUserAppsIncludedInVpn(context);
                int includedAppsCount = includedApps.size();
                // allow the selected apps
                for (Iterator<String> iterator = includedApps.iterator(); iterator.hasNext(); ) {
                    String packageId = iterator.next();
                    // VpnBuilder.addAllowedApplication() is supposed to throw NameNotFoundException
                    // in case the app is no longer available but we observed this is not the case.
                    // Therefore we will perform our own check first
                    if (!PackageHelper.isPackageInstalled(pm, packageId)) {
                        // If the app is no longer installed, remove it from the list
                        iterator.remove();
                        continue;
                    }

                    try {
                        vpnBuilder.addAllowedApplication(packageId);
                        MyLog.i(R.string.individual_app_included, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, packageId);
                    } catch (PackageManager.NameNotFoundException e) {
                        iterator.remove();
                        MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to allowed VPN applications, package not found");
                    }
                }
                // If some packages are no longer installed, updated persisted set
                if (includedAppsCount != includedApps.size()) {
                    VpnAppsUtils.setUserAppsToIncludeInVpn(context, includedApps);
                    includedAppsCount = includedApps.size();
                }

                if (includedAppsCount > 0) {
                    // If there are included apps, set the exclusion mode to INCLUDE_APPS
                    // and add the default included apps to the list
                    vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.INCLUDE_APPS;
                    Set<String> defaultIncludedApps = VpnAppsUtils.getDefaultAppsIncludedInVpn();
                    for (String packageId : defaultIncludedApps) {
                        // Check if the app is installed before checking the signature
                        if (!PackageHelper.isPackageInstalled(pm, packageId)) {
                            continue;
                        }

                        if (PackageHelper.verifyTrustedPackage(pm, packageId)) {
                            try {
                                vpnBuilder.addAllowedApplication(packageId);
                                // Output the package name of the app that is included by default; do not update the count
                                MyLog.i(R.string.individual_app_included, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                                        packageId);
                            } catch (PackageManager.NameNotFoundException e) {
                                MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to allowed VPN applications, package not found");
                            }
                        } else {
                            MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to allowed VPN applications, trust verification failed");
                        }
                    }

                    // Also always include the Psiphon app itself in this mode
                    // Note that we are not checking if the app is installed here, we trust that self is always installed
                    // and log a warning if it is not (should never happen)
                    try {
                        vpnBuilder.addAllowedApplication(context.getPackageName());
                        MyLog.i(R.string.individual_app_included, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                                context.getPackageName());
                    } catch (PackageManager.NameNotFoundException e) {
                        MyLog.w("TunnelManager: VpnBuilder: failed to add self to allowed VPN applications, package not found");
                    }
                } else {
                    // If there are no apps to include, set the exclusion mode to ALL_APPS
                    // Note that we will be excluding default excluded apps in this case later.
                    vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.ALL_APPS;
                }

                vpnAppsExclusionCount = includedAppsCount;
                break;

            case EXCLUDE_APPS:
                Set<String> excludedApps = VpnAppsUtils.getUserAppsExcludedFromVpn(context);
                int excludedAppsCount = excludedApps.size();
                // disallow the selected apps
                for (Iterator<String> iterator = excludedApps.iterator(); iterator.hasNext(); ) {
                    String packageId = iterator.next();
                    // VpnBuilder.addDisallowedApplication() is supposed to throw NameNotFoundException
                    // in case the app is no longer available but we observed this is not the case.
                    // Therefore we will perform our own check first.
                    if (!PackageHelper.isPackageInstalled(pm, packageId)) {
                        // If the app is no longer installed, remove it from the list
                        iterator.remove();
                        continue;
                    }

                    try {
                        vpnBuilder.addDisallowedApplication(packageId);
                        MyLog.i(R.string.individual_app_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                                packageId);
                    } catch (PackageManager.NameNotFoundException e) {
                        iterator.remove();
                        MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to disallowed VPN applications, package not found");
                    }
                }
                // If some packages are no longer installed update persisted set
                if (excludedAppsCount != excludedApps.size()) {
                    VpnAppsUtils.setUserAppsToExcludeFromVpn(context, excludedApps);
                    excludedAppsCount = excludedApps.size();
                }

                if (excludedAppsCount > 0) {
                    // If there are excluded apps, set the exclusion mode to EXCLUDE_APPS
                    // and add the default excluded apps to the list
                    vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.EXCLUDE_APPS;
                    Set<String> defaultExcludedApps = VpnAppsUtils.getDefaultAppsExcludedFromVpn();

                    for (String packageId : defaultExcludedApps) {
                        // Check if the app is installed before checking the signature
                        if (!PackageHelper.isPackageInstalled(pm, packageId)) {
                            continue;
                        }

                        if (PackageHelper.verifyTrustedPackage(pm, packageId)) {
                            try {
                                vpnBuilder.addDisallowedApplication(packageId);
                                // Output the package name of the app that is excluded by default; do not update the count
                                MyLog.i(R.string.individual_app_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                                        packageId);
                            } catch (PackageManager.NameNotFoundException e) {
                                MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to disallowed VPN applications, package not found");
                            }
                        } else {
                            MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to disallowed VPN applications, trust verification failed");
                        }
                    }
                } else {
                    // If there are no apps to exclude, set the exclusion mode to ALL_APPS
                    // Note that we will be excluding default excluded apps in this case later.
                    vpnAppsExclusionSetting = VpnAppsUtils.VpnAppsExclusionSetting.ALL_APPS;
                }

                vpnAppsExclusionCount = excludedAppsCount;
                break;
        }

        // If we are in ALL_APPS mode then disallow apps that should not be tunneled by default if the device supports
        // VPN exclusions.
        if (vpnAppsExclusionSetting == VpnAppsUtils.VpnAppsExclusionSetting.ALL_APPS) {
            if (Utils.supportsVpnExclusions()) {
                Set<String> defaultExcludedApps = VpnAppsUtils.getDefaultAppsExcludedFromVpn();
                // If there are no default excluded apps, output no apps excluded message
                if (defaultExcludedApps.isEmpty()) {
                    MyLog.i(R.string.no_apps_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS);
                } else {
                    for (String packageId : defaultExcludedApps) {
                        // Check if the app is installed before checking the signature
                        if (!PackageHelper.isPackageInstalled(pm, packageId)) {
                            continue;
                        }
                        if (PackageHelper.verifyTrustedPackage(pm, packageId)) {
                            try {
                                vpnBuilder.addDisallowedApplication(packageId);
                                // Output the package name of the app that is excluded
                                MyLog.i(R.string.individual_app_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS,
                                        packageId);
                            } catch (PackageManager.NameNotFoundException e) {
                                MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to disallowed VPN applications, package not found");
                            }
                        } else {
                            MyLog.w("TunnelManager: VpnBuilder: failed to add " + packageId + " to disallowed VPN applications, trust verification failed");
                        }
                    }
                }
            } else {
                // If the device does not support VPN exclusions, output no apps excluded message
                MyLog.i(R.string.no_apps_excluded, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS);
            }
        }

        return vpnBuilder;
    }

    /**
     * Create a tunnel-core config suitable for different tasks (i.e., the main Psiphon app
     * tunnel, the UpgradeChecker temp tunnel and the FeedbackWorker upload operation).
     *
     * @param context
     * @param tunnelConfigManager Config manager to get the sponsor ID and egress region and other config values.
     * @param useUpstreamProxy    If an upstream proxy has been configured, include it in the returned
     *                            config. Used to omit the proxy from the returned config when network
     *                            operations will already be tunneled over a connection which uses the
     *                            configured upstream proxy.
     * @param tempTunnelName      null if not a temporary tunnel. If set, must be a valid to use in file path.
     * @return JSON string of config. null on error.
     */
    public static String buildTunnelCoreConfig(
            Context context,
            TunnelConfigManager tunnelConfigManager,
            boolean useUpstreamProxy,
            List<Authorization> authorizations,
            String tempTunnelName) {
        boolean temporaryTunnel = tempTunnelName != null && !tempTunnelName.isEmpty();

        JSONObject json = new JSONObject();

        try {

            json.put("ClientVersion", EmbeddedValues.CLIENT_VERSION);

            if (authorizations != null && authorizations.size() > 0) {
                JSONArray jsonArray = new JSONArray();
                for (Authorization a : authorizations) {
                    jsonArray.put(a.base64EncodedAuthorization());
                }
                json.put("Authorizations", jsonArray);
            }

            json.put("PropagationChannelId", EmbeddedValues.PROPAGATION_CHANNEL_ID);

            json.put("SponsorId", tunnelConfigManager.getSponsorId());

            json.put("RemoteServerListURLs", new JSONArray(EmbeddedValues.REMOTE_SERVER_LIST_URLS_JSON));

            json.put("ObfuscatedServerListRootURLs", new JSONArray(EmbeddedValues.OBFUSCATED_SERVER_LIST_ROOT_URLS_JSON));

            json.put("RemoteServerListSignaturePublicKey", EmbeddedValues.REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY);

            json.put("ServerEntrySignaturePublicKey", EmbeddedValues.SERVER_ENTRY_SIGNATURE_PUBLIC_KEY);

            json.put("ExchangeObfuscationKey", EmbeddedValues.SERVER_ENTRY_EXCHANGE_OBFUSCATION_KEY);

            if (useUpstreamProxy) {
                if (UpstreamProxySettings.getUseHTTPProxy(context)) {
                    if (UpstreamProxySettings.getProxySettings(context) != null) {
                        json.put("UpstreamProxyUrl", UpstreamProxySettings.getUpstreamProxyUrl(context));
                    }
                    json.put("UpstreamProxyCustomHeaders", UpstreamProxySettings.getUpstreamProxyCustomHeaders(context));
                }
            }

            json.put("EmitDiagnosticNotices", true);

            json.put("EmitDiagnosticNetworkParameters", true);

            json.put("FeedbackUploadURLs", new JSONArray(EmbeddedValues.FEEDBACK_DIAGNOSTIC_INFO_UPLOAD_URLS_JSON));
            json.put("FeedbackEncryptionPublicKey", EmbeddedValues.FEEDBACK_ENCRYPTION_PUBLIC_KEY);
            json.put("EnableFeedbackUpload", true);

            json.put("AdditionalParameters", EmbeddedValues.ADDITIONAL_PARAMETERS);

            // If this is a temporary tunnel (like for UpgradeChecker) we need to override some of
            // the implicit config values.
            if (temporaryTunnel) {
                File tempTunnelDir = new File(context.getFilesDir(), tempTunnelName);
                if (!tempTunnelDir.exists()
                        && !tempTunnelDir.mkdirs()) {
                    // Failed to create DB directory
                    return null;
                }

                // On Android, these directories must be set to the app private storage area.
                // The Psiphon library won't be able to use its current working directory
                // and the standard temporary directories do not exist.
                json.put("DataRootDirectory", tempTunnelDir.getAbsolutePath());

                json.put("MigrateDataStoreDirectory", tempTunnelDir.getAbsolutePath());

                File remoteServerListDownload = new File(tempTunnelDir, "remote_server_list");
                json.put("MigrateRemoteServerListDownloadFilename", remoteServerListDownload.getAbsolutePath());

                File oslDownloadDir = new File(tempTunnelDir, "osl");
                if (oslDownloadDir.exists()) {
                    json.put("MigrateObfuscatedServerListDownloadDirectory", oslDownloadDir.getAbsolutePath());
                }

                // This number is an arbitrary guess at what might be the "best" balance between
                // wake-lock-battery-burning and successful upgrade downloading.
                // Note that the fall-back untunneled upgrade download doesn't start for 30 secs,
                // so we should be waiting longer than that.
                json.put("EstablishTunnelTimeoutSeconds", 300);

                json.put("TunnelWholeDevice", 0);
                json.put("EgressRegion", "");
            } else {
                String egressRegion = tunnelConfigManager.getEgressRegion();
                MyLog.i("EgressRegion", "regionCode", egressRegion);
                json.put("EgressRegion", egressRegion);
            }

            if (tunnelConfigManager.isDisableTimeouts()) {
                //disable timeouts
                MyLog.i("DisableTimeouts", "disableTimeouts", true);
                json.put("NetworkLatencyMultiplierLambda", 0.1);
            }

            json.put("EmitServerAlerts", true);

            JSONArray clientFeaturesJsonArray = new JSONArray();

            AppPreferences mp = new AppPreferences(context);
            int deviceLocationPrecision = mp.getInt(context.getString(R.string.deviceLocationPrecisionParameter), 0);
            if (deviceLocationPrecision > 0) {
                if (ContextCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED) {
                    clientFeaturesJsonArray.put("coarse-location");
                }
            }
            if (Utils.getUnsafeTrafficAlertsOptInState(context)) {
                clientFeaturesJsonArray.put("unsafe-traffic-alerts");
            }
            if (clientFeaturesJsonArray.length() > 0) {
                json.put("ClientFeatures", clientFeaturesJsonArray);
            }

            json.put("DNSResolverAlternateServers", new JSONArray("[\"1.1.1.1\", \"1.0.0.1\", \"8.8.8.8\", \"8.8.4.4\"]"));

            if (!TextUtils.isEmpty(tunnelConfigManager.getDeviceLocation())) {
                json.put("DeviceLocation", tunnelConfigManager.getDeviceLocation());
            }

            json.put("EmitBytesTransferred", true);

            return json.toString();
        } catch (JSONException e) {
            return null;
        }
    }

     // This observable emits a pair consisting of the latest NetworkConnectionState state and a
     // Boolean representing whether we are routing the traffic via tunnel.
     // Emits a new pair every time when either of the sources emits a new value.
    private Observable<Pair<TunnelState.ConnectionData.NetworkConnectionState, Boolean>> connectionObservable() {
        return Observable.combineLatest(m_networkConnectionStatePublishRelay,
                m_isRoutingThroughTunnelPublishRelay,
                ((BiFunction<TunnelState.ConnectionData.NetworkConnectionState, Boolean,
                        Pair<TunnelState.ConnectionData.NetworkConnectionState, Boolean>>) Pair::new))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .distinctUntilChanged();
    }

    /**
     * Configure tunnel with appropriate client platform affixes (i.e., the main Psiphon app
     * tunnel and the UpgradeChecker temp tunnel).
     *
     * @param tunnel
     * @param clientPlatformPrefix null if not applicable (i.e., for main Psiphon app); should be provided
     *                             for temp tunnels. Will be prepended to standard client platform value.
     */
    static public void setPlatformAffixes(PsiphonTunnel tunnel, String clientPlatformPrefix) {
        String prefix = "";
        if (clientPlatformPrefix != null && !clientPlatformPrefix.isEmpty()) {
            prefix = clientPlatformPrefix;
        }

        String suffix = Utils.getClientPlatformSuffix();

        tunnel.setClientPlatformAffixes(prefix, suffix);
    }

    @Override
    public String getPsiphonConfig() {
        setPlatformAffixes(m_tunnel, null);
        m_tunnelConfigAuthorizations =
                Collections.unmodifiableList(Authorization.geAllPersistedAuthorizations(getContext()));

        String config = buildTunnelCoreConfig(getContext(),
                tunnelConfigManager,
                true,
                m_tunnelConfigAuthorizations,
                null);

        return config == null ? "" : config;
    }

    @Override
    public void onDiagnosticMessage(final String message) {
        // Get timestamp ASAP for improved accuracy.
        final Date now = new Date();
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.i(now, message);
            }
        });
    }

    @Override
    public void onAvailableEgressRegions(final List<String> regions) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                // regions are already sorted alphabetically by tunnel core
                AppPreferences mp = new AppPreferences(getContext());
                mp.put(RegionListPreference.KNOWN_REGIONS_PREFERENCE, TextUtils.join(",", regions));

                if (!isSelectedEgressRegionAvailable(regions)) {
                    // command service stop
                    signalStopService();

                    // Set region preference to PsiphonConstants.REGION_CODE_ANY
                    mp.put(m_parentService.getString(R.string.egressRegionPreference), PsiphonConstants.REGION_CODE_ANY);

                    // Send REGION_NOT_AVAILABLE intent,
                    // Activity intent handler will show "Region not available" toast and populate
                    // the region selector with new available regions
                    PendingIntent regionNotAvailablePendingIntent = getPendingIntent(m_parentService, INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE);

                    // If Android < 10 or there is a live activity client then send the intent right away,
                    // otherwise show a notification.
                    if (Build.VERSION.SDK_INT < 29 || pingForActivity()) {
                        try {
                            regionNotAvailablePendingIntent.send();
                        } catch (PendingIntent.CanceledException e) {
                            MyLog.w("regionNotAvailablePendingIntent send failed: " + e);
                        }
                    } else {
                        if (mNotificationManager == null) {
                            return;
                        }

                        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_CHANNEL_ID);
                        notificationBuilder
                                .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                                .setGroup(getContext().getString(R.string.alert_notification_group))
                                .setContentTitle(getContext().getString(R.string.notification_title_region_not_available))
                                .setContentText(getContext().getString(R.string.notification_text_region_not_available))
                                .setStyle(new NotificationCompat.BigTextStyle()
                                        .bigText(getContext().getString(R.string.notification_text_region_not_available)))
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .setContentIntent(regionNotAvailablePendingIntent);
                        mNotificationManager.notify(R.id.notification_id_region_not_available, notificationBuilder.build());
                    }
                }
                // UPDATE:
                // The region list preference view is created with the stored known regions list every time
                // before presenting, there is no need to notify the activity of the data change anymore.
            }
        });
    }

    @Override
    public void onSocksProxyPortInUse(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.e(R.string.socks_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
                signalStopService();
            }
        });
    }

    @Override
    public void onHttpProxyPortInUse(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.e(R.string.http_proxy_port_in_use, MyLog.Sensitivity.NOT_SENSITIVE, port);
                signalStopService();
            }
        });
    }

    @Override
    public void onListeningSocksProxyPort(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.i(R.string.socks_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
                m_tunnelState.listeningLocalSocksProxyPort = port;
            }
        });
    }

    @Override
    public void onListeningHttpProxyPort(final int port) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.i(R.string.http_proxy_running, MyLog.Sensitivity.NOT_SENSITIVE, port);
                m_tunnelState.listeningLocalHttpProxyPort = port;

                final AppPreferences multiProcessPreferences = new AppPreferences(getContext());
                multiProcessPreferences.put(
                        m_parentService.getString(R.string.current_local_http_proxy_port),
                        port);
            }
        });
    }

    @Override
    public void onUpstreamProxyError(final String message) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                // Display the error message only once, and continue trying to connect in
                // case the issue is temporary.
                if (m_lastUpstreamProxyErrorMessage == null || !m_lastUpstreamProxyErrorMessage.equals(message)) {
                    MyLog.w(R.string.upstream_proxy_error, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, message);
                    m_lastUpstreamProxyErrorMessage = message;

                    PendingIntent upstreamProxyErrorPendingIntent = getPendingIntent(m_parentService, INTENT_ACTION_UPSTREAM_PROXY_ERROR);

                    // If Android < 10 or there is a live activity client then send the intent right away,
                    // otherwise show a notification.
                    if (Build.VERSION.SDK_INT < 29 || pingForActivity()) {
                        try {
                            upstreamProxyErrorPendingIntent.send();
                        } catch (PendingIntent.CanceledException e) {
                            MyLog.w("upstreamProxyErrorPendingIntent send failed: " + e);
                        }
                    } else {
                        if (mNotificationManager == null) {
                            return;
                        }

                        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(getContext(), NOTIFICATION_CHANNEL_ID);
                        notificationBuilder
                                .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                                .setGroup(getContext().getString(R.string.alert_notification_group))
                                .setContentTitle(getContext().getString(R.string.notification_title_upstream_proxy_error))
                                .setContentText(getContext().getString(R.string.notification_text_upstream_proxy_error))
                                .setStyle(new NotificationCompat.BigTextStyle()
                                        .bigText(getContext().getString(R.string.notification_text_upstream_proxy_error)))
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setAutoCancel(true)
                                .setContentIntent(upstreamProxyErrorPendingIntent);
                        mNotificationManager.notify(R.id.notification_id_upstream_proxy_error, notificationBuilder.build());
                    }
                }
            }
        });
    }

    @Override
    public void onConnecting() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
                DataTransferStats.getDataTransferStatsForService().stop();
                m_tunnelState.homePages.clear();

                // Do not log "Connecting" if tunnel is stopping
                if (!m_isStopping.get()) {
                    MyLog.i(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);
                }
            }
        });
    }

    @Override
    public void onConnected() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                // Cancel any showing upstream proxy error notifications in case the issue was
                // temporary and connection still succeeded.
                if (mNotificationManager != null) {
                    mNotificationManager.cancel(R.id.notification_id_upstream_proxy_error);
                }

                DataTransferStats.getDataTransferStatsForService().startConnected();

                MyLog.i(R.string.tunnel_connected, MyLog.Sensitivity.NOT_SENSITIVE);

                m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.CONNECTED);
            }
        });
    }

    @Override
    public void onHomepage(final String url) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (String homePage : m_tunnelState.homePages) {
                    if (homePage.equals(url)) {
                        return;
                    }
                }
                m_tunnelState.homePages.add(url);
            }
        });
    }

    @Override
    public void onClientRegion(final String region) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_tunnelState.clientRegion = region;
            }
        });
    }

    @Override
    public void onUntunneledAddress(final String address) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                MyLog.i(R.string.untunneled_address, MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS, address);
            }
        });
    }

    @Override
    public void onBytesTransferred(final long sent, final long received) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                DataTransferStats.DataTransferStatsForService stats = DataTransferStats.getDataTransferStatsForService();
                stats.addBytesSent(sent);
                stats.addBytesReceived(received);
            }
        });
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK);
                MyLog.i(R.string.waiting_for_network_connectivity, MyLog.Sensitivity.NOT_SENSITIVE);
            }
        });
    }

    @Override
    public void onActiveAuthorizationIDs(List<String> acceptedAuthorizationIds) {
        m_Handler.post(() -> {
            // Subscriptions and time passes are handled by the purchase verifier.
            // Purchase verifier will handle the lists of accepted authorizations, requesting
            // new ones if needed, and updating tunnel config manager with the subscription state
            // depending on the verification result.
            purchaseVerifier.onActiveAuthorizationIDs(acceptedAuthorizationIds);

            // Build a list of accepted authorizations from the authorizations snapshot.
            List<Authorization> acceptedAuthorizations = new ArrayList<>();

            for (String Id : acceptedAuthorizationIds) {
                for (Authorization a : m_tunnelConfigAuthorizations) {
                    if (a.Id().equals(Id)) {
                        acceptedAuthorizations.add(a);
                        MyLog.i("TunnelManager::onActiveAuthorizationIDs: accepted authorization of accessType: " +
                                a.accessType() + ", expires: " +
                                Utils.getISO8601String(a.expires()));
                    }
                }
            }

            // Handle removal of expired authorizations
            // NOTE that the tunnel core does not re-read config values in case of automatic
            // reconnects so the m_tunnelConfigAuthorizations snapshot may contain authorizations
            // already removed from the persistent authorization storage.
            handleExpiredAuthorizations(acceptedAuthorizations);

            // Get access types of the accepted authorizations
            Set<String> accessTypes = acceptedAuthorizations.stream()
                    .map(Authorization::accessType)
                    .collect(Collectors.toSet());

            // Update tunnel config manager with speed boost authorization status.
            // If this update results in a change to the config, the tunnel config manager will emit the updated config
            // through Rx subscription to tunnelConfigManager.observeTunnelConfig(). This will automatically trigger a tunnel
            // restart with the new config.
            //
            // Note: Subscription state updates are handled separately by the purchase verifier above.
            boolean hasSpeedBoost = acceptedAuthorizations.stream()
                    .map(Authorization::accessType)
                    .anyMatch(accessType -> accessType.equals(Authorization.ACCESS_TYPE_SPEED_BOOST));

            MyLog.i("TunnelManager::onActiveAuthorizationIDs: user " +
                    (hasSpeedBoost ? "has" : "has no") + " speed boost auth, update speed boost state");

            tunnelConfigManager.updateSpeedBoostState(hasSpeedBoost);

            // Handle notifications based on current authorization states
            boolean hasSpeedBoostOrSubscription = accessTypes.contains(Authorization.ACCESS_TYPE_SPEED_BOOST) ||
                    accessTypes.contains(Authorization.ACCESS_TYPE_GOOGLE_SUBSCRIPTION) ||
                    accessTypes.contains(Authorization.ACCESS_TYPE_GOOGLE_SUBSCRIPTION_LIMITED);

            // If the user has a speed boost or subscription auth, cancel the unlock required notification and disallowed traffic alert
            if (hasSpeedBoostOrSubscription) {
                cancelUnlockRequiredNotification();
                cancelDisallowedTrafficAlertNotification();
            }
        });
    }

    private void handleExpiredAuthorizations(List<Authorization> acceptedAuthorizations) {
        if (m_tunnelConfigAuthorizations != null && !m_tunnelConfigAuthorizations.isEmpty()) {
            // Copy immutable config authorizations snapshot and build a list of not accepted
            // authorizations by removing all elements of the accepted authorizations list.
            List<Authorization> notAcceptedAuthorizations = new ArrayList<>(m_tunnelConfigAuthorizations);
            if (!acceptedAuthorizations.isEmpty()) {
                notAcceptedAuthorizations.removeAll(acceptedAuthorizations);
            }

            // Try to remove all not accepted authorizations from the persistent storage
            // NOTE: empty list check is performed and logged in Authorization::removeAuthorizations
            MyLog.i("TunnelManager::onActiveAuthorizationIDs: check not accepted authorizations");
            boolean hasChanged = Authorization.removeAuthorizations(getContext(), notAcceptedAuthorizations);
            if (hasChanged) {
                final AppPreferences mp = new AppPreferences(getContext());
                mp.put(m_parentService.getString(R.string.persistentAuthorizationsRemovedFlag), true);
                sendClientMessage(ServiceToClientMessage.AUTHORIZATIONS_REMOVED.ordinal(), null);
            }
        } else {
            MyLog.i("TunnelManager::onActiveAuthorizationIDs: current config authorizations list is empty");
        }
    }

    @Override
    public void onStoppedWaitingForNetworkConnectivity() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_networkConnectionStatePublishRelay.accept(TunnelState.ConnectionData.NetworkConnectionState.CONNECTING);
                // Do not log "Connecting" if tunnel is stopping
                if (!m_isStopping.get()) {
                    MyLog.i(R.string.tunnel_connecting, MyLog.Sensitivity.NOT_SENSITIVE);
                }
            }
        });
    }

    @Override
    public void onServerAlert(String reason, String subject, List<String> actionURLs) {
        MyLog.i("Server alert", "reason", reason, "subject", subject);
        if ("disallowed-traffic".equals(reason)) {
            // Do not show alerts when user has Speed Boost or a subscription.
            // Note that this is an extra measure preventing accidental server alerts since
            // the user with this auth type should not be receiving any from the server
            if (tunnelConfigManager.isSpeedBoostActive() || tunnelConfigManager.isSubscriptionActive()) {
                return;
            }

            // Also do not show the alert if we are showing or going to show the Unlock Required UI
            if(unlockOptions.unlockRequired()) {
                return;
            }

            // Disable showing alerts more than once per service run
            if (disallowedTrafficNotificationAlreadyShown) {
                return;
            }
            disallowedTrafficNotificationAlreadyShown = true;

            // Display disallowed traffic alert notification
            m_Handler.post(() -> {
                final Context context = getContext();
                String notificationMessage = context.getString(R.string.disallowed_traffic_alert_notification_message);
                Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_SERVER_ALERT_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                        .setGroup(getContext().getString(R.string.alert_notification_group))
                        .setContentTitle(context.getString(R.string.disallowed_traffic_alert_notification_title))
                        .setContentText(notificationMessage)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationMessage))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setContentIntent(getPendingIntent(m_parentService, INTENT_ACTION_DISALLOWED_TRAFFIC))
                        .setAutoCancel(true)
                        .build();

                if (mNotificationManager != null) {
                    mNotificationManager.notify(R.id.notification_id_disallowed_traffic_alert, notification);
                }
            });
        } else if ("unsafe-traffic".equals(reason)) {
            final Context context = getContext();
            if (Utils.getUnsafeTrafficAlertsOptInState(context)) {
                // Display unsafe traffic alert notification
                m_Handler.post(() -> {
                    // Create a bundle with action urls to add to the notification's pending intent
                    final Bundle unsafeTrafficAlertExtras = new Bundle();
                    // Add the subject to the subjects list, but limit the size
                    if (!unsafeTrafficSubjects.contains(subject)) {
                        if (unsafeTrafficSubjects.size() >= 5) {
                            unsafeTrafficSubjects.remove(0);
                        }
                        unsafeTrafficSubjects.add(subject);
                    }
                    unsafeTrafficAlertExtras.putStringArrayList(DATA_UNSAFE_TRAFFIC_SUBJECTS_LIST, new ArrayList<>(unsafeTrafficSubjects));
                    unsafeTrafficAlertExtras.putStringArrayList(DATA_UNSAFE_TRAFFIC_ACTION_URLS_LIST, new ArrayList<>(actionURLs));

                    // TODO: use a different notification icon for unsafe traffic alerts?
                    String notificationMessage = context.getString(R.string.unsafe_traffic_alert_notification_message);
                    Notification notification = new NotificationCompat.Builder(context, NOTIFICATION_SERVER_ALERT_CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_psiphon_alert_notification)
                            .setGroup(getContext().getString(R.string.alert_notification_group))
                            .setContentTitle(context.getString(R.string.unsafe_traffic_alert_notification_title))
                            .setContentText(notificationMessage)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationMessage))
                            .setPriority(NotificationCompat.PRIORITY_MAX)
                            .setContentIntent(getPendingIntent(m_parentService, INTENT_ACTION_UNSAFE_TRAFFIC, unsafeTrafficAlertExtras))
                            .setAutoCancel(true)
                            .build();

                    if (mNotificationManager != null) {
                        mNotificationManager.notify(R.id.notification_id_unsafe_traffic_alert, notification);
                    }
                });
            }
        }
    }

    @Override
    public void onApplicationParameters(@NonNull Object o) {
        if (!(o instanceof JSONObject)) {
            MyLog.e("TunnelManager::onApplicationParameters: invalid parameter type. Expected JSONObject, got: " + o.getClass().getName());
            return;
        }
        JSONObject params = (JSONObject) o;

        // Process the application parameters
        processDeviceLocationPrecision(params);
        processUnlockOptions(params);
        processTrustedApps(params);
    }

    private void processDeviceLocationPrecision(JSONObject params) {
        // Parse the device location precision from the parameters json object
        // The expected format is:
        // {
        //     "DeviceLocationPrecision": 0
        // }
        try {
            int deviceLocationPrecision = params.optInt("DeviceLocationPrecision");
            final AppPreferences mp = new AppPreferences(getContext());
            mp.put(m_parentService.getString(R.string.deviceLocationPrecisionParameter), deviceLocationPrecision);
        } catch (Exception e) {
            MyLog.e("TunnelManager: failed to parse device location precision: " + e);
        }
    }

    private void processUnlockOptions(JSONObject params) {
        // Expected format:
        // {
        //     "UnlockOptions": {
        //         "Subscription": {}, // Empty or missing "display" field is defaulted to true
        //         "Conduit": { "display": false }
        //     }
        // }
        if (unlockOptionsProcessed) {
            MyLog.w("TunnelManager: UnlockOptions already processed, skipping");
            return;
        }

        Map<String, UnlockOptions.UnlockEntry> entries = new ConcurrentHashMap<>();

        try {
            JSONObject unlockOptionsJson = params.optJSONObject("UnlockOptions");

            if (unlockOptionsJson != null) {
                // Process each key in the JSON
                Iterator<String> keys = unlockOptionsJson.keys();
                while (keys.hasNext()) {
                    String checkerType = keys.next();
                    JSONObject entryObject = unlockOptionsJson.optJSONObject(checkerType);
                    Boolean display = entryObject != null && entryObject.has("display")
                            ? entryObject.optBoolean("display")
                            : null;

                    switch (checkerType) {
                        // Subscription checker
                        // NOTE: During PsiCash phase-out transition, we're treating both subscription and speed boost
                        // as valid subscription methods. After transition, this should be simplified to:
                        // unlockOptions.addChecker(UnlockOptions.CHECKER_SUBSCRIPTION, tunnelConfigManager::isSubscriptionActive);
                        case UnlockOptions.UNLOCK_ENTRY_SUBSCRIPTION:
                            entries.put(
                                    UnlockOptions.UNLOCK_ENTRY_SUBSCRIPTION,
                                    new UnlockOptions.UnlockEntry(
                                            () -> tunnelConfigManager.isSubscriptionActive() || tunnelConfigManager.isSpeedBoostActive(),
                                            display
                                    )
                            );
                            break;

                        // Conduit checker
                        case UnlockOptions.UNLOCK_ENTRY_CONDUIT:
                            entries.put(
                                    UnlockOptions.UNLOCK_ENTRY_CONDUIT,
                                    new UnlockOptions.UnlockEntry(
                                            tunnelConfigManager::isConduitRunningActive,
                                            display
                                    )
                            );
                            break;

                        default:
                            MyLog.w("TunnelManager: unknown unlock option checker type: " + checkerType);
                            break;
                    }
                }
            }
        } catch (Exception e) {
            MyLog.e("TunnelManager: failed to parse UnlockOptions: " + e);
        } finally {
            unlockOptions.setEntries(entries);
            unlockOptionsProcessed = true;
            if (unlockOptions.unlockRequired() && !unlockUIDeliveredThisSession) {
                unlockUIDeliveredThisSession = true;
                deliverUnlockRequiredUI();
            }
        }
    }

    private void processTrustedApps(JSONObject params) {
        // Parse the trusted apps configuration from the parameters json object
        // The expected format is:
        // {
        //     "AndroidTrustedApps": {
        //         "com.example.app1": ["signature1", "signature2"],
        //         "com.example.app2": ["signature3", "signature4", "signature5"]
        //     }
        try {
            JSONObject trustedApps = params.optJSONObject("AndroidTrustedApps");
            if (trustedApps == null) {
                return;
            }

            Map<String, Set<String>> trustedSignatures = new HashMap<>();
            Iterator<String> packageNames = trustedApps.keys();

            while (packageNames.hasNext()) {
                String packageName = packageNames.next();
                JSONArray signatures = trustedApps.getJSONArray(packageName);
                Set<String> signatureSet = new HashSet<>(signatures.length());

                for (int i = 0; i < signatures.length(); i++) {
                    signatureSet.add(signatures.getString(i));
                }
                trustedSignatures.put(packageName, signatureSet);
            }

            // Save the trusted signatures to file
            PackageHelper.saveTrustedSignaturesToFile(getContext().getApplicationContext(), trustedSignatures);

            // Restart the Conduit state observer to pick up new signatures
            setupConduitStateObserver();

            MyLog.i("TunnelManager: Restarted Conduit state observer after updating trusted signatures");
        } catch (JSONException e) {
            MyLog.e("TunnelManager: failed to parse trusted apps signatures: " + e);
        }
    }

    // PurchaseVerifier.VerificationResultListener implementation
    @Override
    public void onVerificationResult(PurchaseVerifier.VerificationResult action) {
        // Update the subscription state in the tunnel config manager based on the purchase verification result.
        // This applies to the following verification outcomes: NO_SUBSCRIPTION, LIMITED_SUBSCRIPTION, or UNLIMITED_SUBSCRIPTION.
        //
        // As a temporary workaround we force a manual restart on new subscription authorizations even if the subscription state of the config
        // hasn't changed to ensure that new authorizations are picked up by the tunnel.
        switch (action) {
            case NO_SUBSCRIPTION:
                MyLog.i("TunnelManager: purchase verification result: NO_SUBSCRIPTION, updating tunnel config manager subscription state");
                tunnelConfigManager.updateSubscriptionState(TunnelConfigManager.SubscriptionState.NONE);
                break;
            case LIMITED_SUBSCRIPTION:
                MyLog.i("TunnelManager: purchase verification result: LIMITED_SUBSCRIPTION, updating tunnel config manager subscription state and restarting tunnel");
                tunnelConfigManager.updateSubscriptionState(TunnelConfigManager.SubscriptionState.LIMITED);
                // Temporary workaround: force tunnel restart to ensure new subscription authorizations take effect.
                onRestartTunnel();
                break;
            case UNLIMITED_SUBSCRIPTION:
                MyLog.i("TunnelManager: purchase verification result: UNLIMITED_SUBSCRIPTION, updating tunnel config manager subscription state and restarting tunnel");
                tunnelConfigManager.updateSubscriptionState(TunnelConfigManager.SubscriptionState.UNLIMITED);
                // Temporary workaround: force tunnel restart to ensure new subscription authorizations take effect.
                onRestartTunnel();
                break;
        }
    }

    @Override
    public void onTrafficRateLimits(long upBytesPerSecond, long downBytesPerSecond) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                m_tunnelState.upstreamRateLimitBytesPerSecond = upBytesPerSecond;
                m_tunnelState.downstreamRateLimitBytesPerSecond = downBytesPerSecond;
                RateLimitHelper.setRateLimits(getContext(), upBytesPerSecond, downBytesPerSecond);
                // Send the updated tunnel state with the rate limits to the client(s) to trigger UI update
                sendClientMessage(ServiceToClientMessage.TUNNEL_CONNECTION_STATE.ordinal(), getTunnelStateBundle());
            }
        });
    }
}
