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
import android.os.Build
import com.psiphon3.TunnelState
import com.psiphon3.log.MyLog
import io.reactivex.Completable
import io.reactivex.Flowable
import kotlin.jvm.JvmOverloads

class AdFlowHelper {
    companion object {
        private const val TAG = "AdFlowHelper"

        @JvmStatic
        @JvmOverloads
        fun executeToggleButtonFlow(
            activity: Activity,
            adManager: AdManager,
            tunnelStateFlowable: Flowable<TunnelState>,
            adLoadingCallback: AdManager.AdLoadingCallback,
            onTunnelStart: Runnable,
        ): Completable {
            return Completable.defer {
                // Early activity state check
                if (activity.isFinishingOrDestroyed()) {
                    return@defer Completable.complete()
                }

                MyLog.i("$TAG: Toggle button interstitial eligible, proceeding with ad load")
                // Initialize ads then load and show start interstitial
                adManager.initializeAds(activity)
                    .andThen(
                        adManager.loadAndShowStartInterstitial(
                            activity,
                            tunnelStateFlowable,
                            adLoadingCallback
                        )
                    )
                    .doOnError { error ->
                        MyLog.w(
                            "$TAG: Error loading start interstitial",
                            error
                        )
                    }
                    .onErrorComplete()
                    .doOnComplete {
                        MyLog.i("$TAG: Start interstitial complete, proceeding to start tunnel")
                        onTunnelStart.run()
                    }
            }
        }

        @JvmStatic
        @JvmOverloads
        fun executeColdStartFlow(
            activity: Activity,
            adManager: AdManager,
            tunnelStateFlowable: Flowable<TunnelState>,
            adLoadingCallback: AdManager.AdLoadingCallback
        ): Completable {
            return Completable.defer {
                // Early activity state check
                if (activity.isFinishingOrDestroyed()) {
                    return@defer Completable.complete()
                }

                // Initial tunnel state check - only proceed if STOPPED
                tunnelStateFlowable
                    .filter { state -> !state.isUnknown }
                    .firstOrError()
                    .flatMapCompletable { tunnelState ->
                        if (!tunnelState.isStopped) {
                            MyLog.i("$TAG: Tunnel is not stopped, skipping ads")
                            return@flatMapCompletable Completable.complete()
                        }

                        MyLog.i("$TAG: Tunnel is stopped, proceeding with cold start flow")

                        // Check activity state again before proceeding
                        if (activity.isFinishingOrDestroyed()) {
                            return@flatMapCompletable Completable.complete()
                        }

                        MyLog.i("$TAG: Ads eligible, proceeding with cold start flow")
                        // Initialize ads then run app open ad and preloading in parallel
                        adManager.initializeAds(activity)
                            .andThen(
                                Completable.defer {
                                    if (activity.isFinishingOrDestroyed()) {
                                        Completable.complete()
                                    } else {
                                        val appOpenAd =
                                            adManager.loadAndShowAppOpenDisconnected(
                                                activity,
                                                tunnelStateFlowable,
                                                adLoadingCallback
                                            )

                                        // Fire-and-forget preloading interstitial in parallel
                                        MyLog.i("$TAG: starting interstitial preloading in parallel")
                                        adManager.ensureStartInterstitialPreloading(
                                            activity,
                                            tunnelStateFlowable
                                        ).subscribe()

                                        appOpenAd // Only wait for app open ad to complete
                                    }
                                }
                            )
                    }
            }
                .doOnError({ error ->
                    MyLog.w("$TAG: Cold start ad flow failed", error)
                })
                .onErrorComplete()
                .doOnComplete { MyLog.i("$TAG: Cold start ad flow complete") }
                .cache()
        }
    }
}

private fun Activity.isFinishingOrDestroyed(): Boolean =
    isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed)
