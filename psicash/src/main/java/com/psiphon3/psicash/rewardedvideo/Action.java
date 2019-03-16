package com.psiphon3.psicash.rewardedvideo;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelState;
import com.psiphon3.psicash.mvibase.MviAction;

interface Action extends MviAction {
    @AutoValue
    abstract class LoadVideoAd implements Action {
        public static LoadVideoAd create(TunnelState status) {
            return new AutoValue_Action_LoadVideoAd(status);
        }

        abstract TunnelState connectionState();
    }
}
