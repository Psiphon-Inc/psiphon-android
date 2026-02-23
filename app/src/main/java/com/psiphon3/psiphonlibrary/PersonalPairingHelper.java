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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.R;
import com.psiphon3.TunnelState;
import com.psiphon3.log.MyLog;

import net.grandcentrix.tray.AppPreferences;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.regex.Pattern;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Single;

/**
 * Helper class to manage the state and configuration of the personal pairing feature.
 * Provides utilities to observe, validate, and update personal pairing settings,
 * handle user imports, and manage storage and relay mechanisms for state changes.
 */
public class PersonalPairingHelper {
    private static final String PSIPHON_SCHEME = "psiphon";
    private static final String PSIPHON_PAIR_HOST = "pair";
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final String PAIR_PATH_SEGMENT = "pair";
    private static final String SUPPORTED_VERSION = "1";
    private static final String VERSION_KEY = "v";
    private static final String DATA_KEY = "data";
    private static final String ID_KEY = "id";
    private static final String NAME_KEY = "name";
    private static final Pattern BASE64URL_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/]+={0,2}$");
    private static final Pattern COMPARTMENT_ID_STANDARD_PATTERN = Pattern.compile("^[A-Za-z0-9+/]{43}$");
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    public enum ImportValidationError {
        INVALID_INPUT_FORMAT,
        MALFORMED_TOKEN,
        UNSUPPORTED_VERSION
    }

    public static class PersonalPairingImportException extends IllegalArgumentException {
        public final ImportValidationError validationError;

        public PersonalPairingImportException(ImportValidationError validationError) {
            super(validationError.name());
            this.validationError = validationError;
        }

        public PersonalPairingImportException(ImportValidationError validationError, Throwable cause) {
            super(validationError.name(), cause);
            this.validationError = validationError;
        }
    }

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

    public static String toStandardBase64CompartmentId(String compartmentId) {
        if (compartmentId == null) {
            return null;
        }
        return compartmentId
                .replace('-', '+')
                .replace('_', '/');
    }

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
            data = new PersonalPairingData(toStandardBase64CompartmentId(compartmentId), alias != null ? alias : "");
        }

        return new PersonalPairingState(enabled, data);
    }

    // Observe personal pairing state changes
    public Flowable<PersonalPairingState> observePersonalPairingState() {
        return personalPairingStateRelay.hide()
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

        PersonalPairingData normalizedData = new PersonalPairingData(
                toStandardBase64CompartmentId(data.compartmentId),
                data.alias);

        PersonalPairingState currentState = personalPairingStateRelay.getValue();
        if (currentState != null && (currentState.enabled != enabled ||
                !Objects.equals(currentState.data, normalizedData))) {
            prefs.put(context.getString(R.string.personalPairingEnabledPreference), enabled);
            prefs.put(context.getString(R.string.personalPairingCompartmentIdPreference), normalizedData.compartmentId);
            prefs.put(context.getString(R.string.personalPairingAliasPreference), normalizedData.alias);
            personalPairingStateRelay.accept(PersonalPairingState.create(enabled, normalizedData));
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
        public final ImportValidationError validationError;

        private ImportResult(Action action,
                             PersonalPairingData data,
                             String existingCompartmentId,
                             Boolean existingEnabled,
                             ImportValidationError validationError) {
            this.action = action;
            this.data = data;
            this.existingCompartmentId = existingCompartmentId;
            this.existingEnabled = existingEnabled;
            this.validationError = validationError;
        }

        public static ImportResult success(PersonalPairingData data) {
            return new ImportResult(Action.SHOW_SUCCESS, data, null, null, null);
        }

        public static ImportResult alreadyExists(PersonalPairingData data) {
            return new ImportResult(Action.SHOW_ALREADY_EXISTS, data, null, null, null);
        }

        public static ImportResult error(ImportValidationError validationError) {
            return new ImportResult(Action.SHOW_ERROR, null, null, null, validationError);
        }

        public static ImportResult promptEnable(PersonalPairingData data) {
            return new ImportResult(Action.PROMPT_ENABLE, data, null, null, null);
        }

        public static ImportResult needsUpdate(PersonalPairingData data, String existingId, Boolean existingEnabled) {
            return new ImportResult(Action.PROMPT_UPDATE, data, existingId, existingEnabled, null);
        }
    }

    public static ImportValidationError validationErrorFromException(Throwable throwable) {
        if (throwable instanceof PersonalPairingImportException) {
            return ((PersonalPairingImportException) throwable).validationError;
        }
        return ImportValidationError.MALFORMED_TOKEN;
    }

    private static PersonalPairingImportException invalidInputFormat() {
        return new PersonalPairingImportException(ImportValidationError.INVALID_INPUT_FORMAT);
    }

    private static PersonalPairingImportException malformedToken() {
        return new PersonalPairingImportException(ImportValidationError.MALFORMED_TOKEN);
    }

    private static PersonalPairingImportException malformedToken(Throwable cause) {
        return new PersonalPairingImportException(ImportValidationError.MALFORMED_TOKEN, cause);
    }

    private static PersonalPairingImportException unsupportedVersion() {
        return new PersonalPairingImportException(ImportValidationError.UNSUPPORTED_VERSION);
    }

    private static String normalizeTokenInput(String input) {
        if (input == null) {
            throw invalidInputFormat();
        }

        String trimmedInput = input.trim();
        if (trimmedInput.isEmpty()) {
            throw invalidInputFormat();
        }

        if (!trimmedInput.contains("://")) {
            return trimmedInput;
        }

        URI uri;
        try {
            uri = new URI(trimmedInput);
        } catch (URISyntaxException e) {
            throw invalidInputFormat();
        }

        String scheme = uri.getScheme();
        if (scheme == null || scheme.isEmpty()) {
            throw invalidInputFormat();
        }

        String[] segments = getRawPathSegments(uri.getRawPath());

        if (PSIPHON_SCHEME.equals(scheme)) {
            if (!PSIPHON_PAIR_HOST.equals(uri.getHost())) {
                throw invalidInputFormat();
            }
            if (segments.length != 1) {
                throw invalidInputFormat();
            }
            String token = segments[0];
            if (token == null || token.isEmpty()) {
                throw invalidInputFormat();
            }
            return token;
        }

        if (HTTP_SCHEME.equals(scheme) || HTTPS_SCHEME.equals(scheme)) {
            return extractTokenFromPairPath(segments);
        }

        throw invalidInputFormat();
    }

    private static String extractTokenFromPairPath(String[] segments) {
        for (int i = segments.length - 2; i >= 0; i--) {
            if (PAIR_PATH_SEGMENT.equals(segments[i])) {
                if (i + 2 != segments.length) {
                    throw invalidInputFormat();
                }
                String token = segments[i + 1];
                if (token == null || token.isEmpty()) {
                    throw invalidInputFormat();
                }
                return token;
            }
        }
        throw invalidInputFormat();
    }

    private static String[] getRawPathSegments(String rawPath) {
        if (rawPath == null || rawPath.isEmpty() || "/".equals(rawPath)) {
            return new String[0];
        }
        if (!rawPath.startsWith("/")) {
            throw invalidInputFormat();
        }
        String[] segments = rawPath.substring(1).split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw invalidInputFormat();
            }
        }
        return segments;
    }

    private static byte[] decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            throw malformedToken();
        }

        if (BASE64URL_PATTERN.matcher(token).matches()) {
            try {
                return decodeUrlSafeBase64(token);
            } catch (IllegalArgumentException e) {
                throw malformedToken(e);
            }
        }

        if (BASE64_PATTERN.matcher(token).matches() && token.length() % 4 == 0) {
            try {
                return Utils.Base64.decode(token);
            } catch (IllegalArgumentException e) {
                throw malformedToken(e);
            }
        }

        throw malformedToken();
    }

    private static String requireNonEmptyString(JsonParser parser) throws IOException {
        if (parser.getCurrentToken() != JsonToken.VALUE_STRING) {
            throw malformedToken();
        }
        String stringValue = parser.getValueAsString();
        if (stringValue.isEmpty()) {
            throw malformedToken();
        }
        return stringValue;
    }

    private static PersonalPairingData parsePayload(byte[] decodedToken) {
        try (JsonParser parser = JSON_FACTORY.createParser(decodedToken)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformedToken();
            }

            String version = null;
            String compartmentId = null;
            String alias = null;
            int topLevelFieldCount = 0;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.getCurrentToken() != JsonToken.FIELD_NAME) {
                    throw malformedToken();
                }

                String fieldName = parser.getCurrentName();
                topLevelFieldCount++;
                parser.nextToken();

                if (VERSION_KEY.equals(fieldName)) {
                    version = requireNonEmptyString(parser);
                } else if (DATA_KEY.equals(fieldName)) {
                    if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                        throw malformedToken();
                    }

                    int dataFieldCount = 0;
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        if (parser.getCurrentToken() != JsonToken.FIELD_NAME) {
                            throw malformedToken();
                        }

                        String dataFieldName = parser.getCurrentName();
                        dataFieldCount++;
                        parser.nextToken();

                        if (ID_KEY.equals(dataFieldName)) {
                            compartmentId = requireNonEmptyString(parser);
                        } else if (NAME_KEY.equals(dataFieldName)) {
                            alias = requireNonEmptyString(parser);
                        } else {
                            throw malformedToken();
                        }
                    }

                    if (dataFieldCount != 2 || compartmentId == null || alias == null) {
                        throw malformedToken();
                    }
                } else {
                    throw malformedToken();
                }
            }

            if (topLevelFieldCount != 2 || version == null) {
                throw malformedToken();
            }

            if (!SUPPORTED_VERSION.equals(version)) {
                throw unsupportedVersion();
            }

            String normalizedCompartmentId = validateAndNormalizeCompartmentId(compartmentId);
            return new PersonalPairingData(normalizedCompartmentId, alias);
        } catch (PersonalPairingImportException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw malformedToken(e);
        }
    }

    private static String validateAndNormalizeCompartmentId(String compartmentId) {
        if (!COMPARTMENT_ID_STANDARD_PATTERN.matcher(compartmentId).matches()) {
            throw malformedToken();
        }

        String normalizedCompartmentId = toStandardBase64CompartmentId(compartmentId);

        try {
            byte[] decoded = decodeUrlSafeBase64(normalizedCompartmentId);
            if (decoded.length != 32) {
                throw malformedToken();
            }
        } catch (IllegalArgumentException e) {
            throw malformedToken(e);
        }

        return normalizedCompartmentId;
    }

    private static byte[] decodeUrlSafeBase64(String token) {
        int padLength = (4 - (token.length() % 4)) % 4;
        String paddedToken = token + "====".substring(0, padLength);
        String normalized = paddedToken
                .replace('-', '+')
                .replace('_', '/');
        return Utils.Base64.decode(normalized);
    }

    // Extract personal pairing data from a token string, deep link, or wrapper URL
    public static PersonalPairingData extractPersonalPairingData(String input) throws IllegalArgumentException {
        String token = normalizeTokenInput(input);
        try {
            byte[] decodedToken = decodeToken(token);
            return parsePayload(decodedToken);
        } catch (PersonalPairingImportException e) {
            throw e;
        } catch (Exception e) {
            throw malformedToken(e);
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
            ImportValidationError validationError = validationErrorFromException(e);
            MyLog.e("PersonalPairingHelper::validatePersonalPairingData error: " + validationError.name());
            return ImportResult.error(validationError);
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

    // Reset all personal pairing preferences
    public static void resetPersonalPairingPreferences(Context context) {
        AppPreferences prefs = new AppPreferences(context);
        prefs.remove(context.getString(R.string.personalPairingEnabledPreference));
        prefs.remove(context.getString(R.string.personalPairingCompartmentIdPreference));
        prefs.remove(context.getString(R.string.personalPairingAliasPreference));
    }
}
