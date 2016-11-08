/*
 * Copyright (c) 2015, Psiphon Inc.
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

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MoreOptionsPreferenceActivity extends PreferenceActivity implements
        OnSharedPreferenceChangeListener, OnPreferenceClickListener {

    Bundle mDefaultSummaryBundle;

    CheckBoxPreference mNotificationSound;
    CheckBoxPreference mNotificationVibration;
    DialogPreference mVpnAppExclusions;
    CheckBoxPreference mUseProxy;
    RadioButtonPreference mUseSystemProxy;
    RadioButtonPreference mUseCustomProxy;
    CheckBoxPreference mUseProxyAuthentication;
    EditTextPreference mProxyHost;
    EditTextPreference mProxyPort;
    EditTextPreference mProxyUsername;
    EditTextPreference mProxyPassword;
    EditTextPreference mProxyDomain;
    Preference mCustomProxyHeadersPref;
    CheckBoxPreference mAddCustomHeadersPreference;
    EditTextPreference mHeaderName1;
    EditTextPreference mHeaderValue1;
    EditTextPreference mHeaderName2;
    EditTextPreference mHeaderValue2;
    EditTextPreference mHeaderName3;
    EditTextPreference mHeaderValue3;

    private class HeaderValueChangeListener implements Preference.OnPreferenceChangeListener {
        EditTextPreference mHeaderName;

        public HeaderValueChangeListener(EditTextPreference pref) {
            mHeaderName = pref;
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            String headerName = mHeaderName.getText();
            String value = (String)newValue;
            if (TextUtils.isEmpty(headerName) && !TextUtils.isEmpty(value)) {
                //Just a warning, do not prevent user from entering empty header name
                Toast toast = Toast.makeText(MoreOptionsPreferenceActivity.this,
                        R.string.custom_proxy_header_ignored_values, Toast.LENGTH_SHORT);
                toast.show();
            }
            return true;
        }
    }

    private Preference.OnPreferenceChangeListener headerNameChangeListener =
            new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {

                    String headerName = (String) newValue;
                    if (!TextUtils.isEmpty(headerName)) {
                        // Validate Header
                        // https://www.w3.org/Protocols/rfc2616/rfc2616-sec2.html#sec2.2
                    /*
                     OCTET          = <any 8-bit sequence of data>
                     CHAR           = <any US-ASCII character (octets 0 - 127)>
                     UPALPHA        = <any US-ASCII uppercase letter "A".."Z">
                     LOALPHA        = <any US-ASCII lowercase letter "a".."z">
                     ALPHA          = UPALPHA | LOALPHA
                     DIGIT          = <any US-ASCII digit "0".."9">
                     CTL            = <any US-ASCII control character
                     (octets 0 - 31) and DEL (127)>
                     CR             = <US-ASCII CR, carriage return (13)>
                     LF             = <US-ASCII LF, linefeed (10)>
                     SP             = <US-ASCII SP, space (32)>
                     HT             = <US-ASCII HT, horizontal-tab (9)>
                     <">            = <US-ASCII double-quote mark (34)>


                     token          = 1*<any CHAR except CTLs or separators>
                     separators     = "(" | ")" | "<" | ">" | "@"
                     | "," | ";" | ":" | "\" | <">
                     | "/" | "[" | "]" | "?" | "="
                     | "{" | "}" | SP | HT

                     https://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.2

                     message-header = field-name ":" [ field-value ]
                     field-name     = token
                     field-value    = *( field-content | LWS )
                     field-content  = <the OCTETs making up the field-value
                     and consisting of either *TEXT or combinations
                     of token, separators, and quoted-string>
                     */

                        boolean isValid = true;
                        char[] separators = {'(', ')', '<', '>', '@',
                                ',', ';', ':', '\\', '"',
                                '/', '[', ']', '?', '=',
                                '{', '}', 32, 9};
                        outerloop:
                        for (int i = 0; i < headerName.length(); i++) {
                            char c = headerName.charAt(i);
                            //  OCTET check
                            if (c < 0 || c > 127) {
                                isValid = false;
                                break outerloop;
                            }
                            //  CTL check
                            if ((c >= 0 && c <= 31) || c == 127) {
                                isValid = false;
                                break outerloop;
                            }

                            // separators check
                            for (int j = 0; j < separators.length; j++) {
                                if (c == separators[j]) {
                                    isValid = false;
                                    break outerloop;
                                }
                            }
                        }
                        if (!isValid) {
                            Toast toast = Toast.makeText(MoreOptionsPreferenceActivity.this,
                                    R.string.custom_proxy_header_invalid_name, Toast.LENGTH_SHORT);
                            toast.show();
                            return false;
                        }
                    } else {
                        //Just a warning, do not prevent user from entering empty header name
                        Toast toast = Toast.makeText(MoreOptionsPreferenceActivity.this,
                                R.string.custom_proxy_header_ignored_values, Toast.LENGTH_SHORT);
                        toast.show();

                    }
                    return true;
                }
            };

    @SuppressWarnings("deprecation")
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        mDefaultSummaryBundle = new Bundle();

        // Store temporary preferences used in this activity in its own file
        PreferenceManager prefMgr = getPreferenceManager();
        prefMgr.setSharedPreferencesName(getString(R.string.moreOptionsPreferencesName));

        PreferenceScreen preferences = getPreferenceScreen();

        mNotificationSound = (CheckBoxPreference) preferences.findPreference(getString(R.string.preferenceNotificationsWithSound));
        mNotificationVibration = (CheckBoxPreference) preferences.findPreference(getString(R.string.preferenceNotificationsWithVibrate));

        mVpnAppExclusions = (DialogPreference) preferences.findPreference(getString(R.string.preferenceExcludeAppsFromVpn));

        mUseProxy = (CheckBoxPreference) preferences.findPreference(getString(R.string.useProxySettingsPreference));
        mUseSystemProxy = (RadioButtonPreference) preferences
                .findPreference(getString(R.string.useSystemProxySettingsPreference));
        mUseCustomProxy = (RadioButtonPreference) preferences
                .findPreference(getString(R.string.useCustomProxySettingsPreference));

        mProxyHost = (EditTextPreference) preferences
                .findPreference(getString(R.string.useCustomProxySettingsHostPreference));
        mProxyPort = (EditTextPreference) preferences
                .findPreference(getString(R.string.useCustomProxySettingsPortPreference));

        mUseProxyAuthentication = (CheckBoxPreference) preferences
                .findPreference(getString(R.string.useProxyAuthenticationPreference));
        mProxyUsername = (EditTextPreference) preferences
                .findPreference(getString(R.string.useProxyUsernamePreference));
        mProxyPassword = (EditTextPreference) preferences
                .findPreference(getString(R.string.useProxyPasswordPreference));
        mProxyDomain = (EditTextPreference) preferences
                .findPreference(getString(R.string.useProxyDomainPreference));

        mCustomProxyHeadersPref =  preferences
                .findPreference(getString(R.string.customProxyHeadersPreference));

        mAddCustomHeadersPreference = (CheckBoxPreference) preferences
                .findPreference(getString(R.string.addCustomHeadersPreference));

        mHeaderName1 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName1));
        mHeaderValue1 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue1));

        mHeaderName2 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName2));
        mHeaderValue2 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue2));

        mHeaderName3 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName3));
        mHeaderValue3 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue3));


        // Initialize with tray preferences values
        AppPreferences mpPreferences = new AppPreferences(this);

        mNotificationSound.setChecked(mpPreferences.getBoolean(getString(R.string.preferenceNotificationsWithSound), false));
        mNotificationVibration.setChecked(mpPreferences.getBoolean(getString(R.string.preferenceNotificationsWithVibrate), false));

        // R.xml.preferences is conditionally loaded at API version 11 and higher from the xml-v11 folder
        // If it isn't null here, we can reasonably assume it can be cast to our MultiSelectListPreference
        if (mVpnAppExclusions != null) {
            String excludedValuesFromPreference = mpPreferences.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
            if (!excludedValuesFromPreference.isEmpty()) {
                Set<String> excludedValuesSet = new HashSet<>(Arrays.asList(excludedValuesFromPreference.split(",")));
                ((InstalledAppsMultiSelectListPreference) mVpnAppExclusions).setValues(excludedValuesSet);

                SharedPreferences.Editor e = preferences.getEditor();
                e.putString(getString(R.string.preferenceExcludeAppsFromVpnString), excludedValuesFromPreference);
                // Use commit (sync) instead of apply (async) to prevent possible race with restarting
                // the tunnel happening before the value is fully persisted to shared preferences
                e.commit();
            }
        }

        mUseProxy.setChecked(mpPreferences.getBoolean(getString(R.string.useProxySettingsPreference), false));
        // set use system proxy preference by default
        mUseSystemProxy.setChecked(mpPreferences.getBoolean(getString(R.string.useSystemProxySettingsPreference), true));
        mUseCustomProxy.setChecked(mpPreferences.getBoolean(getString(R.string.useCustomProxySettingsPreference), false));
        mProxyHost.setText(mpPreferences.getString(getString(R.string.useCustomProxySettingsHostPreference), ""));
        mProxyPort.setText(mpPreferences.getString(getString(R.string.useCustomProxySettingsPortPreference), ""));
        mUseProxyAuthentication.setChecked(mpPreferences.getBoolean(getString(R.string.useProxyAuthenticationPreference), false));
        mProxyUsername.setText(mpPreferences.getString(getString(R.string.useProxyUsernamePreference), ""));
        mProxyPassword.setText(mpPreferences.getString(getString(R.string.useProxyPasswordPreference), ""));
        mProxyDomain.setText(mpPreferences.getString(getString(R.string.useProxyDomainPreference), ""));
        mAddCustomHeadersPreference.setChecked(mpPreferences.getBoolean(getString(R.string.addCustomHeadersPreference), false));
        mHeaderName1.setText(mpPreferences.getString(getString(R.string.customProxyHeaderName1), ""));
        mHeaderValue1.setText(mpPreferences.getString(getString(R.string.customProxyHeaderValue1), ""));
        mHeaderName2.setText(mpPreferences.getString(getString(R.string.customProxyHeaderName2), ""));
        mHeaderValue2.setText(mpPreferences.getString(getString(R.string.customProxyHeaderValue2), ""));
        mHeaderName3.setText(mpPreferences.getString(getString(R.string.customProxyHeaderName3), ""));
        mHeaderValue3.setText(mpPreferences.getString(getString(R.string.customProxyHeaderValue3), ""));


        // Set listeners
        mUseSystemProxy.setOnPreferenceClickListener(this);
        mUseCustomProxy.setOnPreferenceClickListener(this);

        mProxyHost.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String proxyHost = (String)newValue;
                if (TextUtils.isEmpty(proxyHost)) {
                    Toast toast = Toast.makeText(MoreOptionsPreferenceActivity.this, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                    toast.show();
                    return false;
                }
                return true;
            }
        });

        mProxyPort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                int proxyPort;
                try {
                    proxyPort = Integer.valueOf((String) newValue);
                } catch(NumberFormatException e) {
                    proxyPort = 0;
                }
                if (proxyPort >= 1 && proxyPort <= 65535) {
                    return true;
                }
                Toast toast = Toast.makeText(MoreOptionsPreferenceActivity.this, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                toast.show();
                return false;
            }
        });

        mHeaderName1.setOnPreferenceChangeListener(headerNameChangeListener);
        mHeaderName2.setOnPreferenceChangeListener(headerNameChangeListener);
        mHeaderName3.setOnPreferenceChangeListener(headerNameChangeListener);

        mHeaderValue1.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName1));
        mHeaderValue2.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName2));
        mHeaderValue3.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName3));


        initSummary();
        updatePreferencesScreen();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onResume() {
        super.onResume();
        // Set up a listener whenever a key changes
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onPause() {
        super.onPause();
        // Unregister the listener whenever a key changes
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, final String key) {
        Preference curPref = findPreference(key);
        updatePrefsSummary(curPref);
        updatePreferencesScreen();

    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(getString(R.string.moreOptionsPreferencesName), mode);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mUseSystemProxy) {
            mUseSystemProxy.setChecked(true);
            mUseCustomProxy.setChecked(false);
        }
        if (preference == mUseCustomProxy) {
            mUseSystemProxy.setChecked(false);
            mUseCustomProxy.setChecked(true);
        }
        return false;
    }

    protected void updatePrefsSummary(Preference pref) {
        if (pref instanceof EditTextPreference) {
            // EditPreference
            EditTextPreference editTextPref = (EditTextPreference) pref;
            String summary = editTextPref.getText();
            if (!TextUtils.isEmpty(summary)) {
                //hide passwords
                //http://stackoverflow.com/questions/15044595/preventing-edittextpreference-from-updating-summary-for-inputtype-password
                int inputType = editTextPref.getEditText().getInputType() & InputType.TYPE_MASK_VARIATION;
                boolean isPassword = ((inputType  == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                        ||(inputType  == InputType.TYPE_TEXT_VARIATION_PASSWORD)
                        ||(inputType  == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD));

                if (isPassword) {
                    editTextPref.setSummary(summary.replaceAll(".", "*"));
                }
                else {
                    editTextPref.setSummary(summary);
                }
            } else {
                editTextPref.setSummary((CharSequence) mDefaultSummaryBundle.get(editTextPref.getKey()));
            }
        }
    }

    /*
     * Init summary fields
     */
    @SuppressWarnings("deprecation")
    protected void initSummary() {
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            initPrefsSummary(getPreferenceScreen()
                    .getPreference(i));
        }
    }

    /*
     * Init single Preference
     */
    protected void initPrefsSummary(Preference p) {
        if (p instanceof PreferenceGroup) {
            PreferenceGroup pCat = (PreferenceGroup) p;
            for (int i = 0; i < pCat.getPreferenceCount(); i++) {
                initPrefsSummary(pCat.getPreference(i));
            }
        } else if (p instanceof EditTextPreference){
            mDefaultSummaryBundle.putCharSequence(p.getKey(), p.getSummary());
            updatePrefsSummary(p);
        }
    }

    private void disableCustomProxySettings() {
        mProxyHost.setEnabled(false);
        mProxyPort.setEnabled(false);
        mUseProxyAuthentication.setEnabled(false);
        disableProxyAuthenticationSettings();
    }

    private void enableCustomProxySettings() {
        mProxyHost.setEnabled(true);
        mProxyPort.setEnabled(true);
        mUseProxyAuthentication.setEnabled(true);
        enableProxyAuthenticationSettings();
    }

    private void disableProxyAuthenticationSettings() {
        mProxyUsername.setEnabled(false);
        mProxyPassword.setEnabled(false);
        mProxyDomain.setEnabled(false);
    }

    private void enableProxyAuthenticationSettings() {
        mProxyUsername.setEnabled(true);
        mProxyPassword.setEnabled(true);
        mProxyDomain.setEnabled(true);
    }

    private void disableProxySettings() {
        mUseSystemProxy.setEnabled(false);
        mUseCustomProxy.setEnabled(false);
        mCustomProxyHeadersPref.setEnabled(false);
        disableCustomProxySettings();
        disableProxyAuthenticationSettings();
    }

    private void enableProxySettings() {
        mUseSystemProxy.setEnabled(true);
        mUseCustomProxy.setEnabled(true);
        mCustomProxyHeadersPref.setEnabled(true);
        enableCustomProxySettings();
        enableProxyAuthenticationSettings();
    }

    private void disableCustomHeaderSettings() {
        mHeaderName1.setEnabled(false);
        mHeaderValue1.setEnabled(false);
        mHeaderName2.setEnabled(false);
        mHeaderValue2.setEnabled(false);
        mHeaderName3.setEnabled(false);
        mHeaderValue3.setEnabled(false);
    }

    private void enableCustomHeaderSettings() {
        mHeaderName1.setEnabled(true);
        mHeaderValue1.setEnabled(true);
        mHeaderName2.setEnabled(true);
        mHeaderValue2.setEnabled(true);
        mHeaderName3.setEnabled(true);
        mHeaderValue3.setEnabled(true);
    }

    protected void updatePreferencesScreen() {
        if (!mUseProxy.isChecked()) {
            disableProxySettings();
        } else {
            enableProxySettings();
            if (mUseSystemProxy.isChecked()) {
                disableCustomProxySettings();
            } else {
                enableCustomProxySettings();
                if (mUseProxyAuthentication.isChecked()) {
                    enableProxyAuthenticationSettings();
                } else {
                    disableProxyAuthenticationSettings();
                }
            }
            if (mAddCustomHeadersPreference.isChecked()) {
                enableCustomHeaderSettings();
            } else {
                disableCustomHeaderSettings();
            }
        }
    }
}
