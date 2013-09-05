/*
 * Copyright (c) 2012, Psiphon Inc.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;


import com.psiphon3.psiphonlibrary.Diagnostics;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.MailTo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class FeedbackActivity extends Activity
{

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    public void onCreate(Bundle savedInstanceState)
    {
        final Activity activity = this;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.feedback);

        webView = (WebView)findViewById(R.id.feedbackWebView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient()
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url)
            {
                final String feedbackUrl = "feedback?";

                if (url.startsWith("mailto:"))
                {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("message/rfc822");
                    intent.putExtra(Intent.EXTRA_EMAIL, new String[] {MailTo.parse(url).getTo()});

                    // This is a hack to only include the diagnostics attachment when clicking the
                    // feedback mailto: link.  There is another mailto: link now, the get@ responder,
                    // which should not include the diagnostics attachment.
                    // Including the diagnostics attachment will break if the mailto: link changes
                    // to something that does not include "feedback".
                    if (url.contains("feedback"))
                    {
                        File attachmentFile = Diagnostics.createEmailAttachment(activity);
                        if (attachmentFile != null)
                        {
                            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(attachmentFile));
                        }
                    }

                    try
                    {
                        startActivity(intent);
                    }
                    catch (ActivityNotFoundException e)
                    {
                        // Do nothing
                    }

                    return true;
                }
                else if (url.contains(feedbackUrl))
                {
                    if (submitFeedback(url.substring(
                            url.indexOf(feedbackUrl) + feedbackUrl.length())))
                    {
                        Toast.makeText(activity,
                                getString(R.string.FeedbackActivity_Success),
                                Toast.LENGTH_SHORT).show();
                        activity.finish();
                    }
                    else
                    {
                        Toast.makeText(activity,
                                getString(R.string.FeedbackActivity_Failure),
                                Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                
                // Else... Open ordinary URLs (like our download page, FAQ, etc.)
                // in a standard browser. The feedback WebView is not useful
                // for normal webpages (and doesn't seem to support download).
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                try
                {
                    startActivity(browserIntent);
                }
                catch (ActivityNotFoundException e)
                {
                    // Do nothing
                }

                return true;
            }

            private boolean submitFeedback(String urlParameters)
            {
                final String formDataParameterName = "formdata=";
                if (!urlParameters.startsWith(formDataParameterName))
                {
                    MyLog.w(R.string.FeedbackActivity_InvalidURLParameters, MyLog.Sensitivity.NOT_SENSITIVE);
                    return false;
                }

                ServerInterface serverInterface = new ServerInterface(activity);
                serverInterface.start();
                serverInterface.setCurrentServerEntry();

                String formData;
                try
                {
                    formData = URLDecoder.decode(urlParameters.substring(formDataParameterName.length()), "utf-8");
                }
                catch (UnsupportedEncodingException e)
                {
                    MyLog.w(R.string.FeedbackActivity_SubmitFeedbackFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                    return false;
                }

                // Hack to get around network/UI restriction. Still blocks the UI
                // for the duration of the request until timeout.
                // TODO: Actually run in the background
                class FeedbackRequestThread extends Thread
                {
                    private ServerInterface m_serverInterface;
                    private String m_formData;
                    private boolean m_success = false;

                    FeedbackRequestThread(ServerInterface serverInterface, String formData)
                    {
                        m_serverInterface = serverInterface;
                        m_formData = formData;
                    }

                    public void run()
                    {
                        try
                        {
                            m_serverInterface.doFeedbackRequest(m_formData);
                            m_success = true;
                        }
                        catch (PsiphonServerInterfaceException e)
                        {
                            MyLog.w(R.string.FeedbackActivity_SubmitFeedbackFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                        }
                    }

                    public boolean getSuccess()
                    {
                        return m_success;
                    }
                }

                try
                {
                    FeedbackRequestThread thread = new FeedbackRequestThread(serverInterface, formData);
                    thread.start();
                    thread.join();
                    return thread.getSuccess();
                }
                catch (InterruptedException e)
                {
                    MyLog.w(R.string.FeedbackActivity_SubmitFeedbackFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                    return false;
                }
            }
        });

        // Load the feedback page
        String html = getHTMLContent();
        // Get the current locale
        String language = Locale.getDefault().getLanguage();
        
        StringBuilder argsBuilder = new StringBuilder();
        argsBuilder.append("{ ");
        // We are avoiding any possible conflict with the Play Store policy that apps cannot automatically self-upgrade:
        // "An app downloaded from Google Play may not modify, replace or update its own APK binary code using any
        // method other than Google Play's update mechanism."
        // (https://play.google.com/about/developer-content-policy.html)
        // These links, if followed, can be used to side-load upgrade the app, bypassing the Play Store upgrade
        // mechanism.  While it is arguable that this is in conflict with the Play Store policy, we are removing
        // this links to be cautious and to avoid any possible disruptions.
        if (!EmbeddedValues.IS_PLAY_STORE_BUILD)
        {
            argsBuilder.append("\"newVersionURL\":\"").append(EmbeddedValues.GET_NEW_VERSION_URL).append("\", ");
            argsBuilder.append("\"newVersionEmail\": \"").append(EmbeddedValues.GET_NEW_VERSION_EMAIL).append("\", ");
        }
        argsBuilder.append("\"faqURL\": \"").append(EmbeddedValues.FAQ_URL).append("\", ");
        argsBuilder.append("\"dataCollectionInfoURL\": \"").append(EmbeddedValues.DATA_COLLECTION_INFO_URL).append("\" }");
        String args = null;
        try {
            args = URLEncoder.encode(argsBuilder.toString(), "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            assert(false);
        }
        
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append("file:///");
        if (args != null)
        {
            urlBuilder.append("?").append(args);
        }
        urlBuilder.append("#").append(language);
        
        webView.loadDataWithBaseURL(urlBuilder.toString(), html, "text/html", "utf-8", null);
    }

    private String getHTMLContent()
    {
        String html = "";

        try
        {
            InputStream stream = getAssets().open("feedback.html");
            html = streamToString(stream);
        }
        catch (IOException e)
        {
            MyLog.w(R.string.FeedbackActivity_GetHTMLContentFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);

            // Render the default text
            html = "<body>" + getString(R.string.FeedbackActivity_DefaultText) + "</body>";
        }

        return html;
    }

    private String streamToString(InputStream stream) throws IOException
    {
        try
        {
            Reader reader = new BufferedReader(new InputStreamReader(stream));
            Writer writer = new StringWriter();

            int n;
            char[] buffer = new char[2048];
            while ((n = reader.read(buffer)) != -1)
            {
                writer.write(buffer, 0, n);
            }
            return writer.toString();
        }
        finally
        {
            stream.close();
        }
    }
}
