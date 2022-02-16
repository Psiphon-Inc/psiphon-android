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

package com.psiphon3;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.paging.PagedList;
import androidx.paging.RxPagedListBuilder;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.log.LogEntry;
import com.psiphon3.log.LoggingRoomDatabase;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.UpstreamProxySettings;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class MainActivityViewModel extends AndroidViewModel implements LifecycleObserver {
    private final TunnelServiceInteractor tunnelServiceInteractor;
    private final BroadcastReceiver broadcastReceiver;
    private final PublishRelay<Boolean> customProxyValidationResultRelay = PublishRelay.create();
    private final PublishRelay<Object> availableRegionsSelectionRelay = PublishRelay.create();
    private final PublishRelay<Object> openVpnSettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openProxySettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openMoreOptionsRelay = PublishRelay.create();
    private final PublishRelay<String> externalBrowserUrlRelay = PublishRelay.create();
    private final Flowable<LogEntry> lastLogEntryFlowable;
    private final Flowable<PagedList<LogEntry>> logsPagedListFlowable;

    public MainActivityViewModel(@NonNull Application application) {
        super(application);
        tunnelServiceInteractor = new TunnelServiceInteractor(getApplication(), true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.TUNNEL_RESTART);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (BroadcastIntent.TUNNEL_RESTART.equals(action)) {
                        restartPsiphon(TunnelServiceInteractor.RestartMode.TUNNEL_NO_HOME_PAGE);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(getApplication()).registerReceiver(broadcastReceiver, intentFilter);

        LoggingRoomDatabase db = LoggingRoomDatabase.getDatabase(application.getApplicationContext());

        PagedList.Config pagedListConfig = new PagedList.Config.Builder()
                .setPageSize(60)
                .setPrefetchDistance(20)
                .setEnablePlaceholders(true)
                .setInitialLoadSizeHint(60)
                .setMaxSize(100)
                .build();

        logsPagedListFlowable =
                new RxPagedListBuilder<>(
                        db.getPagedStatusLogs(), pagedListConfig)
                        .buildFlowable(BackpressureStrategy.LATEST)
                        .replay(1)
                        .autoConnect(0);

        lastLogEntryFlowable = db.getLastStatusLogEntry()
                .replay(1)
                .autoConnect(0);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    protected void onLifeCycleStop() {
        tunnelServiceInteractor.onStop(getApplication());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    protected void onLifeCycleStart() {
        tunnelServiceInteractor.onStart(getApplication());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        LocalBroadcastManager.getInstance(getApplication().getApplicationContext()).unregisterReceiver(broadcastReceiver);
        tunnelServiceInteractor.onDestroy(getApplication());
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }

    public Flowable<Boolean> dataStatsFlowable() {
        return tunnelServiceInteractor.dataStatsFlowable();
    }

    public void stopTunnelService() {
        tunnelServiceInteractor.stopTunnelService();
    }

    public void startTunnelService() {
        tunnelServiceInteractor.startTunnelService(getApplication());
    }

    public void restartPsiphon(TunnelServiceInteractor.RestartMode restartMode) {
        switch (restartMode) {
            case VPN:
                tunnelServiceInteractor.scheduleVpnServiceRestart(getApplication());
                break;

            case TUNNEL:
                // Note resetReconnectFlag == true to open a sponsor page after reconnect
                tunnelServiceInteractor.commandTunnelRestart(true);
                break;

            case TUNNEL_NO_HOME_PAGE:
                // Note resetReconnectFlag == false to  NOT open a sponsor page after reconnect
                tunnelServiceInteractor.commandTunnelRestart(false);
        }
    }

    public void sendLocaleChangedMessage() {
        tunnelServiceInteractor.sendLocaleChangedMessage();
    }

    // Basic check of proxy settings values
    public boolean validateCustomProxySettings() {
        boolean useHTTPProxyPreference = UpstreamProxySettings.getUseHTTPProxy(getApplication());
        boolean useCustomProxySettingsPreference = UpstreamProxySettings.getUseCustomProxySettings(getApplication());

        if (!useHTTPProxyPreference ||
                !useCustomProxySettingsPreference) {
            return true;
        }
        UpstreamProxySettings.ProxySettings proxySettings = UpstreamProxySettings.getProxySettings(getApplication());
        boolean isValid = proxySettings != null &&
                UpstreamProxySettings.isValidProxyHostName(proxySettings.proxyHost) &&
                UpstreamProxySettings.isValidProxyPort(proxySettings.proxyPort);

        customProxyValidationResultRelay.accept(isValid);

        return isValid;
    }

    public Flowable<Boolean> customProxyValidationResultFlowable() {
        return customProxyValidationResultRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalAvailableRegionsUpdate() {
        availableRegionsSelectionRelay.accept(new Object());
    }

    public Flowable<Object> updateAvailableRegionsFlowable() {
        return availableRegionsSelectionRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenVpnSettings() {
        openVpnSettingsRelay.accept(new Object());
    }

    public Flowable<Object> openVpnSettingsFlowable() {
        return openVpnSettingsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenProxySettings() {
        openProxySettingsRelay.accept(new Object());
    }

    public Flowable<Object> openProxySettingsFlowable() {
        return openProxySettingsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenMoreOptions() {
        openMoreOptionsRelay.accept(new Object());
    }

    public Flowable<Object> openMoreOptionsFlowable() {
        return openMoreOptionsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalExternalBrowserUrl(String url) {
        externalBrowserUrlRelay.accept(url);
    }

    public Flowable<String> externalBrowserUrlFlowable() {
        return externalBrowserUrlRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public Flowable<PagedList<LogEntry>> logsPagedListFlowable() {
        return logsPagedListFlowable;
    }

    public Flowable<String> lastLogEntryFlowable() {
        return lastLogEntryFlowable
                .map(logEntry -> MyLog.getStatusLogMessageForDisplay(logEntry.getLogJson(), getApplication()));
    }
}
