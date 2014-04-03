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

import android.os.Bundle;
import android.os.Handler;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
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
    extends com.psiphon3.psiphonlibrary.MainBase.Activity 
    implements IEvents
{
    private boolean m_loadedWebView = false;
    private WebView m_webView;
    private TextView m_textView;
    private Handler m_handler = new Handler();
    private TunnelCore m_tunnelCore;
    private Toast m_splashScreen;
    private Runnable m_infiniteToast;

    private void makeSplashScreen()
    {
        LayoutInflater inflater = (LayoutInflater)getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View layout = inflater.inflate(R.layout.splash_screen, (ViewGroup)findViewById(R.id.splash_screen));
        m_textView = (TextView)layout.findViewById(R.id.splash_screen_text);

        // Set background to match (0,0) splash image pixel color
        ImageView imageView = (ImageView)layout.findViewById(R.id.splash_screen_image);
        Bitmap bitmap = ((BitmapDrawable)imageView.getDrawable()).getBitmap();
        int pixel = bitmap.getPixel(0, 0);
        layout.setBackgroundColor(pixel);

        m_splashScreen = new Toast(this);        
        m_splashScreen.setGravity(Gravity.FILL, 0, 0);
        m_splashScreen.setDuration(Toast.LENGTH_SHORT);
        m_splashScreen.setView(layout);

        m_infiniteToast = new Runnable()
            {
                @Override
                public void run()
                {
                    m_splashScreen.show();
                    m_handler.postDelayed(m_infiniteToast, 1850);
                }
            };
    }
    
    private void showSplashScreen()
    {
        dismissSplashScreen();
        m_handler.post(m_infiniteToast);
    }

    private void dismissSplashScreen()
    {
        m_handler.removeCallbacks(m_infiniteToast);
        if (m_splashScreen != null)
        {
            m_splashScreen.cancel();
        }
    }

    private class CustomWebViewClient extends WebViewClient
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
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        m_webView = (WebView)findViewById(R.id.webView);
        m_webView.setWebViewClient(new CustomWebViewClient());
        WebSettings webSettings = m_webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        PsiphonConstants.DEBUG = Utils.isDebugMode(this);
        
        // Restore messages previously posted by the service.
        MyLog.restoreLogHistory();
        
        makeSplashScreen();

        PsiphonData.getPsiphonData().setDefaultSocksPort(PsiphonConstants.SOCKS_PORT + 10);
        PsiphonData.getPsiphonData().setDefaultHttpProxyPort(PsiphonConstants.HTTP_PROXY_PORT + 10);
        m_tunnelCore = new TunnelCore(this, null);
        m_tunnelCore.setUseGenericLogMessages(true);
        m_tunnelCore.setEventsInterface(this);
        m_tunnelCore.onCreate();
        m_tunnelCore.startTunnel();
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
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }

    @Override
    protected void onResume()
    {
        super.onResume();

        if (m_tunnelCore.getState() != TunnelCore.State.CONNECTED)
        {
            showSplashScreen();
        }
    }

    @Override
    protected void onPause()
    {
        dismissSplashScreen();
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && m_webView.canGoBack())
        {
            m_webView.goBack();
            return true;
        }

        return super.onKeyDown(keyCode, event);
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

        m_handler.post(
            new Runnable()
            {
                @Override
                public void run()
                {
                    if (m_textView != null)
                    {
                        m_textView.setText(finalMessage);
                    }
                }
            });
    }
    
    /*
     * Events implementation
     */
    
    private String getHomePage()
    {
        // Only supports one home page
        for (String homePage : PsiphonData.getPsiphonData().getHomePages())
        {
            return homePage;
        }

        return null;
    }

    @Override
    public void signalHandshakeSuccess(Context context, boolean isReconnect)
    {
        final Context finalContext = this;

        m_handler.post(
            new Runnable()
            {
                @Override
                public void run()
                {
                    String homePage = getHomePage();
                    if (homePage != null)
                    {
                        dismissSplashScreen();

                        WebViewProxySettings.setLocalProxy(
                                finalContext,
                                PsiphonData.getPsiphonData().getHttpProxyPort());

                        if (!m_loadedWebView)
                        {
                            // Only load the WebView once. So if we get an unexpected
                            // disconnect and reconnect, the WebView retains its state.
                            // Note this means we ignore changes to the home page during
                            // this session.

                            m_webView.loadUrl(homePage);
                            m_loadedWebView = true;
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
                    showSplashScreen();
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
}
