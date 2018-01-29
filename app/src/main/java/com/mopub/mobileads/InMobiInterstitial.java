package com.mopub.mobileads;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.inmobi.ads.InMobiAdRequestStatus;
import com.inmobi.ads.InMobiAdRequestStatus.StatusCode;
import com.inmobi.sdk.InMobiSdk;
import com.mopub.common.MoPub;
import com.mopub.mobileads.CustomEventInterstitial;
import com.mopub.mobileads.MoPubErrorCode;

/*
 * Tested with InMobi SDK  6.2.0
 */
public class InMobiInterstitial extends CustomEventInterstitial implements com.inmobi.ads.InMobiInterstitial.InterstitialAdListener2 {
	private CustomEventInterstitialListener mInterstitialListener;
	private JSONObject serverParams;
	private String accountId = "";
	private long placementId = -1;
	private com.inmobi.ads.InMobiInterstitial iMInterstitial;
	private static boolean isAppIntialize = false;
	private static final String TAG = InMobiInterstitial.class.getSimpleName();

	@Override
	protected void loadInterstitial(Context context, CustomEventInterstitialListener interstitialListener,
									Map<String, Object> localExtras, Map<String, String> serverExtras) {

		mInterstitialListener = interstitialListener;

		Activity activity;
		if (context != null && context instanceof Activity) {
			activity = (Activity) context;
		} else {
			Log.w(TAG, "Context not an Activity. Returning error!");
			mInterstitialListener.onInterstitialFailed(MoPubErrorCode.NO_FILL);
			return;
		}

		try {
			serverParams = new JSONObject(serverExtras);
		} catch (Exception e) {
			Log.e(TAG, "Could not parse server parameters");
			e.printStackTrace();
		}

		try {
			accountId = serverParams.getString("accountid");
			placementId = serverParams.getLong("placementid");
		} catch (JSONException e1) {
			e1.printStackTrace();
		}

		if (!isAppIntialize) {
			InMobiSdk.init(activity, accountId);
			isAppIntialize = true;
		}

		/*
		 * You may also pass the Placement ID by
		 * specifying Custom Event Data in MoPub's web interface.
		 */


		iMInterstitial = new com.inmobi.ads.InMobiInterstitial(activity, placementId, this);

		Map<String, String> map = new HashMap<>();
		map.put("tp", "c_mopub");
		map.put("tp-ver", MoPub.SDK_VERSION);
		iMInterstitial.setExtras(map);
		iMInterstitial.load();
	}

	/*
	 * Abstract methods from CustomEventInterstitial
	 */

	@Override
	public void showInterstitial() {
		if (iMInterstitial != null
				&& iMInterstitial.isReady()) {
			iMInterstitial.show();
		}
	}

	@Override
	public void onInvalidate() {
	}

	@Override
	public void onAdDismissed(com.inmobi.ads.InMobiInterstitial arg0) {
		Log.d(TAG, "InMobi interstitial ad dismissed.");
		if (mInterstitialListener != null) {
			mInterstitialListener.onInterstitialDismissed();
		}
	}

	@Override
	public void onAdDisplayed(com.inmobi.ads.InMobiInterstitial arg0) {
		Log.d(TAG, "InMobi interstitial show on screen.");
		if (mInterstitialListener != null) {
			mInterstitialListener.onInterstitialShown();
		}
	}

	@Override
	public void onAdLoadFailed(com.inmobi.ads.InMobiInterstitial arg0,
							   InMobiAdRequestStatus arg1) {
		Log.d(TAG, "InMobi interstitial ad failed to load.");
		if (mInterstitialListener != null) {

			if (arg1.getStatusCode() == StatusCode.INTERNAL_ERROR) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.INTERNAL_ERROR);
			} else if (arg1.getStatusCode() == StatusCode.REQUEST_INVALID) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.ADAPTER_CONFIGURATION_ERROR);
			} else if (arg1.getStatusCode() == StatusCode.NETWORK_UNREACHABLE) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.NETWORK_INVALID_STATE);
			} else if (arg1.getStatusCode() == StatusCode.NO_FILL) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.NO_FILL);
			} else if (arg1.getStatusCode() == StatusCode.REQUEST_TIMED_OUT) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.NETWORK_TIMEOUT);
			} else if (arg1.getStatusCode() == StatusCode.SERVER_ERROR) {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.SERVER_ERROR);
			} else {
				mInterstitialListener
						.onInterstitialFailed(MoPubErrorCode.UNSPECIFIED);
			}
		}

	}

	@Override
	public void onAdReceived(com.inmobi.ads.InMobiInterstitial inMobiInterstitial) {
		Log.d(TAG, "InMobi Adserver responded with an Ad");
	}

	@Override
	public void onAdLoadSucceeded(com.inmobi.ads.InMobiInterstitial arg0) {
		Log.d(TAG, "InMobi interstitial ad loaded successfully.");
		if (mInterstitialListener != null) {
			mInterstitialListener.onInterstitialLoaded();
		}
	}

	@Override
	public void onAdRewardActionCompleted(com.inmobi.ads.InMobiInterstitial arg0,
										  Map<Object, Object> arg1) {
		Log.d(TAG, "InMobi interstitial onRewardActionCompleted.");

		if (null != arg1) {
			Iterator<Object> iterator = arg1.keySet().iterator();
			while (iterator.hasNext()) {
				String key = iterator.next().toString();
				String value = arg1.get(key).toString();
				Log.d("Rewards: ", key + ":" + value);
			}
		}
	}

	@Override
	public void onAdDisplayFailed(com.inmobi.ads.InMobiInterstitial inMobiInterstitial) {
		Log.d(TAG, "Interstitial ad failed to display.");
	}

	@Override
	public void onAdWillDisplay(com.inmobi.ads.InMobiInterstitial inMobiInterstitial) {
		Log.d(TAG, "Interstitial ad will display.");
	}

	@Override
	public void onUserLeftApplication(com.inmobi.ads.InMobiInterstitial arg0) {
		Log.d(TAG, "InMobi interstitial ad leaving application.");
		mInterstitialListener.onLeaveApplication();
	}

	@Override
	public void onAdInteraction(com.inmobi.ads.InMobiInterstitial arg0,
								Map<Object, Object> arg1) {
		Log.d(TAG, "InMobi interstitial interaction happening.");
		if (mInterstitialListener != null) {
			mInterstitialListener.onInterstitialClicked();
		}
	}
}