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
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
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
        private Preference resetPref;
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
            resetPref = preferences.findPreference(getString(R.string.personalPairingResetPreference));

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

            // Set up reset click listener
            resetPref.setOnPreferenceClickListener(preference -> {
                showResetDialog();
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
                            showToast(getPairingImportErrorString(
                                    PersonalPairingHelper.validationErrorFromException(e)), Toast.LENGTH_LONG);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private void showResetDialog() {
            String compartmentId = compartmentIdPref.getText();

            // Guard against empty ID
            if (compartmentId == null || compartmentId.isEmpty()) {
                showToast(R.string.personal_pairing_reset_error, Toast.LENGTH_SHORT);
                return;
            }

            // Determine how many characters to request
            int charsToRequest = Math.min(compartmentId.length(), 4);
            String lastChars = compartmentId.substring(compartmentId.length() - charsToRequest);

            View dialogView = LayoutInflater.from(getContext())
                    .inflate(R.layout.dialog_reset_pairing, null);

            TextView messageText = dialogView.findViewById(R.id.delete_message);
            TextView symbolsText = dialogView.findViewById(R.id.delete_symbols);

            messageText.setText(getString(R.string.personal_pairing_delete_message, charsToRequest));
            symbolsText.setText(lastChars);

            EditText deleteInput = dialogView.findViewById(R.id.delete_input);
            deleteInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(charsToRequest) });

            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.personal_pairing_reset_dialog_title)
                    .setView(dialogView)
                    .setPositiveButton(R.string.reset_button, null) // Set listener later to prevent auto-dismiss
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();

            // Allow pressing enter to submit
            deleteInput.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if (positiveButton != null) {
                        positiveButton.performClick();
                    }
                    return true;
                }
                return false;
            });

            dialog.setOnShowListener(dialogInterface -> {
                Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setOnClickListener(view -> {
                    String input = deleteInput.getText().toString();
                    if (lastChars.equals(input)) {
                        resetPairingPreferences();
                        showToast(R.string.personal_pairing_reset_success, Toast.LENGTH_SHORT);
                        dialog.dismiss();
                    } else {
                        showToast(R.string.personal_pairing_reset_invalid, Toast.LENGTH_SHORT);
                    }
                });
            });

            dialog.show();
        }

        private void resetPairingPreferences() {
            compartmentIdPref.setText("");
            compartmentIdPref.setSummary(R.string.personal_pairing_compartment_id_summary);
            aliasPref.setText("");
            aliasPref.setSummary(R.string.personal_pairing_alias_summary);
            enabledPref.setChecked(false);
            PersonalPairingHelper.resetPersonalPairingPreferences(getContext());
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
            resetPref.setEnabled(hasCompartmentId);

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

        @StringRes
        private int getPairingImportErrorString(PersonalPairingHelper.ImportValidationError validationError) {
            if (validationError == PersonalPairingHelper.ImportValidationError.UNSUPPORTED_VERSION) {
                return R.string.personal_pairing_unsupported_version;
            }
            if (validationError == PersonalPairingHelper.ImportValidationError.INVALID_INPUT_FORMAT) {
                return R.string.personal_pairing_invalid_url;
            }
            return R.string.personal_pairing_invalid_data;
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
