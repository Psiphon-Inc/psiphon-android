/*
 * Copyright (c) 2024, Psiphon Inc.
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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.PackageHelper;
import com.psiphon3.log.MyLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ca.psiphon.conduit.state.IConduitStateCallback;
import ca.psiphon.conduit.state.IConduitStateService;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;

public class ConduitStateManager {
    private static final String CONDUIT_PACKAGE = "ca.psiphon.conduit";
    private static final String ACTION_BIND_CONDUIT_STATE = "ca.psiphon.conduit.ACTION_BIND_CONDUIT_STATE";
    private static final long RECONNECT_DELAY_MS = 1000; // 1 second delay between reconnection attempts
    private static final int MAX_RETRY_ATTEMPTS = 3; // Maximum number of retry attempts before giving up

    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final Relay<ConduitState> stateUpdateRelay = BehaviorRelay.<ConduitState>create().toSerialized();
    private IConduitStateService stateService;
    private boolean isServiceBound = false;
    private boolean isStopped = true;
    private Context applicationContext;
    private final CompositeDisposable reconnectDisposable = new CompositeDisposable();

    private final IConduitStateCallback stateCallback = new IConduitStateCallback.Stub() {
        @Override
        public void onStateUpdate(String state) {
            ConduitState conduitState = parseStateFromJson(state);
            stateUpdateRelay.accept(conduitState);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MyLog.i("ConduitStateManager: connected to ConduitStateService");
            stateService = IConduitStateService.Stub.asInterface(service);
            isServiceBound = true;
            retryCount.set(0); // Reset retry count on successful connection
            try {
                stateService.registerClient(stateCallback);
            } catch (RemoteException e) {
                MyLog.e("ConduitStateManager: failed to register client: " + e);
                handleServiceError(new ConduitServiceException(ConduitErrorType.BINDING_ERROR, "Failed to register client: " + e.getMessage()));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            MyLog.w("ConduitStateManager: disconnected from ConduitStateService");
            stateService = null;
            isServiceBound = false;
            stateUpdateRelay.accept(ConduitState.UNKNOWN);
            // Attempt to reconnect since this is an unexpected disconnection
            scheduleReconnect();
        }
    };

    // ConduitState enum to represent the state of the Conduit app
    public enum ConduitState {
        UNKNOWN,
        RUNNING,
        STOPPED,
        NOT_INSTALLED,
        CONDUIT_UPGRADE_REQUIRED,
        MAX_RETRIES_EXCEEDED
    }

    // Exception types for ConduitService errors
    public enum ConduitErrorType {
        SECURITY_ERROR,
        BINDING_ERROR,
        PACKAGE_ERROR
    }

    // Helper exception class for ConduitService errors
    public static class ConduitServiceException extends Exception {
        private final ConduitErrorType type;

        public ConduitServiceException(ConduitErrorType type, String message) {
            super(message);
            this.type = type;
        }

        public ConduitErrorType getType() {
            return type;
        }
    }

    private void handleServiceError(ConduitServiceException error) {
        MyLog.i("ConduitStateManager: handling service error: " + error.getType() + " - " + error.getMessage());

        if (isServiceBound && applicationContext != null) {
            try {
                applicationContext.unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                MyLog.e("ConduitStateManager: error unbinding service: " + e);
            }
            isServiceBound = false;
            stateService = null;
        }

        switch (error.getType()) {
            case SECURITY_ERROR:
            case PACKAGE_ERROR:
                // For security and package errors, stop retrying and treat as not installed
                retryCount.set(MAX_RETRY_ATTEMPTS);
                stateUpdateRelay.accept(ConduitState.NOT_INSTALLED);
                break;
            case BINDING_ERROR:
                scheduleReconnect();
                break;
        }
    }

    public ConduitStateManager() {
        // Initialize the state to UNKNOWN
        stateUpdateRelay.accept(ConduitState.UNKNOWN);
    }

    // Schedule a reconnect attempt after a delay until the maximum retry attempts are reached
    private void scheduleReconnect() {
        if (isStopped || applicationContext == null) {
            return;
        }

        int currentRetries = retryCount.incrementAndGet();
        if (currentRetries > MAX_RETRY_ATTEMPTS) {
            MyLog.e("ConduitStateManager: max retry attempts exceeded");
            stateUpdateRelay.accept(ConduitState.MAX_RETRIES_EXCEEDED);
            return;
        }

        MyLog.i("ConduitStateManager: scheduling reconnection attempt " + currentRetries + " of " + MAX_RETRY_ATTEMPTS);
        reconnectDisposable.clear();
        reconnectDisposable.add(
                Completable.timer(RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
                        .subscribe(() -> {
                            if (!isStopped) {
                                checkConduitAndBind();
                            }
                        })
        );
    }

    // Perform checks on the Conduit package and bind to the ConduitStateService
    private void checkConduitAndBind() {
        if (applicationContext == null) return;

        try {
            // Check if the Conduit package is installed
            if (!PackageHelper.isPackageInstalled(applicationContext.getPackageManager(), CONDUIT_PACKAGE)) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.PACKAGE_ERROR,
                        "Conduit package not installed"));
                return;
            }
            // Verify the Conduit package signature
            if (!PackageHelper.verifyTrustedPackage(applicationContext.getPackageManager(), CONDUIT_PACKAGE)) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.SECURITY_ERROR,
                        "Conduit package trust verification failed"));
                return;
            }

            // Finally, check if the ConduitStateService is available in the Conduit app
            if (!isConduitStateServiceAvailable()) {
                stateUpdateRelay.accept(ConduitState.CONDUIT_UPGRADE_REQUIRED);
                return;
            }

            Intent intent = new Intent(ACTION_BIND_CONDUIT_STATE);
            intent.setPackage(CONDUIT_PACKAGE);

                // Attempt to bind to the ConduitStateService. The BIND_AUTO_CREATE flag ensures that
                // the service is created if it is not already running.
            boolean bindRequested = applicationContext.bindService(intent,
                    serviceConnection,
                    Context.BIND_AUTO_CREATE);

            // If the bind request failed, schedule a reconnect
            if (!bindRequested) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.BINDING_ERROR,
                        "Failed to request bind to ConduitStateService"));
            }
        } catch (SecurityException e) {
            handleServiceError(new ConduitServiceException(
                    ConduitErrorType.SECURITY_ERROR,
                    "Security exception binding to ConduitStateService: " + e.getMessage()));
        }
    }

    // Checks if the ConduitStateService is available in the Conduit app
    private boolean isConduitStateServiceAvailable() {
        Intent intent = new Intent(ACTION_BIND_CONDUIT_STATE);
        intent.setPackage(CONDUIT_PACKAGE);
        return applicationContext.getPackageManager().resolveService(intent, 0) != null;
    }

    // Starts the ConduitStateManager by binding to the ConduitStateService of the Conduit app
    public void onStart(Context context) {
        isStopped = false;
        this.applicationContext = context.getApplicationContext();
        checkConduitAndBind();
    }

    // Stops the ConduitStateManager by unbinding from the ConduitStateService
    public void onStop(Context context) {
        isStopped = true;
        reconnectDisposable.clear();
        stateUpdateRelay.accept(ConduitState.UNKNOWN);

        if (stateService != null) {
            try {
                stateService.unregisterClient(stateCallback);
            } catch (RemoteException e) {
                MyLog.e("ConduitStateManager: failed to unregister client: " + e);
            }
        }

        if (isServiceBound) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                MyLog.e("ConduitStateManager: error unbinding service: " + e);
            }
            isServiceBound = false;
        }
        stateService = null;
        applicationContext = null;
    }

    private static final int EXPECTED_SCHEMA_VERSION = 1;

    // Parses the Conduit state json and maps it to the ConduitState
    private ConduitState parseStateFromJson(String stateJson) {
        try {
            JSONObject json = new JSONObject(stateJson);

            // Validate schema version
            int schema = json.optInt("schema", -1);
            if (schema != EXPECTED_SCHEMA_VERSION) {
                MyLog.e("ConduitStateManager: unexpected schema version: " + schema);
                return ConduitState.UNKNOWN;
            }

            // Parse data object
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                MyLog.e("ConduitStateManager: missing 'data' field in state JSON");
                return ConduitState.UNKNOWN;
            }

            // Extract appVersion and log it
            int appVersion = data.optInt("appVersion", -1);
            MyLog.i("ConduitStateManager: parsed appVersion: " + appVersion);

            // Extract and log the running state
            if (data.has("running")) {
                boolean running = data.optBoolean("running", false);
                MyLog.i("ConduitStateManager: parsed running state: " + running);
                return running ? ConduitState.RUNNING : ConduitState.STOPPED;
            } else {
                MyLog.i("ConduitStateManager: running state not provided; returning UNKNOWN");
                return ConduitState.UNKNOWN;
            }
        } catch (JSONException e) {
            MyLog.e("ConduitStateManager: failed to parse state JSON: " + e);
            return ConduitState.UNKNOWN;
        }
    }

    // ConduitState flowable to observe the state changes
    public Flowable<ConduitState> conduitStateFlowable() {
        return stateUpdateRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }
}