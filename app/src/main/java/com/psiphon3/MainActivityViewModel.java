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
import android.database.ContentObserver;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.paging.PagedList;
import androidx.paging.RxPagedListBuilder;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.log.LogEntry;
import com.psiphon3.log.LoggingContentProvider;
import com.psiphon3.log.LogsDataSourceFactory;
import com.psiphon3.log.LogsLastEntryHelper;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.PersonalPairingHelper;
import com.psiphon3.psiphonlibrary.UpstreamProxySettings;

import java.util.Objects;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.disposables.CompositeDisposable;

public class MainActivityViewModel extends AndroidViewModel implements DefaultLifecycleObserver {
    private final PublishRelay<Boolean> customProxyValidationResultRelay = PublishRelay.create();
    private final PublishRelay<Object> availableRegionsSelectionRelay = PublishRelay.create();
    private final PublishRelay<Object> openVpnSettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openProxySettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openMoreOptionsRelay = PublishRelay.create();
    private final PublishRelay<String> externalBrowserUrlRelay = PublishRelay.create();
    private final Flowable<LogEntry> lastLogEntryFlowable;
    private final Flowable<PagedList<LogEntry>> logsPagedListFlowable;
    private final ContentObserver loggingObserver;
    private final PersonalPairingHelper personalPairingHelper;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    public MainActivityViewModel(@NonNull Application application) {
        super(application);
        personalPairingHelper = new PersonalPairingHelper(application);
        LogsLastEntryHelper logsLastEntryHelper = new LogsLastEntryHelper(application.getContentResolver());
        LogsDataSourceFactory logsDataSourceFactory = new LogsDataSourceFactory(application.getContentResolver());

        loggingObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                logsDataSourceFactory.invalidateDataSource();
                logsLastEntryHelper.fetchLatest();
            }
        };

        getApplication().getContentResolver()
                .registerContentObserver(LoggingContentProvider.CONTENT_URI, true, loggingObserver);

        PagedList.Config pagedListConfig = new PagedList.Config.Builder()
                .setPageSize(60)
                .setPrefetchDistance(20)
                .setEnablePlaceholders(true)
                .setInitialLoadSizeHint(60)
                .setMaxSize(100)
                .build();

        logsPagedListFlowable =
                new RxPagedListBuilder<>(
                        logsDataSourceFactory, pagedListConfig)
                        .buildFlowable(BackpressureStrategy.LATEST)
                        .replay(1)
                        .autoConnect(0);

        lastLogEntryFlowable = logsLastEntryHelper.getFlowable()
                .replay(1)
                .autoConnect(0);

        // Also fetch last log entry right away
        logsLastEntryHelper.fetchLatest();
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        compositeDisposable.clear();
        getApplication().getContentResolver().unregisterContentObserver(loggingObserver);
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

    public Flowable<PersonalPairingHelper.PersonalPairingState> personalPairingStateFlowable() {
        return personalPairingHelper.observePersonalPairingState()
                .distinctUntilChanged();
    }

    public Single<PersonalPairingHelper.ImportResult> handlePersonalPairingData(
            String input,
            Flowable<TunnelState> tunnelState) {
        return personalPairingHelper.handleImport(input, tunnelState);
    }

    public void confirmPersonalPairingImport(PersonalPairingHelper.PersonalPairingData data, boolean enableSetting) {
        personalPairingHelper.confirmImport(data, enableSetting);
    }

    public void setPersonalPairingState(boolean isEnabled, @NonNull PersonalPairingHelper.PersonalPairingData data) {
        personalPairingHelper.setPersonalPairingState(isEnabled, data);
    }

    public void setPersonalParingEnabled(boolean isEnabled) {
        personalPairingHelper.setPersonalPairingEnabled(isEnabled);
    }

    // Keep track of the last known pairing state to determine when tunnel restart is needed
    private PersonalPairingHelper.PersonalPairingState lastKnownPersonalPairingState;


    // Observes pairing state changes and triggers tunnel restart when necessary. Restart occurs when:
    // - Pairing is enabled/ disabled
    // - Compartment ID changes while pairing is enabled
    // returns a flowable that emits true when a restart is needed
    public Flowable<Boolean> pairingStateRestartTunnelFlowable() {
        return personalPairingStateFlowable()
                .map(currentState -> {
                    boolean shouldRestart = false;

                    if (lastKnownPersonalPairingState != null) {
                        boolean enableChanged = lastKnownPersonalPairingState.enabled != currentState.enabled;
                        String previousCompartmentId =
                                lastKnownPersonalPairingState.data == null ? null : lastKnownPersonalPairingState.data.compartmentId;
                        String currentCompartmentId =
                                currentState.data == null ? null : currentState.data.compartmentId;
                        boolean compartmentChanged = lastKnownPersonalPairingState.enabled && currentState.enabled &&
                                !Objects.equals(previousCompartmentId, currentCompartmentId);

                        shouldRestart = enableChanged || compartmentChanged;
                    }

                    lastKnownPersonalPairingState = currentState;
                    return shouldRestart;
                })
                .switchMap(shouldRestart -> shouldRestart ? Flowable.just(true) : Flowable.empty());
    }
}
