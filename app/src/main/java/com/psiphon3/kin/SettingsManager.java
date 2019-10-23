package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;

class SettingsManager {
    private final String KIN_PREFERENCES_NAME = "kin_app_prefs";
    private final String OPTED_IN_KEY = "opted_in";
    private final String AUTO_PAY_KEY = "auto_pay";
    private final long TIME_1_MONTH = 30L * 24 * 60 * 60 * 1000;

    /**
     * @param context context for shared preferences
     * @return true if the user has not yet agreed or disagreed to Kin
     */
    boolean needsToOptIn(Context context) {
        return !getSharedPreferences(context).contains(OPTED_IN_KEY);
    }

    /**
     * @param context context for shared preferences
     * @return true if the user has agreed to use Kin, false if they haven't agreed or haven't been asked yet
     */
    public boolean isOptedIn(Context context) {
        return getSharedPreferences(context).getBoolean(OPTED_IN_KEY, false);
    }

    /**
     * Stores the users agreement to Kin in persistent storage.
     *
     * @param context        context for shared preferences
     * @param hasAgreedToKin if the user has agreed to using Kin or not
     */
    void setIsOptedIn(Context context, boolean hasAgreedToKin) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(OPTED_IN_KEY, hasAgreedToKin)
                .apply();
    }

    /**
     * @param context context for shared preferences
     * @return true if the user has agreed to use Kin, false if they haven't agreed or haven't been asked yet
     */
    boolean hasAgreedToAutoPay(Context context) {
        long nextAgreeTime = getSharedPreferences(context).getLong(AUTO_PAY_KEY, 0);
        return nextAgreeTime > System.currentTimeMillis();
    }

    /**
     * Stores the users agreement to Kin in persistent storage.
     *
     * @param context            context for shared preferences
     * @param hasAgreedToAutoPay if the user has agreed to auto pay on connect or not
     */
    void setHasAgreedToAutoPay(Context context, boolean hasAgreedToAutoPay) {
        // If they've agreed, we don't need to ask for another month
        // Otherwise we need to ask every time, so set the next ask to agree time to be 0
        long nextAgreeTime = hasAgreedToAutoPay ? System.currentTimeMillis() + TIME_1_MONTH : 0;
        getSharedPreferences(context)
                .edit()
                .putLong(AUTO_PAY_KEY, nextAgreeTime)
                .apply();
    }

    boolean isAccountRegistered(Context context, String publicKey) {
        return getSharedPreferences(context)
                .contains(publicKey);
    }

    void setAccountRegistered(Context context, String publicKey, boolean registered) {
        SharedPreferences.Editor editor = getSharedPreferences(context).edit();
        if (registered) {
            // store the wallet key
            // value doesn't matter but we need to have one
            editor.putBoolean(publicKey, true);
        } else {
            // remove the key
            editor.remove(publicKey);
        }
        editor.apply();
    }

    private SharedPreferences getSharedPreferences(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext != null) {
            return applicationContext.getSharedPreferences(KIN_PREFERENCES_NAME, Context.MODE_PRIVATE);
        }

        return context.getSharedPreferences(KIN_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
