package com.psiphon3.kin;

import android.content.Context;
import android.content.SharedPreferences;
import android.support.v7.app.AlertDialog;

import com.psiphon3.subscription.R;

import io.reactivex.Single;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.content.DialogInterface.BUTTON_POSITIVE;

class KinPermissionManager {
    private final SettingsManager settingsManager;

    KinPermissionManager(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
    }

    /**
     * Gets the users consent to Kin, asking if not already known
     *
     * @param context context for shared preferences
     * @return single which returns whether the user has agreed to Kin or not
     */
    Single<Boolean> getUsersAgreementToKin(Context context) {
        if (!settingsManager.needsToAgreeToKin(context)) {
            return Single.just(settingsManager.hasAgreedToKin(context));
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
                            settingsManager.setHasAgreedToKin(context, true);
                            emitter.onSuccess(true);
                            break;

                        case BUTTON_NEGATIVE:
                            settingsManager.setHasAgreedToKin(context, false);
                            emitter.onSuccess(false);
                            break;

                        case BUTTON_NEUTRAL:
                        default:
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
                        settingsManager.setHasAgreedToKin(context, false);
                        settingsManager.setHasAgreedToAutoPay(context, false);
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

    Single<Boolean> confirmAutoPaySwitch(Context context) {
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.lbl_kin_auto_pay)
                    .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                        settingsManager.setHasAgreedToAutoPay(context, false);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                        settingsManager.setHasAgreedToAutoPay(context, true);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .create()
                    .show();
        });
    }

    Single<Boolean> confirmPay(Context context) {
        if (settingsManager.hasAgreedToAutoPay(context)) {
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
                            settingsManager.setHasAgreedToAutoPay(context, true);
                            emitter.onSuccess(true);
                        }
                    })
                    .create()
                    .show();
        });
    }
}
