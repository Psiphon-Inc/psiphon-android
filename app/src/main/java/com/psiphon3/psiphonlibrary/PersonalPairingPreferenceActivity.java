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

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.psiphon3.MainActivityViewModel;
import com.psiphon3.R;

public class PersonalPairingPreferenceActivity extends LocalizedActivities.AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new PersonalPairingPreferenceFragment())
                    .commit();
        }

        MainActivityViewModel viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);
    }

    public static class PersonalPairingPreferenceFragment extends PsiphonPreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private CheckBoxPreference enabledPref;
        private Preference importPref;
        private EditTextPreference compartmentIdPref;
        private EditTextPreference aliasPref;
        private Toast currentToast;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            super.onCreatePreferences(savedInstanceState, rootKey);
            addPreferencesFromResource(R.xml.personal_pairing_preferences);
            final PreferenceScreen preferences = getPreferenceScreen();

            // Initialize preferences
            enabledPref = preferences.findPreference(getString(R.string.personalPairingEnabledPreference));
            importPref = preferences.findPreference(getString(R.string.personalPairingImportPreference));
            compartmentIdPref = preferences.findPreference(getString(R.string.personalPairingCompartmentIdPreference));
            aliasPref = preferences.findPreference(getString(R.string.personalPairingAliasPreference));

            // Set initial values from current preferences
            final PreferenceGetter preferenceGetter = getPreferenceGetter();
            enabledPref.setChecked(preferenceGetter.getBoolean(getString(R.string.personalPairingEnabledPreference), false));
            compartmentIdPref.setText(preferenceGetter.getString(getString(R.string.personalPairingCompartmentIdPreference), ""));
            aliasPref.setText(preferenceGetter.getString(getString(R.string.personalPairingAliasPreference), ""));

            // Set up import button click listener
            importPref.setOnPreferenceClickListener(preference -> {
                showImportDialog();
                return true;
            });

            // Set up name preference change listener
            aliasPref.setOnPreferenceChangeListener((preference, newValue) -> {
                if (TextUtils.isEmpty(compartmentIdPref.getText())) {
                    showToast(R.string.personal_pairing_need_compartment_id, Toast.LENGTH_SHORT);
                    return false;
                }
                return true;
            });

            // Set up enabled preference change listener
            enabledPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean newEnabled = (Boolean) newValue;
                if (newEnabled && TextUtils.isEmpty(compartmentIdPref.getText())) {
                    showToast(R.string.personal_pairing_need_compartment_id, Toast.LENGTH_SHORT);
                    return false;
                }
                return true;
            });

            updatePersonalPairingPreferencesUI();
        }

        private void showImportDialog() {
            View dialogView = LayoutInflater.from(getContext())
                    .inflate(R.layout.dialog_import_pairing, null);
            EditText urlInput = dialogView.findViewById(R.id.url_input);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.personal_pairing_import_dialog_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.import_button, (dialog, which) -> {
                        String url = urlInput.getText().toString();
                        try {
                            PersonalPairingHelper.PersonalPairingData data = PersonalPairingHelper.extractPersonalPairingData(url);
                            updatePairingData(data);
                            // Also enable the feature automatically
                            enabledPref.setChecked(true);
                        } catch (IllegalArgumentException e) {
                            showToast(R.string.personal_pairing_invalid_url, Toast.LENGTH_LONG);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void updatePairingData(PersonalPairingHelper.PersonalPairingData data) {
            compartmentIdPref.setText(data.compartmentId);
            aliasPref.setText(data.alias);
            updatePersonalPairingPreferencesUI();
        }

        private void updatePersonalPairingPreferencesUI() {
            boolean hasCompartmentId = !TextUtils.isEmpty(compartmentIdPref.getText());
            boolean isEnabled = enabledPref.isChecked();

            // Update preference states
            aliasPref.setEnabled(hasCompartmentId); // Keep editable if compartment ID is set even if the feature is disabled
            compartmentIdPref.setEnabled(isEnabled && hasCompartmentId);

            // If no compartment ID, ensure feature is disabled
            if (!hasCompartmentId && isEnabled) {
                enabledPref.setChecked(false);
            }

            // Update compartment ID and alias summaries if the compartment ID is set, otherwise show the default summary
            if (hasCompartmentId) {
                String compartmentId = compartmentIdPref.getText();
                compartmentIdPref.setSummary(!TextUtils.isEmpty(compartmentId) ? compartmentId : null);

                String name = aliasPref.getText();
                aliasPref.setSummary(!TextUtils.isEmpty(name) ? name.replace("\n", " ") : null);
            }
        }

        private void showToast(@StringRes int messageId, int toastLength) {
            if (currentToast != null) {
                currentToast.cancel();
            }
            currentToast = Toast.makeText(getContext(), messageId, toastLength);
            currentToast.show();
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceScreen().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceScreen().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            updatePersonalPairingPreferencesUI();
        }
    }
}
