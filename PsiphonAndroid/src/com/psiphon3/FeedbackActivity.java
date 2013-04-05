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
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import org.yaml.snakeyaml.Yaml;

import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.PsiphonData.StatusEntry;
import com.psiphon3.psiphonlibrary.ServerInterface.PsiphonServerInterfaceException;
import com.psiphon3.psiphonlibrary.Utils.Base64;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.MailTo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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
            @SuppressLint("SimpleDateFormat")
            private File createEmailAttachment()
            {
                // Our attachment is YAML, which is then encrypted, and the
                // encryption elements stored in JSON.

                String diagnosticYaml;

                /*
                 * Metadata
                 */

                Map<String, Object> metadata = new HashMap<String, Object>();
                metadata.put("platform", "android");
                metadata.put("version", 2);

                SecureRandom rnd = new SecureRandom();
                byte[] id = new byte[8];
                rnd.nextBytes(id);
                metadata.put("id", Utils.byteArrayToHexString(id));

                /*
                 * System Information
                 */

                Map<String, Object> sysInfo = new HashMap<String, Object>();
                Map<String, Object> sysInfo_Build = new HashMap<String, Object>();
                sysInfo.put("Build", sysInfo_Build);
                sysInfo_Build.put("BRAND", Build.BRAND);
                sysInfo_Build.put("CPU_ABI", Build.CPU_ABI);
                sysInfo_Build.put("MANUFACTURER", Build.MANUFACTURER);
                sysInfo_Build.put("MODEL", Build.MODEL);
                sysInfo_Build.put("TAGS", Build.TAGS);
                sysInfo_Build.put("VERSION__CODENAME", Build.VERSION.CODENAME);
                sysInfo_Build.put("VERSION__RELEASE", Build.VERSION.RELEASE);
                sysInfo_Build.put("VERSION__SDK_INT", Build.VERSION.SDK_INT);
                sysInfo.put("isRooted", Utils.isRooted());
                Map<String, Object> sysInfo_psiphonEmbeddedValues = new HashMap<String, Object>();
                sysInfo.put("PsiphonInfo", sysInfo_psiphonEmbeddedValues);
                sysInfo_psiphonEmbeddedValues.put("PROPAGATION_CHANNEL_ID", EmbeddedValues.PROPAGATION_CHANNEL_ID);
                sysInfo_psiphonEmbeddedValues.put("SPONSOR_ID", EmbeddedValues.SPONSOR_ID);
                sysInfo_psiphonEmbeddedValues.put("CLIENT_VERSION", EmbeddedValues.CLIENT_VERSION);

                /*
                 * Diagnostic History
                 */

                List<Object> diagnosticHistory = new ArrayList<Object>();

                for (PsiphonData.DiagnosticEntry item : PsiphonData.cloneDiagnosticHistory())
                {
                    Map<String, Object> entry = new HashMap<String, Object>();
                    entry.put("timestamp", item.timestamp());
                    entry.put("msg", item.msg());
                    entry.put("data", item.data());

                    diagnosticHistory.add(entry);
                }

                /*
                 * Status History
                 */

                List<Object> statusHistory = new ArrayList<Object>();

                for (StatusEntry internalEntry : PsiphonData.getPsiphonData().cloneStatusHistory())
                {
                    // Don't send any sensitive logs
                    if (internalEntry.sensitivity() == MyLog.Sensitivity.SENSITIVE_LOG)
                    {
                        continue;
                    }

                    Map<String, Object> statusEntry = new HashMap<String, Object>();
                    statusHistory.add(statusEntry);

                    String idName = getResources().getResourceEntryName(internalEntry.id());
                    statusEntry.put("id", idName);
                    statusEntry.put("timestamp", internalEntry.timestamp());
                    statusEntry.put("priority", internalEntry.priority());
                    statusEntry.put("formatArgs", null);
                    statusEntry.put("throwable", null);

                    if (internalEntry.formatArgs() != null && internalEntry.formatArgs().length > 0
                        // Don't send any sensitive format args
                        && internalEntry.sensitivity() != MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS)
                    {
                        statusEntry.put("formatArgs", Arrays.asList(internalEntry.formatArgs()));
                    }

                    if (internalEntry.throwable() != null)
                    {
                        Map<String, Object> throwable = new HashMap<String, Object>();
                        statusEntry.put("throwable", throwable);

                        throwable.put("message", internalEntry.throwable().toString());

                        List<String> stack = new ArrayList<String>();
                        throwable.put("stack", stack);

                        for (StackTraceElement element : internalEntry.throwable().getStackTrace())
                        {
                            stack.add(element.toString());
                        }
                    }
                }

                /*
                 * YAML-ify the diagnostic info
                 */

                Map<String, Object> diagnosticObject = new HashMap<String, Object>();
                Map<String, Object> diagnosticInfo = new HashMap<String, Object>();
                
                diagnosticInfo.put("SystemInformation", sysInfo);
                diagnosticInfo.put("DiagnosticHistory", diagnosticHistory);
                diagnosticInfo.put("StatusHistory", statusHistory);
                
                diagnosticObject.put("Metadata", metadata);
                diagnosticObject.put("DiagnosticInfo", diagnosticInfo);
                
                Yaml yaml = new Yaml();
                diagnosticYaml = yaml.dump(diagnosticObject);

                // Encrypt the file contents
                byte[] contentCiphertext = null, iv = null,
                        wrappedEncryptionKey = null, contentMac = null,
                        wrappedMacKey = null;
                boolean attachOkay = false;
                try
                {
                	int KEY_LENGTH = 128;

                    //
                    // Encrypt the cleartext content
                    //

                    KeyGenerator encryptionKeygen = KeyGenerator.getInstance("AES");
                    encryptionKeygen.init(KEY_LENGTH);
                    SecretKey encryptionKey = encryptionKeygen.generateKey();

                    SecureRandom rng = new SecureRandom();
                    iv = new byte[16];
                    rng.nextBytes(iv);
                    IvParameterSpec ivParamSpec = new IvParameterSpec(iv);

                    Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    aesCipher.init(Cipher.ENCRYPT_MODE, encryptionKey, ivParamSpec);

                    contentCiphertext = aesCipher.doFinal(diagnosticYaml.getBytes("UTF-8"));

                    // Get the IV. (I don't know if it can be different from the
                    // one generated above, but retrieving it here seems safest.)
                    iv = aesCipher.getIV();

                    //
                    // Create a MAC (encrypt-then-MAC).
                    //

                    KeyGenerator macKeygen = KeyGenerator.getInstance("AES");
                    macKeygen.init(KEY_LENGTH);
                    SecretKey macKey = macKeygen.generateKey();
                    Mac mac = Mac.getInstance("HmacSHA256");
                    mac.init(macKey);
                    // Include the IV in the MAC'd data, as per http://tools.ietf.org/html/draft-mcgrew-aead-aes-cbc-hmac-sha2-01
                    mac.update(iv);
                    contentMac = mac.doFinal(contentCiphertext);

                    //
                    // Ready the public key that we'll use to share keys
                    //

                    byte[] publicKeyBytes = Base64.decode(EmbeddedValues.FEEDBACK_ENCRYPTION_PUBLIC_KEY);
                    X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    PublicKey publicKey = keyFactory.generatePublic(spec);
                    Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA1AndMGF1Padding");
                    rsaCipher.init(Cipher.WRAP_MODE, publicKey);

                    //
                    // Wrap the symmetric keys
                    //

                    wrappedEncryptionKey = rsaCipher.wrap(encryptionKey);
                    wrappedMacKey = rsaCipher.wrap(macKey);

                    attachOkay = true;
                }
                catch (GeneralSecurityException e)
                {
                    MyLog.e(R.string.FeedbackActivity_EncryptedFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                }
                catch (UnsupportedEncodingException e)
                {
                    MyLog.e(R.string.FeedbackActivity_EncryptedFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
                }

                File attachmentFile = null;
                if (attachOkay)
                {
                    StringBuilder encryptedContent = new StringBuilder();
                    encryptedContent.append("{\n");
                    encryptedContent.append("  \"contentCiphertext\": \"").append(Utils.Base64.encode(contentCiphertext)).append("\",\n");
                    encryptedContent.append("  \"iv\": \"").append(Utils.Base64.encode(iv)).append("\",\n");
                    encryptedContent.append("  \"wrappedEncryptionKey\": \"").append(Utils.Base64.encode(wrappedEncryptionKey)).append("\",\n");
                    encryptedContent.append("  \"contentMac\": \"").append(Utils.Base64.encode(contentMac)).append("\",\n");
                    encryptedContent.append("  \"wrappedMacKey\": \"").append(Utils.Base64.encode(wrappedMacKey)).append("\"\n");
                    encryptedContent.append("}");

                    try
                    {
                        // The attachment must be created on external storage,
                        // and be publicly readable, or else Gmail gives this error:
                        // E/Gmail(18760): file:// attachment paths must point to file:///storage/sdcard0. Ignoring attachment [obscured file path]

                        File extDir = Environment.getExternalStoragePublicDirectory(PsiphonConstants.TAG);
                        extDir.mkdirs();

                        attachmentFile = new File(
                                extDir,
                                PsiphonConstants.FEEDBACK_ATTACHMENT_FILENAME);

                        FileWriter writer = new FileWriter(attachmentFile, false);
                        writer.write(encryptedContent.toString());
                        writer.close();
                    }
                    catch (IOException e)
                    {
                        attachmentFile = null;
                        MyLog.e(R.string.FeedbackActivity_AttachmentWriteFailed, MyLog.Sensitivity.NOT_SENSITIVE);
                    }
                }

                return attachmentFile;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url)
            {
                final String feedbackUrl = "feedback?";

                if (url.startsWith("mailto:"))
                {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("message/rfc822");
                    intent.putExtra(Intent.EXTRA_EMAIL, new String[] {MailTo.parse(url).getTo()});

                    File attachmentFile = createEmailAttachment();
                    if (attachmentFile != null)
                    {
                        intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(attachmentFile));
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
                return false;
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
        webView.loadDataWithBaseURL("file:///#" + language, html, "text/html", "utf-8", null);
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
