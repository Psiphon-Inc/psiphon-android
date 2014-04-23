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

package com.psiphon3.psiphonlibrary;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.psiphon3.psiphonlibrary.PsiphonData.StatusEntry;
import com.psiphon3.psiphonlibrary.Utils.MyLog;

public class Diagnostics
{
    static public File createEmailAttachment(Context context)
    {
        // Our attachment is JSON, which is then encrypted, and the
        // encryption elements stored in JSON.
        
        String diagnosticJSON = null;
        
        try
        {
            /*
             * Metadata
             */
            
            JSONObject metadata = new JSONObject();
    
            metadata.put("platform", "android");
            metadata.put("version", 3);
    
            SecureRandom rnd = new SecureRandom();
            byte[] id = new byte[8];
            rnd.nextBytes(id);
            metadata.put("id", Utils.byteArrayToHexString(id));
    
            /*
             * System Information
             */
    
            JSONObject sysInfo_Build = new JSONObject();
            sysInfo_Build.put("BRAND", Build.BRAND);
            sysInfo_Build.put("CPU_ABI", Build.CPU_ABI);
            sysInfo_Build.put("MANUFACTURER", Build.MANUFACTURER);
            sysInfo_Build.put("MODEL", Build.MODEL);
            sysInfo_Build.put("DISPLAY", Build.DISPLAY);
            sysInfo_Build.put("TAGS", Build.TAGS);
            sysInfo_Build.put("VERSION__CODENAME", Build.VERSION.CODENAME);
            sysInfo_Build.put("VERSION__RELEASE", Build.VERSION.RELEASE);
            sysInfo_Build.put("VERSION__SDK_INT", Build.VERSION.SDK_INT);
    
            JSONObject sysInfo_psiphonEmbeddedValues = new JSONObject();
            sysInfo_psiphonEmbeddedValues.put("PROPAGATION_CHANNEL_ID", EmbeddedValues.PROPAGATION_CHANNEL_ID);
            sysInfo_psiphonEmbeddedValues.put("SPONSOR_ID", EmbeddedValues.SPONSOR_ID);
            sysInfo_psiphonEmbeddedValues.put("CLIENT_VERSION", EmbeddedValues.CLIENT_VERSION);
    
            JSONObject sysInfo = new JSONObject();
            sysInfo.put("isRooted", Utils.isRooted());
            sysInfo.put("isPlayStoreBuild", EmbeddedValues.IS_PLAY_STORE_BUILD);
            sysInfo.put("language", Locale.getDefault().getLanguage());
            sysInfo.put("networkTypeName", Utils.getNetworkTypeName(context));
            sysInfo.put("Build", sysInfo_Build);
            sysInfo.put("PsiphonInfo", sysInfo_psiphonEmbeddedValues);
    
            /*
             * Diagnostic History
             */
    
            JSONArray diagnosticHistory = new JSONArray();
    
            for (PsiphonData.DiagnosticEntry item : PsiphonData.cloneDiagnosticHistory())
            {
                JSONObject entry = new JSONObject();
                entry.put("timestamp!!timestamp", Utils.getISO8601String(item.timestamp()));
                entry.put("msg", item.msg() == null ? JSONObject.NULL : item.msg());
                entry.put("data", item.data() == null ? JSONObject.NULL : item.data());
                diagnosticHistory.put(entry);
            }
    
            /*
             * Status History
             */

            JSONArray statusHistory = new JSONArray();
    
            for (StatusEntry internalEntry : PsiphonData.getPsiphonData().cloneStatusHistory())
            {
                // Don't send any sensitive logs or debug logs
                if (internalEntry.sensitivity() == MyLog.Sensitivity.SENSITIVE_LOG
                    || internalEntry.priority() == Log.DEBUG)
                {
                    continue;
                }
    
                JSONObject statusEntry = new JSONObject();
    
                String idName = context.getResources().getResourceEntryName(internalEntry.id());
                statusEntry.put("id", idName);
                statusEntry.put("timestamp!!timestamp", Utils.getISO8601String(internalEntry.timestamp()));
                statusEntry.put("priority", internalEntry.priority());
                statusEntry.put("formatArgs", JSONObject.NULL);
                statusEntry.put("throwable", JSONObject.NULL);
    
                if (internalEntry.formatArgs() != null && internalEntry.formatArgs().length > 0
                    // Don't send any sensitive format args
                    && internalEntry.sensitivity() != MyLog.Sensitivity.SENSITIVE_FORMAT_ARGS)
                {
                    JSONArray formatArgs = new JSONArray();
                    for (Object o : internalEntry.formatArgs())
                    {
                        formatArgs.put(o);
                    }
                    statusEntry.put("formatArgs", formatArgs);
                }
    
                if (internalEntry.throwable() != null)
                {
                    JSONObject throwable = new JSONObject();
    
                    throwable.put("message", internalEntry.throwable().toString());
    
                    JSONArray stack = new JSONArray();
                    for (StackTraceElement element : internalEntry.throwable().getStackTrace())
                    {
                        stack.put(element.toString());
                    }
                    throwable.put("stack", stack);

                    statusEntry.put("throwable", throwable);
                }

                statusHistory.put(statusEntry);
            }
    
            /*
             * JSON-ify the diagnostic info
             */
            
            JSONObject diagnosticInfo = new JSONObject();
            diagnosticInfo.put("SystemInformation", sysInfo);
            diagnosticInfo.put("DiagnosticHistory", diagnosticHistory);
            diagnosticInfo.put("StatusHistory", statusHistory);

            JSONObject diagnosticObject = new JSONObject();
            diagnosticObject.put("Metadata", metadata);
            diagnosticObject.put("DiagnosticInfo", diagnosticInfo);
            
            diagnosticJSON = diagnosticObject.toString();
        }
        catch (JSONException e)
        {
            throw new RuntimeException(e);
        }
        
        assert(diagnosticJSON != null);

        // Encrypt the file contents
        Utils.RSAEncryptOutput rsaEncryptOutput = null;
        boolean attachOkay = false;
        try
        {
            rsaEncryptOutput = Utils.encryptWithRSA(
                    diagnosticJSON.getBytes("UTF-8"),
                    EmbeddedValues.FEEDBACK_ENCRYPTION_PUBLIC_KEY);
            attachOkay = true;
        }
        catch (GeneralSecurityException e)
        {
            MyLog.e(R.string.Diagnostics_EncryptedFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
        }
        catch (UnsupportedEncodingException e)
        {
            MyLog.e(R.string.Diagnostics_EncryptedFailed, MyLog.Sensitivity.NOT_SENSITIVE, e);
        }

        File attachmentFile = null;
        if (attachOkay)
        {
            StringBuilder encryptedContent = new StringBuilder();
            encryptedContent.append("{\n");
            encryptedContent.append("  \"contentCiphertext\": \"").append(Utils.Base64.encode(rsaEncryptOutput.mContentCiphertext)).append("\",\n");
            encryptedContent.append("  \"iv\": \"").append(Utils.Base64.encode(rsaEncryptOutput.mIv)).append("\",\n");
            encryptedContent.append("  \"wrappedEncryptionKey\": \"").append(Utils.Base64.encode(rsaEncryptOutput.mWrappedEncryptionKey)).append("\",\n");
            encryptedContent.append("  \"contentMac\": \"").append(Utils.Base64.encode(rsaEncryptOutput.mContentMac)).append("\",\n");
            encryptedContent.append("  \"wrappedMacKey\": \"").append(Utils.Base64.encode(rsaEncryptOutput.mWrappedMacKey)).append("\"\n");
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
                MyLog.e(R.string.Diagnostics_AttachmentWriteFailed, MyLog.Sensitivity.NOT_SENSITIVE);
            }
        }

        return attachmentFile;
    }
}
