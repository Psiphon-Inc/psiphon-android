package com.psiphon3;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

@AutoValue
public abstract class ConduitState {
    private static final Set<Integer> SUPPORTED_SCHEMAS = Set.of(1); // Supported schema versions

    public enum Status {
        UNKNOWN,
        RUNNING,
        STOPPED,
        NOT_INSTALLED,
        INCOMPATIBLE_VERSION,
        MAX_RETRIES_EXCEEDED,
    }

    @NonNull
    public abstract Status status();

    @Nullable
    public abstract Integer appVersion();

    @Nullable
    public abstract String message();

    public static ConduitState unknown() {
        return builder()
                .setStatus(Status.UNKNOWN)
                .build();
    }

    public static ConduitState incompatibleVersion(String message) {
        return builder()
                .setStatus(Status.INCOMPATIBLE_VERSION)
                .setMessage(message)
                .build();
    }

    public static ConduitState maxRetriesExceeded() {
        return builder()
                .setStatus(Status.MAX_RETRIES_EXCEEDED)
                .build();
    }

    public static ConduitState fromJson(String jsonString) throws IllegalArgumentException {
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
        private ConduitState parse(String jsonString) throws IllegalArgumentException {
            try {
                JSONObject wrapper = new JSONObject(jsonString);
                int schema = wrapper.getInt("schema");

                validateSchema(schema);

                return parseSchema(schema, wrapper.getJSONObject("data"));
            } catch (JSONException e) {
                throw new IllegalArgumentException("Failed to parse Conduit state: " + e.getMessage());
            }
        }

        private void validateSchema(int schema) {
            if (!SUPPORTED_SCHEMAS.contains(schema)) {
                throw new IllegalArgumentException("Unsupported schema version: " + schema);
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
        private ConduitState parseSchemaV1(JSONObject data) throws IllegalArgumentException {
            try {
                Builder builder = builder()
                        .setAppVersion(data.getInt("appVersion"));

                if (data.has("running")) {
                    boolean running = data.getBoolean("running");
                    builder.setStatus(running ? Status.RUNNING : Status.STOPPED);
                } else {
                    builder.setStatus(Status.UNKNOWN);
                }
                return builder.build();
            } catch (JSONException e) {
                throw new IllegalArgumentException("Failed to parse schema v1: " + e.getMessage());
            }
        }
    }

    public static Builder builder() {
        return new AutoValue_ConduitState.Builder()
                .setStatus(Status.UNKNOWN)
                .setMessage(null);
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder setStatus(@NonNull Status value);

        public abstract Builder setAppVersion(@Nullable Integer value);

        public abstract Builder setMessage(@Nullable String value);

        abstract ConduitState autoBuild();

        public ConduitState build() {
            return autoBuild();
        }
    }
}