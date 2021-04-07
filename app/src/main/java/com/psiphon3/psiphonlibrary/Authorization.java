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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.google.auto.value.AutoValue;
import com.psiphon3.StringListPreferences;

import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static com.psiphon3.psiphonlibrary.Utils.MyLog;
import static com.psiphon3.psiphonlibrary.Utils.parseRFC3339Date;

@AutoValue
public abstract class Authorization {
    // TODO: PsiCash: production value for ACCESS_TYPE_SPEED_BOOST
    public static final String ACCESS_TYPE_SPEED_BOOST = "speed-boost-test";
    public static final String ACCESS_TYPE_GOOGLE_SUBSCRIPTION = "google-subscription";
    public static final String ACCESS_TYPE_GOOGLE_SUBSCRIPTION_LIMITED = "google-subscription-limited";
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

    public synchronized static boolean storeAuthorization(Context context, Authorization authorization) {
        if (authorization == null) {
            return false;
        }
        List<Authorization> authorizationList = Authorization.geAllPersistedAuthorizations(context);
        if (authorizationList.contains(authorization)) {
            return false;
        }

        // Prior to storing authorization remove all other authorizations of the same type from
        // storage. Psiphon server will only accept one authorization per access type. For example,
        // if there are multiple active authorizations of 'google-subscription' type it is not
        // guaranteed the server will select the one associated with the current purchase which may
        // result in client connect-as-subscriber -> server-reject infinite re-connect loop.
        for (Iterator<Authorization> iterator = authorizationList.iterator(); iterator.hasNext(); ) {
            Authorization a = iterator.next();
            if (a.accessType().equals(authorization.accessType())) {
                iterator.remove();
            }
        }

        authorizationList.add(authorization);
        replaceAllPersistedAuthorizations(context, authorizationList);
        return true;
    }

    public synchronized static boolean removeAuthorizations(Context context, List<Authorization> toRemove) {
        if (toRemove == null || toRemove.size() == 0) {
            MyLog.g("Authorization::removeAuthorizations: remove list is empty");
            return false;
        }
        List<Authorization> authorizations = Authorization.geAllPersistedAuthorizations(context);
        boolean hasChanged = authorizations.removeAll(toRemove);
        if (hasChanged) {
            for (Authorization auth : toRemove) {
                Utils.MyLog.g("Authorization::removeAuthorizations: removing persisted authorization of accessType: " +
                        auth.accessType() + ", expires: " +
                        Utils.getISO8601String(auth.expires()));
            }
            replaceAllPersistedAuthorizations(context, authorizations);
        } else {
            MyLog.g("Authorization::removeAuthorizations: persisted authorizations list has not changed");
        }
        return hasChanged;
    }

    public synchronized static boolean purgeAuthorizationsOfAccessType(Context context, String accessType) {
        if (TextUtils.isEmpty(accessType)) {
            return false;
        }
        boolean hasChanged = false;
        List<Authorization> authorizationList = Authorization.geAllPersistedAuthorizations(context);
        for (Iterator<Authorization> iterator = authorizationList.iterator(); iterator.hasNext(); ) {
            Authorization a = iterator.next();
            if (a.accessType().equals(accessType)) {
                hasChanged = true;
                iterator.remove();
            }
        }
        if (hasChanged) {
            replaceAllPersistedAuthorizations(context, authorizationList);
        }
        return hasChanged;
    }

    public abstract String base64EncodedAuthorization();

    public abstract String Id();

    public abstract String accessType();

    abstract Date expires();

}
