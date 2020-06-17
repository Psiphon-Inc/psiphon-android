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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import androidx.annotation.NonNull;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.Toast;

import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.zirco.ui.activities.MainActivity;

import java.util.Set;

public class MoreOptionsPreferenceActivity extends AppCompatPreferenceActivity implements OnSharedPreferenceChangeListener, OnPreferenceClickListener {

    // This is taken from https://developer.android.com/reference/android/provider/Settings#ACTION_VPN_SETTINGS
    // As we target to low of an SDK we cannot reference this constant directly
    private static final String ACTION_VPN_SETTINGS = "android.settings.VPN_SETTINGS";

    public static final String INTENT_EXTRA_LANGUAGE_CHANGED = "com.psiphon3.psiphonlibrary.MoreOptionsPreferenceActivity.LANGUAGE_CHANGED";

    private interface PreferenceGetter {
        boolean getBoolean(@NonNull final String key, final boolean defaultValue);
        String getString(@NonNull final String key, final String defaultValue);
    }

    private class AppPreferencesWrapper implements PreferenceGetter {
        AppPreferences prefs;

        public AppPreferencesWrapper(AppPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public boolean getBoolean(@NonNull String key, boolean defaultValue) {
            return prefs.getBoolean(key, defaultValue);
        }
        @Override
        public String getString(@NonNull String key, String defaultValue) {
            return prefs.getString(key, defaultValue);
        }
    }

    private class SharedPreferencesWrapper implements PreferenceGetter {
        SharedPreferences prefs;

        public SharedPreferencesWrapper(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public boolean getBoolean(@NonNull String key, boolean defaultValue) {
            return prefs.getBoolean(key, defaultValue);
        }
        @Override
        public String getString(@NonNull String key, String defaultValue) {
            return prefs.getString(key, defaultValue);
        }
    }
    Bundle mDefaultSummaryBundle;

    CheckBoxPreference mNotificationSound;
    CheckBoxPreference mNotificationVibration;
    RadioButtonPreference mTunnelAllApps;
    RadioButtonPreference mTunnelSelectedApps;
    RadioButtonPreference mTunnelNotSelectedApps;
    Preference mSelectApps;
    CheckBoxPreference mUseProxy;
    RadioButtonPreference mUseSystemProxy;
    RadioButtonPreference mUseCustomProxy;
    CheckBoxPreference mUseProxyAuthentication;
    EditTextPreference mProxyHost;
    EditTextPreference mProxyPort;
    EditTextPreference mProxyUsername;
    EditTextPreference mProxyPassword;
    EditTextPreference mProxyDomain;
    ListPreference mLanguageSelector;
    Preference mCustomProxyHeadersPref;
    CheckBoxPreference mAddCustomHeadersPreference;
    EditTextPreference mHeaderName1;
    EditTextPreference mHeaderValue1;
    EditTextPreference mHeaderName2;
    EditTextPreference mHeaderValue2;
    EditTextPreference mHeaderName3;
    EditTextPreference mHeaderValue3;
    EditTextPreference mHeaderName4;
    EditTextPreference mHeaderValue4;
    EditTextPreference mHeaderName5;
    EditTextPreference mHeaderValue5;
    EditTextPreference mHeaderName6;
    EditTextPreference mHeaderValue6;

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

        mHeaderName4 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName4));
        mHeaderValue4 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue4));

        mHeaderName5 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName5));
        mHeaderValue5 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue5));

        mHeaderName6 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderName6));
        mHeaderValue6 = (EditTextPreference) preferences
                .findPreference(getString(R.string.customProxyHeaderValue6));

        PreferenceGetter preferenceGetter;

        // Initialize with current shared preferences if restoring from configuration change,
        // otherwise initialize with tray preferences values.
        if (savedInstanceState != null && savedInstanceState.getBoolean("onSaveInstanceState", false)) {
            preferenceGetter = new SharedPreferencesWrapper(PreferenceManager.getDefaultSharedPreferences(this));
        } else {
            preferenceGetter = new AppPreferencesWrapper(new AppPreferences(this));
        }

        if (Utils.supportsAlwaysOnVPN()) {
            setupNavigateToVPNSettings(preferences);
        }

        if (supportsRoutingConfiguration()) {
            setupTunnelConfiguration(preferences, preferenceGetter);
        }

        setupLanguageSelector(preferences);

        if (mNotificationSound != null) {
            mNotificationSound.setChecked(preferenceGetter.getBoolean(getString(R.string.preferenceNotificationsWithSound), false));
        }

        if (mNotificationVibration != null) {
            mNotificationVibration.setChecked(preferenceGetter.getBoolean(getString(R.string.preferenceNotificationsWithVibrate), false));
        }


        mUseProxy.setChecked(preferenceGetter.getBoolean(getString(R.string.useProxySettingsPreference), false));
        // set use system proxy preference by default
        mUseSystemProxy.setChecked(preferenceGetter.getBoolean(getString(R.string.useSystemProxySettingsPreference), true));
        mUseCustomProxy.setChecked(preferenceGetter.getBoolean(getString(R.string.useCustomProxySettingsPreference), false));
        mProxyHost.setText(preferenceGetter.getString(getString(R.string.useCustomProxySettingsHostPreference), ""));
        mProxyPort.setText(preferenceGetter.getString(getString(R.string.useCustomProxySettingsPortPreference), ""));
        mUseProxyAuthentication.setChecked(preferenceGetter.getBoolean(getString(R.string.useProxyAuthenticationPreference), false));
        mProxyUsername.setText(preferenceGetter.getString(getString(R.string.useProxyUsernamePreference), ""));
        mProxyPassword.setText(preferenceGetter.getString(getString(R.string.useProxyPasswordPreference), ""));
        mProxyDomain.setText(preferenceGetter.getString(getString(R.string.useProxyDomainPreference), ""));
        mAddCustomHeadersPreference.setChecked(preferenceGetter.getBoolean(getString(R.string.addCustomHeadersPreference), false));
        mHeaderName1.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName1), ""));
        mHeaderValue1.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue1), ""));
        mHeaderName2.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName2), ""));
        mHeaderValue2.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue2), ""));
        mHeaderName3.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName3), ""));
        mHeaderValue3.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue3), ""));
        mHeaderName4.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName4), ""));
        mHeaderValue4.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue4), ""));
        mHeaderName5.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName5), ""));
        mHeaderValue5.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue5), ""));
        mHeaderName6.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderName6), ""));
        mHeaderValue6.setText(preferenceGetter.getString(getString(R.string.customProxyHeaderValue6), ""));


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
        mHeaderName4.setOnPreferenceChangeListener(headerNameChangeListener);
        mHeaderName5.setOnPreferenceChangeListener(headerNameChangeListener);
        mHeaderName6.setOnPreferenceChangeListener(headerNameChangeListener);

        mHeaderValue1.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName1));
        mHeaderValue2.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName2));
        mHeaderValue3.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName3));
        mHeaderValue4.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName4));
        mHeaderValue5.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName5));
        mHeaderValue6.setOnPreferenceChangeListener(new HeaderValueChangeListener(mHeaderName6));

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

        // If language preference has changed we need to set new locale based on the current
        // preference value and restart the app.
        if (key.equals(getString(R.string.preferenceLanguageSelection))) {
            String languageCode = mLanguageSelector.getValue();
            try {
                int pos = mLanguageSelector.findIndexOfValue(languageCode);
                mLanguageSelector.setSummary(mLanguageSelector.getEntries()[pos]);
            } catch (Exception ignored) {
            }
            setLanguageAndRestartApp(languageCode);
        }
    }

    private void setLanguageAndRestartApp(String languageCode) {
        // The LocaleManager will correctly set the resource + store the language preference for the future
        LocaleManager localeManager = LocaleManager.getInstance(MoreOptionsPreferenceActivity.this);
        if (languageCode.equals("")) {
            localeManager.resetToSystemLocale(MoreOptionsPreferenceActivity.this);
        } else {
            localeManager.setNewLocale(MoreOptionsPreferenceActivity.this, languageCode);
        }
        // Kill the browser instance if it exists.
        // This is required as it's a singleTask activity and isn't recreated when it loses focus.
        if (MainActivity.INSTANCE != null) {
            MainActivity.INSTANCE.finish();
        }
        // Finish back to the StatusActivity and inform the language has changed
        Intent data = new Intent();
        data.putExtra(INTENT_EXTRA_LANGUAGE_CHANGED, true);
        setResult(RESULT_OK, data);
        finish();
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

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putBoolean("onSaveInstanceState", true);
        super.onSaveInstanceState(savedInstanceState);
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

    private void setupNavigateToVPNSettings(PreferenceScreen preferences) {
        Preference preference = preferences.findPreference(getString(R.string.preferenceNavigateToVPNSetting));
        preference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                startActivity(new Intent(ACTION_VPN_SETTINGS));
                return true;
            }
        });
    }

    private void setupLanguageSelector(PreferenceScreen preferences) {
        // Get the preference view and create the locale manager with the app's context.
        // Cannot use this activity as the context as we also need StatusActivity to pick up on it.
        mLanguageSelector = (ListPreference) preferences.findPreference(getString(R.string.preferenceLanguageSelection));

        // Collect the string array of <language name>,<language code>
        String[] locales = getResources().getStringArray(R.array.languages);
        CharSequence[] languageNames = new CharSequence[locales.length + 1];
        CharSequence[] languageCodes = new CharSequence[locales.length + 1];

        // Setup the "Default" locale
        languageNames[0] = getString(R.string.preference_language_default_language);
        languageCodes[0] = "";

        LocaleManager localeManager = LocaleManager.getInstance(this);
        String currentLocaleLanguageCode = localeManager.getLanguage();
        int currentLocaleLanguageIndex = -1;

        if (localeManager.isSystemLocale(currentLocaleLanguageCode)) {
            currentLocaleLanguageIndex = 0;
        }

        for (int i = 1; i <= locales.length; ++i) {
            // Split the string on the comma
            String[] localeArr = locales[i - 1].split(",");
            languageNames[i] = localeArr[0];
            languageCodes[i] = localeArr[1];

            if (localeArr[1] != null && localeArr[1].equals(currentLocaleLanguageCode)) {
                currentLocaleLanguageIndex = i;
            }
        }

        // Entries are displayed to the user, codes are the value used in the backend
        mLanguageSelector.setEntries(languageNames);
        mLanguageSelector.setEntryValues(languageCodes);

        // If current locale is on the list set it selected
        if (currentLocaleLanguageIndex >= 0) {
            try {
                mLanguageSelector.setValueIndex(currentLocaleLanguageIndex);
                mLanguageSelector.setSummary(languageNames[currentLocaleLanguageIndex]);
            } catch(Exception ignored) {
            }
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
        mHeaderName4.setEnabled(false);
        mHeaderValue4.setEnabled(false);
        mHeaderName5.setEnabled(false);
        mHeaderValue5.setEnabled(false);
        mHeaderName6.setEnabled(false);
        mHeaderValue6.setEnabled(false);
    }

    private void enableCustomHeaderSettings() {
        mHeaderName1.setEnabled(true);
        mHeaderValue1.setEnabled(true);
        mHeaderName2.setEnabled(true);
        mHeaderValue2.setEnabled(true);
        mHeaderName3.setEnabled(true);
        mHeaderValue3.setEnabled(true);
        mHeaderName4.setEnabled(true);
        mHeaderValue4.setEnabled(true);
        mHeaderName5.setEnabled(true);
        mHeaderValue5.setEnabled(true);
        mHeaderName6.setEnabled(true);
        mHeaderValue6.setEnabled(true);
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

        updateAdsConsentPreference();
    }

    private void setupTunnelConfiguration(PreferenceScreen preferences, PreferenceGetter preferenceGetter) {
        mTunnelAllApps = (RadioButtonPreference) preferences.findPreference(getString(R.string.preferenceIncludeAllAppsInVpn));
        mTunnelSelectedApps = (RadioButtonPreference) preferences.findPreference(getString(R.string.preferenceIncludeAppsInVpn));
        mTunnelNotSelectedApps = (RadioButtonPreference) preferences.findPreference(getString(R.string.preferenceExcludeAppsFromVpn));
        mSelectApps = preferences.findPreference(getString(R.string.preferenceSelectApps));

        // Migrate old VPN exclusions preferences if any
        VpnAppsUtils.migrate(getApplicationContext());

        // Also create a snapshot of current VPN exclusion sets. We need this because tunnel restart
        // logic when we return back to main activity from this screen will compare the preferences
        // set in this screen with currently stored preferences in order to make decision if the
        // preferences change needs to trigger a tunnel restart.
        String currentIncludeAppsString = preferenceGetter.getString(getString(R.string.preferenceIncludeAppsInVpnString), "");
        preferences.getEditor().putString(getString(R.string.preferenceIncludeAppsInVpnString), currentIncludeAppsString).apply();
        String currentExcludeAppsString = preferenceGetter.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
        preferences.getEditor().putString(getString(R.string.preferenceExcludeAppsFromVpnString), currentExcludeAppsString).apply();

        if (preferenceGetter.getBoolean(getString(R.string.preferenceIncludeAllAppsInVpn), false)) {
            tunnelAllApps();
        } else if (preferenceGetter.getBoolean(getString(R.string.preferenceIncludeAppsInVpn), false)) {
            tunnelSelectedApps();
        } else {
            tunnelNotSelectedApps();
        }

        mTunnelAllApps.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                tunnelAllApps();
                return true;
            }
        });

        mTunnelSelectedApps.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                tunnelSelectedApps();
                return true;
            }
        });

        mTunnelNotSelectedApps.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                tunnelNotSelectedApps();
                return true;
            }
        });

        mSelectApps.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                final InstalledAppsMultiSelectListPreference installedAppsMultiSelectListPreference =
                        new InstalledAppsMultiSelectListPreference(MoreOptionsPreferenceActivity.this,
                                getLayoutInflater(), mTunnelSelectedApps.isChecked());

                final androidx.appcompat.app.AlertDialog alertDialog = installedAppsMultiSelectListPreference
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface dialog) {
                                if (mTunnelAllApps.isChecked()) {
                                    tunnelAllApps();
                                } else if (mTunnelSelectedApps.isChecked()) {
                                    tunnelSelectedApps();
                                } else {
                                    tunnelNotSelectedApps();
                                }
                            }
                        })
                        .create();

                alertDialog.setOnShowListener(dialog -> {
                    Button button = ((androidx.appcompat.app.AlertDialog) dialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
                    button.setOnClickListener(v -> {
                        if (!installedAppsMultiSelectListPreference.isLoaded()) {
                            alertDialog.dismiss();
                            return;
                        }
                        Set<String> selectedApps = installedAppsMultiSelectListPreference.getSelectedApps();
                        int installedAppsCount = installedAppsMultiSelectListPreference.getInstalledAppsCount();
                        if (installedAppsMultiSelectListPreference.isWhitelist()) {
                            if (selectedApps.size() > 0) {
                                VpnAppsUtils.setPendingAppsToIncludeInVpn(getApplicationContext(), selectedApps);
                            } else {
                                new androidx.appcompat.app.AlertDialog.Builder(MoreOptionsPreferenceActivity.this)
                                        .setIcon(android.R.drawable.ic_dialog_alert)
                                        .setTitle(R.string.bad_vpn_exclusion_setting_alert_title)
                                        .setMessage(R.string.bad_vpn_exclusion_whitelist_alert_message)
                                        .setPositiveButton(R.string.label_ok, null)
                                        .setCancelable(true)
                                        .show();
                                return;
                            }
                        } else {
                            if (installedAppsCount > selectedApps.size()) {
                                VpnAppsUtils.setPendingAppsToExcludeFromVpn(getApplicationContext(), selectedApps);
                            } else {
                                new androidx.appcompat.app.AlertDialog.Builder(MoreOptionsPreferenceActivity.this)
                                        .setIcon(android.R.drawable.ic_dialog_alert)
                                        .setTitle(R.string.bad_vpn_exclusion_setting_alert_title)
                                        .setMessage(R.string.bad_vpn_exclusion_blacklist_alert_message)
                                        .setPositiveButton(R.string.label_ok, null)
                                        .setCancelable(true)
                                        .show();
                                return;
                            }
                        }
                        alertDialog.dismiss();
                    });
                });

                alertDialog.show();

                return true;
            }
        });
    }

    private void tunnelAllApps() {
        mTunnelAllApps.setChecked(true);
        mTunnelSelectedApps.setChecked(false);
        mTunnelNotSelectedApps.setChecked(false);
        mSelectApps.setEnabled(false);
        mSelectApps.setSummary(R.string.preference_routing_all_apps_tunnel_summary);
    }

    private void tunnelSelectedApps() {
        mTunnelAllApps.setChecked(false);
        mTunnelSelectedApps.setChecked(true);
        mTunnelNotSelectedApps.setChecked(false);
        mSelectApps.setEnabled(true);
        int count = VpnAppsUtils.getPendingAppsIncludedInVpn(getApplicationContext()).size();
        String summary = getResources().getQuantityString(R.plurals.preference_routing_select_apps_to_include_summary, count, count);
        mSelectApps.setSummary(summary);
    }

    private void tunnelNotSelectedApps() {
        mTunnelAllApps.setChecked(false);
        mTunnelSelectedApps.setChecked(false);
        mTunnelNotSelectedApps.setChecked(true);
        mSelectApps.setEnabled(true);
        int count = VpnAppsUtils.getPendingAppsExcludedFromVpn(getApplicationContext()).size();
        String summary = getResources().getQuantityString(R.plurals.preference_routing_select_apps_to_exclude_summary, count, count);
        mSelectApps.setSummary(summary);
    }

    private boolean supportsRoutingConfiguration() {
        // technically supported after v14 but the earliest preference file with it is v21
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    private void updateAdsConsentPreference() {
        // Conditionally add / remove 'revoke ads consent' preference
        final ConsentInformation consentInformation = ConsentInformation.getInstance(this);
        PreferenceScreen screen = this.getPreferenceScreen();
        Preference adConsentPref = screen.findPreference(getString(R.string.adConsentPref));

        if(consentInformation.getConsentStatus() != ConsentStatus.UNKNOWN) {
            // Consent status is either PERSONALIZED or NON_PERSONALIZED - create and show
            // 'reset' preference if doesn't exists
            if (adConsentPref == null) {
                PreferenceCategory category = new PreferenceCategory(screen.getContext());
                category.setTitle(R.string.ads_consent_preference_category_title);
                category.setKey(getString(R.string.adConsentPref));
                screen.addPreference(category);

                DialogPreference revokeConsentPref = new RevokeConsentPreference(screen.getContext(),
                        new RevokeConsentPreference.OnDialogDismissListener() {
                            @Override
                            public void onDismiss() {
                                MoreOptionsPreferenceActivity.this.updateAdsConsentPreference();
                            }
                        });
                revokeConsentPref.setTitle(R.string.ads_consent_preference_title);
                revokeConsentPref.setSummary(R.string.ads_consent_preference_summary);
                category.addPreference(revokeConsentPref);
            }
        } else {
            // Consent status UNKNOWN - remove 'reset' preference if exists
            if(adConsentPref != null) {
                screen.removePreference(adConsentPref);
            }
        }
    }

    static private class RevokeConsentPreference extends DialogPreference {
        private OnDialogDismissListener dialogDismissListener;
        RevokeConsentPreference(Context context, OnDialogDismissListener listener) {
            // Using API level 1 constructor.
            super(context, null);
            this.dialogDismissListener = listener;
        }

        @Override
        protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
            builder.setTitle(R.string.ads_consent_preference_dialog_title)
                    .setMessage(getContext().getString(R.string.ads_consent_preference_dialog_preference_message))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int whichButton) {
                            final ConsentInformation consentInformation = ConsentInformation.getInstance(RevokeConsentPreference.this.getContext());
                            consentInformation.setConsentStatus(ConsentStatus.UNKNOWN);
                        }
                    })
                    .setNegativeButton(android.R.string.no, null);
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);
            if(dialogDismissListener != null) {
                dialogDismissListener.onDismiss();
            }
        }

        public interface OnDialogDismissListener {
            void onDismiss();
        }
    }
}
