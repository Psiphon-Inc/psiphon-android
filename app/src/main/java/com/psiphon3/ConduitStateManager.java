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
 */

package com.psiphon3;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.psiphon3.log.MyLog;

import org.json.JSONException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ca.psiphon.conduit.state.IConduitStateCallback;
import ca.psiphon.conduit.state.IConduitStateService;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.processors.BehaviorProcessor;

public class ConduitStateManager {
    private static final String CONDUIT_PACKAGE = "ca.psiphon.conduit";
    private static final String ACTION_BIND_CONDUIT_STATE = "ca.psiphon.conduit.ACTION_BIND_CONDUIT_STATE";
    private static final long RECONNECT_DELAY_MS = 1000; // 1 second delay between reconnection attempts
    private static final int MAX_RETRY_ATTEMPTS = 3; // Maximum number of retry attempts before giving up

    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final BehaviorProcessor<ConduitState> stateProcessor = BehaviorProcessor.createDefault(ConduitState.unknown());
    private IConduitStateService stateService;
    private boolean isServiceBound = false;
    private boolean isStopped = true;
    private Context applicationContext;
    private final CompositeDisposable reconnectDisposable = new CompositeDisposable();
    private final Object lock = new Object();

    private final IConduitStateCallback stateCallback = new IConduitStateCallback.Stub() {
        @Override
        public void onStateUpdate(String state) {

            try {
                stateProcessor.onNext(ConduitState.fromJson(state));
            } catch (JSONException e) {
                MyLog.e("ConduitStateManager: failed to parse ConduitState: " + e);
                stateProcessor.onNext(ConduitState.error("Failed to parse state: " + e.getMessage()));
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (lock) {
                MyLog.i("ConduitStateManager: connected to ConduitStateService");
                stateService = IConduitStateService.Stub.asInterface(service);
                isServiceBound = true;
                retryCount.set(0); // Reset retry count on successful connection
                try {
                    stateService.registerClient(stateCallback);
                } catch (RemoteException e) {
                    handleServiceError(new ConduitServiceException(ConduitErrorType.BINDING_ERROR, "Failed to register client: " + e.getMessage()));
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                MyLog.w("ConduitStateManager: disconnected from ConduitStateService");
                stateService = null;
                isServiceBound = false;
                stateProcessor.onComplete();
                // Attempt to reconnect since this is an unexpected disconnection
                scheduleReconnect();
            }
        }
    };

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
        synchronized (lock) {
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
                    stateProcessor.onNext(ConduitState.builder()
                            .setStatus(ConduitState.Status.NOT_INSTALLED)
                            .setErrorMessage(error.getMessage())
                            .build());
                    stateProcessor.onComplete();
                    break;
                case BINDING_ERROR:
                    scheduleReconnect();
                    break;
            }
        }
    }

    // Schedule a reconnect attempt after a delay until the maximum retry attempts are reached
    private void scheduleReconnect() {
        if (isStopped || applicationContext == null) {
            return;
        }

        int currentRetries = retryCount.incrementAndGet();
        if (currentRetries > MAX_RETRY_ATTEMPTS) {
            stateProcessor.onNext(ConduitState.maxRetriesExceeded());
            stateProcessor.onComplete();
            return;
        }

        synchronized (lock) {
            MyLog.i("ConduitStateManager: scheduling reconnection attempt " + currentRetries + " of " + MAX_RETRY_ATTEMPTS);
            // Clear any existing reconnect attempts and schedule a new one
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
                stateProcessor.onNext(ConduitState.upgradeRequired());
                stateProcessor.onComplete();
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

    // Stops the ConduitStateManager by unbinding from the ConduitStateService
    public void stop() {
        synchronized (lock) {
            isStopped = true;
            // Clear any existing scheduled reconnects
            reconnectDisposable.clear();

            if (stateService != null) {
                try {
                    stateService.unregisterClient(stateCallback);
                } catch (RemoteException e) {
                    MyLog.e("ConduitStateManager: failed to unregister client: " + e);
                }
            }

            if (isServiceBound && applicationContext != null) {
                try {
                    applicationContext.unbindService(serviceConnection);
                } catch (IllegalArgumentException e) {
                    MyLog.e("ConduitStateManager: error unbinding service: " + e);
                }
                isServiceBound = false;
            }

            stateService = null;
            applicationContext = null;
            stateProcessor.onComplete();
        }
    }

    // ConduitState flowable for observing Conduit state changes
    // Note that subscription to this flowable will automatically bind to the ConduitStateService if not already bound
    public Flowable<ConduitState> stateFlowable() {
        return stateProcessor
                .doOnSubscribe(subscription -> {
                    synchronized (lock) {
                        if (!isServiceBound && !isStopped) {
                            checkConduitAndBind();
                        }
                    }
                })
                .doOnCancel(() -> {
                    // stop the ConduitStateManager if there are no subscribers
                    if (!stateProcessor.hasSubscribers()) {
                        stop();
                    }
                });
    }
}