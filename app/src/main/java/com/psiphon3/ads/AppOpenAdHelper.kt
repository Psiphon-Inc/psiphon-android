package com.psiphon3.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.CompletableSubject
import java.util.concurrent.TimeoutException

class AppOpenAdHelper private constructor(
    private val activity: Activity,
    private val config: AppOpenAdConfig,
    private val tunnelStateFlowable: Flowable<TunnelState>,
    private val loadingCallback: AdManager.AdLoadingCallback
) {

    companion object {
        private const val AD_LOAD_TIMEOUT_SECONDS = 10
        private const val TAG = "AppOpenAdHelper"

        fun create(
            activity: Activity,
            config: AppOpenAdConfig,
            tunnelStateFlowable: Flowable<TunnelState>,
            loadingCallback: AdManager.AdLoadingCallback
        ): AppOpenAdHelper {
            return AppOpenAdHelper(activity, config, tunnelStateFlowable, loadingCallback)
        }
    }

    private var appOpenAd: AppOpenAd? = null
    private var isAdReady = false
    private var isStale = false
    private var isTimedOut = false

    private var tunnelStateDisposable: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    // Joinable load
    private var loadSubject: CompletableSubject? = null

    init {
        startTunnelStateMonitoring()
    }

    private fun startTunnelStateMonitoring() {
        tunnelStateDisposable = tunnelStateFlowable
            .subscribe(
                { st ->
                    if (!st.isUnknown && st.status() != config.requiredTunnelState) {
                        MyLog.w("$TAG: Tunnel state=${st.status()} != ${config.requiredTunnelState}; mark stale")
                        markAsStale()
                    }
                },
                { err -> MyLog.e("$TAG: tunnel monitor error: $err") }
            )
    }

    // Completes when ad is loaded; errors if stale/timedOut or on load failure/timeout.
    fun loadAd(): Completable = synchronized(this) {
        if (isAdReady && appOpenAd != null && !isStale && !isTimedOut) {
            return@synchronized Completable.complete()
        }
        if (isStale || isTimedOut) {
            return@synchronized Completable.error(IllegalStateException("AppOpen ad invalid state (stale=$isStale, timedOut=$isTimedOut)"))
        }
        loadSubject?.let { return@synchronized it.hide() }

        val subj = CompletableSubject.create()
        loadSubject = subj

        // Progress/timeout
        loadingCallback.startedLoading(AD_LOAD_TIMEOUT_SECONDS)
        startLoadingCountdown()

        AppOpenAd.load(
            activity,
            config.adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    if (isStale || isTimedOut) {
                        MyLog.w("$TAG: loaded but stale/timedOut; discard")
                        loadSubject?.onError(IllegalStateException("Loaded after stale/timeout"))
                        loadSubject = null
                        return
                    }
                    stopCountdown()
                    appOpenAd = ad
                    isAdReady = true
                    MyLog.i("$TAG: App open ad loaded")
                    loadSubject?.onComplete()
                    loadSubject = null
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (isTimedOut) return
                    MyLog.e("$TAG: load failed: $error")
                    stopCountdown()
                    cleanup()
                    loadSubject?.onError(RuntimeException("AppOpen load failed: ${error.message}"))
                    loadSubject = null
                }
            }
        )

        subj.hide()
    }

    // Completes when ad is shown and dismissed; errors if not ready/stale/timedOut or on show failure.
    fun showAd(): Completable = Completable.create { emitter ->
        if (!isAdReady || appOpenAd == null || isStale || isTimedOut) {
            cleanup()
            emitter.onError(IllegalStateException("AppOpen not ready or stale (ready=$isAdReady, stale=$isStale, timedOut=$isTimedOut)"))
            return@create
        }

        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // optional: log/metrics
            }

            override fun onAdDismissedFullScreenContent() {
                cleanup()
                if (!emitter.isDisposed) emitter.onComplete()
            }

            override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                cleanup()
                if (!emitter.isDisposed) emitter.onError(RuntimeException("AppOpen failed to show: ${adError.message}"))
            }
        }

        appOpenAd?.let { MyLog.i("$TAG: showing app open ad") }
        appOpenAd?.show(activity)
    }

    private fun startLoadingCountdown() {
        var elapsedSeconds = 0.1f
        fun updateProgress() {
            if (isStale || isTimedOut || isAdReady) return

            if (elapsedSeconds >= AD_LOAD_TIMEOUT_SECONDS) {
                MyLog.i("$TAG: timeout after $AD_LOAD_TIMEOUT_SECONDS s")
                onAdLoadTimeout()
                return
            }

            loadingCallback.updateLoadingProgress(elapsedSeconds)
            elapsedSeconds += 0.1f
            countdownRunnable = Runnable { updateProgress() }
            handler.postDelayed(countdownRunnable!!, 100)
        }
        updateProgress()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    private fun onAdLoadTimeout() {
        isTimedOut = true
        stopCountdown()
        val subj = loadSubject
        loadSubject = null
        cleanup()
        subj?.onError(TimeoutException("AppOpen load timeout"))
    }

    private fun markAsStale() {
        if (isStale) return
        isStale = true
        stopCountdown()
        cleanup()
        // fail the in-flight load if any
        loadSubject?.onError(IllegalStateException("AppOpen stale due to tunnel change"))
        loadSubject = null
    }

    private fun cleanup() {
        stopCountdown()
        tunnelStateDisposable?.dispose()
        tunnelStateDisposable = null
        appOpenAd = null
        isAdReady = false
    }

    fun dispose() {
        cleanup()
    }
}
