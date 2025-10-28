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
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.psiphon3.log.MyLog
import io.reactivex.Completable

class ConsentManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ConsentManager? = null

        @JvmStatic
        fun getInstance(context: Context): ConsentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConsentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val consentInformation = UserMessagingPlatform.getConsentInformation(context)

    fun gatherConsent(activity: Activity): Completable {
        return Completable.create { emitter ->
            if (activity.isFinishing || activity.isDestroyed) {
                if (!emitter.isDisposed) {
                    emitter.onComplete()
                }
                return@create
            }

            MyLog.i("ConsentManager: gathering consent")

            val params = ConsentRequestParameters.Builder()
                .build()

            consentInformation.requestConsentInfoUpdate(
                activity,
                params,
                {
                    if (activity.isFinishing || activity.isDestroyed) {
                        if (!emitter.isDisposed) {
                            emitter.onComplete()
                        }
                        return@requestConsentInfoUpdate
                    }

                    // Consent info updated successfully - now show form if needed
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) {
                            MyLog.e("Consent form error: ${formError.errorCode}: ${formError.message}")
                        }

                        if (!activity.isFinishing && !activity.isDestroyed && !emitter.isDisposed) {
                            // Complete regardless of error (don't block ads for consent issues)
                            emitter.onComplete()
                        }
                    }
                },
                { requestError ->
                    MyLog.e("Consent request error: ${requestError.errorCode}: ${requestError.message}")
                    if (!activity.isFinishing && !activity.isDestroyed && !emitter.isDisposed) {
                        // Complete even on error (don't block app)
                        emitter.onComplete()
                    }
                }
            )
        }
    }

    fun showPrivacyOptionsForm(activity: Activity): Completable {
        return Completable.create { emitter ->
            if (activity.isFinishing || activity.isDestroyed) {
                if (!emitter.isDisposed) {
                    emitter.onComplete()
                }
                return@create
            }

            MyLog.i("ConsentManager: showing privacy options form")

            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                if (formError != null) {
                    MyLog.e("Privacy options form error: ${formError.errorCode}: ${formError.message}")
                }

                if (!activity.isFinishing && !activity.isDestroyed && !emitter.isDisposed) {
                    emitter.onComplete()
                }
            }
        }
    }

    fun canRequestAds(): Boolean = consentInformation.canRequestAds()

    fun isPrivacyOptionsRequired(): Boolean {
        return consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }
}