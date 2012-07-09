package com.psiphon3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;

import android.app.Activity;
import android.content.Intent;
import android.net.MailTo;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class FeedbackActivity extends Activity {

    private WebView webView;
    
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
                    startActivity(intent);
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
                return false;
            }
            
            private boolean submitFeedback(String urlParameters)
            {
                final String formDataParameterName = "formdata=";
                if (urlParameters.startsWith(formDataParameterName))
                {
                    String formdata;
                    try
                    {
                        formdata = URLDecoder.decode(urlParameters.substring(formDataParameterName.length()), "utf-8");
                        // TODO: submit this now.
                        return true;
                    }
                    catch (UnsupportedEncodingException e)
                    {
                        // Just fail
                    }
                }
                return false;
            }
        });

        // Load the feedback page
        String html = getHTMLContent();
        webView.loadDataWithBaseURL("file:///", html, "text/html", "utf-8", null);
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
