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
abstract class Authorization {
    static final String SPEED_BOOST_TYPE = "speed-boost";
    static final String SUBSCRIPTION_TYPE = "subscription";
    private static final String PREFERENCE_AUTHORIZATIONS_LIST = "preferenceAuthorizations";
    private static final String PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST = "preferenceSpeedBoostDeletedIds";

    @Nullable
    static Authorization fromBase64Encoded(String base64EncodedAuthorization) {
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
            return new ArrayList<Authorization>();
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

    synchronized static void storeAuthorizationsList(Context context, List<Authorization> authorizations) {
        JSONArray jsonArray = new JSONArray(authorizations);
        StringListPreferences preferences = new StringListPreferences(context);
        preferences.put(PREFERENCE_AUTHORIZATIONS_LIST, jsonArray.toString());
    }

    synchronized static void storeAuthorization(Context context, Authorization authorization) {
        List<Authorization> authorizationList = Authorization.geAllPersistedAuthorizations(context);
        if (authorizationList.contains(authorization)) {
            return;
        }
        authorizationList.add(authorization);
        storeAuthorizationsList(context, authorizationList);
    }

    public synchronized static void storeDeletedSpeedBoostAuthorizationIds(Context context, List<String> Ids) {
        JSONArray jsonArray = new JSONArray(Ids);
        StringListPreferences preferences = new StringListPreferences(context);
        preferences.put(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST, jsonArray.toString());
    }

    @NonNull
    public static List<String> getDeletedSpeedBoostAuthorizationIds(Context context) {
        StringListPreferences preferences = new StringListPreferences(context);
        try {
            return preferences.getStringList(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST);
        } catch (ItemNotFoundException e) {
            return new ArrayList<String>();
        }
    }

    public synchronized static void clearDeletedSpeedBoostAuthorizationIds(Context context) {
        StringListPreferences preferences = new StringListPreferences(context);
        preferences.put(PREFERENCE_DELETED_SPEEDBOOST_AUTHORIZATION_IDS_LIST, new ArrayList<String>());
    }

    public synchronized static void removeAuthorizations(Context context, List<Authorization> toRemove) {
        List<Authorization> authorizations = Authorization.geAllPersistedAuthorizations(context);
        authorizations.removeAll(toRemove);
        Authorization.storeAuthorizationsList(context, authorizations);
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
