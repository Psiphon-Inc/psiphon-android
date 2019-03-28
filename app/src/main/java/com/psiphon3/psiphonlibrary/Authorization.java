/*
 *
 * Copyright (c) 2019, Psiphon Inc.
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
    private static final String PREFERENCE_AUTHORIZATIONS_LIST = "preferenceAuthorizations";

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
    public static List<Authorization> geAllPersistedAuthorizations(Context context) {
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

    synchronized static void removeAuthorizations(Context context, List<Authorization> toRemove) {
        if (toRemove.size() == 0) {
            return;
        }
        List<Authorization> authorizations = Authorization.geAllPersistedAuthorizations(context);
        authorizations.removeAll(toRemove);
        replaceAllPersistedAuthorizations(context, authorizations);
    }

    public abstract String base64EncodedAuthorization();

    public abstract String Id();

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
                MyLog.g(String.format("%s : JSON exception parsing '%s': %s", getClass().getSimpleName(), key, e.toString()));
                return result;
            }
        }
    }
}
