package com.mopub.mobileads;

import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.mopub.common.VisibleForTesting;
import com.mopub.common.logging.MoPubLog;
import com.mopub.network.AdResponse;

import java.lang.ref.WeakReference;
import java.util.Map;

abstract class AdLoader {

    WeakReference<AdViewController> mWeakAdViewController;
    AdLoader(AdViewController adViewController) {
        mWeakAdViewController = new WeakReference<AdViewController>(adViewController);
    }

    abstract void load();

    @Nullable
    static AdLoader fromAdResponse(AdResponse response, AdViewController adViewController) {
        MoPubLog.i("Performing custom event.");

        // If applicable, try to invoke the new custom event system (which uses custom classes)
        String adTypeCustomEventName = response.getCustomEventClassName();
        if (adTypeCustomEventName != null) {
            Map<String, String> customEventData = response.getServerExtras();
            return new CustomEventAdLoader(adViewController, adTypeCustomEventName, customEventData);
        }

        MoPubLog.i("Failed to create custom event.");
        return null;
    }

    static class CustomEventAdLoader extends AdLoader {
        private String mCustomEventClassName;
        private Map<String,String> mServerExtras;

        public CustomEventAdLoader(AdViewController adViewController,
                String customEventCLassName,
                Map<String, String> serverExtras) {
            super(adViewController);
            mCustomEventClassName = customEventCLassName;
            mServerExtras = serverExtras;
        }

        @Override
        void load() {
            AdViewController adViewController = mWeakAdViewController.get();
            if (adViewController == null
                    || adViewController.isDestroyed()
                    || TextUtils.isEmpty(mCustomEventClassName)) {
                return;
            }
            adViewController.setNotLoading();

            final MoPubView moPubView = adViewController.getMoPubView();
            if (moPubView == null) {
                MoPubLog.d("Can't load an ad in this ad view because it was destroyed.");
                return;
            }
            moPubView.loadCustomEvent(mCustomEventClassName, mServerExtras);
        }

        @VisibleForTesting
        Map<String, String> getServerExtras() {
            return mServerExtras;
        }
    }
}
