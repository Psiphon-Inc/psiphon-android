package com.mopub.mobileads;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.aerserv.sdk.AerServConfig;
import com.aerserv.sdk.AerServEvent;
import com.aerserv.sdk.AerServEventListener;
import com.aerserv.sdk.AerServInterstitial;

import java.util.List;
import java.util.Map;

public class AerServCustomEventInterstitial extends CustomEventInterstitial {

    private static final String KEYWORDS = "keywords";
    private static final String LOG_TAG = AerServCustomEventInterstitial.class.getSimpleName();
    private static final String PLACEMENT = "placement";
    private static final String TIMEOUT_MILLIS = "timeoutMillis";

    private AerServInterstitial aerServInterstitial = null;

    @Override
    protected void loadInterstitial(final Context context,
                                    final CustomEventInterstitialListener customEventInterstitialListener,
                                    Map<String, Object> localExtras,
                                    Map<String, String> serverExtras) {
        // Error checking
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (customEventInterstitialListener == null) {
            throw new IllegalArgumentException("CustomEventInterstitialListener cannot be null");
        }

        // Get placement
        String PLC = AerServPluginUtil.getString(PLACEMENT, localExtras, serverExtras);
        if (PLC == null) {
            Log.w(LOG_TAG, "Cannot load AerServ ad because placement is missing");
            customEventInterstitialListener.onInterstitialFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            return;
        }
        Log.d(LOG_TAG, "Placement is: " + PLC);

        // Read and configure optional properties
        AerServConfig aerServConfig = new AerServConfig(context, PLC);
        aerServConfig.setPreload(true);
        Integer timeoutMillis = AerServPluginUtil.getInteger(TIMEOUT_MILLIS, localExtras, serverExtras);
        if (timeoutMillis != null) {
            Log.d(LOG_TAG, "Timeout is: " + timeoutMillis + " millis");
            aerServConfig.setTimeout(timeoutMillis);
        }
        List<String> keywords = AerServPluginUtil.getStringList(KEYWORDS, localExtras, serverExtras);
        if (keywords != null) {
            aerServConfig.setKeywords(keywords);
            Log.d(LOG_TAG, "Keywords are: " + keywords);
        }

        // Map AerServ ad events to MoPub ad events
        aerServConfig.setEventListener(new AerServEventListener() {
            @Override
            public void onAerServEvent(final AerServEvent aerServEvent, List<Object> list) {
                Handler handler = new Handler(context.getMainLooper());
                Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        switch(aerServEvent) {
                            case PRELOAD_READY:
                                Log.d(LOG_TAG, "Interstitial ad loaded");
                                customEventInterstitialListener.onInterstitialLoaded();
                                break;
                            case AD_FAILED:
                                Log.d(LOG_TAG, "Failed to load interstitial ad");
                                customEventInterstitialListener.onInterstitialFailed(MoPubErrorCode.NETWORK_NO_FILL);
                                break;
                            case AD_IMPRESSION:
                                Log.d(LOG_TAG, "Interstitial ad shown");
                                customEventInterstitialListener.onInterstitialShown();
                                break;
                            case AD_CLICKED:
                                Log.d(LOG_TAG, "Interstitial ad clicked");
                                customEventInterstitialListener.onInterstitialClicked();
                                break;
                            case AD_DISMISSED:
                                Log.d(LOG_TAG, "Interstitial ad dismissed");
                                customEventInterstitialListener.onInterstitialDismissed();
                                break;
                            default:
                                Log.d(LOG_TAG, "The following AerServ interstitial ad event cannot be "
                                        + "mapped and will be ignored: " + aerServEvent.name());
                                break;
                        }
                    }
                };
                handler.post(runnable);
            }
        });

        Log.d(LOG_TAG, "Loading interstitial ad");
        aerServInterstitial = new AerServInterstitial(aerServConfig);
    }

    @Override
    protected void onInvalidate() {
        if(aerServInterstitial != null) {
            aerServInterstitial.kill();
            aerServInterstitial = null;
        }
    }

    @Override
    protected void showInterstitial() {
        if (aerServInterstitial != null) {
            aerServInterstitial.show();
        }
    }
}
