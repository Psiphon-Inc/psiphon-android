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
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.ads.MobileAds
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject

class AdManager : DefaultLifecycleObserver {
    companion object {
        private const val TAG = "AdManager"
    }

    @Volatile private var cachedInitializeCompletable: Completable? = null
    @Volatile private var lifecycleOwner: LifecycleOwner? = null
    private val resumedSubject = BehaviorSubject.createDefault(false)
    @Volatile private var bannerAdHelper: BannerAdHelper? = null


    interface AdLoadingCallback {
        fun startedLoading(timeoutSeconds: Int) {}
        fun updateLoadingProgress(elapsedSeconds: Float) {}// float for smooth progress updates
        fun done() {}
    }

    fun register(owner: LifecycleOwner) {
        if (lifecycleOwner === owner) return
        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(this)
        // Set initial state
        val isResumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        resumedSubject.onNext(isResumed)
    }

    fun dispose() {
        lifecycleOwner?.lifecycle?.removeObserver(this)
        lifecycleOwner = null
        resumedSubject.onNext(false)
        clearBannerAdHelper()
    }

    override fun onResume(owner: LifecycleOwner) {
        resumedSubject.onNext(true)
    }

    override fun onPause(owner: LifecycleOwner) {
        resumedSubject.onNext(false)
    }

    private fun resumedFlowable(): Flowable<Boolean> =
        resumedSubject.hide().distinctUntilChanged()
            .toFlowable(io.reactivex.BackpressureStrategy.LATEST)
            .observeOn(AndroidSchedulers.mainThread())

    private fun waitForResumed(): Completable {
        return resumedFlowable()
            .filter { it }
            .firstOrError()
            .ignoreElement()
    }

    fun canRequestAds(context: Context): Boolean {
        return ConsentManager.getInstance(context).canRequestAds()
    }

    fun initializeAds(context: Context): Completable {
        // Create once per AdManager instance and reuse for subsequent calls in the session.
        cachedInitializeCompletable?.let { return it }

        synchronized(this) {
            cachedInitializeCompletable?.let { return it }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                val notSupported =
                    Completable.error(RuntimeException("Ads not supported on API < 23"))
                cachedInitializeCompletable = notSupported
                return notSupported
            }

            val init = Completable.create { emitter ->
                MobileAds.initialize(context) { initStatus ->
                    val summary = initStatus.adapterStatusMap.entries.joinToString { (adapter, status) ->
                        "${adapter}:${status.initializationState}"
                    }
                    MyLog.i("$TAG: MobileAds initialized: $summary")
                    if (!emitter.isDisposed) {
                        emitter.onComplete()
                    }
                }
            }
                .subscribeOn(Schedulers.io())
                .andThen(ConsentManager.getInstance(context).gatherConsent(context as Activity))
                .cache()

            cachedInitializeCompletable = init
            return init
        }
    }

    fun loadAndShowAppOpenDisconnected(
        activity: Activity,
        tunnelStateFlowable: Flowable<TunnelState>,
        callback: AdLoadingCallback
    ): Completable {
        requireLifecycleRegistered()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !canRequestAds(activity)) {
            callback.done()
            return Completable.complete()
        }

        val helper = AppOpenAdHelper.create(
            activity = activity,
            config = AdConfig.APP_OPEN_DISCONNECTED,
            tunnelStateFlowable = tunnelStateFlowable,
            loadingCallback = callback
        )

        // load -> wait for resume -> show -> cleanup
        return Completable.complete()
            .observeOn(AndroidSchedulers.mainThread())
            .andThen(Completable.defer { helper.loadAd() })
            .andThen(waitForResumed())
            .andThen(helper.showAd())
            .doFinally {
                callback.done()
                helper.dispose()
            }
    }

    // Toggle button interstitial ad management
    @Volatile private var startInterstitialAdHelper: StartInterstitialAdHelper? = null
    private val interstitialHelperLock = Any()

    private fun getOrCreateStartInterstitialAdHelper(
        activity: Activity,
        tunnelStateFlowable: Flowable<TunnelState>,
        loadingCallback: AdLoadingCallback? = null,
        updateCallbackIfExisting: Boolean = false
    ): StartInterstitialAdHelper {
        synchronized(interstitialHelperLock) {
            startInterstitialAdHelper?.let { existing ->
                // Reuse if it's not stale/timed out (it may be in-flight or ready).
                if (existing.isReusable()) {
                    // Update callback if we are reusing an in-flight load to get progress updates.
                    if (updateCallbackIfExisting && loadingCallback != null) {
                        existing.updateLoadingCallback(loadingCallback)
                    }
                    return existing
                }
                // else dispose and replace
                existing.dispose()
                startInterstitialAdHelper = null
            }

            val helper = StartInterstitialAdHelper.create(
                activity = activity,
                config = AdConfig.INTERSTITIAL_DISCONNECTED,
                tunnelStateFlowable = tunnelStateFlowable,
                loadingCallback = loadingCallback ?: object : AdLoadingCallback {}
            )
            startInterstitialAdHelper = helper
            return helper
        }
    }

    private fun clearStartInterstitialAdHelper(helper: StartInterstitialAdHelper) {
        synchronized(interstitialHelperLock) {
            if (startInterstitialAdHelper === helper) startInterstitialAdHelper = null
        }
    }

    fun ensureStartInterstitialPreloading(
        activity: Activity,
        tunnelStateFlowable: Flowable<TunnelState>
    ): Completable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !canRequestAds(activity)) {
            return Completable.complete()
        }

        return tunnelStateFlowable
            .filter { !it.isUnknown }
            .firstOrError()
            .flatMapCompletable { state ->
                if (!state.isStopped) return@flatMapCompletable Completable.complete()

                val helper = getOrCreateStartInterstitialAdHelper(
                    activity = activity,
                    tunnelStateFlowable = tunnelStateFlowable
                )
                // Joinable load; ignore failures during preload.
                helper.loadAd()
                    .doOnError { e -> MyLog.w("$TAG: preload failed/timeout/stale: $e") }
                    .onErrorComplete()
            }
            .onErrorComplete()
    }

    fun loadAndShowStartInterstitial(
        activity: Activity,
        tunnelStateFlowable: Flowable<TunnelState>,
        callback: AdLoadingCallback
    ): Completable {
        requireLifecycleRegistered()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !canRequestAds(activity)) {
            callback.done()
            return Completable.complete()
        }

        val helper = getOrCreateStartInterstitialAdHelper(
            activity = activity,
            tunnelStateFlowable = tunnelStateFlowable,
            loadingCallback = callback,
            updateCallbackIfExisting = true
        )

        // load -> wait for resume -> show -> cleanup
        return Completable.complete()
            .observeOn(AndroidSchedulers.mainThread())
            .andThen(Completable.defer { helper.loadAd() })
            .andThen(waitForResumed())
            .andThen(helper.showAd())
            .doFinally {
                callback.done()
                helper.dispose()
                clearStartInterstitialAdHelper(helper)   // one-shot after show
            }
    }

    fun attachDisconnectedBannerAd(
        activity: Activity,
        bannerContainer: FrameLayout,
        placeholder: View,
        tunnelStateFlowable: Flowable<TunnelState>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !canRequestAds(activity)) {
            placeholder.visibility = View.VISIBLE
            clearBannerAdHelper()
            return
        }

        synchronized(this) {
            val helper = bannerAdHelper ?: BannerAdHelper.create(
                activity = activity,
                config = AdConfig.BANNER_DISCONNECTED,
                tunnelStateFlowable = tunnelStateFlowable,
                container = bannerContainer,
                placeholder = placeholder
            ).also { bannerAdHelper = it }

            helper.start()
        }
    }

    private fun clearBannerAdHelper() {
        synchronized(this) {
            bannerAdHelper?.dispose()
            bannerAdHelper = null
        }
    }

    private fun requireLifecycleRegistered() {
        if (lifecycleOwner == null) {
            throw IllegalStateException("AdManager lifecycle not registered; call register() with a LifecycleOwner")
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        clearBannerAdHelper()
    }

    fun disposeBannerAd() {
        clearBannerAdHelper()
    }
}
