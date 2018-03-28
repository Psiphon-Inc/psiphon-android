package com.mopub.mobileads;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.aerserv.sdk.AerServBanner;
import com.aerserv.sdk.AerServConfig;
import com.aerserv.sdk.AerServEvent;
import com.aerserv.sdk.AerServEventListener;
import com.mopub.common.util.Views;

import java.util.List;
import java.util.Map;

public class AerServCustomEventBanner extends CustomEventBanner {

    private static final String KEYWORDS = "keywords";
    private static final String LOG_TAG = AerServCustomEventBanner.class.getSimpleName();
    private static final String PLACEMENT = "placement";
    private static final String TIMEOUT_MILLIS = "timeoutMillis";

    private AerServBanner aerServBanner = null;
    private CustomEventBannerListener mBannerListener = null;

    @Override
    protected void loadBanner(final Context context,
                              final CustomEventBannerListener customEventBannerListener,
                              final Map<String, Object> localExtras,
                              final Map<String, String> serverExtras) {
        // Error checking
        if(context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (customEventBannerListener == null) {
            throw new IllegalArgumentException("CustomEventBannerListener cannot be null");
        }

        // Get placement
        mBannerListener = customEventBannerListener;
        String PLC = AerServPluginUtil.getString(PLACEMENT, localExtras, serverExtras);
        if (PLC == null) {
            Log.w(LOG_TAG, "Cannot load AerServ ad because placement is missing");
            customEventBannerListener.onBannerFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
            return;
        }
        Log.d(LOG_TAG, "Placement is: " + PLC);
        aerServBanner = new AerServBanner(context);

        // Read and configure optional properties
        AerServConfig aerServConfig = new AerServConfig(context, PLC);
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
                        switch (aerServEvent) {
                            case AD_LOADED:
                                Log.d(LOG_TAG, "Banner ad loaded");
                                mBannerListener.onBannerLoaded(aerServBanner);
                                break;
                            case AD_FAILED:
                                Log.d(LOG_TAG, "Failed to load banner ad");
                                mBannerListener.onBannerFailed(MoPubErrorCode.NETWORK_NO_FILL);
                                break;
                            case AD_CLICKED:
                                Log.d(LOG_TAG, "Banner ad clicked");
                                mBannerListener.onBannerClicked();
                                break;
                            default:
                                Log.d(LOG_TAG, "The following AerServ banner ad event cannot be mapped ");
                                break;
                        }
                    }
                };
                handler.post(runnable);
            }
        });

        aerServBanner.configure(aerServConfig);
        Log.d(LOG_TAG, "Loading banner ad");
        aerServBanner.show();
    }

    @Override
    protected void onInvalidate() {
        if (aerServBanner != null) {
            Views.removeFromParent(aerServBanner);
            aerServBanner.kill();
            aerServBanner = null;
        }
    }
}