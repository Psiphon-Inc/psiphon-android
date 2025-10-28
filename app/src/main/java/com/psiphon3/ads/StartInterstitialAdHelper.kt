package com.psiphon3.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.disposables.Disposable
import io.reactivex.subjects.CompletableSubject
import java.util.concurrent.TimeoutException

class StartInterstitialAdHelper private constructor(
    private val activity: Activity,
    private val config: InterstitialAdConfig,
    private val tunnelStateFlowable: Flowable<TunnelState>,
    private var loadingCallback: AdManager.AdLoadingCallback
) {

    companion object {
        private const val AD_LOAD_TIMEOUT_SECONDS = 10
        private const val TAG = "StartInterstitialAdHelper"

        fun create(
            activity: Activity,
            config: InterstitialAdConfig,
            tunnelStateFlowable: Flowable<TunnelState>,
            loadingCallback: AdManager.AdLoadingCallback
        ): StartInterstitialAdHelper {
            return StartInterstitialAdHelper(activity, config, tunnelStateFlowable, loadingCallback)
        }
    }

    private var interstitialAd: InterstitialAd? = null
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

    fun isReusable(): Boolean = !isStale && !isTimedOut

    fun updateLoadingCallback(cb: AdManager.AdLoadingCallback) {
        this.loadingCallback = cb
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

    // Completes when loaded; joins if already loading. Errors on timeout / stale / SDK load fail.
    fun loadAd(): Completable = synchronized(this) {
        if (isAdReady && interstitialAd != null && !isStale && !isTimedOut) {
            return@synchronized Completable.complete()
        }
        if (isStale || isTimedOut) {
            return@synchronized Completable.error(IllegalStateException("Interstitial ad invalid state (stale=$isStale, timedOut=$isTimedOut)"))
        }
        loadSubject?.let { return@synchronized it.hide() }

        val subj = CompletableSubject.create()
        loadSubject = subj

        // Progress/timeout
        loadingCallback.startedLoading(AD_LOAD_TIMEOUT_SECONDS)
        startLoadingCountdown()

        val startLoad = {
            InterstitialAd.load(
                activity,
                config.adUnitId,
                AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        if (isStale || isTimedOut) {
                            MyLog.w("$TAG: loaded but stale/timedOut; discard")
                            loadSubject?.onError(IllegalStateException("Loaded after stale/timeout"))
                            loadSubject = null
                            return
                        }
                        stopCountdown()
                        interstitialAd = ad
                        isAdReady = true
                        MyLog.i("$TAG: Interstitial ad loaded")
                        loadSubject?.onComplete()
                        loadSubject = null
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (isTimedOut) return
                        MyLog.e("$TAG: load failed: $error")
                        stopCountdown()
                        cleanup()
                        loadSubject?.onError(RuntimeException("Interstitial load failed: ${error.message}"))
                        loadSubject = null
                    }
                }
            )
        }

        if (Looper.myLooper() == Looper.getMainLooper()) startLoad() else handler.post { startLoad() }

        subj.hide()
    }

    // Completes on dismiss; errors if not ready/stale or on SDK show failure.
    fun showAd(): Completable = Completable.create { emitter ->
        if (!isAdReady || interstitialAd == null || isStale || isTimedOut) {
            cleanup()
            emitter.onError(IllegalStateException("Interstitial not ready or stale (ready=$isAdReady, stale=$isStale, timedOut=$isTimedOut)"))
            return@create
        }

        interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // optional: log/metrics
            }

            override fun onAdDismissedFullScreenContent() {
                cleanup()
                if (!emitter.isDisposed) emitter.onComplete()
            }

            override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                cleanup()
                if (!emitter.isDisposed) emitter.onError(RuntimeException("Interstitial failed to show: ${adError.message}"))
            }
        }

        interstitialAd?.let { MyLog.i("$TAG: showing interstitial ad") }
        interstitialAd?.show(activity)
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
        subj?.onError(TimeoutException("Interstitial load timeout"))
    }

    private fun markAsStale() {
        if (isStale) return
        isStale = true
        stopCountdown()
        cleanup()
        // fail the in-flight load if any
        loadSubject?.onError(IllegalStateException("Interstitial stale due to tunnel change"))
        loadSubject = null
    }

    private fun cleanup() {
        stopCountdown()
        tunnelStateDisposable?.dispose()
        tunnelStateDisposable = null
        interstitialAd = null
        isAdReady = false
    }

    fun dispose() {
        cleanup()
    }
}
