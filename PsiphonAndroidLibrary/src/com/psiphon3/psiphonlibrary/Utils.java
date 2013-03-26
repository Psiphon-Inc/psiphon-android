package com.psiphon3.psiphonlibrary;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.io.IOException;

import org.apache.http.conn.util.InetAddressUtils;

import com.psiphon3.psiphonlibrary.PsiphonData.StatusEntry;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;


public class Utils
{

    private static SecureRandom s_secureRandom = new SecureRandom();
    static byte[] generateSecureRandomBytes(int byteCount)
    {
        byte bytes[] = new byte[byteCount];
        s_secureRandom.nextBytes(bytes);
        return bytes;
    }

    private static Random s_insecureRandom = new Random();
    static byte[] generateInsecureRandomBytes(int byteCount)
    {
        byte bytes[] = new byte[byteCount];
        s_insecureRandom.nextBytes(bytes);
        return bytes;
    }

    // from:
    // http://stackoverflow.com/questions/140131/convert-a-string-representation-of-a-hex-dump-to-a-byte-array-using-java
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character
                    .digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // from:
    // http://stackoverflow.com/questions/332079/in-java-how-do-i-convert-a-byte-array-to-a-string-of-hex-digits-while-keeping-l
    public static String byteArrayToHexString(byte[] bytes) 
    {
        char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) 
        {
            v = bytes[j] & 0xFF;
            hexChars[j*2] = hexArray[v/16];
            hexChars[j*2 + 1] = hexArray[v%16];
        }
        return new String(hexChars);
    }

    /***************************************************************
     * Copyright (c) 1998, 1999 Nate Sammons <nate@protomatter.com> This library
     * is free software; you can redistribute it and/or modify it under the
     * terms of the GNU Library General Public License as published by the Free
     * Software Foundation; either version 2 of the License, or (at your option)
     * any later version.
     * 
     * This library is distributed in the hope that it will be useful, but
     * WITHOUT ANY WARRANTY; without even the implied warranty of
     * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Library
     * General Public License for more details.
     * 
     * You should have received a copy of the GNU Library General Public License
     * along with this library; if not, write to the Free Software Foundation,
     * Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
     * 
     * Contact support@protomatter.com with your questions, comments, gripes,
     * praise, etc...
     ***************************************************************/

    /***************************************************************
     * - moved to the net.matuschek.util tree by Daniel Matuschek - replaced
     * deprecated getBytes() method in method decode - added String
     * encode(String) method to encode a String to base64
     ***************************************************************/

    /**
     * Base64 encoder/decoder. Does not stream, so be careful with using large
     * amounts of data
     * 
     * @author Nate Sammons
     * @author Daniel Matuschek
     * @version $Id: Base64.java,v 1.4 2001/04/17 10:09:27 matuschd Exp $
     */
    public static class Base64 {

        private Base64() {
            super();
        }

        /**
         * Encode some data and return a String.
         */
        public final static String encode(byte[] d) {
            if (d == null)
                return null;
            byte data[] = new byte[d.length + 2];
            System.arraycopy(d, 0, data, 0, d.length);
            byte dest[] = new byte[(data.length / 3) * 4];

            // 3-byte to 4-byte conversion
            for (int sidx = 0, didx = 0; sidx < d.length; sidx += 3, didx += 4) {
                dest[didx] = (byte) ((data[sidx] >>> 2) & 077);
                dest[didx + 1] = (byte) ((data[sidx + 1] >>> 4) & 017 | (data[sidx] << 4) & 077);
                dest[didx + 2] = (byte) ((data[sidx + 2] >>> 6) & 003 | (data[sidx + 1] << 2) & 077);
                dest[didx + 3] = (byte) (data[sidx + 2] & 077);
            }

            // 0-63 to ascii printable conversion
            for (int idx = 0; idx < dest.length; idx++) {
                if (dest[idx] < 26)
                    dest[idx] = (byte) (dest[idx] + 'A');
                else if (dest[idx] < 52)
                    dest[idx] = (byte) (dest[idx] + 'a' - 26);
                else if (dest[idx] < 62)
                    dest[idx] = (byte) (dest[idx] + '0' - 52);
                else if (dest[idx] < 63)
                    dest[idx] = (byte) '+';
                else
                    dest[idx] = (byte) '/';
            }

            // add padding
            for (int idx = dest.length - 1; idx > (d.length * 4) / 3; idx--) {
                dest[idx] = (byte) '=';
            }
            return new String(dest);
        }

        /**
         * Encode a String using Base64 using the default platform encoding
         **/
        public final static String encode(String s) {
            return encode(s.getBytes());
        }

        /**
         * Decode data and return bytes.
         */
        public final static byte[] decode(String str) {
            if (str == null)
                return null;
            byte data[] = str.getBytes();
            return decode(data);
        }

        /**
         * Decode data and return bytes. Assumes that the data passed in is
         * ASCII text.
         */
        public final static byte[] decode(byte[] data) {
            int tail = data.length;
            while (data[tail - 1] == '=')
                tail--;
            byte dest[] = new byte[tail - data.length / 4];

            // ascii printable to 0-63 conversion
            for (int idx = 0; idx < data.length; idx++) {
                if (data[idx] == '=')
                    data[idx] = 0;
                else if (data[idx] == '/')
                    data[idx] = 63;
                else if (data[idx] == '+')
                    data[idx] = 62;
                else if (data[idx] >= '0' && data[idx] <= '9')
                    data[idx] = (byte) (data[idx] - ('0' - 52));
                else if (data[idx] >= 'a' && data[idx] <= 'z')
                    data[idx] = (byte) (data[idx] - ('a' - 26));
                else if (data[idx] >= 'A' && data[idx] <= 'Z')
                    data[idx] = (byte) (data[idx] - 'A');
            }

            // 4-byte to 3-byte conversion
            int sidx, didx;
            for (sidx = 0, didx = 0; didx < dest.length - 2; sidx += 4, didx += 3) {
                dest[didx] = (byte) (((data[sidx] << 2) & 255) | ((data[sidx + 1] >>> 4) & 3));
                dest[didx + 1] = (byte) (((data[sidx + 1] << 4) & 255) | ((data[sidx + 2] >>> 2) & 017));
                dest[didx + 2] = (byte) (((data[sidx + 2] << 6) & 255) | (data[sidx + 3] & 077));
            }
            if (didx < dest.length) {
                dest[didx] = (byte) (((data[sidx] << 2) & 255) | ((data[sidx + 1] >>> 4) & 3));
            }
            if (++didx < dest.length) {
                dest[didx] = (byte) (((data[sidx + 1] << 4) & 255) | ((data[sidx + 2] >>> 2) & 017));
            }
            return dest;
        }
    }
    
    /**
     * URL-encodes a string. This is largely redundant with URLEncoder.encode,
     * but it tries to avoid using the deprecated URLEncoder.encode(String) while not
     * throwing the exception of URLEncoder.encode(String, String).
     * @param s  The string to URL encode.
     * @return The URL encoded version of s. 
     */
    static public String urlEncode(String s)
    {
        try
        {
            return URLEncoder.encode(s, "UTF-8");
        } 
        catch (UnsupportedEncodingException e)
        {
            Log.e(PsiphonConstants.TAG, e.getMessage());

            // Call the deprecated form of the function, which doesn't throw.
            return URLEncoder.encode(s);
        }                    
    }

    /**
     * Wrapper around Android's Log functionality. This should be used so that
     * LogCat messages will be turned off in production builds. For the reason
     * why we want this, see the link below.
     * If the logger member variable is set, messages will also be logged to 
     * that facility (except debug messages).
     * @see <a href="http://blog.parse.com/2012/04/10/discovering-a-major-security-hole-in-facebooks-android-sdk/">Discovering a Major Security Hole in Facebook's Android SDK</a>
     */
    static public class MyLog
    {
        static public interface ILogInfoProvider
        {
            public String getResourceString(int stringResID, Object[] formatArgs);
            public String getResourceEntryName(int stringResID);
        }
        
        static public interface ILogger
        {
            public void log(Date timestamp, int priority, String message);
        }
        
        static public ILogInfoProvider logInfoProvider;
        static public ILogger logger;
        
        /**
         * Used to indicate the sensitivity level of the log. This will affect
         * log handling in some situations (like sending as diagnostic info).
         * "Sensitive" refers to info that might identify the user or their 
         * activities.
         */
        public enum Sensitivity
        {
            /**
             * The log does not contain sensitive information.
             */
            NOT_SENSITIVE,
            
            /**
             * The log message itself is sensitive information.
             */
            SENSITIVE_LOG,
            
            /**
             * The format arguments to the log messages are sensitive, but the 
             * log message itself is not. 
             */
            SENSITIVE_FORMAT_ARGS
        }
        
        /**
         * Safely wraps the string resource extraction function. If an error 
         * occurs with the format specifiers, the raw string will be returned.
         * @param stringResID The string resource ID.
         * @param formatArgs The format arguments. May be empty (non-existent).
         * @return The requested string, possibly formatted.
         */
        static private String myGetResString(int stringResID, Object[] formatArgs)
        {
            try
            {
                return logInfoProvider.getResourceString(stringResID, formatArgs);
            }
            catch (IllegalFormatException e)
            {
                return logInfoProvider.getResourceString(stringResID, null);
            }
        }
        
        static public void restoreLogHistory()
        {
            // We need to clear out the history and restore a copy, because the
            // act of restoring will rebuild the history.
            
            ArrayList<StatusEntry> history = PsiphonData.cloneStatusHistory();
            PsiphonData.clearStatusHistory();
            
            for (StatusEntry logEntry : history)
            {
                MyLog.println(
                        logEntry.id(), 
                        logEntry.sensitivity(), 
                        logEntry.formatArgs(), 
                        logEntry.throwable(), 
                        logEntry.priority(),
                        logEntry.timestamp());
            }
        }
        
        static public void d(String msg)
        {
            MyLog.println(new Date(), msg, null, Log.DEBUG);
        }

        static public void d(String msg, Throwable throwable)
        {
            MyLog.println(new Date(), msg, throwable, Log.DEBUG);
        }

        /**
         * Log a diagnostic entry. This is the same as a debug ({@link #d(String)}) entry,
         * except it will also be included in the feedback diagnostic attachment.
         * @param msg The message to log.
         */
        static public void g(String msg, Object data)
        {
            PsiphonData.addDiagnosticEntry(new Date(), msg, data);
            // We're not logging the `data` at all. In the future we may want to.
            MyLog.d(msg);
        }

        static public void e(int stringResID, Sensitivity sensitivity, Object... formatArgs)
        {
            MyLog.println(stringResID, sensitivity, formatArgs, null, Log.ERROR);
        }

        static public void e(int stringResID, Sensitivity sensitivity, Throwable throwable)
        {
            MyLog.println(stringResID, sensitivity, null, throwable, Log.ERROR);
        }
        
        static public void w(int stringResID, Sensitivity sensitivity, Object... formatArgs)
        {
            MyLog.println(stringResID, sensitivity, formatArgs, null, Log.WARN);
        }

        static public void w(int stringResID, Sensitivity sensitivity, Throwable throwable)
        {
            MyLog.println(stringResID, sensitivity, null, throwable, Log.WARN);
        }
        
        static public void i(int stringResID, Sensitivity sensitivity, Object... formatArgs)
        {
            MyLog.println(stringResID, sensitivity, formatArgs, null, Log.INFO);
        }

        static public void i(int stringResID, Sensitivity sensitivity, Throwable throwable)
        {
            MyLog.println(stringResID, sensitivity, null, throwable, Log.INFO);
        }
        
        static public void v(int stringResID, Sensitivity sensitivity, Object... formatArgs)
        {
            MyLog.println(stringResID, sensitivity, formatArgs, null, Log.VERBOSE);
        }

        static public void v(int stringResID, Sensitivity sensitivity, Throwable throwable)
        {
            MyLog.println(stringResID, sensitivity, null, throwable, Log.VERBOSE);
        }

        private static void println(
                int stringResID, 
                Sensitivity sensitivity, 
                Object[] formatArgs, 
                Throwable throwable, 
                int priority)
        {
            println(
                stringResID,
                sensitivity,
                formatArgs,
                throwable,
                priority,
                new Date());
        }

        private static void println(
                int stringResID, 
                Sensitivity sensitivity, 
                Object[] formatArgs, 
                Throwable throwable, 
                int priority,
                Date timestamp)
        {
            PsiphonData.addStatusEntry(
                    timestamp,
                    stringResID,
                    logInfoProvider.getResourceEntryName(stringResID), 
                    sensitivity,
                    formatArgs, 
                    throwable, 
                    priority);
            
            println(timestamp, MyLog.myGetResString(stringResID, formatArgs), throwable, priority);
        }
        
        private static void println(Date timestamp, String msg, Throwable throwable, int priority)
        {
            // If we're not running in debug mode, don't log debug messages at all.
            // (This may be redundant with the logic below, but it'll save us if
            // the log below changes.)
            if (!PsiphonConstants.DEBUG && priority == Log.DEBUG)
            {
                return;
            }
            
            // If the external logger has been set, use it.
            // But don't put debug messages to the external logger.
            if (logger != null && priority != Log.DEBUG)
            {
                String loggerMsg = msg;
                
                if (throwable != null)
                {
                    // Just report the first line of the stack trace
                    String[] stackTraceLines = Log.getStackTraceString(throwable).split("\n");
                    loggerMsg = loggerMsg + (stackTraceLines.length > 0 ? "\n" + stackTraceLines[0] : ""); 
                }
                
                logger.log(timestamp, priority, loggerMsg);
            }
            
            // Do not log to LogCat at all if we're not running in debug mode.

            if (!PsiphonConstants.DEBUG)
            {
                return;
            }
                        
            // Log to LogCat
            // Note that this is basically identical to how Log.e, etc., are implemented.
            if (throwable != null)
            {
                msg = msg + '\n' + Log.getStackTraceString(throwable);
            }
            Log.println(priority, PsiphonConstants.TAG, msg);
        }
    }

    // From:
    // http://abhinavasblog.blogspot.ca/2011/06/check-for-debuggable-flag-in-android.html
    /*
    Copyright [2011] [Abhinava Srivastava]

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
    */
    public static boolean isDebugMode(Activity context)
    {
        boolean debug = false;
        PackageInfo packageInfo = null;
        try
        {
            packageInfo = context.getPackageManager().getPackageInfo(
                    context.getApplication().getPackageName(),
                    PackageManager.GET_CONFIGURATIONS);
        } 
        catch (NameNotFoundException e)
        {
            e.printStackTrace();
        }
        if (packageInfo != null)
        {
            int flags = packageInfo.applicationInfo.flags;
            if ((flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            {
                debug = true;
            } 
            else
            {
                debug = false;
            }
        }
        return debug;
    }

    public static boolean isRooted()
    {
        //Method 1 check for presence of 'test-keys' in the build tags 
        String buildTags = android.os.Build.TAGS;
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true;
        }
        
        //Method 2 check for presence of Superuser app
        try {
            File file = new File("/system/app/Superuser.apk");
            if (file.exists()) {
                return true;
            }
        } catch (Exception e) { }
        
        //Method 3 check for presence of 'su' in the PATH
        String path = null;
        Map<String,String> env = System.getenv();

        if (env != null && (path = env.get("PATH")) != null) {
            String [] dirs = path.split(":");
            for (String dir : dirs){
                String suPath = dir + "/" + "su";
                File suFile = new File(suPath);
                if (suFile != null && suFile.exists()) {
                    return true;
                }
            }
        }
        return false;
    }
    
    public static boolean hasVpnService()
    {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH;
    }
    
    public static String getLocalTimeString(Date date)
    {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
        String dateStr = sdf.format(date);
        return dateStr;
    }

    public static String getISO8601String(Date date)
    {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        String dateStr = sdf.format(date);
        dateStr += "Z";
        return dateStr;
    }

    public static String getISO8601String()
    {
        return getISO8601String(new Date());
    }

    public static boolean isPortAvailable(int port)
    {
        Socket socket = new Socket();
        SocketAddress sockaddr = new InetSocketAddress("127.0.0.1", port);
        
        try 
        {
            socket.connect(sockaddr, 1000);
            // The connect succeeded, so there is already something running on that port
            return false;
        }
        catch (SocketTimeoutException e)
        {
            // The socket is in use, but the server didn't respond quickly enough
            return false;
        }
        catch (IOException e)
        {
            // The connect failed, so the port is available
            return true;
        }
        finally
        {
            if (socket != null)
            {
                try 
                {
                    socket.close();
                } 
                catch (IOException e) 
                {
                    /* should not be thrown */
                }
            }
        }
    }

    public static int findAvailablePort(int start_port, int max_increment)
    {
        for(int port = start_port; port < (start_port + max_increment); port++)
        {
            if (isPortAvailable(port))
            {
                return port;
            }
        }

        return 0;
    }
    
    public static boolean hasNetworkConnectivity(Context context)
    {
        ConnectivityManager connectivityManager =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }

    public static String selectPrivateAddress()
    {
        // Select one of 10.0.0.1, 172.16.0.1, or 192.168.0.1 depending on
        // which private address range isn't in use.

        final String CANDIDATE_10_SLASH_8 = "10.0.0.1";
        final String CANDIDATE_172_16_SLASH_12 = "172.16.0.1";
        final String CANDIDATE_192_168_SLASH_16 = "192.168.0.1";
        
        ArrayList<String> candidates = new ArrayList<String>();
        candidates.add(CANDIDATE_10_SLASH_8);
        candidates.add(CANDIDATE_172_16_SLASH_12);
        candidates.add(CANDIDATE_192_168_SLASH_16);
        
        List<NetworkInterface> netInterfaces;
        try
        {
            netInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        }
        catch (SocketException e)
        {
            return null;
        }

        for (NetworkInterface netInterface : netInterfaces)
        {
            for (InetAddress inetAddress : Collections.list(netInterface.getInetAddresses()))
            {
                String ipAddress = inetAddress.getHostAddress();
                if (InetAddressUtils.isIPv4Address(ipAddress))
                {
                    if (ipAddress.startsWith("10."))
                    {
                        candidates.remove(CANDIDATE_10_SLASH_8);
                    }
                    else if (
                        ipAddress.length() >= 6 &&
                        ipAddress.substring(0, 6).compareTo("172.16") >= 0 && 
                        ipAddress.substring(0, 6).compareTo("172.31") <= 0)
                    {
                        candidates.remove(CANDIDATE_172_16_SLASH_12);
                    }
                    else if (ipAddress.startsWith("192.168"))
                    {
                        candidates.remove(CANDIDATE_192_168_SLASH_16);
                    }
                }
            }
        }
        
        if (candidates.size() > 0)
        {
            return candidates.get(0);
        }
        
        return null;
    }
    
    public static String byteCountToDisplaySize(long bytes, boolean si)
    {
        // http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java/3758880#3758880
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }
}
