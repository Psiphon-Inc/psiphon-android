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

import java.util.ArrayList;

import android.os.Build;

public class PsiphonConstants
{
    public static Boolean DEBUG = false; // may be changed by activity

    public final static String TAG = "Psiphon";

    public final static String SERVER_ENTRY_FILENAME = "psiphon_server_entries.json";

    public final static String LAST_CONNECTED_FILENAME = "last_connected";

    public final static String LAST_CONNECTED_NO_VALUE = "None";

    public final static int CLIENT_SESSION_ID_SIZE_IN_BYTES = 16;

    public final static int STANDARD_DNS_PORT = 53;

    public final static int SOCKS_PORT = 1080;

    public final static int HTTP_PROXY_PORT = 8080;

    public final static int DNS_PROXY_PORT = 9053;

    public final static int TRANSPARENT_PROXY_PORT = 9080;

    public final static int DEFAULT_WEB_SERVER_PORT = 443;

    public final static int CHECK_TUNNEL_SERVER_FIRST_PORT = 9001;

    public final static int CHECK_TUNNEL_SERVER_LAST_PORT = 10000;

    public final static int CHECK_TUNNEL_TIMEOUT_MILLISECONDS = 500;

    public final static int SESSION_ESTABLISHMENT_TIMEOUT_MILLISECONDS = 20000;

    public final static int TARGET_PROTOCOL_ROTATION_SESSION_DURATION_THRESHOLD_MILLISECONDS = 2*60*1000;

    public final static String RELAY_PROTOCOL_OSSH = "OSSH";

    public final static String RELAY_PROTOCOL_FRONTED_MEEK_OSSH = "FRONTED-MEEK-OSSH";

    public final static String RELAY_PROTOCOL_UNFRONTED_MEEK_OSSH = "UNFRONTED-MEEK-OSSH";
    
    public final static String RELAY_PROTOCOL_UNFRONTED_MEEK_HTTPS_OSSH = "UNFRONTED-MEEK-HTTPS-OSSH";

    // The character restrictions are dictated by the server.
    public final static String PLATFORM = ("Android_" + Build.VERSION.RELEASE).replaceAll("[^\\w\\-\\.]", "_");

    public final static int HTTPS_REQUEST_SHORT_TIMEOUT = 5000;

    public final static int HTTPS_REQUEST_LONG_TIMEOUT = 20000;
    
    public final static int HTTPS_REQUEST_FINAL_REQUEST_TIMEOUT = 1000;

    public final static int SECONDS_BETWEEN_SUCCESSFUL_REMOTE_SERVER_LIST_FETCH = 60*60*6;

    public final static int SECONDS_BETWEEN_UNSUCCESSFUL_REMOTE_SERVER_LIST_FETCH = 60*5;

    public final static String ROOTED = "_rooted";

    public final static String PLAY_STORE_BUILD = "_playstore";
    
    public final static String REMOTE_SERVER_LIST_ETAG_KEY = "REMOTE_SERVER_LIST_ETAG";

    // Only one of these capabilities are needed
    public final static ArrayList<String> SUFFICIENT_CAPABILITIES_FOR_TUNNEL = new ArrayList<String>() {{
        add(ServerInterface.ServerEntry.CAPABILITY_OSSH);
        add(ServerInterface.ServerEntry.CAPABILITY_FRONTED_MEEK);
        add(ServerInterface.ServerEntry.CAPABILITY_UNFRONTED_MEEK);
        add(ServerInterface.ServerEntry.CAPABILITY_UNFRONTED_MEEK_HTTPS);}};

    public final static String VPN_INTERFACE_NETMASK = "255.255.255.0";

    public final static int VPN_INTERFACE_MTU = 1500;

    public final static int UDPGW_SERVER_PORT = 7300;

    public final static String TUNNEL_WHOLE_DEVICE_DNS_RESOLVER_ADDRESS = "8.8.4.4";

    public final static long PREEMPTIVE_RECONNECT_LIFETIME_ADJUSTMENT_MILLISECONDS = -5000;

    public final static long PREEMPTIVE_RECONNECT_SOCKET_TIMEOUT_MILLISECONDS = 5000;

    public final static long PREEMPTIVE_RECONNECT_BIND_WAIT_MILLISECONDS = 100;
}
