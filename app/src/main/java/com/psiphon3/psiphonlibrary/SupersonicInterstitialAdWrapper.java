package com.psiphon3.psiphonlibrary;

import android.app.Activity;
import android.os.AsyncTask;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.supersonic.mediationsdk.logger.SupersonicError;
import com.supersonic.mediationsdk.sdk.InterstitialListener;
import com.supersonic.mediationsdk.sdk.Supersonic;
import com.supersonic.mediationsdk.sdk.SupersonicFactory;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

public class SupersonicInterstitialAdWrapper implements InterstitialListener {

    private Supersonic mMediationAgent;
    private WeakReference<Activity> mWeakActivity;
    private boolean mInterstitialReady = false;
    private WrapperAdListener mWrapperAdListener = null;

    private AsyncTask mGAIDRequestTask;

    private boolean mInitInFlight;

    //Set the Application Key - can be retrieved from Supersonic platform
    private final String mAppKey = "49a684d5";

    public SupersonicInterstitialAdWrapper(Activity activity) {
        mWeakActivity = new WeakReference<>(activity);
        mMediationAgent = SupersonicFactory.getInstance();
        mMediationAgent.setInterstitialListener(this);
        mInitInFlight = false;
    }

    public void setAdListener(WrapperAdListener listener) {
        mWrapperAdListener = listener;
    }

    public void showInterstitial() {
        if(isInterstitialAvailable()) {
            mMediationAgent.showInterstitial();
        }
    }

    public synchronized void loadInterstitial() {
        if(mInitInFlight) {
            // onInterstitialInitSuccess will call loadInterstitial when init is done
            // do nothing
            return;
        }
        else {
            if (isSupersonicObjectInitialized()) {
                mMediationAgent.loadInterstitial();
            } else {
                mInitInFlight = true;

                if (mGAIDRequestTask != null && !mGAIDRequestTask.isCancelled()) {
                    mGAIDRequestTask.cancel(false);
                }
                mGAIDRequestTask = new UserIdRequestTask().execute();
            }
        }
    }

    public boolean isInterstitialAvailable() {
        return mMediationAgent != null && mInterstitialReady;
    }

    @Override
    public void onInterstitialInitSuccess() {
        mMediationAgent.loadInterstitial();
        mInitInFlight = false;
    }

    @Override
    public void onInterstitialInitFailed(SupersonicError supersonicError) {
        mInitInFlight = false;
        if(mWrapperAdListener != null) {
            mWrapperAdListener.onFailed(supersonicError);
        }
    }

    @Override
    public void onInterstitialReady() {
        mInterstitialReady = true;
        if(mWrapperAdListener != null) {
            mWrapperAdListener.onReady();
        }
    }

    @Override
    public void onInterstitialLoadFailed(SupersonicError supersonicError) {
        if(mWrapperAdListener != null) {
            mWrapperAdListener.onFailed(supersonicError);
        }
    }

    @Override
    public void onInterstitialOpen() {

    }

    @Override
    public void onInterstitialClose() {
        if(mWrapperAdListener != null) {
            mWrapperAdListener.onClose();
        }
    }

    @Override
    public void onInterstitialShowSuccess() {
        if(mWrapperAdListener != null) {
            mWrapperAdListener.onShowSuccess();
        }
    }

    @Override
    public void onInterstitialShowFailed(SupersonicError supersonicError) {

    }

    @Override
    public void onInterstitialClick() {
    }

    private final class UserIdRequestTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {
            Activity activity = mWeakActivity.get();
            if (activity != null) {
                try {
                    return AdvertisingIdClient.getAdvertisingIdInfo(activity).getId();
                } catch (final IOException | GooglePlayServicesRepairableException | GooglePlayServicesNotAvailableException e) {
                    // do nothing
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(String GAID) {
            if (GAID != null) {
                Activity activity = mWeakActivity.get();
                if (activity != null) {
                    mMediationAgent.initInterstitial(activity, mAppKey, GAID);
                }
            }
        }
    }

    public void onPause() {
        Activity activity = mWeakActivity.get();
        if (mMediationAgent != null && activity != null) {
            mMediationAgent.onPause(activity);
        }
    }
    public void onResume() {
        Activity activity = mWeakActivity.get();
        if (mMediationAgent != null && activity != null) {
            mMediationAgent.onResume(activity);
        }
    }

    public void onDestroy() {
        if (mMediationAgent != null) {
            mMediationAgent.setLogListener(null);
        }
        if (mGAIDRequestTask != null && !mGAIDRequestTask.isCancelled()) {
            mGAIDRequestTask.cancel(true);
            mGAIDRequestTask = null;
        }
        mWeakActivity.clear();
    }

    public interface WrapperAdListener {
        void onFailed(SupersonicError supersonicError);
        void onShowSuccess();
        void onClose();
        void onReady();
    }

    private boolean isSupersonicObjectInitialized() {
        try {
            Field f = mMediationAgent.getClass().getDeclaredField("mAtomicBaseInit");
            f.setAccessible(true);
            AtomicBoolean mAtomicBaseInit = (AtomicBoolean) f.get(mMediationAgent);
            return mAtomicBaseInit.get();
        } catch (NoSuchFieldException e) {
            return false;
        } catch (IllegalAccessException e) {
            return false;
        }
    }
}
