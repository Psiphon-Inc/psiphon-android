package com.psiphon3.psicash.rewardedvideo;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.psicash.mvibase.MviAction;

interface Action extends MviAction {
    @AutoValue
    abstract class LoadVideoAd implements Action {
        public static LoadVideoAd create(TunnelConnectionStatus status) {
            return new AutoValue_Action_LoadVideoAd(status);
        }

        abstract TunnelConnectionStatus connectionStatus();
    }
}
