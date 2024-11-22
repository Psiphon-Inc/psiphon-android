package com.psiphon3;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;

import org.json.JSONException;
import org.json.JSONObject;

@AutoValue
public abstract class ConduitState {
    public enum Status {
        UNKNOWN,
        RUNNING,
        STOPPED,
        NOT_INSTALLED,
        CONDUIT_UPGRADE_REQUIRED,
        MAX_RETRIES_EXCEEDED,
        ERROR
    }

    @NonNull
    public abstract Status status();

    @Nullable
    public abstract Integer appVersion();

    @Nullable
    public abstract Boolean running();

    @Nullable
    public abstract String errorMessage();

    public static ConduitState unknown() {
        return builder()
                .setStatus(Status.UNKNOWN)
                .build();
    }

    public static ConduitState upgradeRequired() {
        return builder()
                .setStatus(Status.CONDUIT_UPGRADE_REQUIRED)
                .build();
    }

    public static ConduitState maxRetriesExceeded() {
        return builder()
                .setStatus(Status.MAX_RETRIES_EXCEEDED)
                .build();
    }

    public static ConduitState error(String errorMessage) {
        return builder()
                .setStatus(Status.ERROR)
                .setErrorMessage(errorMessage)
                .build();
    }

    public static ConduitState fromJson(String jsonString) throws JSONException {
        return Parser.INSTANCE.parse(jsonString);
    }

    private enum Parser {
        INSTANCE;

        private static final int EXPECTED_SCHEMA_VERSION = 1;

        // Parse Conduit state data json
        // {
        //  "schema": 1, // data schema version
        //  "data": { ... } // data object
        // }
        private ConduitState parse(String jsonString) {
            try {
                JSONObject wrapper = new JSONObject(jsonString);
                int schema = wrapper.getInt("schema");

                if (schema != EXPECTED_SCHEMA_VERSION) {
                    return error("Unexpected schema version: " + schema);
                }

                return parseSchema(schema, wrapper.getJSONObject("data"));
            } catch (JSONException e) {
                return error("Failed to parse JSON: " + e.getMessage());
            }
        }

        private ConduitState parseSchema(int schema, JSONObject data) throws JSONException {
            switch (schema) {
                case 1:
                    return parseSchemaV1(data);
                default:
                    throw new IllegalArgumentException("Unsupported schema: " + schema);
            }
        }

        // Parse Conduit state data object schema version 1
        // {
        //  "appVersion": 123, // App version code
        //  "running": true/false, // Proxy running status, omitted for UNKNOWN state
        // }
        private ConduitState parseSchemaV1(JSONObject data) {
            try {
                Builder builder = builder()
                        .setAppVersion(data.getInt("appVersion"));

                if (data.has("running")) {
                    boolean running = data.getBoolean("running");
                    builder.setRunning(running)
                            .setStatus(running ? Status.RUNNING : Status.STOPPED);
                } else {
                    builder.setStatus(Status.UNKNOWN);
                }
                return builder.build();
            } catch (JSONException e) {
                return error("Failed to parse schema v1 data: " + e.getMessage());
            }
        }
    }

    public static Builder builder() {
        return new AutoValue_ConduitState.Builder()
                .setStatus(Status.UNKNOWN)
                .setErrorMessage(null);
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder setStatus(@NonNull Status value);
        public abstract Builder setAppVersion(@Nullable Integer value);
        public abstract Builder setRunning(@Nullable Boolean value);
        public abstract Builder setErrorMessage(@Nullable String value);

        abstract ConduitState autoBuild();

        public ConduitState build() {
            return autoBuild();
        }
    }

    public boolean isError() {
        return status() == Status.ERROR;
    }
}