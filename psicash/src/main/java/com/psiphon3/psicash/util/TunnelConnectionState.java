package com.psiphon3.psicash.util;


import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class TunnelConnectionState {
    public enum Status {
        DISCONNECTED,
        CONNECTED,
    }

    @AutoValue
    public static abstract class ConnectionData {
        public abstract String clientRegion();

        public abstract String clientVersion();

        public abstract String propagationChannelId();

        public abstract String sponsorId();


        public abstract boolean vpnMode();

        public abstract int httpPort();

        public static Builder builder() {
            return new AutoValue_TunnelConnectionState_ConnectionData.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder setClientRegion(String value);

            public abstract Builder setClientVersion(String value);

            public abstract Builder setPropagationChannelId(String value);

            public abstract Builder setSponsorId(String value);

            public abstract Builder setVpnMode(boolean isVpn);

            public abstract Builder setHttpPort(int port);

            public abstract ConnectionData build();
        }
    }

    public abstract Status status();
    @Nullable
    public abstract ConnectionData connectionData();

    public static TunnelConnectionState disconnected() {
        return new AutoValue_TunnelConnectionState(Status.DISCONNECTED, null);
    }

    public static TunnelConnectionState connected(ConnectionData connectionData) {
        return new AutoValue_TunnelConnectionState(Status.CONNECTED, connectionData);
    }
}
