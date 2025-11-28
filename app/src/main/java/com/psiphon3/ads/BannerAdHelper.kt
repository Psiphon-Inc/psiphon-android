package com.psiphon3.ads

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlin.math.roundToInt

class BannerAdHelper private constructor(
    private val activity: Activity,
    private val config: BannerAdConfig,
    private val tunnelStateFlowable: Flowable<TunnelState>,
    private val container: FrameLayout,
    private val placeholder: View
) {

    companion object {
        private const val TAG = "BannerAdHelper"

        fun create(
            activity: Activity,
            config: BannerAdConfig,
            tunnelStateFlowable: Flowable<TunnelState>,
            container: FrameLayout,
            placeholder: View
        ): BannerAdHelper {
            return BannerAdHelper(
                activity,
                config,
                tunnelStateFlowable,
                container,
                placeholder
            )
        }
    }

    private var adView: AdView? = null
    private var started = false
    private val disposables = CompositeDisposable()

    fun start() {
        if (started) return
        started = true
        startMonitoring()
    }

    private fun startMonitoring() {
        disposables.add(
            tunnelStateFlowable
                .map { it.status() }
                .filter { it != TunnelState.Status.UNKNOWN }
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { status ->
                        if (status == config.requiredTunnelState) {
                            ensureAdLoaded()
                        } else {
                            destroyAd("tunnel=$status")
                        }
                    },
                    { err -> MyLog.e("$TAG: monitor error: $err") }
                )
        )
    }

    private fun ensureAdLoaded() {
        if (adView != null) return
        // Make sure the container is laid out and measured before loading the banner
        container.doOnLayout { view ->
            loadBannerIfNeeded(view.width, view.height)
        }
    }

    private fun loadBannerIfNeeded(widthPx: Int, heightPx: Int) {
        if (adView != null) return
        if (widthPx <= 0 || heightPx <= 0) {
            MyLog.w("$TAG: banner container not measured (w=$widthPx, h=$heightPx), skipping load")
            destroyAd("container not measured")
            return
        }

        val density = activity.resources.displayMetrics.density
        val adWidth = (widthPx / density).roundToInt().coerceAtLeast(1)
        val adHeight = (heightPx / density).roundToInt().coerceAtLeast(1)

        MyLog.i("$TAG: starting banner load (w=$adWidth, h=$adHeight)")

        val size = AdSize.getInlineAdaptiveBannerAdSize(adWidth, adHeight)
        val view = AdView(activity)
        view.adUnitId = config.adUnitId
        view.setAdSize(size)
        view.adListener = object : AdListener() {
            override fun onAdLoaded() {
                placeholder.visibility = View.GONE
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                destroyAd("load failed: ${error.message}")
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        container.addView(view, params)
        adView = view
        view.loadAd(AdRequest.Builder().build())
    }

    private fun destroyAd(reason: String) {
        container.post {
            if (adView == null) {
                placeholder.visibility = View.VISIBLE
                return@post
            }

            MyLog.i("$TAG: destroying banner ad ($reason)")
            adView?.destroy()
            adView?.let { container.removeView(it) }
            adView = null
            placeholder.visibility = View.VISIBLE
        }
    }

    fun dispose() {
        disposables.dispose()
        destroyAd("dispose")
    }
}
