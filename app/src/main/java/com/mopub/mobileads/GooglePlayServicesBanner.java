package com.mopub.mobileads;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.mopub.common.util.Views;

import java.util.Map;

import static com.google.android.gms.ads.AdSize.BANNER;
import static com.google.android.gms.ads.AdSize.FULL_BANNER;
import static com.google.android.gms.ads.AdSize.LEADERBOARD;
import static com.google.android.gms.ads.AdSize.MEDIUM_RECTANGLE;

/*
 * Compatible with version 9.4.0 of the Google Play Services SDK.
 */

// Note: AdMob ads will now use this class as Google has deprecated the AdMob SDK.

class GooglePlayServicesBanner extends CustomEventBanner {
    /*
     * These keys are intended for MoPub internal use. Do not modify.
     */
    public static final String AD_UNIT_ID_KEY = "adUnitID";
    public static final String AD_WIDTH_KEY = "adWidth";
    public static final String AD_HEIGHT_KEY = "adHeight";
    public static final String LOCATION_KEY = "location";

    private CustomEventBannerListener mBannerListener;
    private AdView mGoogleAdView;

    @Override
    protected void loadBanner(
            final Context context,
            final CustomEventBannerListener customEventBannerListener,
            final Map<String, Object> localExtras,
            final Map<String, String> serverExtras) {
        mBannerListener = customEventBannerListener;
        final String adUnitId;
        final int adWidth;
        final int adHeight;

        if (extrasAreValid(serverExtras)) {
            adUnitId = serverExtras.get(AD_UNIT_ID_KEY);
            adWidth = Integer.parseInt(serverExtras.get(AD_WIDTH_KEY));
            adHeight = Integer.parseInt(serverExtras.get(AD_HEIGHT_KEY));
        } else {
            mBannerListener.onBannerFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            return;
        }

        mGoogleAdView = new AdView(context);
        mGoogleAdView.setAdListener(new AdViewListener());
        mGoogleAdView.setAdUnitId(adUnitId);

        final AdSize adSize = calculateAdSize(adWidth, adHeight);
        if (adSize == null) {
            mBannerListener.onBannerFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            return;
        }

        mGoogleAdView.setAdSize(adSize);

        final AdRequest.Builder builder = new AdRequest.Builder();

        if (localExtras.containsKey("client_region")) {
            final Location location = LocationUtil.locationFromCountryCode((String)localExtras.get("client_region"));
            if (location != null) {
                builder.setLocation(location);
            }
        }

        /*
        From https://developers.google.com/admob/android/eu-consent#forward_consent_to_the_google_mobile_ads_sdk

        "The default behavior of the Google Mobile Ads SDK is to serve personalized ads. If a user has consented to
        receive only non-personalized ads, you can configure an AdRequest object with the following code to specify
        that only non-personalized ads should be requested."
        */

        if (ConsentInformation.getInstance(context).getConsentStatus() == ConsentStatus.NON_PERSONALIZED) {
            Bundle extras = new Bundle();
            extras.putString("npa", "1");
            builder.addNetworkExtrasBundle(AdMobAdapter.class, extras);
        }

        final AdRequest adRequest = builder
                .setRequestAgent("MoPub")
                .build();

        try {
            mGoogleAdView.loadAd(adRequest);
        } catch (NoClassDefFoundError e) {
            // This can be thrown by Play Services on Honeycomb.
            mBannerListener.onBannerFailed(MoPubErrorCode.NETWORK_NO_FILL);
        }
    }

    @Override
    protected void onInvalidate() {
        Views.removeFromParent(mGoogleAdView);
        if (mGoogleAdView != null) {
            mGoogleAdView.setAdListener(null);
            mGoogleAdView.destroy();
        }
    }

    private boolean extrasAreValid(Map<String, String> serverExtras) {
        try {
            Integer.parseInt(serverExtras.get(AD_WIDTH_KEY));
            Integer.parseInt(serverExtras.get(AD_HEIGHT_KEY));
        } catch (NumberFormatException e) {
            return false;
        }

        return serverExtras.containsKey(AD_UNIT_ID_KEY);
    }

    private AdSize calculateAdSize(int width, int height) {
        // Use the smallest AdSize that will properly contain the adView
        if (width <= BANNER.getWidth() && height <= BANNER.getHeight()) {
            return BANNER;
        } else if (width <= MEDIUM_RECTANGLE.getWidth() && height <= MEDIUM_RECTANGLE.getHeight()) {
            return MEDIUM_RECTANGLE;
        } else if (width <= FULL_BANNER.getWidth() && height <= FULL_BANNER.getHeight()) {
            return FULL_BANNER;
        } else if (width <= LEADERBOARD.getWidth() && height <= LEADERBOARD.getHeight()) {
            return LEADERBOARD;
        } else {
            return null;
        }
    }

    private class AdViewListener extends AdListener {
        /*
         * Google Play Services AdListener implementation
         */
        @Override
        public void onAdClosed() {

        }

        @Override
        public void onAdFailedToLoad(int errorCode) {
            Log.d("MoPub", "Google Play Services banner ad failed to load.");
            if (mBannerListener != null) {
                mBannerListener.onBannerFailed(MoPubErrorCode.NETWORK_NO_FILL);
            }
        }

        @Override
        public void onAdLeftApplication() {

        }

        @Override
        public void onAdLoaded() {
            Log.d("MoPub", "Google Play Services banner ad loaded successfully. Showing ad...");
            if (mBannerListener != null) {
                mBannerListener.onBannerLoaded(mGoogleAdView);
            }
        }

        @Override
        public void onAdOpened() {
            Log.d("MoPub", "Google Play Services banner ad clicked.");
            if (mBannerListener != null) {
                mBannerListener.onBannerClicked();
            }
        }
    }

    @Deprecated // for testing
    AdView getGoogleAdView() {
        return mGoogleAdView;
    }
}
