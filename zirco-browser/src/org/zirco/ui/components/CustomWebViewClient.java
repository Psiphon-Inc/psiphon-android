/*
 * Zirco Browser for Android
 * 
 * Copyright (C) 2010 - 2012 J. Devauchelle and contributors.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package org.zirco.ui.components;

import org.zirco.R;
import org.zirco.controllers.Controller;
import org.zirco.ui.activities.MainActivity;
import org.zirco.utils.ApplicationUtils;
import org.zirco.utils.Constants;
import org.zirco.utils.UrlUtils;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebView.HitTestResult;
import android.widget.EditText;

/**
 * Convenient extension of WebViewClient.
 */
public class CustomWebViewClient extends WebViewClient {
	
	private MainActivity mMainActivity;
	
	public CustomWebViewClient(MainActivity mainActivity) {
		super();
		mMainActivity = mainActivity;
	}
	
	@Override
	public void onPageFinished(WebView view, String url) {			
		((CustomWebView) view).notifyPageFinished();
		mMainActivity.onPageFinished(url);
		
		super.onPageFinished(view, url);
	}

	@Override
	public void onPageStarted(WebView view, String url, Bitmap favicon) {
		
		// Some magic here: when performing WebView.loadDataWithBaseURL, the url is "file:///android_asset/startpage,
		// whereas when the doing a "previous" or "next", the url is "about:start", and we need to perform the
		// loadDataWithBaseURL here, otherwise it won't load.
		if (url.equals(Constants.URL_ABOUT_START)) {
			view.loadDataWithBaseURL("file:///android_asset/startpage/",
					ApplicationUtils.getStartPage(view.getContext()), "text/html", "UTF-8", "about:start");
		}
		
		((CustomWebView) view).notifyPageStarted();
		mMainActivity.onPageStarted(url);
		
		super.onPageStarted(view, url, favicon);
	}

	@Override
	public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
		
		StringBuilder sb = new StringBuilder();
		
		sb.append(view.getResources().getString(R.string.Commons_SslWarningsHeader));
		sb.append("\n\n");
		
		if (error.hasError(SslError.SSL_UNTRUSTED)) {
			sb.append(" - ");
			sb.append(view.getResources().getString(R.string.Commons_SslUntrusted));
			sb.append("\n");
		}
		
		if (error.hasError(SslError.SSL_IDMISMATCH)) {
			sb.append(" - ");
			sb.append(view.getResources().getString(R.string.Commons_SslIDMismatch));
			sb.append("\n");
		}
		
		if (error.hasError(SslError.SSL_EXPIRED)) {
			sb.append(" - ");
			sb.append(view.getResources().getString(R.string.Commons_SslExpired));
			sb.append("\n");
		}
		
		if (error.hasError(SslError.SSL_NOTYETVALID)) {
			sb.append(" - ");
			sb.append(view.getResources().getString(R.string.Commons_SslNotYetValid));
			sb.append("\n");
		}
		
		ApplicationUtils.showContinueCancelDialog(view.getContext(),
				android.R.drawable.ic_dialog_info,
				view.getResources().getString(R.string.Commons_SslWarning),
				sb.toString(),
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						handler.proceed();
					}

				},
				new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						dialog.dismiss();
						handler.cancel();
					}
		});
	}

	@Override
	public boolean shouldOverrideUrlLoading(WebView view, String url) {				
		
		if (isExternalApplicationUrl(url)) {
			mMainActivity.onExternalApplicationUrl(url);
			return true;
			
		} else if (url.startsWith(Constants.URL_ACTION_SEARCH)) {
			String searchTerm = url.replace(Constants.URL_ACTION_SEARCH, "");
			
			String searchUrl = Controller.getInstance().getPreferences().getString(Constants.PREFERENCES_GENERAL_SEARCH_URL, Constants.URL_SEARCH_GOOGLE);
			String newUrl = String.format(searchUrl, searchTerm);
			
			view.loadUrl(newUrl);
			return true;
			
		} else if (view.getHitTestResult().getType() == HitTestResult.EMAIL_TYPE) {
			mMainActivity.onMailTo(url);
			return true;
			
		} else {
						
			// If the url is not from GWT mobile view, and is in the mobile view url list, then load it with GWT.			
			if ((!url.startsWith(Constants.URL_GOOGLE_MOBILE_VIEW_NO_FORMAT)) &&
					(UrlUtils.checkInMobileViewUrlList(view.getContext(), url))) {
				
				String newUrl = String.format(Constants.URL_GOOGLE_MOBILE_VIEW, url);
				view.loadUrl(newUrl);
				return true;
				
			} else {			
				((CustomWebView) view).resetLoadedUrl();
				mMainActivity.onUrlLoading(url);
				return false;
			}
		}
	}
	
	@Override
	public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, final String host, final String realm) {
		String username = null;
        String password = null;
        
        boolean reuseHttpAuthUsernamePassword = handler.useHttpAuthUsernamePassword();
        
        if (reuseHttpAuthUsernamePassword && view != null) {
            String[] credentials = view.getHttpAuthUsernamePassword(
                    host, realm);
            if (credentials != null && credentials.length == 2) {
                username = credentials[0];
                password = credentials[1];
            }
        }

        if (username != null && password != null) {
            handler.proceed(username, password);
        } else {
        	LayoutInflater factory = LayoutInflater.from(mMainActivity);
            final View v = factory.inflate(R.layout.http_authentication_dialog, null);
            
            if (username != null) {
                ((EditText) v.findViewById(R.id.username_edit)).setText(username);
            }
            if (password != null) {
                ((EditText) v.findViewById(R.id.password_edit)).setText(password);
            }
            
            AlertDialog dialog = new AlertDialog.Builder(mMainActivity)
            .setTitle(String.format(mMainActivity.getString(R.string.HttpAuthenticationDialog_DialogTitle), host, realm))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setView(v)
            .setPositiveButton(R.string.Commons_Proceed,
                    new DialogInterface.OnClickListener() {
                         public void onClick(DialogInterface dialog,
                                 int whichButton) {
                            String nm = ((EditText) v
                                    .findViewById(R.id.username_edit))
                                    .getText().toString();
                            String pw = ((EditText) v
                                    .findViewById(R.id.password_edit))
                                    .getText().toString();
                            mMainActivity.setHttpAuthUsernamePassword(host, realm, nm, pw);
                            handler.proceed(nm, pw);
                        }})
            .setNegativeButton(R.string.Commons_Cancel,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            handler.cancel();
                        }})
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface dialog) {
                        handler.cancel();
                    }})
            .create();
            
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            dialog.show();
                        
            v.findViewById(R.id.username_edit).requestFocus();            
        }
	}

	private boolean isExternalApplicationUrl(String url) {
		return url.startsWith("vnd.") ||
				url.startsWith("rtsp://") ||
				url.startsWith("itms://") ||
				url.startsWith("itpc://");
	}

}
