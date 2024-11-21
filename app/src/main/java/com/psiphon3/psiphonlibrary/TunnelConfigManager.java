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
import com.psiphon3.Location;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.List;
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

        private TunnelConfig(Builder builder) {
            this.egressRegion = builder.egressRegion;
            this.disableTimeouts = builder.disableTimeouts;
            this.deviceLocation = builder.deviceLocation;
            this.sponsorshipState = builder.sponsorshipState;
        }

        public static class Builder {
            private String egressRegion;
            private boolean disableTimeouts;
            private String deviceLocation;
            private SponsorshipState sponsorshipState;

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

            TunnelConfig build() {
                return new TunnelConfig(this);
            }
        }

        public String getSponsorId() {
            // Evaluate the sponsor ID based on the current state of the sponsorship
            // 1. If the user has an unlimited subscription, use the subscription sponsor ID
            // 2. If the user has a limited subscription, use the subscription sponsor ID
            // 3. If the user has speed boost authorization, use the speed boost sponsor ID
            // 4. If the user is running conduit, use the conduit sponsor ID
            // 5. Otherwise, use the embedded sponsor ID (fallback and default)
            if (sponsorshipState.subscriptionState == SubscriptionState.UNLIMITED) {
                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
            }
            if (sponsorshipState.subscriptionState == SubscriptionState.LIMITED) {
                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
            }
            if (sponsorshipState.hasSpeedBoostAuth) {
                return BuildConfig.SPEED_BOOST_SPONSOR_ID;
            }
            if (sponsorshipState.isConduitRunning) {
                return BuildConfig.CONDUIT_RUNNING_SPONSOR_ID;
            }
            return EmbeddedValues.SPONSOR_ID;
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
        updateConfig(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(subscriptionState)
                .withSpeedBoost(currentState.hasSpeedBoostAuth)
                .withConduit(currentState.isConduitRunning)
                .build());
    }

    public void updateSpeedBoostState(boolean isAuthorized) {
        updateConfig(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(currentState.subscriptionState)
                .withSpeedBoost(isAuthorized)
                .withConduit(currentState.isConduitRunning)
                .build());
    }

    public void updateConduitState(boolean isRunning) {
        updateConfig(currentState -> new SponsorshipState.Builder()
                .withSubscriptionState(currentState.subscriptionState)
                .withSpeedBoost(currentState.hasSpeedBoostAuth)
                .withConduit(isRunning)
                .build());
    }

    /**
     * Initializes the tunnel configuration with externally provided states.
     */
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
                            .build();
                }
        ).doOnSuccess(tunnelConfigBehaviorRelay::accept);
    }

    public TunnelConfig getCurrentConfig() {
        return tunnelConfigBehaviorRelay.getValue();
    }

    private void updateConfig(Function<SponsorshipState, SponsorshipState> updateFunction) {
        TunnelConfig currentConfig = getCurrentConfig();
        if (currentConfig == null) {
            return;
        }

        SponsorshipState newState = updateFunction.apply(currentConfig.sponsorshipState);
        // Only publish an update if the state of the sponsorship has changed
        if (!newState.equals(currentConfig.sponsorshipState)) {
            TunnelConfig newConfig = new TunnelConfig.Builder()
                    .egressRegion(currentConfig.egressRegion)
                    .disableTimeouts(currentConfig.disableTimeouts)
                    .deviceLocation(currentConfig.deviceLocation)
                    .sponsorshipState(newState)
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