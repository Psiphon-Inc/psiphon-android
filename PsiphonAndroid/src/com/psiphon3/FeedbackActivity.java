package com.psiphon3;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;

import android.app.Activity;
import android.content.Intent;
import android.net.MailTo;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class FeedbackActivity extends Activity {

    private WebView webView;
    
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.feedback);
        
        webView = (WebView)findViewById(R.id.feedbackWebView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient()
        {
            public boolean shouldOverrideUrlLoading(WebView view, String url)
            {
                if (url.startsWith("mailto:"))
                {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("message/rfc822");
                    intent.putExtra(Intent.EXTRA_EMAIL, new String[] {MailTo.parse(url).getTo()});
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        // Load the feedback page
        String html = getHTMLContent();
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
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
