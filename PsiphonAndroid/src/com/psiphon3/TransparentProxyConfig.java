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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.zip.ZipInputStream;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import com.psiphon3.Utils.MyLog;


// Based on Orbot's TorTransProxy/TorServiceUtils/TorBinaryInstaller implementations
//
// https://guardianproject.info/apps/orbot/
// https://gitweb.torproject.org/orbot.git

public class TransparentProxyConfig
{
    public static class PsiphonTransparentProxyException extends Exception
    {
        private static final long serialVersionUID = 1L;
        
        public PsiphonTransparentProxyException()
        {
            super();
        }
        
        public PsiphonTransparentProxyException(String message)
        {
            super(message);
        }
    }

    public static void setupTransparentProxyRouting(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesPath = getIpTables(context);
        StringBuilder script = new StringBuilder();        
        int psiphonUid = context.getApplicationInfo().uid;
        
        // Store existing iptables configuration to be restored later on. This
        // will help with compatibility with other apps using iptables, although
        // we'll still have conflicts if they're running at the same time.

        saveIpTables(context);
        
        flushIpTables(context);
        
        // Forward all TCP connections, except for Psiphon,
        // through the transparent proxy

        // TODO: test for REDIRECT support and use NATD when unsupported?
        
        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -A OUTPUT -p tcp");
        script.append(" ! -d 127.0.0.1"); // allow access to localhost
        script.append(" -m owner ! --uid-owner ");
        script.append(psiphonUid);
        script.append(" -m tcp --syn");
        script.append(" -j REDIRECT --to-ports ");
        script.append(PsiphonData.getPsiphonData().getTransparentProxyPort());
        script.append(" || exit\n");

        // Forward all UDP DNS through the DNS proxy, except for Psiphon

        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -A OUTPUT -p udp");
        script.append(" -m owner ! --uid-owner ");
        script.append(psiphonUid);
        script.append(" -m udp --dport "); 
        script.append(PsiphonConstants.STANDARD_DNS_PORT);
        script.append(" -j REDIRECT --to-ports ");
        script.append(PsiphonData.getPsiphonData().getDnsProxyPort());
        script.append(" || exit\n");

        // Forward TCP DNS through transparent proxy
        // (including the Psiphon DNS proxy requests)

        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -A OUTPUT -p tcp");
        script.append(" -m tcp --syn --dport "); 
        script.append(PsiphonConstants.STANDARD_DNS_PORT);
        script.append(" -j REDIRECT --to-ports ");
        script.append(PsiphonData.getPsiphonData().getTransparentProxyPort());
        script.append(" || exit\n");

        String[] cmdAdd = {script.toString()};      
        
        doShellCommand(context, cmdAdd, true, true);
    }

    public static void teardownTransparentProxyRouting(Context context)
            throws PsiphonTransparentProxyException
    {
        // (Do an explict flush in case the restore does nothing)
        
        flushIpTables(context);

        restoreIpTables(context);
    }

    private static void saveIpTables(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesSavePath = getIpTablesSave(context);

        File rulesFile = new File(
                context.getDir(IPTABLES_SAVED_RULES_SUBDIRECTORY, Context.MODE_PRIVATE),
                IPTABLES_SAVED_RULES_FILENAME);

        final StringBuilder script = new StringBuilder();        
        
        // If the rules file already exists, assume it's there because
        // of a failed restore and don't overwrite it. That way the original
        // rules from the previous save will still be restored.

        // NOTE: this  isn't completely robust. A user may have manually
        // (or via DroidWall) repaired rules after a failed restore and
        // this logic will ignore those changes.
        
        if (!rulesFile.exists())
        {
            script.append(ipTablesSavePath);
            script.append(" > ");
            script.append(rulesFile.getAbsolutePath());
            
            String[] cmd = {script.toString()};
    
            doShellCommand(context, cmd, true, true);
        }
    }

    private static void restoreIpTables(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesRestorePath = getIpTablesRestore(context);
        
        File rulesFile = new File(
                context.getDir(IPTABLES_SAVED_RULES_SUBDIRECTORY, Context.MODE_PRIVATE),
                IPTABLES_SAVED_RULES_FILENAME);

        final StringBuilder script = new StringBuilder();        
        
        script.append(ipTablesRestorePath);
        script.append(" < ");
        script.append(rulesFile.getAbsolutePath());
        script.append(" || exit\n");

        // Remove the rules file when successfully restored. If the restore
        // fails, the file is retained and will be retried next time.
        
        script.append("rm ");
        script.append(rulesFile.getAbsolutePath());
        script.append(" || exit\n");

        String[] cmd = {script.toString()};

        doShellCommand(context, cmd, true, true);        
    }

    private static void flushIpTables(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesPath = getIpTables(context);
        
        final StringBuilder script = new StringBuilder();        
        
        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -F || exit\n");
        
        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -F || exit\n");
        
        String[] cmd = {script.toString()};

        doShellCommand(context, cmd, true, true);        
    }

    static final String IPTABLES_SAVED_RULES_SUBDIRECTORY = "saved-iptables-rules";
    static final String IPTABLES_SAVED_RULES_FILENAME = "iptables.rules";

    static final String IPTABLES_FILENAME = "iptables";
    static final String IPTABLES_SAVE_FILENAME = "iptables-save";
    static final String IPTABLES_RESTORE_FILENAME = "iptables-restore";

    static final String IPTABLES_BUNDLED_ARM7_BINARIES_SUFFIX = "_arm7.zip";
    static final String IPTABLES_BUNDLED_ARM_BINARIES_SUFFIX = "_arm.zip";
    static final String IPTABLES_BUNDLED_X86_BINARIES_SUFFIX = "_x86.zip";
    static final String IPTABLES_BUNDLED_MIPS_BINARIES_SUFFIX = "_mips.zip";

    static final String BUNDLED_BINARY_DATA_SUBDIRECTORY = "bundled-binaries";
    static final String BUNDLED_BINARY_ASSET_SUBDIRECTORY = "bundled-binaries";
    static final String SYSTEM_BINARY_PATH = "/system/bin/";
    static final String SYSTEM_BINARY_ALT_PATH = "/system/xbin/";
    
    private static String getBundledBinaryPlatformSuffix(Context context)
    {
        // NOTE: no MIPS binaries are bundled at the moment
        if (0 == Build.CPU_ABI.compareTo("armeabi-v7a")) return IPTABLES_BUNDLED_ARM7_BINARIES_SUFFIX;
        else if (0 == Build.CPU_ABI.compareTo("armeabi")) return IPTABLES_BUNDLED_ARM_BINARIES_SUFFIX;
        else if (0 == Build.CPU_ABI.compareTo("x86")) return IPTABLES_BUNDLED_X86_BINARIES_SUFFIX;
        else if (0 == Build.CPU_ABI.compareTo("mips")) return IPTABLES_BUNDLED_MIPS_BINARIES_SUFFIX;
        return null;
    }
    
    private static boolean extractBundledBinary(Context context, String sourceAssetName, File targetFile)
    {
        try
        {
            AssetManager assetManager = context.getAssets();
            InputStream zippedAsset = assetManager.open(
                    new File(BUNDLED_BINARY_ASSET_SUBDIRECTORY, sourceAssetName).getPath());
            ZipInputStream zipStream = new ZipInputStream(zippedAsset);            
            zipStream.getNextEntry();
            InputStream bundledBinary = zipStream;
    
            FileOutputStream file = new FileOutputStream(targetFile);
    
            byte[] buffer = new byte[8192];
            int length;
            while ((length = bundledBinary.read(buffer)) != -1)
            {
                file.write(buffer, 0 , length);
            }
            file.close();
            bundledBinary.close();
    
            String chmodCommand = "chmod 700 " + targetFile.getAbsolutePath();
            Runtime.getRuntime().exec(chmodCommand).waitFor();
            
            return true;
        }
        catch (InterruptedException e)
        {
        }
        catch (IOException e)
        {
        }
        return false;
    }
    
    private static String getBinaryPath(Context context, String binaryFilename)
            throws PsiphonTransparentProxyException
    {
        File binary = null;

        // Try to use bundled binary

        String bundledSuffix = getBundledBinaryPlatformSuffix(context);
        
        if (bundledSuffix != null)
        {        
            binary = new File(
                            context.getDir(BUNDLED_BINARY_DATA_SUBDIRECTORY, Context.MODE_PRIVATE),
                            binaryFilename);
            if (binary.exists())
            {
                return binary.getAbsolutePath();
            }
            else if (extractBundledBinary(
                        context,
                        binaryFilename + bundledSuffix,
                        binary))
            {
                return binary.getAbsolutePath();
            }
            // else fall through to system binary case
        }
        
        // Otherwise look for system binary
        
        binary = new File(SYSTEM_BINARY_PATH, binaryFilename);
        if (binary.exists())
        {
            return binary.getAbsolutePath();
        }
                
        binary = new File(SYSTEM_BINARY_ALT_PATH, binaryFilename);
        if (binary.exists())
        {
            return binary.getAbsolutePath();
        }
        
        throw new PsiphonTransparentProxyException(
                context.getString(R.string.iptables_binary_not_found));
    }

    private static String getIpTables(Context context)
            throws PsiphonTransparentProxyException
    {
        return getBinaryPath(context, IPTABLES_FILENAME);
    }

    private static String getIpTablesSave(Context context)
            throws PsiphonTransparentProxyException
    {
        return getBinaryPath(context, IPTABLES_SAVE_FILENAME);
    }

    private static String getIpTablesRestore(Context context)
            throws PsiphonTransparentProxyException
    {
        return getBinaryPath(context, IPTABLES_RESTORE_FILENAME);
    }

    private static void doShellCommand(Context context, String[] cmds, boolean runAsRoot, boolean waitFor)
            throws PsiphonTransparentProxyException
    {
        int exitCode = -1;
        StringBuilder output = new StringBuilder();

        try
        {
            Process proc = null;
            
            if (runAsRoot)
            {
                proc = Runtime.getRuntime().exec("su");
            }
            else
            {
                proc = Runtime.getRuntime().exec("sh");
            }
                    
            OutputStreamWriter out = new OutputStreamWriter(proc.getOutputStream());
            for (int i = 0; i < cmds.length; i++)
            {
                out.write(cmds[i]);
                out.write("\n");
            }
            
            out.flush();
            out.write("exit\n");
            out.flush();

            if (waitFor)
            {            
                final char buf[] = new char[100];
                
                // Consume stdout
                InputStreamReader reader = new InputStreamReader(proc.getInputStream());
                int read = 0;
                while ((read = reader.read(buf)) != -1)
                {
                    output.append(buf, 0, read);
                }
                
                // Consume stderr
                reader = new InputStreamReader(proc.getErrorStream());
                read = 0;
                while ((read = reader.read(buf)) != -1)
                {
                    output.append(buf, 0, read);
                }
                
                exitCode = proc.waitFor();
            }
        }
        catch (Exception ex)
        {
            throw new PsiphonTransparentProxyException(ex.getMessage());
        }
        
        if (exitCode != 0)
        {
            String message = String.format(context.getString(
                                    R.string.transparent_proxy_command_failed),
                                    output.toString());
            throw new PsiphonTransparentProxyException(message);
        }
    }
}
