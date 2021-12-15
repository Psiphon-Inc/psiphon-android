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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.google.ads.consent.ConsentInformation;
import com.google.ads.consent.ConsentStatus;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.MainActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.account.PsiCashAccountWebViewDialog;
import com.psiphon3.psicash.settings.PsiCashSettingsIntent;
import com.psiphon3.psicash.settings.PsiCashSettingsViewModel;
import com.psiphon3.psicash.settings.PsiCashSettingsViewState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.subscription.R;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;

public class MoreOptionsPreferenceActivity extends LocalizedActivities.AppCompatActivity {
    public static final String INTENT_EXTRA_LANGUAGE_CHANGED = "com.psiphon3.psiphonlibrary.MoreOptionsPreferenceActivity.LANGUAGE_CHANGED";

    private TunnelServiceInteractor tunnelServiceInteractor;
    private BroadcastReceiver broadcastReceiver;

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        tunnelServiceInteractor = new TunnelServiceInteractor(this, true);

        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(android.R.id.content, new MoreOptionsPreferenceFragment())
                    .commit();
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.TUNNEL_RESTART);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (BroadcastIntent.TUNNEL_RESTART.equals(action)) {
                        tunnelServiceInteractor.commandTunnelRestart(false);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        tunnelServiceInteractor.onDestroy(getApplicationContext());
    }

    @Override
    protected void onStart() {
        super.onStart();
        tunnelServiceInteractor.onStart(getApplicationContext());
    }

    @Override
    protected void onStop() {
        super.onStop();
        tunnelServiceInteractor.onStop(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        tunnelServiceInteractor.onResume();
    }

    public static class MoreOptionsPreferenceFragment extends PsiphonPreferenceFragmentCompat
            implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final String PSICASH_MANAGEMENT_URL = "PSICASH_MANAGEMENT_URL";
        ListPreference mLanguageSelector;

        // PsiCash
        private final PublishRelay<PsiCashSettingsIntent> intentsPublishRelay = PublishRelay.create();
        private final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private PsiCashSettingsViewModel psiCashSettingsViewModel;
        private Disposable psiCashUpdatesDisposable;
        private View progressOverlay;
        private Preference psiCashAccountManagePref;
        private Preference psiCashAccountLoginPref;
        private Preference psiCashAccountLogoutPref;
        private Preference psiCashAccountPrefCategory;

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

            setupLanguageSelector(preferences);
            setupAbouts(preferences);
            setupAdsConsentPreference(preferences);
            setupPsiCashAccount(preferences);
        }

        @Override
        public void onResume() {
            super.onResume();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

            // Get PsiCash updates when foregrounded and on tunnel state changes after
            Flowable<TunnelState> tunnelStateFlowable =
                    ((MoreOptionsPreferenceActivity) requireActivity()).tunnelStateFlowable();
            psiCashUpdatesDisposable = tunnelStateFlowable
                    .filter(tunnelState -> !tunnelState.isUnknown())
                    .distinctUntilChanged()
                    .doOnNext(__ ->
                            intentsPublishRelay.accept(PsiCashSettingsIntent.GetPsiCash.create(
                            tunnelStateFlowable)))
                    .subscribe();
            compositeDisposable.add(psiCashUpdatesDisposable);
        }

        @Override
        public void onPause() {
            super.onPause();
            // Unregister the listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            if (psiCashUpdatesDisposable != null) {
                psiCashUpdatesDisposable.dispose();
            }
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

            // Finish back to the MainActivity and inform the language has changed
            Intent data = new Intent();
            data.putExtra(INTENT_EXTRA_LANGUAGE_CHANGED, true);
            getActivity().setResult(RESULT_OK, data);
            getActivity().finish();
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

        private void setupAdsConsentPreference(PreferenceScreen preferences) {
            Preference category = preferences.findPreference(getString(R.string.adConsentPreferenceCategory));
            final ConsentInformation consentInformation = ConsentInformation.getInstance(getContext());
            if (consentInformation.getConsentStatus() != ConsentStatus.UNKNOWN) {
                category.setVisible(true);
                Preference pref = preferences.findPreference(getString(R.string.adConsentPreference));
                pref.setOnPreferenceClickListener(preference -> {
                    new AlertDialog.Builder(getContext())
                            .setTitle(R.string.ads_consent_preference_dialog_title)
                            .setMessage(getContext().getString(R.string.ads_consent_preference_dialog_preference_message))
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, (dialog, whichButton) -> {
                                final ConsentInformation consentInformation1 = ConsentInformation.getInstance(getContext());
                                consentInformation1.setConsentStatus(ConsentStatus.UNKNOWN);
                                category.setVisible(false);
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    return true;
                });
            }
        }

        @Override
        public void onActivityCreated(@Nullable Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);

            ViewGroup parentView = (ViewGroup) getView().getParent();
            if (parentView instanceof FrameLayout) {
                LayoutInflater inflater = LayoutInflater.from(requireContext());
                progressOverlay = inflater.inflate(R.layout.include_progress_overlay, null);
                parentView.addView(progressOverlay);
            }

            psiCashSettingsViewModel = new ViewModelProvider(requireActivity(),
                    new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                    .get(PsiCashSettingsViewModel.class);


            Flowable<TunnelState> tunnelStateFlowable =
                    ((MoreOptionsPreferenceActivity) requireActivity()).tunnelStateFlowable();

            compositeDisposable.add(Observable.combineLatest(
                    tunnelStateFlowable.toObservable(),
                    psiCashSettingsViewModel.states(),
                    ((BiFunction<TunnelState, PsiCashSettingsViewState, Pair<TunnelState, PsiCashSettingsViewState>>) Pair::new))
                    .distinctUntilChanged()
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::render));


            psiCashSettingsViewModel.processIntents(intentsPublishRelay);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            // Call through to main activity if tunnel connect is requested
            if (data != null && MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION.equals(data.getAction())) {
                try {
                    requireActivity().setResult(Activity.RESULT_OK, data);
                    requireActivity().finish();
                } catch (RuntimeException ignored) {
                }
            } else {
                super.onActivityResult(requestCode, resultCode, data);
            }
        }

        private void showPsiCashProgress(boolean enable) {
            if (progressOverlay != null) {
                progressOverlay.setVisibility(enable ? View.VISIBLE : View.GONE);
            }
        }

        private void setupPsiCashAccount(PreferenceScreen preferenceScreen) {
            psiCashAccountManagePref = findPreference(getString(R.string.psicashAccountManagePreferenceKey));
            psiCashAccountLoginPref = findPreference(getString(R.string.psicashAccountLoginPreferenceKey));
            psiCashAccountLogoutPref = findPreference(getString(R.string.psicashAccountLogoutPreferenceKey));
            psiCashAccountPrefCategory = findPreference(getString(R.string.psicashAccountPreferenceCategory));

            Flowable<TunnelState> tunnelStateFlowable =
                    ((MoreOptionsPreferenceActivity) requireActivity()).tunnelStateFlowable();

            psiCashAccountLoginPref.setOnPreferenceClickListener(preference -> {
                if (preference.isVisible() && preference.isEnabled()) {
                    try {
                        UiHelpers.openPsiCashAccountActivity(requireActivity());
                    } catch (RuntimeException ignored) {
                    }
                }
                return true;
            });

            psiCashAccountLogoutPref.setOnPreferenceClickListener(preference -> {
                if (preference.isVisible() && preference.isEnabled()) {
                    compositeDisposable.add(tunnelStateFlowable
                            .filter(s -> !s.isUnknown())
                            .firstOrError()
                            .doOnSuccess(s -> {
                                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(requireContext())
                                        .setIcon(R.drawable.psicash_coin)
                                        .setTitle(requireContext().getString(R.string.psicash_logout_alert_title))
                                        .setCancelable(true)
                                        .setPositiveButton(R.string.psicash_continue_log_out_lbl, (dialog, which) ->
                                                intentsPublishRelay.accept(
                                                        PsiCashSettingsIntent.LogoutAccount.create(tunnelStateFlowable)));
                                if (s.isStopped()) {
                                    alertDialogBuilder
                                            .setMessage(requireContext().getString(R.string.psicash_logout_alert_disconnected_message))
                                            .setNegativeButton(R.string.psicash_cancel_lbl, (dialog, which) -> {
                                            })
                                            .setNeutralButton(R.string.connect_to_psiphon_button_text, (dialog, which) -> {
                                                try {
                                                    Intent data = new Intent(MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION);
                                                    requireActivity().setResult(Activity.RESULT_OK, data);
                                                    requireActivity().finish();
                                                } catch (RuntimeException ignored) {
                                                }
                                            });
                                } else if (s.isRunning()) {
                                    if (s.connectionData().isConnected()) {
                                        alertDialogBuilder
                                                .setMessage(requireContext().getString(R.string.psicash_logout_alert_connected_message))
                                                .setNegativeButton(R.string.psicash_cancel_lbl, (dialog, which) -> {
                                                });
                                    } else {
                                        alertDialogBuilder
                                                .setMessage(requireContext().getString(R.string.psicash_logout_alert_connecting_message))
                                                .setNegativeButton(R.string.psicash_wait_lbl, (dialog, which) -> {
                                                });
                                    }
                                }
                                alertDialogBuilder.show();
                            })
                            .subscribe());
                }
                return true;
            });

            // Hook up manage account button
            psiCashAccountManagePref.setOnPreferenceClickListener(preference -> {
                String manageAccountUrl = preference.getExtras().getString(PSICASH_MANAGEMENT_URL);
                if (preference.isVisible() && preference.isEnabled() && manageAccountUrl != null) {
                    new PsiCashAccountWebViewDialog(requireContext(), tunnelStateFlowable)
                            .load(manageAccountUrl);
                }
                return true;
            });
        }

        public void render(Pair<TunnelState, PsiCashSettingsViewState> statePair) {
            TunnelState tunnelState = statePair.first;
            PsiCashSettingsViewState viewState = statePair.second;

            psiCashAccountManagePref.getExtras().putString(PSICASH_MANAGEMENT_URL, viewState.accountManagementUrl());

            switch (viewState.accountState()) {
                case NOT_ACCOUNT:
                case ACCOUNT_LOGGED_OUT:
                    psiCashAccountPrefCategory.setVisible(true);
                    psiCashAccountLoginPref.setVisible(true);
                    psiCashAccountLogoutPref.setVisible(false);
                    psiCashAccountManagePref.setVisible(false);
                    break;
                case ACCOUNT_LOGGED_IN:
                    psiCashAccountPrefCategory.setVisible(true);
                    psiCashAccountLoginPref.setVisible(false);
                    psiCashAccountLogoutPref.setVisible(true);
                    psiCashAccountManagePref.setVisible(true);
                    break;
                case INVALID:
                default:
                    psiCashAccountPrefCategory.setVisible(false);
            }

            showPsiCashProgress(viewState.psiCashTransactionInFlight());

            boolean isConnected = tunnelState.isRunning() &&
                    tunnelState.connectionData().isConnected();

            // Disable / enable logout based on 'in flight' state
            psiCashAccountLogoutPref.setEnabled(!viewState.psiCashTransactionInFlight());

            // Disable / enable login and manage account based on 'in flight' state + tunnel state
            psiCashAccountLoginPref.setEnabled(isConnected && !viewState.psiCashTransactionInFlight());
            psiCashAccountLoginPref.setSummary(isConnected ? "" :
                    getString(R.string.psicash_account_preference_connection_required_hint));

            psiCashAccountManagePref.setEnabled(isConnected && !viewState.psiCashTransactionInFlight());
            psiCashAccountManagePref.setSummary(isConnected ? "" :
                    getString(R.string.psicash_account_preference_connection_required_hint));

        }
    }
}
