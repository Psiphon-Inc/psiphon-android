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

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.URLUtil;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;

import com.psiphon3.psiphonlibrary.UpgradeManager;
import com.psiphon3.psiphonlibrary.UpgradeManager.UpgradeInstaller;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.PsiphonConstants;
import com.psiphon3.psiphonlibrary.PsiphonData;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.TunnelCore;
import com.psiphon3.psiphonlibrary.TunnelService;
import com.psiphon3.psiphonlibrary.TunnelVpnService;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.Utils.MyLog;


public class StatusActivity 
    extends com.psiphon3.psiphonlibrary.MainBase.Activity
{
    public static final String ADD_MESSAGE_CLASS = "com.psiphon3.PsiphonAndroidActivity.ADD_MESSAGE_CLASS";
    public static final String HANDSHAKE_SUCCESS = "com.psiphon3.PsiphonAndroidActivity.HANDSHAKE_SUCCESS";
    public static final String HANDSHAKE_SUCCESS_IS_RECONNECT = "com.psiphon3.PsiphonAndroidActivity.HANDSHAKE_SUCCESS_IS_RECONNECT";
    public static final String UNEXPECTED_DISCONNECT = "com.psiphon3.PsiphonAndroidActivity.UNEXPECTED_DISCONNECT";
    public static final String TUNNEL_STARTING = "com.psiphon3.PsiphonAndroidActivity.TUNNEL_STARTING";
    public static final String TUNNEL_STOPPING = "com.psiphon3.PsiphonAndroidActivity.TUNNEL_STOPPING";

    public static final String TUNNEL_WHOLE_DEVICE_PREFERENCE = "tunnelWholeDevicePreference";
    
    private static final int VPN_PREPARE = 100;
    
    private StatusListViewManager m_statusListManager;
    private Button m_toggleButton;
    private CheckBox m_tunnelWholeDeviceToggle;
    private boolean m_tunnelWholeDevicePromptShown = false;
    private LocalBroadcastManager m_localBroadcastManager;
    private final Events m_eventsInterface = new Events();
    private static boolean m_firstRun = true;
        
    private boolean m_boundToTunnelService = false;
    private ServiceConnection m_tunnelServiceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service)
        {
            TunnelService.LocalBinder binder = (TunnelService.LocalBinder) service;
            TunnelService tunnelService = binder.getService();
            m_boundToTunnelService = true;
            tunnelService.setEventsInterface(m_eventsInterface);
            startService(new Intent(StatusActivity.this, TunnelService.class));
        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0)
        {
            m_boundToTunnelService = false;
        }
    };

    private boolean m_boundToTunnelVpnService = false;
    private ServiceConnection m_tunnelVpnServiceConnection = new ServiceConnection()
    {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service)
        {
            // VpnService backwards compatibility: this has sufficient lazy class loading
            // as onServiceConnected is only called on bind.

            TunnelVpnService.LocalBinder binder = (TunnelVpnService.LocalBinder) service;
            TunnelVpnService tunnelVpnService = binder.getService();
            m_boundToTunnelVpnService = true;
            tunnelVpnService.setEventsInterface(m_eventsInterface);
            startService(new Intent(StatusActivity.this, TunnelVpnService.class));
        }
        
        @Override
        public void onServiceDisconnected(ComponentName arg0)
        {
            m_boundToTunnelVpnService = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.main);
        
        // Set up the list view
        m_statusListManager = new StatusListViewManager((ListView)findViewById(R.id.statusList));
        
        m_toggleButton = (Button)findViewById(R.id.toggleButton);
        initToggleText();

        /*
        // Draw attention to the new Start/Stop command
        // http://stackoverflow.com/questions/4852281/android-how-to-make-a-button-flashing
        final Animation animation = new AlphaAnimation(1, 0);
        animation.setDuration(500);
        animation.setInterpolator(new LinearInterpolator());
        animation.setRepeatCount(2);
        animation.setRepeatMode(Animation.REVERSE);
        m_toggleButton.startAnimation(animation);
        */

        m_tunnelWholeDeviceToggle = (CheckBox)findViewById(R.id.tunnelWholeDeviceToggle);

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
        // Use PsiphonData to communicate the setting to the TunnelService so it doesn't need to
        // repeat the isRooted check. The preference is retained even if the device becomes "unrooted"
        // and that's why setTunnelWholeDevice != tunnelWholeDevicePreference.
        PsiphonData.getPsiphonData().setTunnelWholeDevice(canWholeDevice && tunnelWholeDevicePreference);
        
        PsiphonData.getPsiphonData().setDownloadUpgrades(true);
        
        // Note that this must come after the above lines, or else the activity
        // will not be sufficiently initialized for isDebugMode to succeed. (Voodoo.)
        PsiphonConstants.DEBUG = Utils.isDebugMode(this);

        // Listen for new messages
        // Using local broad cast (http://developer.android.com/reference/android/support/v4/content/LocalBroadcastManager.html)
        
        m_localBroadcastManager = LocalBroadcastManager.getInstance(StatusActivity.this);

        m_localBroadcastManager.registerReceiver(
                new TunnelStartingReceiver(),
                new IntentFilter(TUNNEL_STARTING));

        m_localBroadcastManager.registerReceiver(
                new TunnelStoppingReceiver(),
                new IntentFilter(TUNNEL_STOPPING));
        
        // Restore messages previously posted by the service.
        MyLog.restoreLogHistory();
        
        // Auto-start on app first run
        if (m_firstRun)
        {
            m_firstRun = false;
            startUp();
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        
        PsiphonData.getPsiphonData().setStatusActivityForeground(true);
    }
    
    @Override
    protected void onPause()
    {
        super.onPause();
        
        unbindTunnelService();
        
        PsiphonData.getPsiphonData().setStatusActivityForeground(false);
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
        // TODO: use TunnelStartingReceiver/TunnelStoppingReceiver to track state?
        if (!isServiceRunning())
        {
            startUp();
        }
        else
        {
            stopTunnel(this);
        }
    }

    private void initToggleText()
    {
        // Only use this in onCreate. For updating the text when the activity
        // is showing and the service is stopping, it's more reliable to
        // use TunnelStoppingReceiver.
        m_toggleButton.setText(isServiceRunning() ? getText(R.string.stop) : getText(R.string.start));
    }
    
    public class TunnelStartingReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            m_toggleButton.setText(getText(R.string.stop));
        }
    }

    public class TunnelStoppingReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            // When the tunnel self-stops, we also need to unbind to ensure the service is destroyed
            unbindTunnelService();
            m_toggleButton.setText(getText(R.string.start));
        }
    }
    
    public void onTunnelWholeDeviceToggle(View v)
    {
        boolean restart = false;

        if (isServiceRunning())
        {
            stopTunnel(this);
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
        if (URLUtil.isValidUrl(EmbeddedValues.INFO_LINK_URL))
        {
            // TODO: if connected, open in Psiphon browser? 
            // Events.displayBrowser(this, Uri.parse(PsiphonConstants.INFO_LINK_URL));

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(EmbeddedValues.INFO_LINK_URL));
            startActivity(browserIntent);
        }
    }
    
    private void startUp()
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
    
    private void startTunnel(Context context)
    {
        boolean waitingForPrompt = false;
        
        if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService())
        {
            // VpnService backwards compatibility: for lazy class loading the VpnService
            // class reference has to be in another function (doVpnPrepare), not just
            // in a conditional branch.
            waitingForPrompt = doVpnPrepare();
        }
        if (!waitingForPrompt)
        {
            startService(this);
        }
    }
    
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    private boolean doVpnPrepare()
    {
        // VpnService: need to display OS user warning. If whole device option is
        // selected and we expect to use VpnService, so the prompt here in the UI
        // before starting the service.
        
        Intent intent = VpnService.prepare(this);
        if (intent != null)
        {
            // Catching ActivityNotFoundException as per:
            // http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
            //
            // TODO: can we disable the mode before we reach this this failure point with
            // resolveActivity()? We'll need the intent from prepare() or we'll have to mimic it.
            // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29
            
            try
            {
                startActivityForResult(intent, VPN_PREPARE);                
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

            // startTunnelService will be called in onActivityResult
            return true;
        }
        
        return false;
    }
    
    @Override
    protected void onActivityResult(int request, int result, Intent data)
    {
        if (request == VPN_PREPARE && result == RESULT_OK)
        {
            startService(this);
        }
    }
    
    private void startService(Context context)
    {
        // TODO: onResume calls this and when there was only one kind of service
        // it was safe to call through to bindService, which would start that
        // service if it was not already running. Now we have two types of services,
        // can we rely on blindly rebinding? What if the getTunnelWholeDevice()
        // value changed, can we end up with two running services? For now,
        // we have some asserts.
        
        if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService())
        {
            assert(m_boundToTunnelService == false);
            
            // VpnService backwards compatibility: doStartTunnelVpnService is a wrapper
            // function so we don't reference the undefined class when this function
            // is loaded.
            doStartTunnelVpnService(context);
        }
        else
        {
            assert(m_boundToTunnelVpnService == false);

            Intent intent = new Intent(context, TunnelService.class);
            bindService(intent, m_tunnelServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }
    
    private void doStartTunnelVpnService(Context context)
    {
        Intent intent = new Intent(context, TunnelVpnService.class);
        bindService(intent, m_tunnelVpnServiceConnection, Context.BIND_AUTO_CREATE);        
    }
    
    private void stopTunnel(Context context)
    {
        unbindTunnelService();
        if (PsiphonData.getPsiphonData().getTunnelWholeDevice() && Utils.hasVpnService())
        {
            doStopVpnTunnel(context);
        }
        else
        {
            stopService(new Intent(context, TunnelService.class));
        }
    }

    private void doStopVpnTunnel(Context context)
    {        
        TunnelCore currentTunnelCore = PsiphonData.getPsiphonData().getCurrentTunnelCore();
        
        if (currentTunnelCore != null)
        {
            // See comments in stopVpnServiceHelper about stopService.
            currentTunnelCore.stopVpnServiceHelper();
            stopService(new Intent(context, TunnelVpnService.class));
        }
    }
    
    private void unbindTunnelService()
    {
        if (m_boundToTunnelService)
        {
            unbindService(m_tunnelServiceConnection);
            m_boundToTunnelService = false;
        }
        if (m_boundToTunnelVpnService)
        {
            unbindService(m_tunnelVpnServiceConnection);
            m_boundToTunnelVpnService = false;
        }
    }
    
    /**
     * Determine if the Psiphon local service is currently running.
     * @see <a href="http://stackoverflow.com/a/5921190/729729">From StackOverflow answer: "android: check if a service is running"</a>
     * @return True if the service is already running, false otherwise.
     */
    private boolean isServiceRunning()
    {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
        {
            if (TunnelService.class.getName().equals(service.service.getClassName()) ||
                    (Utils.hasVpnService() && isVpnService(service.service.getClassName())))
            {
                return true;
            }
        }
        return false;
    }
    
    private boolean isVpnService(String className)
    {
        return TunnelVpnService.class.getName().equals(className);
    }

    /*
     * MyLog.ILogger implementation
     */

    /**
     * @see com.psiphon3.psiphonlibrary.Utils.MyLog.ILogger#statusEntryAdded()
     */
    @Override
    public void statusEntryAdded()
    {
        m_statusListManager.notifyStatusAdded();
    }
}
