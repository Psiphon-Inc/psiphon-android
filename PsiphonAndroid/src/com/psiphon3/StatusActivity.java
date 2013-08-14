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

package com.psiphon3;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TabHost;

import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.RegionAdapter;
import com.psiphon3.psiphonlibrary.ServerInterface;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.Utils.MyLog;


public class StatusActivity 
    extends com.psiphon3.psiphonlibrary.MainBase.TabbedActivityBase
{
    public static final String TUNNEL_WHOLE_DEVICE_PREFERENCE = "tunnelWholeDevicePreference";
    public static final String EGRESS_REGION_PREFERENCE = "egressRegionPreference";
    
    private static boolean m_firstRun = true;
    private CheckBox m_tunnelWholeDeviceToggle;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private RegionAdapter m_regionAdapter;
    private SpinnerHelper m_regionSelector;

    public StatusActivity()
    {
        m_eventsInterface = new Events();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        setContentView(R.layout.main);

        m_tabHost = (TabHost)findViewById(R.id.tabHost);
        m_toggleButton = (Button)findViewById(R.id.toggleButton);
        m_tunnelWholeDeviceToggle = (CheckBox)findViewById(R.id.tunnelWholeDeviceToggle);
        m_regionSelector = new SpinnerHelper(findViewById(R.id.regionSelector));

        super.onCreate(savedInstanceState);

        // Transparent proxy-based "Tunnel Whole Device" option is only available on rooted devices and
        // defaults to true on rooted devices.
        // On Android 4+, we offer "Whole Device" via the VpnService facility, which does not require root.
        // We prefer VpnService when available, even when the device is rooted.
        boolean isRooted = Utils.isRooted();
        boolean canRunVpnService = Utils.hasVpnService() && !PsiphonData.getPsiphonData().getVpnServiceUnavailable();
        boolean canWholeDevice = isRooted || canRunVpnService;
       
        m_tunnelWholeDeviceToggle.setEnabled(canWholeDevice);
        boolean tunnelWholeDevicePreference = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE, canWholeDevice);
        m_tunnelWholeDeviceToggle.setChecked(tunnelWholeDevicePreference);

        if (m_firstRun)
        {
            // Force setServerExists calls so we can configure the region spinner.
            // TODO: fix this hack.
            new ServerInterface(this);
        }
 
        m_regionAdapter = new RegionAdapter(this);
        m_regionSelector.setAdapter(m_regionAdapter);
        String egressRegionPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(EGRESS_REGION_PREFERENCE, ServerInterface.ServerEntry.REGION_CODE_ANY);
        PsiphonData.getPsiphonData().setEgressRegion(egressRegionPreference);
        int position = m_regionAdapter.getPositionForRegionCode(egressRegionPreference);
        m_regionSelector.setSelection(position);

        m_regionSelector.setOnItemSelectedListener(regionSpinnerOnItemSelected);
        // Re-populate the spinner when it is expanded -- the underlying region list could change
        // due to background server discovery or remote server list fetch.
        m_regionSelector.getSpinner().setOnTouchListener(regionSpinnerOnTouch);
        m_regionSelector.getSpinner().setOnKeyListener(regionSpinnerOnKey);
        
        // Use PsiphonData to communicate the setting to the TunnelService so it doesn't need to
        // repeat the isRooted check. The preference is retained even if the device becomes "unrooted"
        // and that's why setTunnelWholeDevice != tunnelWholeDevicePreference.
        PsiphonData.getPsiphonData().setTunnelWholeDevice(canWholeDevice && tunnelWholeDevicePreference);
        PsiphonData.getPsiphonData().setDownloadUpgrades(true);
        
        // Auto-start on app first run
        if (m_firstRun)
        {
            m_firstRun = false;
            startUp();
        }
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
            
        // If the app is already foreground (so onNewIntent is being called), 
        // the incoming intent is not automatically set as the activity's intent
        // (i.e., the intent returned by getIntent()). We want this behaviour, 
        // so we'll set it explicitly. 
        setIntent(intent);

        // Handle explicit intent that is received when activity is already running
        HandleCurrentIntent();
    }

    protected void HandleCurrentIntent()
    {
        Intent intent = getIntent();
        
        if (intent == null || intent.getAction() == null)
        {
            return;
        }

        if (0 == intent.getAction().compareTo(HANDSHAKE_SUCCESS))
        {
            // Show the home page. Always do this in browser-only mode, even
            // after an automated reconnect -- since the status activity was
            // brought to the front after an unexpected disconnect. In whole
            // device mode, after an automated reconnect, we don't re-invoke
            // the browser.
            if (!PsiphonData.getPsiphonData().getTunnelWholeDevice()
                || !intent.getBooleanExtra(HANDSHAKE_SUCCESS_IS_RECONNECT, false))
            {
                Events.displayBrowser(this);
            }
            
            // We only want to respond to the HANDSHAKE_SUCCESS action once,
            // so we need to clear it (by setting it to a non-special intent).
            setIntent(new Intent(
                            "ACTION_VIEW",
                            null,
                            this,
                            this.getClass()));
        }
        
        // No explicit action for UNEXPECTED_DISCONNECT, just show the activity
    }
    
    public void onToggleClick(View v)
    {
        doToggle();
    }

    public void onTunnelWholeDeviceToggle(View v)
    {
        boolean restart = false;

        if (isServiceRunning())
        {
            doToggle();
            restart = true;
        }

        boolean tunnelWholeDevicePreference = m_tunnelWholeDeviceToggle.isChecked();
        updateWholeDevicePreference(tunnelWholeDevicePreference);
        
        if (restart)
        {
            startTunnel(this);
        }
    }
    
    protected void updateWholeDevicePreference(boolean tunnelWholeDevicePreference)
    {
        // No isRooted check: the user can specify whatever preference they
        // wish. Also, CheckBox enabling should cover this (but isn't required to).
        Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putBoolean(TUNNEL_WHOLE_DEVICE_PREFERENCE, tunnelWholeDevicePreference);
        editor.commit();
        
        PsiphonData.getPsiphonData().setTunnelWholeDevice(tunnelWholeDevicePreference);
    }

    public void onRegionSelected(int position)
    {
        String selectedRegionCode = m_regionAdapter.getSelectedRegionCode(position);
        
        String egressRegionPreference = PreferenceManager.getDefaultSharedPreferences(this).getString(EGRESS_REGION_PREFERENCE, ServerInterface.ServerEntry.REGION_CODE_ANY);
        if (selectedRegionCode.equals(egressRegionPreference)
            && selectedRegionCode.equals(PsiphonData.getPsiphonData().getEgressRegion()))
        {
            return;
        }
        
        boolean restart = false;

        // NOTE: reconnects even when Any is selected: we could select a faster server
        if (isServiceRunning())
        {
            doToggle();
            restart = true;
        }

        updateEgressRegionPreference(selectedRegionCode);
        
        if (restart)
        {
            startTunnel(this);
        }
    }
    
    protected void updateEgressRegionPreference(String egressRegionPreference)
    {
        // No isRooted check: the user can specify whatever preference they
        // wish. Also, CheckBox enabling should cover this (but isn't required to).
        Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(EGRESS_REGION_PREFERENCE, egressRegionPreference);
        editor.commit();
        
        PsiphonData.getPsiphonData().setEgressRegion(egressRegionPreference);
    }

    private AdapterView.OnItemSelectedListener regionSpinnerOnItemSelected = new AdapterView.OnItemSelectedListener()
    {

        public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
        {
            onRegionSelected(position);
        }

        public void onNothingSelected(AdapterView parent)
        {
        }
    };
    
    private View.OnTouchListener regionSpinnerOnTouch = new View.OnTouchListener()
    {
        public boolean onTouch(View v, MotionEvent event)
        {
            if (event.getAction() == MotionEvent.ACTION_UP)
            {
                m_regionAdapter.populate();
            }
            return false;
        }
    };

    private View.OnKeyListener regionSpinnerOnKey = new View.OnKeyListener()
    {
        public boolean onKey(View v, int keyCode, KeyEvent event)
        {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            {
                m_regionAdapter.populate();
                return true;
            }
            else
            {
                return false;
            }
        }
    };
    
    public void onOpenBrowserClick(View v)
    {
        Events.displayBrowser(this);       
    }
    
    public void onFeedbackClick(View v)
    {
        Intent feedbackIntent = new Intent(this, FeedbackActivity.class);
        startActivity(feedbackIntent);
    }

    public void onAboutClick(View v)
    {
        doAbout();
    }
    
    @Override
    protected void startUp()
    {        
        // If the user hasn't set a whole-device-tunnel preference, show a prompt
        // (and delay starting the tunnel service until the prompt is completed)
        
        boolean hasPreference = PreferenceManager.getDefaultSharedPreferences(this).contains(TUNNEL_WHOLE_DEVICE_PREFERENCE);
                
        if (m_tunnelWholeDeviceToggle.isEnabled() &&
            !hasPreference &&
            !isServiceRunning())
        {
            if (!m_tunnelWholeDevicePromptShown)
            {
                final Context context = this;

                new AlertDialog.Builder(context)
                    .setCancelable(false)
                    .setOnKeyListener(
                            new DialogInterface.OnKeyListener() {
                                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                    // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                    return keyCode == KeyEvent.KEYCODE_SEARCH;
                                }})
                    .setTitle(R.string.StatusActivity_WholeDeviceTunnelPromptTitle)
                    .setMessage(R.string.StatusActivity_WholeDeviceTunnelPromptMessage)
                    .setPositiveButton(R.string.StatusActivity_WholeDeviceTunnelPositiveButton,
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    // Persist the "on" setting
                                    updateWholeDevicePreference(true);
                                    startTunnel(context);
                                }})
                    .setNegativeButton(R.string.StatusActivity_WholeDeviceTunnelNegativeButton,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        // Turn off and persist the "off" setting
                                        m_tunnelWholeDeviceToggle.setChecked(false);
                                        updateWholeDevicePreference(false);
                                        startTunnel(context);
                                    }})
                    .setOnCancelListener(
                            new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface dialog) {
                                    // Don't change or persist preference (this prompt may reappear)
                                    startTunnel(context);
                                }})
                    .show();
                m_tunnelWholeDevicePromptShown = true;
            }
            else
            {
                // ...there's a prompt already showing (e.g., user hit Home with the
                // prompt up, then resumed Psiphon)
            }
            
            // ...wait and let onClick handlers will start tunnel
        }
        else
        {
            // No prompt, just start the tunnel (if not already running)
            
            startTunnel(this);
        }

        // Handle the intent that resumed that activity
        HandleCurrentIntent();
    }
    
    @Override
    protected boolean doVpnPrepare()
    {
        try
        {
            return super.doVpnPrepare();
        }
        catch (ActivityNotFoundException e)
        {
            MyLog.e(R.string.tunnel_whole_device_exception, MyLog.Sensitivity.NOT_SENSITIVE);
            
            // VpnService is broken. For rooted devices, proceed with starting Whole Device in root mode.
            
            if (Utils.isRooted())
            {
                PsiphonData.getPsiphonData().setVpnServiceUnavailable(true);

                // false = not waiting for prompt, so service will be started immediately
                return false;
            }

            // For non-rooted devices, turn off the option and abort.
            
            m_tunnelWholeDeviceToggle.setChecked(false);
            m_tunnelWholeDeviceToggle.setEnabled(false);
            updateWholeDevicePreference(false);

            // true = waiting for prompt, although we can't start the activity so onActivityResult won't be called
            return true;
        }
    }
}
