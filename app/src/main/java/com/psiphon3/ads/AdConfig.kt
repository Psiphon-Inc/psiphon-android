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

import com.psiphon3.TunnelState
import com.psiphon3.subscription.BuildConfig

data class AppOpenAdConfig(
    // TODO: need a proper state with connected/disconnected, this here only
    // gives us the STOPPED state, but not the CONNECTED state.
    val requiredTunnelState: TunnelState.Status,
    val adUnitId: String,
)

object AdConfig {

    val APP_OPEN_DISCONNECTED = AppOpenAdConfig(
        requiredTunnelState = TunnelState.Status.STOPPED,
        adUnitId = BuildConfig.APP_OPEN_DISCONNECTED_ID
    )
}