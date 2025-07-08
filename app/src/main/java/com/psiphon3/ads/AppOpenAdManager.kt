/*
 * Copyright (c) 2025, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.ads

import android.app.Activity
import android.os.Handler
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable

class AppOpenAdManager private constructor(
    private val activity: Activity,
    private val config: AppOpenAdConfig,
    private val tunnelStateFlowable: Flowable<TunnelState>,
    private val callback: AdManagerCallback,
    private val loadingCallback: AdManager.AdLoadingCallback
) : DefaultLifecycleObserver {

    companion object {
        private const val AD_LOAD_TIMEOUT_SECONDS = 10

        fun create(
            activity: Activity,
            config: AppOpenAdConfig,
            tunnelStateFlowable: Flowable<TunnelState>,
            callback: AdManagerCallback,
            loadingCallback: AdManager.AdLoadingCallback
        ): AppOpenAdManager {
            return AppOpenAdManager(activity, config, tunnelStateFlowable, callback, loadingCallback)
        }
    }

    interface AdManagerCallback {
        fun onAdShowing()
        fun onAdCompleted()
        fun onAdFailed(error: String)
    }

    private var appOpenAd: AppOpenAd? = null
    private var isActivityResumed = true // Assume resumed initially
    private var isAdReady = false
    private var isStale = false
    private var isTimedOut = false
    private var tunnelStateDisposable: Disposable? = null
    private val handler = Handler()
    private var countdownRunnable: Runnable? = null

    init {
        // Start monitoring activity lifecycle
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(this)
        }

        // Start monitoring tunnel state
        startTunnelStateMonitoring()

        // Start loading ad
        loadAd()
    }

    private fun startTunnelStateMonitoring() {
        tunnelStateDisposable = tunnelStateFlowable
            .filter { !it.isUnknown }
            .subscribe(
                { tunnelState ->
                    if (tunnelState.status() != config.requiredTunnelState) {
                        MyLog.w("AppOpenAdManager: Tunnel state changed to ${tunnelState.status()}, marking ad as stale")
                        markAsStale()
                    }
                },
                { error ->
                    MyLog.e("AppOpenAdManager: Error monitoring tunnel state: $error")
                }
            )
    }

    private fun loadAd() {
        if (isStale || isTimedOut) return

        val adUnitId = config.adUnitId

        // Start countdown progress
        loadingCallback.startedLoading(AD_LOAD_TIMEOUT_SECONDS)
        startLoadingCountdown()

        AppOpenAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    this@AppOpenAdManager.onAdLoaded(ad)
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    this@AppOpenAdManager.onAdFailedToLoad(error)
                }
            }
        )
    }

    private fun startLoadingCountdown() {
        var elapsedSeconds = .1f // Start at 0.1 seconds to show initial progress

        fun updateProgress() {
            if (isStale || isTimedOut || isAdReady) {
                return
            }

            if (elapsedSeconds >= AD_LOAD_TIMEOUT_SECONDS) {
                MyLog.i("AdOpenAdManager: Ad loading timeout reached after $AD_LOAD_TIMEOUT_SECONDS seconds")
                onAdLoadTimeout()
                return
            }

            // Convert tenths back to seconds for the callback
            loadingCallback.updateLoadingProgress(elapsedSeconds)
            elapsedSeconds += 0.1f // Increment by 0.1 seconds
            countdownRunnable = Runnable { updateProgress() }
            handler.postDelayed(countdownRunnable!!, 100) // Update every 100ms
        }

        updateProgress()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            countdownRunnable = null
        }
    }

    private fun onAdLoadTimeout() {
        isTimedOut = true
        stopCountdown()
        cleanup()
        callback.onAdFailed("Ad loading timeout")
    }

    private fun onAdLoaded(ad: AppOpenAd) {
        if (isStale || isTimedOut) {
            MyLog.w("AppOpenAdManager: Ad loaded but already stale or timed out, discarding")
            appOpenAd = null
            return
        }

        stopCountdown() // Stop the countdown timer

        appOpenAd = ad
        isAdReady = true

        MyLog.i("AppOpenAdManager: App open ad loaded successfully")

        // Show immediately if activity is resumed, otherwise wait
        if (isActivityResumed) {
            MyLog.i("AppOpenAdManager: showing ad")
            showAd()
        } else {
            MyLog.i("AppOpenAdManager: waiting for activity resume to show")
        }
    }

    private fun onAdFailedToLoad(error: LoadAdError) {
        if (isTimedOut) {
            return
        }

        MyLog.e("AppOpenAdManager: Failed to load app open ad: $error")
        stopCountdown() // Stop the countdown timer
        cleanup()
        callback.onAdFailed("Failed to load: $error")
    }

    private fun showAd() {
        if (!isAdReady || appOpenAd == null || isStale) {
            MyLog.w("AppOpenAdManager: Cannot show ad - ready: $isAdReady, ad: ${appOpenAd != null}, stale: $isStale")
            return
        }

        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                callback.onAdShowing()
            }

            override fun onAdDismissedFullScreenContent() {
                cleanup()
                callback.onAdCompleted()
            }

            override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                cleanup()
                callback.onAdFailed("Failed to show: ${adError.message}")
            }
        }

        appOpenAd?.show(activity)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        isActivityResumed = true

        // Show ad if it's ready and waiting
        if (isAdReady && appOpenAd != null && !isStale) {
            showAd()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        isActivityResumed = false
    }

    private fun markAsStale() {
        if (isStale) return
        isStale = true
        stopCountdown() // Stop countdown if running
        cleanup()
        callback.onAdCompleted() // Complete the flow without showing
    }

    private fun cleanup() {
        stopCountdown()

        if (activity is LifecycleOwner) {
            activity.lifecycle.removeObserver(this)
        }

        tunnelStateDisposable?.dispose()
        tunnelStateDisposable = null

        appOpenAd = null
    }

    fun dispose() {
        cleanup()
    }
}
