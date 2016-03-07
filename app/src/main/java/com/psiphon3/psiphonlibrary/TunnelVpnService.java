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

import com.psiphon3.psiphonlibrary.Utils.MyLog;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.psiphon3.R;

@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class TunnelVpnService extends VpnService
{
    private TunnelManager m_Manager = new TunnelManager(this);

    public class LocalBinder extends Binder
    {
        public TunnelVpnService getService()
        {
            return TunnelVpnService.this;
        }
    }
    private final IBinder m_binder = new LocalBinder();
    
    @Override
    public IBinder onBind(Intent intent)
    {
        // Need to use super class behavior in specified cases:
        // http://developer.android.com/reference/android/net/VpnService.html#onBind%28android.content.Intent%29
        
        String action = intent.getAction();
        if (action != null && action.equals(SERVICE_INTERFACE))
        {
            return super.onBind(intent);
        }
        
        return m_binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return m_Manager.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate()
    {
        PsiphonData.getPsiphonData().setCurrentTunnelManager(m_Manager);
    }

    @Override
    public void onDestroy()
    {
        PsiphonData.getPsiphonData().setCurrentTunnelManager(null);
        m_Manager.onDestroy();
    }

    @Override
    public void onRevoke()
    {
        MyLog.w(R.string.vpn_service_revoked, MyLog.Sensitivity.NOT_SENSITIVE);
        // stopSelf will trigger onDestroy in the main thread, which will in turn invoke m_Core.onDestroy
        stopSelf();
    }
    
    public VpnService.Builder newBuilder()
    {
        return new VpnService.Builder();
    }
}
