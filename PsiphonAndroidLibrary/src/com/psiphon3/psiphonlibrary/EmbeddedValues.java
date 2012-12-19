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

public interface EmbeddedValues
{
    final String PROPAGATION_CHANNEL_ID = "";
    
    final String SPONSOR_ID = "";
    
    final String CLIENT_VERSION = "2";
    
    final String EMBEDDED_SERVER_LIST = "";

    final String REMOTE_SERVER_LIST_URL =
        "https://s3.amazonaws.com/invalid_bucket_name/server_entries";

    final String REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY = "";
    
    final String FEEDBACK_ENCRYPTION_PUBLIC_KEY = "";

    // NOTE: Info link may be opened when not tunneled
    final String INFO_LINK_URL = "https://sites.google.com/a/psiphon3.com/psiphon3/";
}
