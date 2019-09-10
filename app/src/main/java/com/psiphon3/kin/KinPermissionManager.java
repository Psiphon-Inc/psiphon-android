package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;

import io.reactivex.Single;

public class KinPermissionManager {
    private static final String KIN_PREFERENCES_NAME = "kin_app_prefs";
    private static final String AGREED_TO_KIN_KEY = "agreed_to_kin";

    /**
     * @param context context for shared preferences
     * @return true if the user has not yet agreed or disagreed to Kin
     */
    public static boolean needsToAgreeToKin(Context context) {
        SharedPreferences sharedPreferences = getSharedPreferences(context);
        return !sharedPreferences.contains(AGREED_TO_KIN_KEY);
    }

    /**
     * @param context context for shared preferences
     * @return true if the user has agreed to use Kin, false if they haven't agreed or haven't been asked yet
     */
    public static boolean hasAgreedToKin(Context context) {
        SharedPreferences sharedPreferences = getSharedPreferences(context);
        return sharedPreferences.getBoolean(AGREED_TO_KIN_KEY, false);
    }

    /**
     * Stores the users agreement to Kin in persistent storage.
     *
     * @param context        context for shared preferences
     * @param hasAgreedToKin if the user has agreed to using Kin or not
     */
    public static void setHasAgreedToKin(Context context, boolean hasAgreedToKin) {
        getSharedPreferences(context).edit().putBoolean(AGREED_TO_KIN_KEY, hasAgreedToKin).apply();
    }

    /**
     * Gets the users consent to Kin, asking if not already known
     *
     * @param context context for shared preferences
     * @return single which returns whether the user has agreed to Kin or not
     */
    public static Single<Boolean> getUsersAgreementToKin(Context context) {
        return Single.create(emitter -> {
            if (!KinPermissionManager.needsToAgreeToKin(context) && !emitter.isDisposed()) {
                emitter.onSuccess(hasAgreedToKin(context));
                return;
            }

            new PermissionDialog(context, emitter).show();
        });
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(KIN_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
