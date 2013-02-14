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

import android.content.Context;
import android.content.Intent;


public interface Events
{
	public void appendStatusMessage(Context context, String message, int messageClass);
	
	public void signalHandshakeSuccess(Context context);
	
	public void signalUnexpectedDisconnect(Context context);
	
	public void signalTunnelStarting(Context context);

	public void signalTunnelStopping(Context context);
	
	Intent pendingSignalNotification(Context context);
}
