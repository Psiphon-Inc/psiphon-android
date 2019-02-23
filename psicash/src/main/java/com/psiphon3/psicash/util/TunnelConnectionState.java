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
    public static abstract class PsiCashMetaData {
        public abstract String clientRegion();

        public abstract String clientVersion();

        public abstract String propagationChannelId();

        public abstract String sponsorId();

        public static Builder builder() {
            return new AutoValue_TunnelConnectionState_PsiCashMetaData.Builder();
        }

        @AutoValue.Builder
        public static abstract class Builder {
            public abstract Builder setClientRegion(String value);

            public abstract Builder setClientVersion(String value);

            public abstract Builder setPropagationChannelId(String value);

            public abstract Builder setSponsorId(String value);

            public abstract PsiCashMetaData build();
        }
    }

    public abstract Status status();

    public abstract boolean vpnMode();

    public abstract int httpPort();

    @Nullable
    public abstract PsiCashMetaData psiCashMetaData();

    public static TunnelConnectionState disconnected() {
        return new AutoValue_TunnelConnectionState(Status.DISCONNECTED, false, 0, null);
    }

    public static TunnelConnectionState connected(boolean vpnMode, int httpPort, PsiCashMetaData psiCashMetaData) {
        return new AutoValue_TunnelConnectionState(Status.CONNECTED, vpnMode, httpPort, psiCashMetaData);
    }
}
