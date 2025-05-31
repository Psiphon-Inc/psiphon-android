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

import android.content.Context;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.Objects;
import java.util.function.Function;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;


public class TunnelConfigManager {
    private final Context context;
    private final BehaviorRelay<TunnelConfig> tunnelConfigBehaviorRelay = BehaviorRelay.create();

    public enum SubscriptionState {
        NONE,
        LIMITED,
        UNLIMITED
    }

    public enum RestartType {
        FULL_RESTART,    // Triggers stopRouteThroughTunnel() and full UI updates
        QUIET_RESTART    // Just restarts tunnel
    }

    private static class BaseConfig {
        private final String egressRegion;
        private final boolean disableTimeouts;

        BaseConfig(String egressRegion, boolean disableTimeouts) {
            this.egressRegion = egressRegion;
            this.disableTimeouts = disableTimeouts;
        }
    }

    private static class SponsorshipState {
        private final SubscriptionState subscriptionState;
        private final boolean hasSpeedBoostAuth;
        private final boolean isConduitRunning;

        private SponsorshipState(Builder builder) {
            this.subscriptionState = builder.subscriptionState;
            this.hasSpeedBoostAuth = builder.hasSpeedBoostAuth;
            this.isConduitRunning = builder.isConduitRunning;
        }

        public String getSponsorId() {
            // Evaluate the sponsor ID based on the current state of the sponsorship
            // 1. If the user has an unlimited subscription, use the subscription sponsor ID
            // 2. If the user has a limited subscription, use the subscription sponsor ID
            // 3. If the user has speed boost authorization, use the speed boost sponsor ID
            // 4. If the user is running conduit, use the conduit sponsor ID
            // 5. Otherwise, use the embedded sponsor ID (fallback and default)
            if (subscriptionState == SubscriptionState.UNLIMITED) {
                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
            }
            if (subscriptionState == SubscriptionState.LIMITED) {
                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
            }
            if (hasSpeedBoostAuth) {
                return BuildConfig.SPEED_BOOST_SPONSOR_ID;
            }
            if (isConduitRunning) {
                return BuildConfig.CONDUIT_RUNNING_SPONSOR_ID;
            }
            return EmbeddedValues.SPONSOR_ID;
        }

        public static class Builder {
            private SubscriptionState subscriptionState;
            private boolean hasSpeedBoostAuth;
            private boolean isConduitRunning;

            public Builder withSubscriptionState(SubscriptionState state) {
                this.subscriptionState = state;
                return this;
            }
            public Builder withSpeedBoost(boolean hasAuth) {
                this.hasSpeedBoostAuth = hasAuth;
                return this;
            }

            public Builder withConduit(boolean isRunning) {
                this.isConduitRunning = isRunning;
                return this;
            }

            public SponsorshipState build() {
                return new SponsorshipState(this);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SponsorshipState that = (SponsorshipState) o;
            return subscriptionState == that.subscriptionState &&
                    hasSpeedBoostAuth == that.hasSpeedBoostAuth &&
                    isConduitRunning == that.isConduitRunning;
        }

        @Override
        public int hashCode() {
            return Objects.hash(subscriptionState, hasSpeedBoostAuth, isConduitRunning);
        }
    }

    public static class TunnelConfig {
        private final String egressRegion;
        private final boolean disableTimeouts;
        private final String deviceLocation;
        private final SponsorshipState sponsorshipState;
        private final RestartType restartType;

        private TunnelConfig(Builder builder) {
            this.egressRegion = builder.egressRegion;
            this.disableTimeouts = builder.disableTimeouts;
            this.deviceLocation = builder.deviceLocation;
            this.sponsorshipState = builder.sponsorshipState;
            this.restartType = builder.restartType != null ? builder.restartType : RestartType.FULL_RESTART;
        }

        public static class Builder {
            private String egressRegion;
            private boolean disableTimeouts;
            private String deviceLocation;
            private SponsorshipState sponsorshipState;
            private RestartType restartType;

            Builder egressRegion(String region) {
                this.egressRegion = region;
                return this;
            }

            Builder disableTimeouts(boolean disable) {
                this.disableTimeouts = disable;
                return this;
            }

            Builder deviceLocation(String location) {
                this.deviceLocation = location;
                return this;
            }

            Builder sponsorshipState(SponsorshipState sponsorshipState) {
                this.sponsorshipState = sponsorshipState;
                return this;
            }

            Builder restartType(RestartType restartType) {
                this.restartType = restartType;
                return this;
            }

            TunnelConfig build() {
                return new TunnelConfig(this);
            }
        }

        public String getSponsorId() {
            return sponsorshipState.getSponsorId();
        }

        public RestartType getRestartType() {
            return restartType;
        }
    }

    public TunnelConfigManager(Context context) {
        this.context = context;
    }

    public String getEgressRegion() {
        TunnelConfig config = getCurrentConfig();
        return config != null ? config.egressRegion : PsiphonConstants.REGION_CODE_ANY;
    }

    public String getSponsorId() {
        TunnelConfig config = getCurrentConfig();
        return config != null ? config.getSponsorId() : EmbeddedValues.SPONSOR_ID;
    }

    public boolean isDisableTimeouts() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.disableTimeouts;
    }

    public CharSequence getDeviceLocation() {
        TunnelConfig config = getCurrentConfig();
        return config != null ? config.deviceLocation : "";
    }

    public boolean isSpeedBoostActive() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.sponsorshipState.hasSpeedBoostAuth;
    }

    public boolean isSubscriptionActive() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.sponsorshipState.subscriptionState != SubscriptionState.NONE;
    }

    public boolean isConduitRunningActive() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.sponsorshipState.isConduitRunning;
    }

    public Observable<TunnelConfig> observeTunnelConfig() {
        return tunnelConfigBehaviorRelay
                .hide();
    }

    public void updateSubscriptionState(SubscriptionState subscriptionState) {
        updateConfigWithRestartType(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(subscriptionState)
                .withSpeedBoost(currentState.hasSpeedBoostAuth)
                .withConduit(currentState.isConduitRunning)
                .build(), RestartType.FULL_RESTART);
    }

    public void updateSpeedBoostState(boolean isAuthorized) {
        updateConfigWithRestartType(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(currentState.subscriptionState)
                .withSpeedBoost(isAuthorized)
                .withConduit(currentState.isConduitRunning)
                .build(), RestartType.FULL_RESTART);
    }

    // Conduit-specific update method that chooses restart type based on enforcement
    public void updateConduitStateConditional(boolean isRunning, boolean hasConduitEnforcement) {
        RestartType restartType = hasConduitEnforcement ? RestartType.FULL_RESTART : RestartType.QUIET_RESTART;

        updateConfigWithRestartType(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(currentState.subscriptionState)
                .withSpeedBoost(currentState.hasSpeedBoostAuth)
                .withConduit(isRunning)
                .build(), restartType);
    }

    // Initializes the tunnel configuration with externally provided states.
    public Single<TunnelConfig> initConfiguration(
            Single<Boolean> speedBoostStateSingle,
            Single<Boolean> conduitStateSingle,
            Single<String> deviceLocationSingle,
            Single<SubscriptionState> subscriptionStateSingle) {
        return Single.zip(
                getBaseConfig(), // Keep this internal
                speedBoostStateSingle,
                conduitStateSingle,
                deviceLocationSingle,
                subscriptionStateSingle,
                (baseConfig, hasSpeedBoost, isConduitRunning, deviceLocation, subscriptionState) -> {
                    SponsorshipState sponsorshipState = new SponsorshipState.Builder()
                            .withSubscriptionState(subscriptionState)
                            .withSpeedBoost(hasSpeedBoost)
                            .withConduit(isConduitRunning)
                            .build();

                    return new TunnelConfig.Builder()
                            .egressRegion(baseConfig.egressRegion)
                            .disableTimeouts(baseConfig.disableTimeouts)
                            .deviceLocation(deviceLocation)
                            .sponsorshipState(sponsorshipState)
                            .restartType(RestartType.FULL_RESTART)
                            .build();
                }
        ).doOnSuccess(tunnelConfigBehaviorRelay::accept);
    }

    public TunnelConfig getCurrentConfig() {
        return tunnelConfigBehaviorRelay.getValue();
    }

    private boolean shouldPublishUpdate(SponsorshipState currentState, SponsorshipState newState) {
        // Always publish a new config if subscription state changes even if sponsor ID is the same
        if (currentState.subscriptionState != newState.subscriptionState) {
            return true;
        }

        // In any other case, only publish if the sponsor ID changes
        return !currentState.getSponsorId().equals(newState.getSponsorId());
    }

    private void updateConfigWithRestartType(Function<SponsorshipState, SponsorshipState> updateFunction,
                                             RestartType restartType) {
        TunnelConfig currentConfig = getCurrentConfig();
        if (currentConfig == null) {
            return;
        }

        SponsorshipState newState = updateFunction.apply(currentConfig.sponsorshipState);
        if (shouldPublishUpdate(currentConfig.sponsorshipState, newState)) {
            TunnelConfig newConfig = new TunnelConfig.Builder()
                    .egressRegion(currentConfig.egressRegion)
                    .disableTimeouts(currentConfig.disableTimeouts)
                    .deviceLocation(currentConfig.deviceLocation)
                    .sponsorshipState(newState)
                    .restartType(restartType)
                    .build();

            tunnelConfigBehaviorRelay.accept(newConfig);
        }
    }

    private Single<BaseConfig> getBaseConfig() {
        return Single.fromCallable(() -> {
                    final AppPreferences preferences = new AppPreferences(context);
                    String egressRegion = preferences.getString(
                            context.getString(R.string.egressRegionPreference),
                            PsiphonConstants.REGION_CODE_ANY
                    );
                    boolean disableTimeouts = preferences.getBoolean(
                            context.getString(R.string.disableTimeoutsPreference),
                            false
                    );

                    if (egressRegion == null || egressRegion.trim().isEmpty()) {
                        egressRegion = PsiphonConstants.REGION_CODE_ANY;
                    }

                    return new BaseConfig(egressRegion, disableTimeouts);
                })
                .subscribeOn(Schedulers.io())
                .doOnError(error -> MyLog.e("TunnelConfigManager: Error loading base config: " + error));
    }
}