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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import android.content.Context;

import com.psiphon3.Utils.MyLog;


// Based on Orbot's TorTransProxy/TorServiceUtils implementations
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
        boolean runRoot = true;
        boolean waitFor = true;
        String ipTablesPath = getIpTablesPath(context);
        StringBuilder script = new StringBuilder();        
        int psiphonUid = context.getApplicationInfo().uid;
        
        flushIpTables(context);
        
        // Set up port redirection

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
        
        /* TODO: provide DNS server

        // Same for DNS

        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -A OUTPUT -p udp -m owner ! --uid-owner ");
        script.append(torUid);
        script.append(" -m udp --dport "); 
        script.append(PsiphonContants.STANDARD_DNS_PORT);
        script.append(" -j REDIRECT --to-ports ");
        script.append(PsiphonData.getPsiphonData().getDNSPort());
        script.append(" || exit\n");
        */
        
        int[] ports = {
                /*PsiphonData.getPsiphonData().getDNSPort(),*/
                PsiphonData.getPsiphonData().getTransparentProxyPort(),
                PsiphonData.getPsiphonData().getHttpProxyPort(),
                PsiphonData.getPsiphonData().getSocksPort()
        };
        
        for (int port : ports)
        {
            // Allow packets to localhost (contains all the port-redirected ones)

            script.append(ipTablesPath);
            script.append(" -t filter");
            script.append(" -A OUTPUT");
            script.append(" -m owner ! --uid-owner ");
            script.append(psiphonUid);
            script.append(" -p tcp");
            script.append(" -d 127.0.0.1");
            script.append(" --dport ");
            script.append(port);    
            script.append(" -j ACCEPT");
            script.append(" || exit\n");        
        }
        
        // Allow loopback

        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -A OUTPUT");
        script.append(" -p tcp");
        script.append(" -o lo");
        script.append(" -j ACCEPT");
        script.append(" || exit\n");
        
        // Allow everything for Psiphon

        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -A OUTPUT");
        script.append(" -m owner --uid-owner ");
        script.append(psiphonUid);
        script.append(" -j ACCEPT");
        script.append(" || exit\n");
        
        // Reject DNS that is not from Psiphon (order is important - first matched rule counts!)

        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -A OUTPUT");
        script.append(" -p udp");
        script.append(" --dport ");
        script.append(PsiphonConstants.STANDARD_DNS_PORT);
        script.append(" -j REJECT");
        script.append(" || exit\n");
        
        // Reject all other outbound TCP packets

        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -A OUTPUT");
        script.append(" -p tcp");
        script.append(" -j REJECT");
        script.append(" || exit\n");
        
        String[] cmdAdd = {script.toString()};      
        
        doShellCommand(context, cmdAdd, runRoot, waitFor);
    }

    public static void teardownTransparentProxyRouting(Context context)
            throws PsiphonTransparentProxyException
    {
        flushIpTables(context);
    }

    private static void flushIpTables(Context context)
            throws PsiphonTransparentProxyException
    {
        String ipTablesPath = getIpTablesPath(context);
        
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

    private static String getIpTablesPath(Context context)
    {
        File iptables = new File("/system/bin/iptables");
        if (iptables.exists())
        {
            return iptables.getAbsolutePath();
        }
                
        iptables = new File("/system/xbin/iptables");
        if (iptables.exists())
        {
            return iptables.getAbsolutePath();
        }
        
        // TODO: throw on failure

        return "";
    }

    private static void doShellCommand(Context context, String[] cmds, boolean runAsRoot, boolean waitFor)
            throws PsiphonTransparentProxyException
    {
        int exitCode = -1;

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
                MyLog.d("executing shell cmd: " + cmds[i] + "; runAsRoot=" + runAsRoot + ";waitFor=" + waitFor);
                out.write(cmds[i]);
                out.write("\n");
            }
            
            out.flush();
            out.write("exit\n");
            out.flush();
                
            StringBuilder output = new StringBuilder();
    
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
            
            MyLog.d(output.toString());
        }
        catch (Exception ex)
        {
            throw new PsiphonTransparentProxyException(ex.getMessage());
        }
        
        if (exitCode != 0)
        {
            throw new PsiphonTransparentProxyException(
                    String.format(context.getString(R.string.transparent_proxy_command_failed), exitCode));
        }
    }
}
