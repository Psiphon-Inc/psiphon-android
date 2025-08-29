/*
 * Copyright (c) 2025, Psiphon Inc.
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
 */

package com.psiphon3;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppUpdatePolicy {

    public static class AppNotConfiguredException extends JSONException {
        public AppNotConfiguredException(String packageName) {
            super("No update policy found for package: " + packageName);
        }
    }

    private final int stalenessThresholdDays;
    private final List<Integer> mustUpdateVersions;
    private final int cutoffVersion;

    private static final int DEFAULT_STALENESS_THRESHOLD_DAYS = 30;
    private static final int PLAY_IMMEDIATE_THRESHOLD = 5;
    private static final int DEFAULT_CUTOFF_VERSION = 0;
    private static final List<Integer> DEFAULT_MUST_UPDATE_VERSIONS = Collections.emptyList();

    private static final int DEFAULT_TTL_DAYS = 7;
    private static final int MIN_TTL_DAYS = 1;
    private static final int MAX_TTL_DAYS = 60;


    public AppUpdatePolicy(int stalenessThresholdDays, @NonNull List<Integer> mustUpdateVersions, int cutoffVersion) {
        this.stalenessThresholdDays = stalenessThresholdDays;
        this.mustUpdateVersions = new ArrayList<>(mustUpdateVersions);
        this.cutoffVersion = cutoffVersion;
    }

    public static AppUpdatePolicy getDefaultPolicy() {
        return new AppUpdatePolicy(
                DEFAULT_STALENESS_THRESHOLD_DAYS,
                DEFAULT_MUST_UPDATE_VERSIONS,
                DEFAULT_CUTOFF_VERSION
        );
    }

    public static AppUpdatePolicy fromJson(@NonNull String json, @NonNull String packageName) throws JSONException {
        JSONObject obj = new JSONObject(json);
        JSONObject appPolicy = obj.optJSONObject(packageName);
        if (appPolicy == null) {
            throw new AppNotConfiguredException(packageName);
        }
        return fromJson(appPolicy);
    }

    public static int getTtlDays(@NonNull String json) {
        try {
            JSONObject obj = new JSONObject(json);
            int ttl = obj.optInt("ttlDays", DEFAULT_TTL_DAYS);
            // Clamp to reasonable bounds
            return Math.max(MIN_TTL_DAYS, Math.min(MAX_TTL_DAYS, ttl));
        } catch (Exception e) {
            // If JSON parsing fails, return default TTL
            return DEFAULT_TTL_DAYS;
        }
    }

    public static AppUpdatePolicy fromJson(@NonNull JSONObject json) throws JSONException {
        // Validate that all required fields are present
        if (!json.has("stalenessThresholdDays")) {
            throw new JSONException("Missing required field: stalenessThresholdDays");
        }
        if (!json.has("cutoffVersion")) {
            throw new JSONException("Missing required field: cutoffVersion");
        }
        if (!json.has("mustUpdateVersions")) {
            throw new JSONException("Missing required field: mustUpdateVersions");
        }

        // Parse and validate field values
        int stalenessThreshold = json.getInt("stalenessThresholdDays");
        if (stalenessThreshold < 0) {
            throw new JSONException("Invalid stalenessThresholdDays: must be non-negative, got " + stalenessThreshold);
        }

        int cutoff = json.getInt("cutoffVersion");
        if (cutoff < 0) {
            throw new JSONException("Invalid cutoffVersion: must be non-negative, got " + cutoff);
        }

        List<Integer> mustUpdate = new ArrayList<>();
        JSONArray mustUpdateArray = json.getJSONArray("mustUpdateVersions");
        for (int i = 0; i < mustUpdateArray.length(); i++) {
            int version = mustUpdateArray.getInt(i);
            if (version <= 0) {
                throw new JSONException("Invalid version in mustUpdateVersions: must be positive, got " + version);
            }
            mustUpdate.add(version);
        }

        return new AppUpdatePolicy(stalenessThreshold, mustUpdate, cutoff);
    }

    public boolean shouldForceUpdate(int currentVersion, int playPriority, int stalenessDays) {
        if (currentVersion <= 0) { // unknown version: ignore version-based rules
            return playPriority >= PLAY_IMMEDIATE_THRESHOLD || stalenessDays >= stalenessThresholdDays;
        }
        if (mustUpdateVersions.contains(currentVersion)) {
            return true;
        }
        if (cutoffVersion > 0 && currentVersion < cutoffVersion) {
            return true;
        }
        if (playPriority >= PLAY_IMMEDIATE_THRESHOLD) {
            return true;
        }
        return stalenessDays >= stalenessThresholdDays;
    }

    private static class AppUpdatePolicyStorage extends SafeFileStorage<AppUpdatePolicyData> {
        private static final String LOCK_FILE = "app_update_policy.lock";
        private static final String TEMP_FILE = "app_update_policy_temp.json";
        private static final String POLICY_FILE = "app_update_policy.json";

        public AppUpdatePolicyStorage() {
            super(LOCK_FILE, TEMP_FILE, POLICY_FILE);
        }

        @Override
        protected void writeDataToStream(AppUpdatePolicyData data, OutputStreamWriter writer) throws IOException {
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("policy", data.policyJson);
                jsonObject.put("timestamp", data.timestampMs);
                writer.write(jsonObject.toString());
            } catch (JSONException e) {
                throw new IOException("Failed to serialize app update policy to JSON", e);
            }
        }

        @Override
        protected AppUpdatePolicyData readDataFromStream(BufferedReader reader) throws IOException {
            try {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }

                if (builder.length() == 0) {
                    return getDefaultValue();
                }

                JSONObject jsonObject = new JSONObject(builder.toString());
                String policyJson = jsonObject.optString("policy", "");
                long timestampMs = jsonObject.optLong("timestamp", 0L);

                return new AppUpdatePolicyData(policyJson, timestampMs);
            } catch (JSONException e) {
                throw new IOException("Failed to parse app update policy JSON", e);
            }
        }

        @Override
        protected AppUpdatePolicyData getDefaultValue() {
            return new AppUpdatePolicyData("", 0L);
        }
    }

    public static class AppUpdatePolicyData {
        public final String policyJson;
        public final long timestampMs;

        public AppUpdatePolicyData(String policyJson, long timestampMs) {
            this.policyJson = policyJson;
            this.timestampMs = timestampMs;
        }
    }

    private static final AppUpdatePolicyStorage policyStorage = new AppUpdatePolicyStorage();

    public static void saveAppUpdatePolicyToFile(Context context, String policyJson, long timestampMs) {
        policyStorage.save(context, new AppUpdatePolicyData(policyJson, timestampMs));
    }

    public static AppUpdatePolicyData readAppUpdatePolicyFromFile(Context context) {
        return policyStorage.load(context);
    }
}
