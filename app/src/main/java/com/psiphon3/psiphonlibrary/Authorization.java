package com.psiphon3.psiphonlibrary;


import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.google.auto.value.AutoValue;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.psiphon3.psiphonlibrary.Utils.MyLog;
import static com.psiphon3.psiphonlibrary.Utils.parseRFC3339Date;

@AutoValue
public abstract class Authorization {
    static final String ACCESS_TYPE_SPEED_BOOST = "speed-boost";
    static final String ACCESS_TYPE_GOOGLE_SUBSCRIPTION = "google-subscription";
    private static final String PREFERENCE_AUTHORIZATIONS_LIST = "preferenceAuthorizations";
    private static final String PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST = "preferenceSpeedBoostDeletedIds";

    @Nullable
    public static Authorization fromBase64Encoded(String base64EncodedAuthorization) {
        String Id, accessType, expiresDateString;
        Date expires;

        if (TextUtils.isEmpty(base64EncodedAuthorization)) {
            return null;
        }

        byte[] decoded = android.util.Base64.decode(base64EncodedAuthorization,
                android.util.Base64.DEFAULT);
        try {
            JSONObject authorizationObject = new JSONObject(new String(decoded)).getJSONObject("Authorization");

            Id = authorizationObject.getString("ID");
            if (TextUtils.isEmpty(Id)) {
                MyLog.g("authorization 'ID' is empty");
                return null;
            }

            accessType = authorizationObject.getString("AccessType");
            if (TextUtils.isEmpty(accessType)) {
                MyLog.g("authorization 'AccessType' is empty");
                return null;
            }

            expiresDateString = authorizationObject.getString("Expires");
            if (TextUtils.isEmpty(expiresDateString)) {
                MyLog.g("authorization 'Expires' is empty");
                return null;
            }
        } catch (JSONException e) {
            MyLog.g("JSON exception parsing authorization token: " + e.getMessage());
            return null;
        }

        try {
            expires = parseRFC3339Date(expiresDateString);
        } catch (ParseException e) {
            MyLog.g("ParseException: " + e.getMessage() + " while parsing 'Expires' field: " + expiresDateString);
            return null;
        }

        return new AutoValue_Authorization(base64EncodedAuthorization, Id, accessType, expires);
    }

    @NonNull
    static List<Authorization> geAllPersistedAuthorizations(Context context) {
        StringListPreferences preferences = new StringListPreferences(context);
        List<String> encodedAuthList;
        try {
            encodedAuthList = preferences.getStringList(PREFERENCE_AUTHORIZATIONS_LIST);
        } catch (ItemNotFoundException e) {
            return new ArrayList<>();
        }
        List<Authorization> resultList = new ArrayList<>();
        for (String encodedAuth : encodedAuthList) {
            Authorization a = Authorization.fromBase64Encoded(encodedAuth);
            if (a != null) {
                resultList.add(a);
            }
        }
        return resultList;
    }

    private synchronized static void replaceAllPersistedAuthorizations(Context context, List<Authorization> authorizations) {
        List<String> base64EncodedAuthorizations = new ArrayList<>();
        for (Authorization a: authorizations) {
            base64EncodedAuthorizations.add(a.base64EncodedAuthorization());
        }
        StringListPreferences preferences = new StringListPreferences(context);
        preferences.put(PREFERENCE_AUTHORIZATIONS_LIST, base64EncodedAuthorizations);
    }

    public synchronized static void storeAuthorization(Context context, Authorization authorization) {
        if (authorization == null) {
            return;
        }
        List<Authorization> authorizationList = Authorization.geAllPersistedAuthorizations(context);
        if (authorizationList.contains(authorization)) {
            return;
        }
        authorizationList.add(authorization);
        replaceAllPersistedAuthorizations(context, authorizationList);
    }

    synchronized static void storeRemovedSpeedBoostAuthorizationIds(Context context, List<String> Ids) {
        if(Ids.size() == 0) {
            return;
        }
        StringListPreferences preferences = new StringListPreferences(context);

        List<String> currentIds = Authorization.getRemovedSpeedBoostAuthorizationIds(context);

        // Merge with no dupes
        currentIds.removeAll(Ids);
        currentIds.addAll(Ids);

        preferences.put(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST, currentIds);
    }

    @NonNull
    public static List<String> getRemovedSpeedBoostAuthorizationIds(Context context) {
        StringListPreferences preferences = new StringListPreferences(context);
        try {
            return preferences.getStringList(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST);
        } catch (ItemNotFoundException e) {
            return new ArrayList<>();
        }
    }

    public synchronized static void clearRemovedSpeedBoostAuthorizationIds(Context context) {
        StringListPreferences preferences = new StringListPreferences(context);
        preferences.put(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST, new ArrayList<>());
    }

    synchronized static void removeAuthorizations(Context context, List<Authorization> toRemove) {
        if (toRemove.size() == 0) {
            return;
        }
        List<Authorization> authorizations = Authorization.geAllPersistedAuthorizations(context);
        authorizations.removeAll(toRemove);
        replaceAllPersistedAuthorizations(context, authorizations);
    }

    abstract String base64EncodedAuthorization();

    abstract String Id();

    abstract String accessType();

    abstract Date expires();

    private static class StringListPreferences extends AppPreferences {
        StringListPreferences(Context context) {
            super(context);
        }

        public void put(@NonNull String key, List<String> value) {
            JSONArray jsonArray = new JSONArray(value);
            super.put(key, jsonArray.toString());
        }

        @NonNull
        List<String> getStringList(@NonNull String key) throws ItemNotFoundException {
            List<String> result = new ArrayList<>();
            try {
                String jsonString = super.getString(key);
                if (TextUtils.isEmpty(jsonString)) {
                    return result;
                }
                JSONArray jsonArray = new JSONArray(jsonString);

                for (int i = 0; i < jsonArray.length(); i++) {
                    result.add(jsonArray.getString(i));
                }
                return result;
            } catch (JSONException e) {
                MyLog.g("MultiProcessPreferences: JSON exception parsing '" + key + "': " + e.toString());
                return result;
            }
        }
    }
}
