package com.psiphon3.psicash.rewardedvideo;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.psicash.mvibase.MviIntent;

interface Intent extends MviIntent {
    @AutoValue
    abstract class LoadVideoAd implements Intent {
        public static LoadVideoAd create(TunnelConnectionStatus status) {
            return new AutoValue_Intent_LoadVideoAd(status);
        }

        abstract TunnelConnectionStatus connectionStatus();
    }

}
