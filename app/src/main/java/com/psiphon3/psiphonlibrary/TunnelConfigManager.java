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
import com.psiphon3.BuildConfig;
import com.psiphon3.R;
import com.psiphon3.log.MyLog;

import net.grandcentrix.tray.AppPreferences;

import java.util.Objects;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;


public class TunnelConfigManager {
    private final Context context;
    private final BehaviorRelay<TunnelConfig> tunnelConfigBehaviorRelay = BehaviorRelay.create();

    public enum RestartType {
        FULL_RESTART,    // Triggers stopRouteThroughTunnel() and full UI updates
        QUIET_RESTART    // Just restarts tunnel
    }

    private static class BaseConfig {
        private final String egressRegion;
        private final boolean disableTimeouts;
        private final String personalPairingCompartmentId;

        BaseConfig(String egressRegion, boolean disableTimeouts, String personalPairingCompartmentId) {
            this.egressRegion = egressRegion;
            this.disableTimeouts = disableTimeouts;
            this.personalPairingCompartmentId = personalPairingCompartmentId;
        }
    }

    private static class SponsorshipState {
        private final boolean isConduitRunning;

        private SponsorshipState(Builder builder) {
            this.isConduitRunning = builder.isConduitRunning;
        }

        public String getSponsorId(Context context) {
            // Evaluate the sponsor ID based on the current state of the sponsorship
            // 1. If the user is running conduit AND the app has never been sideloaded, use the conduit sponsor ID
            // 2. Otherwise, use the default sponsor ID (fallback and default)
            if (isConduitRunning && !EmbeddedValues.hasEverBeenSideLoaded(context.getApplicationContext())) {
                return BuildConfig.CONDUIT_RUNNING_SPONSOR_ID;
            }
            return EmbeddedValues.SPONSOR_ID;
        }

        public static class Builder {
            private boolean isConduitRunning;

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
            return isConduitRunning == that.isConduitRunning;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isConduitRunning);
        }
    }

    public static class TunnelConfig {
        private final String egressRegion;
        private final boolean disableTimeouts;
        private final String deviceLocation;
        private final String personalPairingCompartmentId;
        private final SponsorshipState sponsorshipState;
        private final RestartType restartType;

        private TunnelConfig(Builder builder) {
            this.egressRegion = builder.egressRegion;
            this.disableTimeouts = builder.disableTimeouts;
            this.deviceLocation = builder.deviceLocation;
            this.personalPairingCompartmentId = builder.personalPairingCompartmentId;
            this.sponsorshipState = builder.sponsorshipState;
            this.restartType = builder.restartType != null ? builder.restartType : RestartType.FULL_RESTART;
        }

        public static class Builder {
            private String egressRegion;
            private boolean disableTimeouts;
            private String deviceLocation;
            private String personalPairingCompartmentId;
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

            Builder personalPairingCompartmentId(String personalPairingCompartmentId) {
                this.personalPairingCompartmentId = personalPairingCompartmentId;
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

        public String getSponsorId(Context context) {
            return sponsorshipState.getSponsorId(context);
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
        return config != null ? config.getSponsorId(this.context) : EmbeddedValues.SPONSOR_ID;
    }

    public boolean isDisableTimeouts() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.disableTimeouts;
    }

    public String getDeviceLocation() {
        TunnelConfig config = getCurrentConfig();
        return config != null ? config.deviceLocation : "";
    }

    public String getPersonalPairingCompartmentId() {
        TunnelConfig config = getCurrentConfig();
        return config != null ? config.personalPairingCompartmentId : "";
    }

    public boolean isConduitRunningActive() {
        TunnelConfig config = getCurrentConfig();
        return config != null && config.sponsorshipState.isConduitRunning;
    }

    public Observable<TunnelConfig> observeTunnelConfig() {
        return tunnelConfigBehaviorRelay
                .hide();
    }

    // Conduit-specific update method that chooses restart type based on enforcement
    public void updateConduitStateConditional(boolean isRunning, boolean hasConduitEnforcement) {
        RestartType restartType = hasConduitEnforcement ? RestartType.FULL_RESTART : RestartType.QUIET_RESTART;

        updateConfigWithRestartType(currentState -> new SponsorshipState.Builder()
                .withConduit(isRunning)
                .build(), restartType);
    }

    // Initializes the tunnel configuration with externally provided states.
    public Single<TunnelConfig> initConfiguration(
            Single<Boolean> conduitStateSingle,
            Single<String> deviceLocationSingle) {
        return Single.zip(
                getBaseConfig(), // Keep this internal
                conduitStateSingle,
                deviceLocationSingle,
                (baseConfig, isConduitRunning, deviceLocation) -> {
                    SponsorshipState sponsorshipState = new SponsorshipState.Builder()
                            .withConduit(isConduitRunning)
                            .build();

                    return new TunnelConfig.Builder()
                            .egressRegion(baseConfig.egressRegion)
                            .disableTimeouts(baseConfig.disableTimeouts)
                            .deviceLocation(deviceLocation)
                            .personalPairingCompartmentId(baseConfig.personalPairingCompartmentId)
                            .sponsorshipState(sponsorshipState)
                            .restartType(RestartType.FULL_RESTART)
                            .build();
                })
                .subscribeOn(Schedulers.io())
                .doOnSuccess(config -> {
                    MyLog.i("TunnelConfigManager: initial config created with sponsor ID: " +
                            config.getSponsorId(context));
                    tunnelConfigBehaviorRelay.accept(config);
                });
    }

    private TunnelConfig getCurrentConfig() {
        return tunnelConfigBehaviorRelay.getValue();
    }

    private Single<BaseConfig> getBaseConfig() {
        return Single.fromCallable(() -> {
            AppPreferences appPreferences = new AppPreferences(context);
            String egressRegion = appPreferences.getString(
                    context.getString(R.string.egressRegionPreference),
                    PsiphonConstants.REGION_CODE_ANY);
            boolean disableTimeouts = appPreferences.getBoolean(
                    context.getString(R.string.disableTimeoutsPreference),
                    false);
            boolean personalPairingEnabled = appPreferences.getBoolean(
                    context.getString(R.string.personalPairingEnabledPreference), false);

            String personalPairingCompartmentId = "";
            if (personalPairingEnabled) {
                personalPairingCompartmentId = appPreferences.getString(
                        context.getString(R.string.personalPairingCompartmentIdPreference), "");
                personalPairingCompartmentId = PersonalPairingHelper.toStandardBase64CompartmentId(
                        personalPairingCompartmentId);
                if (personalPairingCompartmentId.isEmpty()) {
                    MyLog.w("TunnelConfigManager: personal pairing enabled but compartment ID is empty.");
                }
            }

            return new BaseConfig(egressRegion, disableTimeouts, personalPairingCompartmentId);
        }).subscribeOn(Schedulers.io());
    }

    private void updateConfigWithRestartType(java.util.function.Function<SponsorshipState, SponsorshipState> updater,
                                             RestartType restartType) {
        TunnelConfig currentConfig = getCurrentConfig();
        if (currentConfig == null) {
            MyLog.w("TunnelConfigManager: cannot update config, no current config available");
            return;
        }

        SponsorshipState newSponsorshipState = updater.apply(currentConfig.sponsorshipState);
        // Only emit a new config if the sponsor ID has changed.
        // This avoids unnecessary restarts when the conduit running state changes
        // but the effective sponsor ID remains the same (e.g., sideloaded apps).
        //
        // Note: Unlike the Pro version where different subscription levels share the same
        // sponsor ID and require SponsorshipState-based restart logic, here we only
        // care about actual sponsor ID changes that affect the tunnel core config.
        if (!newSponsorshipState.getSponsorId(context).equals(currentConfig.getSponsorId(context))) {
            TunnelConfig newConfig = new TunnelConfig.Builder()
                    .egressRegion(currentConfig.egressRegion)
                    .disableTimeouts(currentConfig.disableTimeouts)
                    .deviceLocation(currentConfig.deviceLocation)
                    .personalPairingCompartmentId(currentConfig.personalPairingCompartmentId)
                    .sponsorshipState(newSponsorshipState)
                    .restartType(restartType)
                    .build();
            MyLog.i("TunnelConfigManager: config updated with sponsor ID: " + newConfig.getSponsorId(context) +
                    ", restart type: " + restartType);
            tunnelConfigBehaviorRelay.accept(newConfig);
        }
    }
}
