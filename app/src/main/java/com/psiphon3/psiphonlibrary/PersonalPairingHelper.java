/*
 * Copyright (c) 2024, Psiphon Inc.
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

import androidx.annotation.NonNull;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.R;
import com.psiphon3.TunnelState;
import com.psiphon3.log.MyLog;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.Objects;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;

/**
 * Helper class to manage the state and configuration of the personal pairing feature.
 * Provides utilities to observe, validate, and update personal pairing settings,
 * handle user imports, and manage storage and relay mechanisms for state changes.
 */
public class PersonalPairingHelper {
    private static final String HTTPS_PREFIX = "https://hextempulant.net/pair/";
    private static final String SUPPORTED_VERSION = "1";
    private static final String VERSION_KEY = "v";
    private static final String DATA_KEY = "data";
    private static final String ID_KEY = "id";
    private static final String NAME_KEY = "name";

    public static class PersonalPairingState {
        public final boolean enabled;
        public final PersonalPairingData data;

        public PersonalPairingState(boolean enabled, PersonalPairingData data) {
            this.enabled = enabled;
            this.data = data;
        }

        public static PersonalPairingState create(boolean enabled, PersonalPairingData data) {
            return new PersonalPairingState(enabled, data);
        }

        public PersonalPairingState withEnabled(boolean enabled) {
            return new PersonalPairingState(enabled, this.data);
        }

        public PersonalPairingState withData(PersonalPairingData data) {
            return new PersonalPairingState(this.enabled, data);
        }
    }

    public static class PersonalPairingData {
        public final String compartmentId;
        public final String alias;

        public PersonalPairingData(String compartmentId, String alias) {
            this.compartmentId = compartmentId;
            this.alias = alias;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PersonalPairingData that = (PersonalPairingData) o;
            return Objects.equals(compartmentId, that.compartmentId) &&
                    Objects.equals(alias, that.alias);
        }

        @Override
        public int hashCode() {
            return Objects.hash(compartmentId, alias);
        }
    }

    private final BehaviorRelay<PersonalPairingState> personalPairingStateRelay;
    private final AppPreferences prefs;
    private final Context context;

    public PersonalPairingHelper(Context context) {
        this.context = context;
        this.prefs = new AppPreferences(context);
        this.personalPairingStateRelay = BehaviorRelay.createDefault(loadInitialState());
    }

    // Load initial state from multi-process shared preferences
    private PersonalPairingState loadInitialState() {
        boolean enabled = prefs.getBoolean(context.getString(R.string.personalPairingEnabledPreference), false);
        String compartmentId = prefs.getString(
                context.getString(R.string.personalPairingCompartmentIdPreference), "");
        String alias = prefs.getString(
                context.getString(R.string.personalPairingAliasPreference), "");

        PersonalPairingData data = null;
        if (compartmentId != null && !compartmentId.isEmpty()) {
            data = new PersonalPairingData(compartmentId, alias != null ? alias : "");
        }

        return new PersonalPairingState(enabled, data);
    }

    // Observe personal pairing state changes
    public Flowable<PersonalPairingState> observePersonalPairingState() {
        return personalPairingStateRelay.hide()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    // Observe personal pairing state enabled flag changes
    public Flowable<Boolean> observePersonalPairingEnabled() {
        return personalPairingStateRelay.map(state -> state.enabled)
                .toFlowable(BackpressureStrategy.LATEST);
    }

    // Update personal pairing state enabled flag
    public void setPersonalPairingEnabled(boolean enabled) {
        PersonalPairingState currentState = personalPairingStateRelay.getValue();
        if (currentState != null && currentState.enabled != enabled) {
            prefs.put(context.getString(R.string.personalPairingEnabledPreference), enabled);
            personalPairingStateRelay.accept(currentState.withEnabled(enabled));
        }
    }

    // Update personal pairing state data, i.e. compartment ID and alias values and enabled flag
    public void setPersonalPairingState(boolean enabled, PersonalPairingData data) {
        if (data == null) {
            return;
        }
        PersonalPairingState currentState = personalPairingStateRelay.getValue();
        if (currentState != null && (currentState.enabled != enabled ||
                !Objects.equals(currentState.data, data))) {
            prefs.put(context.getString(R.string.personalPairingEnabledPreference), enabled);
            prefs.put(context.getString(R.string.personalPairingCompartmentIdPreference), data.compartmentId);
            prefs.put(context.getString(R.string.personalPairingAliasPreference), data.alias);
            personalPairingStateRelay.accept(PersonalPairingState.create(enabled, data));
        }
    }

    // Container class for import result and data
    public static class ImportResult {
        public enum Action {
            // Data imported successfully
            SHOW_SUCCESS,
            // Data already exists (same compartment ID)
            SHOW_ALREADY_EXISTS,
            // Data import failed
            SHOW_ERROR,
            // Prompt user to enable the feature
            PROMPT_ENABLE,
            // Prompt user to update existing data
            PROMPT_UPDATE
        }

        public final Action action;
        public final PersonalPairingData data;
        public final String existingCompartmentId;
        public final Boolean existingEnabled;

        private ImportResult(Action action, PersonalPairingData data, String existingCompartmentId, Boolean existingEnabled) {
            this.action = action;
            this.data = data;
            this.existingCompartmentId = existingCompartmentId;
            this.existingEnabled = existingEnabled;
        }

        public static ImportResult success(PersonalPairingData data) {
            return new ImportResult(Action.SHOW_SUCCESS, data, null, null);
        }

        public static ImportResult alreadyExists(PersonalPairingData data) {
            return new ImportResult(Action.SHOW_ALREADY_EXISTS, data, null, null);
        }

        public static ImportResult error() {
            return new ImportResult(Action.SHOW_ERROR, null, null, null);
        }

        public static ImportResult promptEnable(PersonalPairingData data) {
            return new ImportResult(Action.PROMPT_ENABLE, data, null, null);
        }

        public static ImportResult needsUpdate(PersonalPairingData data, String existingId, Boolean existingEnabled) {
            return new ImportResult(Action.PROMPT_UPDATE, data, existingId, existingEnabled);
        }
    }

    // Extract personal pairing data from a base64-encoded string
    public static PersonalPairingData extractPersonalPairingData(String input) throws IllegalArgumentException {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Input cannot be empty");
        }

        String base64Data = input;
        if (input.startsWith(HTTPS_PREFIX)) {
            base64Data = input.substring(HTTPS_PREFIX.length());
        }

        try {
            String jsonStr = new String(Utils.Base64.decode(base64Data));
            JSONObject json = new JSONObject(jsonStr);

            // Verify version
            if (!SUPPORTED_VERSION.equals(json.getString(VERSION_KEY))) {
                throw new IllegalArgumentException("Unsupported version");
            }

            // Extract data
            JSONObject data = json.getJSONObject(DATA_KEY);
            return new PersonalPairingData(
                    data.getString(ID_KEY),
                    data.getString(NAME_KEY)
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid pairing data", e);
        }
    }

    // Validate personal pairing data and determine the appropriate action
    private ImportResult validatePersonalPairingData(String input) {
        try {
            PersonalPairingData personalPairingData = extractPersonalPairingData(input);
            String storedCompartmentId = prefs.getString(context.getString(R.string.personalPairingCompartmentIdPreference), "");
            Boolean storedEnabled = prefs.getBoolean(context.getString(R.string.personalPairingEnabledPreference), false);
            if (storedCompartmentId == null || storedCompartmentId.isEmpty()) {
                return ImportResult.promptEnable(personalPairingData);
            } else if (storedCompartmentId.equals(personalPairingData.compartmentId)) {
                return ImportResult.alreadyExists(personalPairingData);
            } else {
                return ImportResult.needsUpdate(personalPairingData, storedCompartmentId, storedEnabled);
            }
        } catch (IllegalArgumentException e) {
            MyLog.e("PersonalPairingHelper::validatePersonalPairingData error: " + e.getMessage());
            return ImportResult.error();
        }
    }

    // Handle the import of personal pairing data import and determine the appropriate action
    public Single<ImportResult> handleImport(@NonNull String input, Flowable<TunnelState> tunnelState) {
        return Single.fromCallable(() -> validatePersonalPairingData(input))
                .flatMap(result -> {
                    if (result.action == ImportResult.Action.PROMPT_ENABLE) {
                        // If importing a new pairing while the tunnel is running, prompt the user to enable
                        // the feature because enabling the feature will restart the tunnel
                        // Otherwise, enable the feature automatically without prompting
                        return tunnelState
                                .firstOrError()
                                .map(state -> {
                                    if (state.isRunning()) {
                                        // Pass through the PROMPT_ENABLE result to trigger the prompt UI
                                        return result;
                                    } else {
                                        // Enable the feature automatically, pass SHOW_SUCCESS result to trigger import success UI
                                        setPersonalPairingState(true, result.data);
                                        return ImportResult.success(result.data);
                                    }
                                });
                    }
                    return Single.just(result);
                });
    }

    // Save the personal pairing data after user confirmation and sets the feature enabled flag
    public void confirmImport(PersonalPairingData data, boolean enableSetting) {
        setPersonalPairingState(enableSetting, data);
    }
}