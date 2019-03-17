package com.psiphon3.psicash.util;


import android.support.annotation.Nullable;

import com.google.auto.value.AutoValue;

@AutoValue
public abstract class TunnelState {
    public enum Status {
        RUNNING,
        STOPPED,
    }

    @AutoValue
    public static abstract class ConnectionData {
        public abstract boolean isConnected();

        public abstract String clientRegion();

        public abstract String clientVersion();

        public abstract String propagationChannelId();

        public abstract String sponsorId();

        public abstract boolean vpnMode();

        public abstract int httpPort();

        public static Builder builder() {
            return new AutoValue_TunnelState_ConnectionData.Builder()
                    .setIsConnected(false)
                    .setClientRegion("")
                    .setClientVersion("")
                    .setPropagationChannelId("")
                    .setSponsorId("")
                    .setVpnMode(false)
                    .setHttpPort(0);
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder setIsConnected(boolean value);

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

    public abstract ConnectionData connectionData();

    public static TunnelState stopped() {
        return new AutoValue_TunnelState(Status.STOPPED, ConnectionData.builder().build());
    }

    public static TunnelState running(ConnectionData connectionData) {
        return new AutoValue_TunnelState(Status.RUNNING, connectionData);
    }

    public boolean isRunning() {
        return status() == Status.RUNNING;
    }
}
