package com.psiphon3.psicash.util;


import com.google.auto.value.AutoValue;

@AutoValue
public abstract class TunnelConnectionState {
    public enum Status {
        DISCONNECTED,
        CONNECTED,
    }

    public abstract Status status();
    public abstract boolean vpnMode();
    public abstract int httpPort();

    public static TunnelConnectionState disconnected() {
        return new AutoValue_TunnelConnectionState(Status.DISCONNECTED, false, 0);
    }

    public static TunnelConnectionState connected(boolean vpnMode, int httpPort) {
        return new AutoValue_TunnelConnectionState(Status.CONNECTED, vpnMode, httpPort);
    }
}
