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
            // log error
            return;
        }

        mListener = listener;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            mListener.onInterstitialFailed(MoPubErrorCode.ADAPTER_NOT_FOUND);
            return;
        }

        Presage.getInstance().load(new IADHandler() {
            @Override
            public void onAdNotAvailable() {
                if (mListener != null) {
                    mListener.onInterstitialFailed(MoPubErrorCode.NETWORK_NO_FILL);
                }
            }

            @Override
            public void onAdAvailable() {

            }

            @Override
            public void onAdLoaded() {
                if (mListener != null) {
                    mListener.onInterstitialLoaded();
                }
            }

            @Override
            public void onAdClosed() {}

            @Override
            public void onAdError(int i) {
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
        if (Presage.getInstance().canShow()) {
            Presage.getInstance().show(new IADHandler() {
                @Override
                public void onAdNotAvailable() {

                }

                @Override
                public void onAdAvailable() {

                }

                @Override
                public void onAdLoaded() {
                    if (mListener != null) {
                        mListener.onInterstitialLoaded();
                    }
                }

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
