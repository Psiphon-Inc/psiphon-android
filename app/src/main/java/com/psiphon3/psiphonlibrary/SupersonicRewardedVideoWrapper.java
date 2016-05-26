package com.psiphon3.psiphonlibrary;

import android.app.Activity;
import android.os.AsyncTask;
import android.util.Log;

import com.google.android.gms.ads.identifier.AdvertisingIdClient;
import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.supersonic.mediationsdk.logger.SupersonicError;
import com.supersonic.mediationsdk.model.Placement;
import com.supersonic.mediationsdk.sdk.RewardedVideoListener;
import com.supersonic.mediationsdk.sdk.Supersonic;
import com.supersonic.mediationsdk.sdk.SupersonicFactory;

import java.io.IOException;
import java.lang.ref.WeakReference;

public class SupersonicRewardedVideoWrapper implements RewardedVideoListener {

    static final int VIDEO_REWARD_MINUTES = 100;

    private boolean mIsInitialized = false;
    private Supersonic mMediationAgent;
    private  String mPlacement;
    private WeakReference<Activity> mWeakActivity;
    private boolean mIsVideoAvailable = false;

    private AsyncTask mGAIDRequestTask;

    //Set the Application Key - can be retrieved from Supersonic platform
    private final String mAppKey = "49a64b4d";

    public SupersonicRewardedVideoWrapper(Activity activity, String placement) {
        mPlacement = placement;
        mWeakActivity = new WeakReference<Activity>(activity);
        mMediationAgent = SupersonicFactory.getInstance();
        mMediationAgent.setRewardedVideoListener(SupersonicRewardedVideoWrapper.this);

        initialize();
    }

    public void initialize() {
        if(mIsInitialized) {
            return;
        }

        if (mGAIDRequestTask != null && !mGAIDRequestTask.isCancelled()) {
            mGAIDRequestTask.cancel(false);
        }
        mGAIDRequestTask = new UserIdRequestTask().execute();
        mIsVideoAvailable = mMediationAgent.isRewardedVideoAvailable();
    }

    public void setRewardedVideoListener(RewardedVideoListener listener) {
        mMediationAgent.setRewardedVideoListener(listener);
    }

    public void playVideo() {
        if(isRewardedVideoAvailable()) {
            mMediationAgent.showRewardedVideo();
        }
    }

    public boolean isRewardedVideoAvailable() {
        return mMediationAgent != null && mIsVideoAvailable;
    }

    @Override
    public void onRewardedVideoInitSuccess() {

    }

    @Override
    public void onRewardedVideoInitFail(SupersonicError supersonicError) {

    }

    @Override
    public void onRewardedVideoAdOpened() {

    }

    @Override
    public void onRewardedVideoAdClosed() {

    }

    @Override
    public void onVideoAvailabilityChanged(boolean b) {
        mIsVideoAvailable = b;
    }

    @Override
    public void onVideoStart() {

    }

    @Override
    public void onVideoEnd() {

    }

    @Override
    public void onRewardedVideoAdRewarded(Placement placement) {
        Activity activity = mWeakActivity.get();
        Log.d("Psiphon-Pro", "onRewardedVideoAdRewarded");
        if (activity != null) {
            FreeTrialTimer.addTimeSyncSeconds(activity, VIDEO_REWARD_MINUTES * 60);
        }
    }

    @Override
    public void onRewardedVideoShowFail(SupersonicError supersonicError) {

    }

    private final class UserIdRequestTask extends AsyncTask<Void, Void, String> {

        @Override
        protected String doInBackground(Void... params) {
            Activity activity = SupersonicRewardedVideoWrapper.this.mWeakActivity.get();
            if (activity != null) {
                try {
                    String GAID = AdvertisingIdClient.getAdvertisingIdInfo(activity).getId();
                    return GAID;
                } catch (final IOException e) {

                } catch (final GooglePlayServicesNotAvailableException e) {

                } catch (final GooglePlayServicesRepairableException e) {

                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(String GAID) {
            if (GAID != null) {
                Activity activity = SupersonicRewardedVideoWrapper.this.mWeakActivity.get();
                if (activity != null) {
                    mMediationAgent.initRewardedVideo(activity, SupersonicRewardedVideoWrapper.this.mAppKey, GAID);
                    SupersonicRewardedVideoWrapper.this.mIsInitialized = true;
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
        mWeakActivity = null;
    }
}

