/*
 *
 * Copyright (c) 2019, Psiphon Inc.
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

package com.psiphon3;

import android.app.Application;

import com.psiphon3.psiphonlibrary.WebViewProxySettings;

import java.util.ArrayList;
import java.util.List;

public class PsiphonApplication extends Application {
    private static List<Object> excludedReceivers = new ArrayList<>();

    @Override
    public void onCreate() {
        // Build a list of receivers to be excluded from sending a proxy change intent
        excludedReceivers = WebViewProxySettings.getReceivers(this);
        super.onCreate();
    }

    public static List<Object> getExcludedReceivers() {
        return excludedReceivers;
    }
}
