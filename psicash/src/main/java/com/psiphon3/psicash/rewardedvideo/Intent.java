package com.psiphon3.psicash.rewardedvideo;

import com.google.auto.value.AutoValue;
import com.psiphon3.psicash.util.TunnelState;
import com.psiphon3.psicash.mvibase.MviIntent;

public interface Intent extends MviIntent {
    @AutoValue
    abstract class LoadVideoAd implements Intent {
        public static LoadVideoAd create(TunnelState status) {
            return new AutoValue_Intent_LoadVideoAd(status);
        }

        abstract TunnelState connectionState();
    }

}
