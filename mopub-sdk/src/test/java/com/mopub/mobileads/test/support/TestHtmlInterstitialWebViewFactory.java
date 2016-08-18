package com.mopub.mobileads.test.support;

import android.content.Context;

import com.mopub.common.AdReport;
import com.mopub.mobileads.HtmlInterstitialWebView;
import com.mopub.mobileads.factories.HtmlInterstitialWebViewFactory;

import static com.mopub.mobileads.CustomEventInterstitial.CustomEventInterstitialListener;
import static org.mockito.Mockito.mock;

public class TestHtmlInterstitialWebViewFactory extends HtmlInterstitialWebViewFactory {
    private HtmlInterstitialWebView mockHtmlInterstitialWebView = mock(HtmlInterstitialWebView.class);

    private CustomEventInterstitialListener latestListener;
    private boolean latestIsScrollable;
    private String latestRedirectUrl;
    private String latestClickthroughUrl;

    public static HtmlInterstitialWebView getSingletonMock() {
        return getTestFactory().mockHtmlInterstitialWebView;
    }

    private static TestHtmlInterstitialWebViewFactory getTestFactory() {
        return (TestHtmlInterstitialWebViewFactory) instance;
    }

    @Override
    public HtmlInterstitialWebView internalCreate(Context context, AdReport adReport, CustomEventInterstitialListener customEventInterstitialListener, boolean isScrollable, String redirectUrl, String clickthroughUrl) {
        latestListener = customEventInterstitialListener;
        latestIsScrollable = isScrollable;
        latestRedirectUrl = redirectUrl;
        latestClickthroughUrl = clickthroughUrl;
        return getTestFactory().mockHtmlInterstitialWebView;
    }

    public static CustomEventInterstitialListener getLatestListener() {
        return getTestFactory().latestListener;
    }

    public static boolean getLatestIsScrollable() {
        return getTestFactory().latestIsScrollable;
    }
    public static String getLatestRedirectUrl() {
        return getTestFactory().latestRedirectUrl;
    }

    public static String getLatestClickthroughUrl() {
        return getTestFactory().latestClickthroughUrl;
    }
}
