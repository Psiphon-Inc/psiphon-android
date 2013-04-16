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

package com.psiphon3.psiphonlibrary;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

import com.psiphon3.psiphonlibrary.R;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.stericson.RootTools.Command;
import com.stericson.RootTools.RootTools;

import android.content.Context;
import android.os.Build;


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
    
    static int SHELL_COMMAND_TIMEOUT = 2000; // 2 seconds 

    private static String[] setupTransparentProxyRoutingCommands(
            String ipTablesPath, int psiphonUid, boolean useRedirect)
    {
        return new String[]
        {
            // Create a user-defined chain to hold the Psiphon rules
            ipTablesPath + " -t nat -N psiphon",
                
            // Forward all UDP DNS through the DNS proxy, except for Psiphon
            ipTablesPath +
                " -t nat -A psiphon -p udp -m owner ! --uid-owner " +
                psiphonUid + " -m udp --dport " +
                PsiphonConstants.STANDARD_DNS_PORT +
                (useRedirect ? " -j REDIRECT --to-ports " : " -j DNAT --to-destination 127.0.0.1:") +
                PsiphonData.getPsiphonData().getDnsProxyPort(),

            // Forward TCP DNS through transparent proxy (including the Psiphon DNS proxy requests)
            ipTablesPath +
                " -t nat -A psiphon -p tcp -m tcp --syn --dport " +
                PsiphonConstants.STANDARD_DNS_PORT +
                (useRedirect ? " -j REDIRECT --to-ports " : " -j DNAT --to-destination 127.0.0.1:") +
                PsiphonData.getPsiphonData().getTransparentProxyPort(),

            // Exclude LAN ranges from remaining rules (return to parent chain)
            ipTablesPath + " -t nat -A psiphon -d 192.168.0.0/16 -j RETURN",
            ipTablesPath + " -t nat -A psiphon -d 172.16.0.0/12 -j RETURN",
            ipTablesPath + " -t nat -A psiphon -d 10.0.0.0/8 -j RETURN",
                
            // Forward all TCP connections, except for Psiphon, through the transparent proxy.
            // Localhost is excepted (as are LAN ranges, which match the ACCEPT rules above)
                
            ipTablesPath +
                " -t nat -A psiphon -p tcp  ! -d 127.0.0.1 -m owner ! --uid-owner " +
                psiphonUid +
                (useRedirect ? " -m tcp --syn -j REDIRECT --to-ports " :
                    " -m tcp --syn -j DNAT --to-destination 127.0.0.1:") +
                PsiphonData.getPsiphonData().getTransparentProxyPort(),
            
            // Insert the Psiphon rules at the start of the OUTPUT chain
            ipTablesPath + " -t nat -I OUTPUT -j psiphon"
        };
    }

    public static void setupTransparentProxyRouting(Context context)
            throws PsiphonTransparentProxyException
    {
        // Ensure any dangling Psiphon configuration has been removed.
        teardownTransparentProxyRouting(context);
        
        String ipTablesPath = getIpTables(context);
        int psiphonUid = context.getApplicationInfo().uid;

        // First attempt to use the REDIRECT jump target.  If any exception occurs,
        // use DNAT instead.
        
        try
        {
            doShellCommands(context,
                    setupTransparentProxyRoutingCommands(ipTablesPath, psiphonUid, true));
        }
        catch (PsiphonTransparentProxyException e)
        {
            teardownTransparentProxyRouting(context);
            doShellCommands(context,
                    setupTransparentProxyRoutingCommands(ipTablesPath, psiphonUid, false));
        }
    }

    public static void teardownTransparentProxyRouting(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesPath = getIpTables(context);
        
        boolean psiphonChainExists = false;
        try
        {
            // Check if user-defined Psiphon chain exists
            doShellCommands(context, ipTablesPath + " -t nat -F psiphon");
            psiphonChainExists = true;
        }
        catch (PsiphonTransparentProxyException e)
        {
            // Assume this is the "No chain/target/match by that name." error
        }
        
        if (psiphonChainExists)
        {
            // Delete the user-defined Psiphon chain. The -D (delete) command
            // may fail in the case of a partial setup, so we allow it to fail.

            doShellCommands(context, ipTablesPath + " -t nat -F psiphon");
            try
            {
                doShellCommands(context, ipTablesPath + " -t nat -D OUTPUT -j psiphon");
            }
            catch (PsiphonTransparentProxyException e)
            {
                // Assume this is the "No chain/target/match by that name." error
            }
            doShellCommands(context, ipTablesPath + " -t nat -X psiphon");
        }
    }

    static final String IPTABLES_FILENAME = "iptables";

    static final String IPTABLES_BUNDLED_ARM7_BINARIES_SUFFIX = "_arm7.zip";
    static final String IPTABLES_BUNDLED_ARM_BINARIES_SUFFIX = "_arm.zip";
    static final String IPTABLES_BUNDLED_X86_BINARIES_SUFFIX = "_x86.zip";
    static final String IPTABLES_BUNDLED_MIPS_BINARIES_SUFFIX = "_mips.zip";

    static final String BUNDLED_BINARY_DATA_SUBDIRECTORY = "bundled-binaries";
    static final String SYSTEM_BINARY_PATH = "/system/bin/";
    static final String SYSTEM_BINARY_ALT_PATH = "/system/xbin/";
    
    private static int getBundledIpTablesResourceForPlatform(Context context)
    {
        // NOTE: no MIPS binaries are bundled at the moment
        if (0 == Build.CPU_ABI.compareTo("armeabi-v7a")) return R.raw.iptables_arm7;
        else if (0 == Build.CPU_ABI.compareTo("armeabi")) return R.raw.iptables_arm;
        else if (0 == Build.CPU_ABI.compareTo("x86")) return R.raw.iptables_x86;
        else if (0 == Build.CPU_ABI.compareTo("mips")) return R.raw.iptables_mips;
        return 0;
    }
    
    private static boolean extractBundledIpTables(Context context, int sourceResourceId, File targetFile)
    {
        try
        {
            InputStream zippedAsset = context.getResources().openRawResource(sourceResourceId);
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
            Thread.currentThread().interrupt();
        }
        catch (IOException e)
        {
            MyLog.e(R.string.TransparentProxyConfig_iptablesExtractFailed, MyLog.Sensitivity.NOT_SENSITIVE);
        }
        return false;
    }
    
    private static String getIpTablesPath(Context context, String binaryFilename)
            throws PsiphonTransparentProxyException
    {
        File binary = null;

        // Try to use bundled binary

        int bundledIpTablesResourceId = getBundledIpTablesResourceForPlatform(context);
        
        if (bundledIpTablesResourceId != 0)
        {        
            binary = new File(
                            context.getDir(BUNDLED_BINARY_DATA_SUBDIRECTORY, Context.MODE_PRIVATE),
                            binaryFilename);
            if (binary.exists())
            {
                return binary.getAbsolutePath();
            }
            else if (extractBundledIpTables(
                        context,
                        bundledIpTablesResourceId,
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
        return getIpTablesPath(context, IPTABLES_FILENAME);
    }

    private static void doShellCommands(Context context, String... commands)
            throws PsiphonTransparentProxyException
    {
        // Run commands one-at-a-time in persistent root shell and abort after the first error
        
        for (String command : commands)
        {
            int exitCode = -1;
            
            final StringBuilder outputBuffer = new StringBuilder();
            try
            {
                Command cmd = new Command(0, command)
                {
                        @Override
                        public void output(int id, String line)
                        {
                            outputBuffer.append(line);
                            outputBuffer.append("\n");
                        }
                };

                exitCode = RootTools.getShell(true).add(cmd).exitCode(SHELL_COMMAND_TIMEOUT);
            }
            catch (Exception ex)
            {
                throw new PsiphonTransparentProxyException(ex.getMessage());
            }
            
            if (exitCode != 0)
            {
                String message = String.format(context.getString(
                                        R.string.transparent_proxy_command_failed),
                                        outputBuffer.toString());
                throw new PsiphonTransparentProxyException(message);
            }
        }
    }
}
