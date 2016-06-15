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

public class SupersonicInterstitialAdWrapper implements InterstitialListener {


    private boolean mIsInitialized = false;
    private Supersonic mMediationAgent;
    private WeakReference<Activity> mWeakActivity;
    private boolean mInterstitialReady = false;

    private AsyncTask mGAIDRequestTask;

    //Set the Application Key - can be retrieved from Supersonic platform
    private final String mAppKey = "49a64b4d";

    public SupersonicInterstitialAdWrapper(Activity activity) {
        mWeakActivity = new WeakReference<>(activity);
        mMediationAgent = SupersonicFactory.getInstance();
        mMediationAgent.setInterstitialListener(this);
    }

    public void initializeAndLoadInterstitial() {
        if(mIsInitialized) {
            return;
        }
        mIsInitialized = true;

        if (mGAIDRequestTask != null && !mGAIDRequestTask.isCancelled()) {
            mGAIDRequestTask.cancel(false);
        }
        mGAIDRequestTask = new UserIdRequestTask().execute();
    }

    public void showInterstitial() {
        if(isInterstitialAvailable()) {
            mMediationAgent.showInterstitial();
        }
    }

    public void loadInterstitial() {
        if (mIsInitialized) {
            mMediationAgent.loadInterstitial();
        } else {
            initializeAndLoadInterstitial();
        }
    }

    public boolean isInterstitialAvailable() {
        return mMediationAgent != null && mInterstitialReady;
    }

    @Override
    public void onInterstitialInitSuccess() {

    }

    @Override
    public void onInterstitialInitFailed(SupersonicError supersonicError) {

    }

    @Override
    public void onInterstitialReady() {
        mInterstitialReady = true;
    }

    @Override
    public void onInterstitialLoadFailed(SupersonicError supersonicError) {

    }

    @Override
    public void onInterstitialOpen() {

    }

    @Override
    public void onInterstitialClose() {

    }

    @Override
    public void onInterstitialShowSuccess() {

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
                    mMediationAgent.loadInterstitial();
                    mInterstitialReady = mMediationAgent.isInterstitialReady();
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
}
