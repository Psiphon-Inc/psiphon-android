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
import android.content.Context;
import android.support.multidex.MultiDex;

import com.psiphon3.psiphonlibrary.LocaleManager;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.Utils;

import java.io.IOException;

import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;

public class PsiphonApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        // Do not set locale in the base context if we detected system language should be used
        // because it will prevent locale change when it is triggered via onConfigurationChanged
        // callback when user changes locale in the OS settings.
        LocaleManager localeManager = LocaleManager.getInstance(base);
        if (localeManager.isSetToSystemLocale()) {
            super.attachBaseContext(base);
        } else {
            super.attachBaseContext(localeManager.setLocale(base));
        }
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PsiphonConstants.DEBUG = Utils.isDebugMode(this);

        // If an Rx subscription is disposed while the observable is still running its async task
        // which may throw an error the error will have nowhere to go and will result in an uncaught
        // UndeliverableException being thrown. We are going to set up a global error handler to make
        // sure the app is not crashed in this case. For more details see
        // https://github.com/ReactiveX/RxJava/wiki/What's-different-in-2.0#error-handling
        RxJavaPlugins.setErrorHandler(e -> {
            if (e instanceof UndeliverableException) {
                e = e.getCause();
            }
            if ((e instanceof IOException)) {
                // fine, irrelevant network problem or API that throws on cancellation
                return;
            }
            if (e instanceof InterruptedException) {
                // fine, some blocking code was interrupted by a dispose call
                return;
            }
            Utils.MyLog.g("RxJava undeliverable exception received: " + e);
        });
    }
}
