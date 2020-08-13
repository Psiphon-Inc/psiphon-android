/*
 * Copyright (c) 2020, Psiphon Inc.
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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;

import com.psiphon3.MainActivityViewModel;
import com.psiphon3.subscription.R;

import java.util.ArrayList;

public class ProxyOptionsPreferenceActivity extends LocalizedActivities.AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new ProxyOptionsPreferenceFragment())
                    .commit();
        }

        MainActivityViewModel viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) {
            return true;
        }
        return super.onSupportNavigateUp();
    }

    public static class ProxyOptionsPreferenceFragment extends PsiphonPreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        CheckBoxPreference useProxy;
        Preference customProxyHeadersPreference;
        RadioButtonPreference useSystemProxy;
        RadioButtonPreference useCustomProxy;
        CheckBoxPreference useProxyAuthentication;
        EditTextPreference proxyHost;
        EditTextPreference proxyPort;
        EditTextPreference proxyUsername;
        EditTextPreference proxyPassword;
        EditTextPreference proxyDomain;
        private ArrayList<EditTextPreference> editTextPreferences;
        private Bundle defaultSummaryBundle = new Bundle();

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            addPreferencesFromResource(R.xml.proxy_options_preferences);
            final PreferenceScreen preferences = getPreferenceScreen();

            useProxy = (CheckBoxPreference) preferences.findPreference(getString(R.string.useProxySettingsPreference));
            useSystemProxy = (RadioButtonPreference) preferences
                    .findPreference(getString(R.string.useSystemProxySettingsPreference));
            useCustomProxy = (RadioButtonPreference) preferences
                    .findPreference(getString(R.string.useCustomProxySettingsPreference));

            customProxyHeadersPreference = preferences
                    .findPreference(getString(R.string.customProxyHeadersPreference));

            proxyHost = (EditTextPreference) preferences
                    .findPreference(getString(R.string.useCustomProxySettingsHostPreference));
            proxyPort = (EditTextPreference) preferences
                    .findPreference(getString(R.string.useCustomProxySettingsPortPreference));
            proxyPort.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER));

            useProxyAuthentication = (CheckBoxPreference) preferences
                    .findPreference(getString(R.string.useProxyAuthenticationPreference));
            proxyUsername = (EditTextPreference) preferences
                    .findPreference(getString(R.string.useProxyUsernamePreference));
            proxyPassword = (EditTextPreference) preferences
                    .findPreference(getString(R.string.useProxyPasswordPreference));
            proxyPassword.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            proxyDomain = (EditTextPreference) preferences
                    .findPreference(getString(R.string.useProxyDomainPreference));

            editTextPreferences = new ArrayList<>();
            editTextPreferences.add(proxyHost);
            editTextPreferences.add(proxyPort);
            editTextPreferences.add(proxyUsername);
            editTextPreferences.add(proxyPassword);
            editTextPreferences.add(proxyDomain);

            // Collect default summaries of EditTextPreferences
            for (Preference pref : editTextPreferences) {
                if (pref != null) {
                    defaultSummaryBundle.putCharSequence(pref.getKey(), pref.getSummary());
                }
            }

            final PreferenceGetter preferenceGetter = getPreferenceGetter();
            useProxy.setChecked(preferenceGetter.getBoolean(getString(R.string.useProxySettingsPreference), false));
            // set use system proxy preference by default
            useSystemProxy.setChecked(preferenceGetter.getBoolean(getString(R.string.useSystemProxySettingsPreference), true));
            useCustomProxy.setChecked(!useSystemProxy.isChecked());

            proxyHost.setText(preferenceGetter.getString(getString(R.string.useCustomProxySettingsHostPreference), ""));
            proxyPort.setText(preferenceGetter.getString(getString(R.string.useCustomProxySettingsPortPreference), ""));
            useProxyAuthentication.setChecked(preferenceGetter.getBoolean(getString(R.string.useProxyAuthenticationPreference), false));
            proxyUsername.setText(preferenceGetter.getString(getString(R.string.useProxyUsernamePreference), ""));
            proxyPassword.setText(preferenceGetter.getString(getString(R.string.useProxyPasswordPreference), ""));
            proxyDomain.setText(preferenceGetter.getString(getString(R.string.useProxyDomainPreference), ""));

            useSystemProxy.setOnPreferenceChangeListener((preference, o) -> {
                useSystemProxy.setChecked(true);
                useCustomProxy.setChecked(false);
                return false;
            });

            useCustomProxy.setOnPreferenceChangeListener((preference, o) -> {
                useSystemProxy.setChecked(false);
                useCustomProxy.setChecked(true);
                return false;
            });

            proxyHost.setOnPreferenceChangeListener((preference, newValue) -> {
                String proxyHost = (String) newValue;
                if (TextUtils.isEmpty(proxyHost)) {
                    Toast toast = Toast.makeText(getActivity(), R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                    toast.show();
                    return false;
                }
                return true;
            });

            proxyPort.setOnPreferenceChangeListener((preference, newValue) -> {
                int proxyPort;
                try {
                    proxyPort = Integer.parseInt((String) newValue);
                } catch (NumberFormatException e) {
                    proxyPort = 0;
                }
                if (proxyPort >= 1 && proxyPort <= 65535) {
                    return true;
                }
                Toast toast = Toast.makeText(getActivity(), R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                toast.show();
                return false;
            });

            Context context = getContext();
            PreferenceManager prefMgr = getPreferenceManager();
            prefMgr.setSharedPreferencesName(getString(R.string.moreOptionsPreferencesName));
            SharedPreferences.Editor editor = prefMgr.getSharedPreferences().edit();

            // Copy 'add custom headers' preference from app preferences into shared preferences
            // to be used by the nested custom proxy headers screen
            String addCustomHeadersPrefStr = getString(R.string.addCustomHeadersPreference);
            boolean addCustomHeaders = preferenceGetter
                    .getBoolean(addCustomHeadersPrefStr, false);
            editor.putBoolean(addCustomHeadersPrefStr, addCustomHeaders);

            // Also copy custom headers from app preferences into shared preferences
            // to be used by the nested custom proxy headers screen
            for (int position = 1; position <= 6; position++) {
                int nameID = context.getResources().getIdentifier("customProxyHeaderName" + position, "string", context.getPackageName());
                int valueID = context.getResources().getIdentifier("customProxyHeaderValue" + position, "string", context.getPackageName());

                String namePrefStr = context.getResources().getString(nameID);
                String valuePrefStr = context.getResources().getString(valueID);

                String name = preferenceGetter.getString(namePrefStr, "");
                String value = preferenceGetter.getString(valuePrefStr, "");

                editor.putString(namePrefStr, name).apply();
                editor.putString(valuePrefStr, value).apply();
            }

            updateProxyPreferencesUI();
        }

        @Override
        public void onResume() {
            super.onResume();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            // Unregister the listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            updateProxyPreferencesUI();
        }

        private void disableCustomProxySettings() {
            proxyHost.setEnabled(false);
            proxyPort.setEnabled(false);
            useProxyAuthentication.setEnabled(false);
            disableProxyAuthenticationSettings();
        }

        private void enableCustomProxySettings() {
            proxyHost.setEnabled(true);
            proxyPort.setEnabled(true);
            useProxyAuthentication.setEnabled(true);
            enableProxyAuthenticationSettings();
        }

        private void disableProxyAuthenticationSettings() {
            proxyUsername.setEnabled(false);
            proxyPassword.setEnabled(false);
            proxyDomain.setEnabled(false);
        }

        private void enableProxyAuthenticationSettings() {
            proxyUsername.setEnabled(true);
            proxyPassword.setEnabled(true);
            proxyDomain.setEnabled(true);
        }

        private void disableProxySettings() {
            useSystemProxy.setEnabled(false);
            useCustomProxy.setEnabled(false);
            customProxyHeadersPreference.setEnabled(false);
            disableCustomProxySettings();
        }

        private void enableProxySettings() {
            useSystemProxy.setEnabled(true);
            useCustomProxy.setEnabled(true);
            customProxyHeadersPreference.setEnabled(true);
            enableCustomProxySettings();
        }

        private void updateProxyPreferencesUI() {
            if (!useProxy.isChecked()) {
                disableProxySettings();
            } else {
                enableProxySettings();
                if (useSystemProxy.isChecked()) {
                    disableCustomProxySettings();
                } else {
                    enableCustomProxySettings();
                    if (useProxyAuthentication.isChecked()) {
                        enableProxyAuthenticationSettings();
                    } else {
                        disableProxyAuthenticationSettings();
                    }
                }
            }
            // Update summaries
            for (EditTextPreference editTextPref : editTextPreferences) {
                if (editTextPref != null) {
                    String summary = editTextPref.getText();
                    if (summary != null && !summary.trim().equals("")) {
                        boolean isPassword = editTextPref.getKey().equals(getString(R.string.useProxyPasswordPreference));
                        if (isPassword) {
                            editTextPref.setSummary(editTextPref.getText().replaceAll(".", "*"));
                        } else {
                            editTextPref.setSummary(editTextPref.getText());
                        }
                    } else {
                        editTextPref.setSummary((CharSequence) defaultSummaryBundle.get(editTextPref.getKey()));
                    }
                }
            }
        }
    }

    // CustomHeadersPreferenceFragment is only referenced from the proxy_options_preferences resource,
    // so we are using @Keep annotation to make sure the class is not removed by the R8 in minified builds.
    @Keep
    public static class CustomHeadersPreferenceFragment extends PsiphonPreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        CheckBoxPreference addCustomHeadersPreference;
        private ArrayList<EditTextPreference> editTextPreferences = new ArrayList<>();
        private Bundle defaultSummaryBundle = new Bundle();

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            setPreferencesFromResource(R.xml.custom_proxy_headers_preferences_screen, rootKey);
            final PreferenceScreen preferences = getPreferenceScreen();
            final PreferenceGetter preferenceGetter = getSharedPreferenceGetter();

            addCustomHeadersPreference = preferences
                    .findPreference(getString(R.string.addCustomHeadersPreference));
            addCustomHeadersPreference.setChecked(preferenceGetter
                    .getBoolean(getString(R.string.addCustomHeadersPreference), false));


            final Context context = getContext();
            for (int position = 1; position <= 6; position++) {
                int nameID = context.getResources().getIdentifier("customProxyHeaderName" + position, "string", context.getPackageName());
                int valueID = context.getResources().getIdentifier("customProxyHeaderValue" + position, "string", context.getPackageName());

                String namePrefStr = context.getResources().getString(nameID);
                String valuePrefStr = context.getResources().getString(valueID);

                String name = preferenceGetter.getString(namePrefStr, "");
                String value = preferenceGetter.getString(valuePrefStr, "");

                EditTextPreference namePreference = findPreference(namePrefStr);
                editTextPreferences.add(namePreference);
                EditTextPreference valuePreference = findPreference(valuePrefStr);
                editTextPreferences.add(valuePreference);

                namePreference.setText(name);
                valuePreference.setText(value);
                namePreference.setOnPreferenceChangeListener((preference, newValue) -> {
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
                            if (c > 127) {
                                isValid = false;
                                break;
                            }
                            //  CTL check
                            if (c <= 31 || c == 127) {
                                isValid = false;
                                break;
                            }

                            // separators check
                            for (char separator : separators) {
                                if (c == separator) {
                                    isValid = false;
                                    break outerloop;
                                }
                            }
                        }
                        if (!isValid) {
                            Toast toast = Toast.makeText(context,
                                    R.string.custom_proxy_header_invalid_name, Toast.LENGTH_SHORT);
                            toast.show();
                            return false;
                        }
                    } else {
                        // Just a warning, do not prevent user from entering empty header name
                        Toast toast = Toast.makeText(context,
                                R.string.custom_proxy_header_ignored_values, Toast.LENGTH_SHORT);
                        toast.show();

                    }
                    return true;
                });
                valuePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    String headerName = namePreference.getText();
                    String headerValue = (String) newValue;
                    if (TextUtils.isEmpty(headerName) && !TextUtils.isEmpty(headerValue)) {
                        // Just a warning, do not prevent user from entering empty header name
                        Toast toast = Toast.makeText(context,
                                R.string.custom_proxy_header_ignored_values, Toast.LENGTH_SHORT);
                        toast.show();
                    }
                    return true;
                });
            }

            if (addCustomHeadersPreference.isChecked()) {
                enableCustomHeaderSettings();
            } else {
                disableCustomHeaderSettings();
            }

            // Collect default summaries of EditTextPreferences
            for (Preference pref : editTextPreferences) {
                if (pref != null) {
                    defaultSummaryBundle.putCharSequence(pref.getKey(), pref.getSummary());
                }
            }
            updateCustomProxyHeadersPreferencesUI();
        }

        @Override
        public void onResume() {
            super.onResume();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            // Unregister the listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            updateCustomProxyHeadersPreferencesUI();
        }

        private void updateCustomProxyHeadersPreferencesUI() {
            if (addCustomHeadersPreference.isChecked()) {
                enableCustomHeaderSettings();
            } else {
                disableCustomHeaderSettings();
            }
            // Update summaries
            for (EditTextPreference editTextPref : editTextPreferences) {
                if (editTextPref != null) {
                    String summary = editTextPref.getText();
                    if (summary != null && !summary.trim().equals("")) {
                        editTextPref.setSummary(editTextPref.getText());
                    } else {
                        editTextPref.setSummary((CharSequence) defaultSummaryBundle.get(editTextPref.getKey()));
                    }
                }
            }
        }

        private void enableCustomHeaderSettings() {
            for (EditTextPreference preference : editTextPreferences) {
                preference.setEnabled(true);
            }
        }

        private void disableCustomHeaderSettings() {
            for (EditTextPreference preference : editTextPreferences) {
                preference.setEnabled(false);
            }
        }
    }
}
