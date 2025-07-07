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
import com.google.android.gms.ads.MobileAds
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.schedulers.Schedulers

class AdManager {
    companion object {
        private const val TAG = "AdManager"
    }

    fun initializeAds(context: Context): Completable {
        return Completable.defer {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return@defer Completable.error(RuntimeException("Ads not supported on API < 23"))
            }

            Completable.create { emitter ->
                MobileAds.initialize(context) { status ->
                    MyLog.i("MobileAds initialized with status: $status")
                    if (!emitter.isDisposed) {
                        emitter.onComplete()
                    }
                }
            }
                .subscribeOn(Schedulers.io())
                .andThen(ConsentManager.getInstance(context).gatherConsent(context as Activity))
        }.cache()
    }

    fun loadAndShowAppOpenDisconnected(
        activity: Activity,
        tunnelStateFlowable: Flowable<TunnelState>,
        callback: AdLoadingCallback
    ): Completable {
        return Completable.create { emitter ->
            if (!canRequestAds(activity)) {
                MyLog.w("AdManager: Cannot request ads - consent not granted or ads not available")
                callback.done()
                if (!emitter.isDisposed) {
                    emitter.onComplete()
                }
                return@create
            }

            val adManagerCallback = object : AppOpenAdManager.AdManagerCallback {
                override fun onAdShowing() {
                }

                override fun onAdCompleted() {
                    callback.done()
                    if (!emitter.isDisposed) {
                        emitter.onComplete()
                    }
                }

                override fun onAdFailed(error: String) {
                    callback.done()
                    if (!emitter.isDisposed) {
                        emitter.onComplete()
                    }
                }
            }

            // Create and start the app open ad manager
            val appOpenAdManager = AppOpenAdManager.create(
                activity = activity,
                config = AdConfig.APP_OPEN_DISCONNECTED,
                tunnelStateFlowable = tunnelStateFlowable,
                callback = adManagerCallback,
                loadingCallback = callback
            )

            // Clean up when emitter is disposed
            emitter.setCancellable {
                appOpenAdManager.dispose()
            }
        }
    }

    fun canRequestAds(context: Context): Boolean {
        return ConsentManager.getInstance(context).canRequestAds()
    }

    interface AdLoadingCallback {
        fun startedLoading(timeoutSeconds: Int)
        fun updateLoadingProgress(remainingSeconds: Float) // float for smooth progress updates
        fun done()
    }
}
