package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v7.app.AlertDialog;
import android.util.Log;

import com.psiphon3.subscription.R;

import io.reactivex.Single;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.content.DialogInterface.BUTTON_POSITIVE;

class KinPermissionManager {
    private final String KIN_PREFERENCES_NAME = "kin_app_prefs";
    private final String AGREED_TO_KIN_KEY = "agreed_to_kin";
    private final String AUTO_PAY_KEY = "auto_pay";
    private final long TIME_1_MONTH = 30L * 24 * 60 * 60 * 1000;

    /**
     * @param context context for shared preferences
     * @return true if the user has not yet agreed or disagreed to Kin
     */
    private boolean needsToAgreeToKin(Context context) {
        return !getSharedPreferences(context).contains(AGREED_TO_KIN_KEY);
    }

    /**
     * @param context context for shared preferences
     * @return true if the user has agreed to use Kin, false if they haven't agreed or haven't been asked yet
     */
    boolean hasAgreedToKin(Context context) {
        return getSharedPreferences(context).getBoolean(AGREED_TO_KIN_KEY, false);
    }

    /**
     * Stores the users agreement to Kin in persistent storage.
     *
     * @param context        context for shared preferences
     * @param hasAgreedToKin if the user has agreed to using Kin or not
     */
    void setHasAgreedToKin(Context context, boolean hasAgreedToKin) {
        getSharedPreferences(context)
                .edit()
                .putBoolean(AGREED_TO_KIN_KEY, hasAgreedToKin)
                .apply();
    }

    /**
     * Gets the users consent to Kin, asking if not already known
     *
     * @param context context for shared preferences
     * @return single which returns whether the user has agreed to Kin or not
     */
    Single<Boolean> getUsersAgreementToKin(Context context) {
        if (!needsToAgreeToKin(context)) {
            return Single.just(hasAgreedToKin(context));
        }

        return optIn(context);
    }

    Single<Boolean> optIn(Context context) {
        return Single.create(emitter ->
                PermissionDialog.show(context, button -> {
                    if (emitter.isDisposed()) {
                        return;
                    }

                    // Return if the user agreed or not
                    // If it was dismissed, ask next time they open the app
                    switch (button) {
                        case BUTTON_POSITIVE:
                            Log.e("tst", "optIn: 1");
                            setHasAgreedToKin(context, true);
                            emitter.onSuccess(true);
                            break;

                        case BUTTON_NEGATIVE:
                            Log.e("tst", "optIn: 2");
                            setHasAgreedToKin(context, false);
                            emitter.onSuccess(false);
                            break;

                        case BUTTON_NEUTRAL:
                        default:
                            Log.e("tst", "optIn: 3");
                            emitter.onSuccess(false);
                            break;
                    }
                })
        );
    }

    Single<Boolean> optOut(Context context) {
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.lbl_kin_opt_out)
                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                        setHasAgreedToKin(context, false);
                        setHasAgreedToAutoPay(context, false);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(false);
                        }
                    })
                    .create()
                    .show();
        });
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

    Single<Boolean> confirmAutoPaySwitch(Context context) {
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.lbl_kin_auto_pay)
                    .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                        setHasAgreedToAutoPay(context, false);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                        setHasAgreedToAutoPay(context, true);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .create()
                    .show();
        });
    }

    Single<Boolean> confirmPay(Context context) {
        if (hasAgreedToAutoPay(context)) {
            return Single.just(true);
        }

        // This is a bit weird, but the layout of AlertDialogs is
        // (Neutral) | (Negative) | (Positive) so this will give us No | Yes | Always
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.lbl_kin_pay)
                    .setNeutralButton(R.string.lbl_no, (dialog, which) -> {
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(false);
                        }
                    })
                    .setNegativeButton(R.string.lbl_yes, (dialog, which) -> {
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .setPositiveButton(R.string.lbl_auto_pay, (dialog, which) -> {
                        if (!emitter.isDisposed()) {
                            setHasAgreedToAutoPay(context, true);
                            emitter.onSuccess(true);
                        }
                    })
                    .create()
                    .show();
        });
    }

    private SharedPreferences getSharedPreferences(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext != null) {
            return applicationContext.getSharedPreferences(KIN_PREFERENCES_NAME, Context.MODE_PRIVATE);
        }

        return context.getSharedPreferences(KIN_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
