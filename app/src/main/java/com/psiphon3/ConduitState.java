package com.psiphon3;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;

import org.json.JSONException;
import org.json.JSONObject;

@AutoValue
public abstract class ConduitState {
    public abstract int appVersion();

    @Nullable
    public abstract Boolean running();

    public static ConduitState fromJson(String jsonString) throws JSONException {
        return Parser.INSTANCE.parse(jsonString);
    }

    private enum Parser {
        INSTANCE;

        // Parse Conduit state data json
        // {
        //  "schema": 1, // data schema version
        //  "data": { ... } // data object
        // }
        private ConduitState parse(String jsonString) throws JSONException {
            JSONObject wrapper = new JSONObject(jsonString);
            return parseSchema(wrapper.getInt("schema"), wrapper.getJSONObject("data"));
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
        private ConduitState parseSchemaV1(JSONObject data) throws JSONException {
            return builder()
                    .setAppVersion(data.getInt("appVersion"))
                    .setRunning(data.has("running") ? data.getBoolean("running") : null)
                    .build();
        }
    }

    public static Builder builder() {
        return new AutoValue_ConduitState.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder setAppVersion(int value);
        public abstract Builder setRunning(@Nullable Boolean value);
        public abstract ConduitState build();
    }
}