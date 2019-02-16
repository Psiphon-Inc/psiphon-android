package com.psiphon3;

import android.app.Application;

import com.psiphon3.psiphonlibrary.WebViewProxySettings;

public class PsiphonApplication extends Application {
    @Override
    public void onCreate() {
	// Build a list of receivers to be excluded from sending a proxy change intent
        WebViewProxySettings.excludeReceivers(this);
        super.onCreate();
    }
}
