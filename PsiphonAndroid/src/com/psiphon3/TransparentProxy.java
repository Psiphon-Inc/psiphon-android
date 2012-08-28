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

import com.psiphon3.Utils.MyLog;


// Based on Orbot's TorTransProxy/TorServiceUtils implementations
//
// https://guardianproject.info/apps/orbot/
// https://gitweb.torproject.org/orbot.git

public class TransparentProxy
{    
    public static void setupTransparentProxy(Context context)
    {
        boolean runRoot = true;
        boolean waitFor = true;
        String ipTablesPath = getIpTablesPath(context);
        StringBuilder script = new StringBuilder();        
        StringBuilder res = new StringBuilder();
        int code = -1;
        int psiphonUid = context.getApplicationInfo().uid;
        
        flushIptables(context);
        
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
                PsiphonData.getPsiphonData().getHTTPPort(),
                PsiphonData.getPsiphonData().getSOCKSPort()
        };
        
        for (int port : ports)
        {
            // Allow packets to localhost (contains all the port-redirected ones)

            script.append(ipTablesPath);
            script.append(" -t filter");
            script.append(" -A OUTPUT");
            script.append(" -m owner ! --uid-owner ");
            script.append(torUid);
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
        
        doShellCommand(cmdAdd, runRoot, waitFor);
    }

    public static void teardownTransparentProxy(Context context)
    {
        flushIpTables();
    }

    public static void flushIpTables(Context context)
    {
        String ipTablesPath = getIpTablesPath(context);
        
        final StringBuilder script = new StringBuilder();        
        StringBuilder res = new StringBuilder();
        int code = -1;
        
        script.append(ipTablesPath);
        script.append(" -t nat");
        script.append(" -F || exit\n");
        
        script.append(ipTablesPath);
        script.append(" -t filter");
        script.append(" -F || exit\n");
        
        String[] cmd = {script.toString()};
        doShellCommand(cmd, true, true);        
    }

    private static String getIpTablesPath(Context context)
    {
        File iptables = new File("/system/bin/iptables");
        if (iptables.exists())
        {
            return iptables.getAbsolutePath();
        }
                
        File iptables = new File("/system/xbin/iptables");
        if (iptables.exists())
        {
            return iptables.getAbsolutePath();
        }
        
        // TODO: throw on failure

        return "";
    }

    private static int doShellCommand(String[] cmds, boolean runAsRoot, boolean waitFor) throws Exception
    {
        Process proc = null;
        int exitCode = -1;
        
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
            
        StringBuilder log;

        if (waitFor)
        {            
            final char buf[] = new char[10];
            
            // Consume the "stdout"
            InputStreamReader reader = new InputStreamReader(proc.getInputStream());
            int read=0;
            while ((read=reader.read(buf)) != -1)
            {
                if (log != null) log.append(buf, 0, read);
            }
            
            // Consume the "stderr"
            reader = new InputStreamReader(proc.getErrorStream());
            read=0;
            while ((read=reader.read(buf)) != -1)
            {
                if (log != null) log.append(buf, 0, read);
            }
            
            exitCode = proc.waitFor();
        }
        
        MyLog.d(log.toString());
        
        // TODO: throw on failure
        
        return exitCode;
    }
}
