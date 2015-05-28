/*
 * Copyright (c) 2013, Psiphon Inc.
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

/*[[[cog
import cog
import utils
packagename = utils.get_string(buildname, 'package')
cog.outl('package %s;' % packagename)
]]]*/
package com.psiphon3.psiphonproxiedwebapp;
//[[[end]]]

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.HttpAuthHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.TunnelCore;
import com.psiphon3.psiphonlibrary.IEvents;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.WebViewProxySettings;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class MainActivity 
    extends com.psiphon3.psiphonlibrary.MainBase.SupportFragmentActivity 
    implements IEvents
{
    private enum Display {SPLASH_SCREEN, PROXIED_WEB_VIEW};
    private Display m_currentDisplay;
    private SplashScreen m_splashScreen;
    private ProxiedWebView m_proxiedWebView;
    private TunnelCore m_tunnelCore;
    private Handler m_handler;
    boolean m_initializedWebApp = false;
    private Toast mBackPressedToast;

    public static class SplashScreen extends Fragment
    {
        public static final String FRAGMENT_TAG = "SplashScreen";

        private TextView m_textView;

        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
        {
            View view = inflater.inflate(R.layout.splash_screen, container, false);

            m_textView = (TextView)view.findViewById(R.id.splash_screen_text);

            // Set background to match (0,0) splash image pixel color
            ImageView imageView = (ImageView)view.findViewById(R.id.splash_screen_image);
            Bitmap bitmap = ((BitmapDrawable)imageView.getDrawable()).getBitmap();
            int pixel = bitmap.getPixel(0, 0);
            view.setBackgroundColor(pixel);
            
            setRetainInstance(true);

            return view;
        }
        
        public void setSplashText(String text)
        {
            m_textView.setText(text);
        }
    }
    
    public static class ProxiedWebView extends Fragment
    {
        public static final String FRAGMENT_TAG = "ProxiedWebView";

        private WebView m_webView;

        private static class CustomWebViewClient extends WebViewClient
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url)
            {
                // Always open links in the proxied WebView
                return false;
            }

            @Override
            public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, final String host, final String realm)
            {
                handler.proceed(EmbeddedValues.PROXIED_WEB_APP_HTTP_AUTH_USERNAME, EmbeddedValues.PROXIED_WEB_APP_HTTP_AUTH_PASSWORD);
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
        {
            View view = inflater.inflate(R.layout.proxied_web_view, container, false);

            m_webView = (WebView)view.findViewById(R.id.webView);
            m_webView.setWebViewClient(new CustomWebViewClient());
            WebSettings webSettings = m_webView.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);

            setRetainInstance(true);

            return view;
        }
        
        public WebView getWebView()
        {
            return  m_webView;
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);

        PsiphonConstants.DEBUG = Utils.isDebugMode(this);
        
        // Restore messages previously posted by the service.
        MyLog.restoreLogHistory();
        
        m_splashScreen = new SplashScreen();
        m_proxiedWebView = new ProxiedWebView();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(R.id.content_frame, m_splashScreen, SplashScreen.FRAGMENT_TAG);
        transaction.add(R.id.content_frame, m_proxiedWebView, ProxiedWebView.FRAGMENT_TAG);
        transaction.hide(m_proxiedWebView);
        transaction.commit();
        m_currentDisplay = Display.SPLASH_SCREEN;
        
        PsiphonData.getPsiphonData().setDefaultSocksPort(PsiphonConstants.SOCKS_PORT + 10);
        PsiphonData.getPsiphonData().setDefaultHttpProxyPort(PsiphonConstants.HTTP_PROXY_PORT + 10);
        PsiphonData.getPsiphonData().setEnableReportedStats(false);

        m_tunnelCore = new TunnelCore(this, null);
        m_tunnelCore.setUseGenericLogMessages(true);
        m_tunnelCore.setEventsInterface(this);
        m_tunnelCore.onCreate();
        m_tunnelCore.startTunnel();
        
        m_handler = new Handler();
    }

    void displaySplashScreen()
    {
        if (m_currentDisplay != Display.SPLASH_SCREEN)
        {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.hide(m_proxiedWebView);
            transaction.show(m_splashScreen);
            transaction.commitAllowingStateLoss();
            m_currentDisplay = Display.SPLASH_SCREEN;
        }
    }

    void displayProxiedWebView()
    {
        if (m_currentDisplay != Display.PROXIED_WEB_VIEW)
        {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.hide(m_splashScreen);
            transaction.show(m_proxiedWebView);
            transaction.commitAllowingStateLoss();
            m_currentDisplay = Display.PROXIED_WEB_VIEW;
        }        
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        
        m_tunnelCore.stopTunnel();
        m_tunnelCore.onDestroy();
        m_tunnelCore = null;
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (m_tunnelCore.getState() != TunnelCore.State.CONNECTED)
        {
            displaySplashScreen();
        }
    }

    @Override
    protected void onPause()
    {
        // Don't leave toast dangling if e.g., Home button pressed
        if (mBackPressedToast != null)
        {
            View view = mBackPressedToast.getView();
            if (view != null)
            {
                if (view.isShown())
                {
                    mBackPressedToast.cancel();
                }
            }            
        }
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if (keyCode == KeyEvent.KEYCODE_BACK)
        {
            if (m_currentDisplay == Display.PROXIED_WEB_VIEW)
            {
                WebView webView = m_proxiedWebView.getWebView();
                if (webView.canGoBack())
                {
                    webView.goBack();
                    return true;
                }
            }

            // Confirm before dismissing app
            
            if (mBackPressedToast != null)
            {
                View view = mBackPressedToast.getView();
                if (view != null)
                {
                    if (view.isShown())
                    {
                        mBackPressedToast.cancel();
                        return super.onKeyDown(keyCode, event);
                    }
                }
            }
            String prompt = getString(R.string.back_pressed_confirmation_prompt, getString(R.string.app_name));
            mBackPressedToast = Toast.makeText(this, prompt, Toast.LENGTH_LONG);
            mBackPressedToast.show();
            return true;
        }
        else
        {
        	return super.onKeyDown(keyCode,  event);
        }
    }

    /*
     * MyLog.ILogger implementation
     */

    /**
     * @see com.psiphon3.psiphonlibrary.Utils.MyLog.ILogger#statusEntryAdded()
     */
    @Override
    public void statusEntryAdded()
    {
        // Find the last non-debug status entry.
        PsiphonData.StatusEntry entry = null;
        int index = -1;
        while (true) {
            entry = PsiphonData.getPsiphonData().getStatusEntry(index);
            if (entry == null) 
            {
                // No status entry.
                return;
            }
            
            index -= 1;
            
            if (entry.priority() != Log.DEBUG) {
                break;
            }
        }
        
        final String finalMessage = getString(entry.id(), entry.formatArgs());

        if (m_handler != null && m_splashScreen != null)
        {
            m_handler.post(
                new Runnable()
                {
                    @Override
                    public void run()
                    {
                        m_splashScreen.setSplashText(finalMessage);
                    }
                });
        }
    }
    
    /*
     * Events implementation
     */
    
    @Override
    public void signalHandshakeSuccess(Context context, boolean isReconnect)
    {
        final Context finalContext = context;

        m_handler.post(
            new Runnable()
            {
                @Override
                public void run()
                {
                    if (PsiphonData.getPsiphonData().getHomePages().size() > 0)
                    {
                        String webAppUrl = PsiphonData.getPsiphonData().getHomePages().get(0);

                        displayProxiedWebView();

                        WebViewProxySettings.setLocalProxy(
                                finalContext,
                                PsiphonData.getPsiphonData().getHttpProxyPort());

                        if (!m_initializedWebApp)
                        {
                            // Only load the WebView once. So if we get an unexpected
                            // disconnect and reconnect, the WebView retains its state.
                            // Note this means we ignore changes to the home page during
                            // this session.

                            m_proxiedWebView.getWebView().loadUrl(webAppUrl);
                            m_initializedWebApp = true;
                        }
                    }
                }
            });
    }

    @Override
    public void signalUnexpectedDisconnect(Context context)
    {
        m_handler.post(
            new Runnable()
            {
                @Override
                public void run()
                {
                    displaySplashScreen();
                }
            });
    }

    @Override
    public void signalTunnelStarting(Context context)
    {
    }

    @Override
    public void signalTunnelStopping(Context context)
    {
    }

    @Override
    public Intent pendingSignalNotification(Context context)
    {
        return null;
    }
    
    @Override
    public void displayBrowser(Context context)
    {
        return;
    }

    @Override
    public void displayBrowser(Context context, Uri uri)
    {
        return;
    }
}
