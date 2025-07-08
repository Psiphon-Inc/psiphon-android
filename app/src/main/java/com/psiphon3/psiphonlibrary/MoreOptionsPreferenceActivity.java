/*
 * Copyright (c) 2021, Psiphon Inc.
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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.ads.ConsentManager;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;

public class MoreOptionsPreferenceActivity extends LocalizedActivities.AppCompatActivity {
    public static final String INTENT_EXTRA_LANGUAGE_CHANGED = "com.psiphon3.psiphonlibrary.MoreOptionsPreferenceActivity.LANGUAGE_CHANGED";

    public Flowable<TunnelState> tunnelStateFlowable() {
        return getTunnelServiceInteractor().tunnelStateFlowable();
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new MoreOptionsPreferenceFragment())
                    .commit();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public static class MoreOptionsPreferenceFragment extends PsiphonPreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        ListPreference mLanguageSelector;

        private final CompositeDisposable compositeDisposable = new CompositeDisposable();

        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            addPreferencesFromResource(R.xml.more_options_preferences);
            final PreferenceScreen preferences = getPreferenceScreen();
            final PreferenceGetter preferenceGetter = getPreferenceGetter();

            // Notifications
            if (Utils.supportsNotificationSound()) {
                CheckBoxPreference notificationSoundCheckBox =
                        (CheckBoxPreference) preferences.findPreference(getString(R.string.preferenceNotificationsWithSound));
                CheckBoxPreference notificationVibrationCheckBox =
                        (CheckBoxPreference) preferences.findPreference(getString(R.string.preferenceNotificationsWithVibrate));
                notificationSoundCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.preferenceNotificationsWithSound), false));
                notificationVibrationCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.preferenceNotificationsWithVibrate), false));
            } else {
                // Remove "Notifications" category
                preferences.removePreference(findPreference(getString(R.string.preferencesNotifications)));
            }

            // Advanced
            boolean hasUpgradeChecker = false;
            try {
                Class.forName("com.psiphon3.psiphonlibrary.UpgradeChecker");
                hasUpgradeChecker = true;
            } catch (ClassNotFoundException e) {
                //my class isn't there!
            }
            CheckBoxPreference upgradeWiFiOnlyCheckBox =
                    (CheckBoxPreference) preferences.findPreference(getString(R.string.downloadWifiOnlyPreference));
            if (!EmbeddedValues.IS_PLAY_STORE_BUILD && hasUpgradeChecker) {
                upgradeWiFiOnlyCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.downloadWifiOnlyPreference), false));
            } else {
                preferences.removePreferenceRecursively(getString(R.string.downloadWifiOnlyPreference));
            }
            CheckBoxPreference disableTimeoutsCheckBox =
                    (CheckBoxPreference) preferences.findPreference(getString(R.string.disableTimeoutsPreference));
            disableTimeoutsCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.disableTimeoutsPreference), false));

            // TODO: Check if there are any VPN exclusions enabled and inform the user via pref summary?
            //  Maybe even disable the setting if that's the case? Whatever it is do the same when
            //  the 'unsafe traffic' alerts preference is on and user enables VPN exclusions in
            //  VpnOptionsPreferenceActivity
            CheckBoxPreference unsafeTrafficAlertsCheckBox =
                    (CheckBoxPreference) preferences.findPreference(getString(R.string.unsafeTrafficAlertsPreference));
            unsafeTrafficAlertsCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.unsafeTrafficAlertsPreference), false));

            if (Utils.supportsNfc(getContext())) {
                CheckBoxPreference nfcCheckBox =
                        (CheckBoxPreference) preferences.findPreference(getString(R.string.nfcBumpPreference));
                nfcCheckBox.setChecked(preferenceGetter.getBoolean(getString(R.string.nfcBumpPreference), true));
            } else {
                preferences.removePreferenceRecursively(getString(R.string.nfcBumpPreference));
            }

            setupLanguageSelector(preferences);
            setupAbouts(preferences);
            setupConsentManagement(preferences);
        }

        @Override
        public void onResume() {
            super.onResume();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

            // Check if consent category should be shown/hidden
            updateConsentCategoryVisibility();
        }

        private void updateConsentCategoryVisibility() {
            ConsentManager consentManager = ConsentManager.getInstance(requireContext());
            Preference category = findPreference(getString(R.string.adsPrivacyPreferenceCategory));
            if (category != null) {
                category.setVisible(consentManager.isPrivacyOptionsRequired());
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            // Unregister the listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            compositeDisposable.dispose();
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, final String key) {
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
            LocaleManager localeManager = LocaleManager.getInstance(getActivity());
            if (languageCode.equals("")) {
                localeManager.resetToSystemLocale(getActivity());
            } else {
                localeManager.setNewLocale(getActivity(), languageCode);
            }

            // Signal tunnel service
            ((LocalizedActivities.AppCompatActivity) requireActivity())
                    .getTunnelServiceInteractor()
                    .sendLocaleChangedMessage();
            // Finish back to the MainActivity and inform the language has changed
            Intent data = new Intent();
            data.putExtra(INTENT_EXTRA_LANGUAGE_CHANGED, true);
            requireActivity().setResult(RESULT_OK, data);
            requireActivity().finish();
        }

        private void setupLanguageSelector(PreferenceScreen preferences) {
            // Get the preference view and create the locale manager with the app's context.
            // Cannot use this activity as the context as we also need MainActivity to pick up on it.
            mLanguageSelector = (ListPreference) preferences.findPreference(getString(R.string.preferenceLanguageSelection));

            // Collect the string array of <language name>,<language code>
            String[] locales = getResources().getStringArray(R.array.languages);
            CharSequence[] languageNames = new CharSequence[locales.length + 1];
            CharSequence[] languageCodes = new CharSequence[locales.length + 1];

            // Setup the "Default" locale
            languageNames[0] = getString(R.string.preference_language_default_language);
            languageCodes[0] = "";

            LocaleManager localeManager = LocaleManager.getInstance(getActivity());
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
                } catch (Exception ignored) {
                }
            }
        }

        private void setupAbouts(PreferenceScreen preferences) {
            setupAbout(preferences.findPreference(getString(R.string.preferenceAbout)), EmbeddedValues.INFO_LINK_URL);
            setupAbout(preferences.findPreference(getString(R.string.preferenceAboutMalAware)), getString(R.string.AboutMalAwareLink));
        }

        private void setupAbout(Preference pref, String aboutURL) {
            final Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(aboutURL));
            pref.setOnPreferenceClickListener(preference -> {
                try {
                    requireContext().startActivity(browserIntent);
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            });
        }

        private void setupConsentManagement(PreferenceScreen preferences) {
            ConsentManager consentManager = ConsentManager.getInstance(requireContext());

            // Check if privacy options are required
            if (!consentManager.isPrivacyOptionsRequired()) {
                // Remove the entire "Advertising Privacy" category if not needed
                preferences.removePreference(findPreference(getString(R.string.adsPrivacyPreferenceCategory)));
                return;
            }

            // If we get here, privacy options are required - set up the preference
            Preference consentPref = preferences.findPreference(getString(R.string.adsConsentPreference));
            if (consentPref != null) {
                consentPref.setOnPreferenceClickListener(preference -> {
                    Disposable disposable = consentManager.showPrivacyOptionsForm(requireActivity())
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(
                                    () -> {
                                        // Form dismissed - no action needed
                                    },
                                    error -> {
                                        // Handle error if needed
                                    }
                            );
                    compositeDisposable.add(disposable);
                    return true;
                });
            }
        }
    }
}
