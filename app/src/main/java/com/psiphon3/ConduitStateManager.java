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
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.log.MyLog;

import org.reactivestreams.Subscriber;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final long RECONNECT_DELAY_MS = 500; // 1/2 second delay between reconnection attempts
    private static final int MAX_RETRY_ATTEMPTS = 3; // Maximum number of retry attempts before giving up

    private final Context applicationContext;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private final BehaviorRelay<ConduitState> stateRelay;

    private final AtomicInteger retryCount = new AtomicInteger(0);
    private IConduitStateService stateService;
    private boolean isServiceBound = false;
    private final CompositeDisposable reconnectDisposable = new CompositeDisposable();
    private final Object lock = new Object();

    private final IConduitStateCallback stateCallback = new IConduitStateCallback.Stub() {
        @Override
        public void onStateUpdate(String state) {
            try {
                stateRelay.accept(ConduitState.fromJson(state));
            } catch (IllegalArgumentException e) {
                MyLog.e("ConduitStateManager: failed to parse ConduitState: " + e);
                stateRelay.accept(ConduitState.error("Failed to parse ConduitState: " + e.getMessage()));
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (lock) {
                if (isShutdown.get()) {
                    return;
                }
                MyLog.i("ConduitStateManager: connected to ConduitStateService");
                stateService = IConduitStateService.Stub.asInterface(service);
                isServiceBound = true;
                retryCount.set(0); // Reset retry count on successful connection
                try {
                    stateService.registerClient(stateCallback);
                } catch (RemoteException e) {
                    handleServiceError(new ConduitServiceException(ConduitErrorType.BINDING_ERROR,
                            "Failed to register client: " + e.getMessage()));
                }
                catch (SecurityException e) {
                    // Security exception can occur if the client is not allowed to register
                    // (e.g. if the client is not signet with a certificate that the service trusts)
                    handleServiceError(new ConduitServiceException(ConduitErrorType.SECURITY_ERROR,
                            "Security exception registering client: " + e.getMessage()));
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (lock) {
                if (isShutdown.get()) {
                    return;
                }
                MyLog.w("ConduitStateManager: disconnected from ConduitStateService");
                stateService = null;
                isServiceBound = false;
                // Attempt to reconnect since this is an unexpected disconnection
                scheduleReconnect();
            }
        }
    };

    // Exception types for ConduitService errors
    public enum ConduitErrorType {
        PACKAGE_TRUST_ERROR,
        SECURITY_ERROR,
        BINDING_ERROR,
        PACKAGE_NOT_FOUND_ERROR,
        SERVICE_NOT_FOUND_ERROR,
        MAX_RETRIES_EXCEEDED
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

    private ConduitStateManager(Context applicationContext) {
        this.applicationContext = applicationContext;
        this.stateRelay = BehaviorRelay.createDefault(ConduitState.unknown());
    }

    public static ConduitStateManager newManager(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        return new ConduitStateManager(context.getApplicationContext());
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

            // Map the error type to the appropriate action as follows:
            // - PACKAGE_TRUST_ERROR, SERVICE_NOT_FOUND_ERROR: stop retrying, emit "incompatible version" state and complete
            // - PACKAGE_NOT_FOUND_ERROR: stop retrying, emit "not installed" state and complete
            // - SECURITY_ERROR: stop retrying and emit the error
            // - MAX_RETRIES_EXCEEDED: stop retrying and emit the error
            // - BINDING_ERROR: schedule a reconnect attempt
            switch (error.getType()) {
                case PACKAGE_TRUST_ERROR:
                case SERVICE_NOT_FOUND_ERROR:
                    retryCount.set(MAX_RETRY_ATTEMPTS);
                    stateRelay.accept(ConduitState.incompatibleVersion(error.getMessage()));
                    break;

                case PACKAGE_NOT_FOUND_ERROR:
                    retryCount.set(MAX_RETRY_ATTEMPTS);
                    stateRelay.accept(ConduitState.builder()
                            .setStatus(ConduitState.Status.NOT_INSTALLED)
                            .setMessage(error.getMessage())
                            .build());
                    break;

                case SECURITY_ERROR:
                case MAX_RETRIES_EXCEEDED:
                    retryCount.set(MAX_RETRY_ATTEMPTS);
                    stateRelay.accept(ConduitState.error(error.getMessage()));
                    break;

                case BINDING_ERROR:
                    scheduleReconnect();
                    break;

                default:
                    throw new IllegalArgumentException("Unhandled error type: " + error.getType());
            }
        }
    }

    // Schedule a reconnect attempt after a delay until the maximum retry attempts are reached
    private void scheduleReconnect() {
        synchronized (lock) {
            if (isShutdown.get()) {
                return;
            }

            if (applicationContext == null) {
                throw new IllegalStateException("Application context is null");
            }

            int currentRetries = retryCount.incrementAndGet();
            if (currentRetries > MAX_RETRY_ATTEMPTS) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.MAX_RETRIES_EXCEEDED,
                        "Maximum binding retries to ConduitStateService exceeded"));
                return;
            }

            MyLog.i("ConduitStateManager: scheduling reconnection attempt " + currentRetries + " of " + MAX_RETRY_ATTEMPTS);
            // Clear any existing reconnect attempts and schedule a new one
            reconnectDisposable.clear();
            reconnectDisposable.add(
                    Completable.timer(RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
                            .subscribe(this::checkConduitAndBind)
            );
        }
    }

    // Perform checks on the Conduit package and bind to the ConduitStateService
    private void checkConduitAndBind() {
        synchronized (lock) {
            if (isShutdown.get()) {
                return;
            }

            if (applicationContext == null) {
                throw new IllegalStateException("Application context is null");
            }

            if (isServiceBound) {
                return;
            }

            // Check if the Conduit package is installed
            if (!PackageHelper.isPackageInstalled(applicationContext.getPackageManager(), CONDUIT_PACKAGE)) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.PACKAGE_NOT_FOUND_ERROR,
                        "Conduit package not installed"));
                return;
            }
            // Verify the Conduit package signature
            if (!PackageHelper.verifyTrustedPackage(applicationContext.getPackageManager(), CONDUIT_PACKAGE)) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.PACKAGE_TRUST_ERROR,
                        "Conduit package trust verification failed"));
                return;
            }

            // Finally, check if the ConduitStateService is available in the Conduit app
            if (!isConduitStateServiceAvailable()) {
                handleServiceError(new ConduitServiceException(
                        ConduitErrorType.SERVICE_NOT_FOUND_ERROR,
                        "ConduitStateService not found in Conduit package"));
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
        }
    }

    // Checks if the ConduitStateService is available in the Conduit app
    private boolean isConduitStateServiceAvailable() {
        Intent intent = new Intent(ACTION_BIND_CONDUIT_STATE);
        intent.setPackage(CONDUIT_PACKAGE);
        return applicationContext.getPackageManager().resolveService(intent, 0) != null;
    }

    private void shutdown() {
        if (!isShutdown.compareAndSet(false, true)) {
            throw new IllegalStateException("ConduitStateManager is already shutdown");
        }
        synchronized (lock) {
            MyLog.i("ConduitStateManager: shutting down");

            // Clear any existing scheduled reconnects
            reconnectDisposable.clear();

            if (stateService != null) {
                try {
                    stateService.unregisterClient(stateCallback);
                } catch (RemoteException e) {
                    if (!(e instanceof DeadObjectException)) {
                        MyLog.e("ConduitStateManager: failed to unregister client: " + e);
                    }
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
        }
    }

    // ConduitState flowable for observing Conduit state changes
    // Note that subscription to this flowable will automatically bind to the ConduitStateService if not already bound
    public Flowable<ConduitState> stateFlowable() {
        if (isShutdown.get()) {
            return Flowable.just(ConduitState.error("ConduitStateManager is shutdown"));
        }

        return stateRelay
                .toFlowable(BackpressureStrategy.LATEST)
                .distinctUntilChanged()
                // Complete the stream after emitting a terminal state
                .takeUntil(ConduitStateManager::isTerminalState)
                .doOnSubscribe(subscription -> {
                    synchronized (lock) {
                        if (!isServiceBound) {
                            checkConduitAndBind();
                        }
                    }
                })
                .doFinally(() -> {
                    // stop the ConduitStateManager if there are no subscribers
                    if (!stateRelay.hasObservers()) {
                        MyLog.i("ConduitStateManager: no subscribers, will shutdown");
                        shutdown();
                    }
                });
    }

    private static boolean isTerminalState(ConduitState state) {
        switch (state.status()) {
            case NOT_INSTALLED:        // User needs to install Conduit
            case INCOMPATIBLE_VERSION: // User needs to update Conduit
            case UNSUPPORTED_SCHEMA:   // User needs to update Psiphon Pro
            case ERROR:                // Something went wrong
                return true;
            case UNKNOWN:
            case RUNNING:
            case STOPPED:
            default:
                return false;
        }
    }
}