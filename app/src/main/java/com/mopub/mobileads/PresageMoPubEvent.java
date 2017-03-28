package com.mopub.mobileads;

import io.presage.Presage;
import io.presage.IADHandler;

import java.util.Map;

import android.content.Context;
import android.os.Build;

import com.mopub.mobileads.CustomEventInterstitial;
import com.mopub.mobileads.MoPubErrorCode;

public class PresageMoPubEvent extends CustomEventInterstitial {

    private CustomEventInterstitialListener mListener;

    public PresageMoPubEvent() {
        super();
    }

    @Override
    protected void loadInterstitial(Context context,
            CustomEventInterstitialListener listener, Map<String, Object> arg2,
            Map<String, String> arg3) {

        if (listener == null) {
            return;
        }

        mListener = listener;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            mListener.onInterstitialFailed(MoPubErrorCode.ADAPTER_NOT_FOUND);
            return;
        }

        Presage.getInstance().loadInterstitial(new IADHandler() {

            @Override
            public void onAdNotFound() {
                if (mListener != null) {
                    mListener.onInterstitialFailed(MoPubErrorCode.NETWORK_NO_FILL);
                }
            }

            @Override
            public void onAdFound() {
                if (mListener != null) {
                    mListener.onInterstitialLoaded();
                }
            }

            @Override
            public void onAdClosed() {}

            @Override
            public void onAdError(int code) {
                if (mListener != null) {
                    mListener.onInterstitialFailed(MoPubErrorCode.NETWORK_NO_FILL);
                }
            }

            @Override
            public void onAdDisplayed() {}
        });
    }

    @Override
    protected void showInterstitial() {
        if (Presage.getInstance().isInterstitialLoaded()) {
            Presage.getInstance().showInterstitial(new IADHandler() {
                @Override
                public void onAdFound() {}

                @Override
                public void onAdNotFound() {}

                @Override
                public void onAdClosed() {
                    if (mListener != null) {
                        mListener.onInterstitialDismissed();
                    }
                }

                @Override
                public void onAdError(int i) {
                    if (mListener != null) {
                        mListener.onInterstitialFailed(MoPubErrorCode.NETWORK_NO_FILL);
                    }
                }

                @Override
                public void onAdDisplayed() {
                    if (mListener != null) {
                        mListener.onInterstitialShown();
                    }
                }
            });
        }
    }

    @Override
    protected void onInvalidate() {
        //nothing to do here
    }

}

