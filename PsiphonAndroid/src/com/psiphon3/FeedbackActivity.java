package com.psiphon3;

import android.app.Activity;
import android.content.Intent;
import android.net.MailTo;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.util.Linkify;
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

        // Load the default text
        Spannable sp = new SpannableString(getString(R.string.FeedbackActivity_DefaultText));
        Linkify.addLinks(sp,  Linkify.ALL);
        final String defaultHtml = "<body>" + Html.toHtml(sp) + "</body>";
        webView.loadData(defaultHtml, "text/html", "utf-8");
    }
}
