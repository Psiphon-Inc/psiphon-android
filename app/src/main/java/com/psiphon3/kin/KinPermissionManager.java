package com.psiphon3.kin;

import android.content.Context;
import android.support.v7.app.AlertDialog;

import com.psiphon3.subscription.R;

import io.reactivex.Single;

import static android.content.DialogInterface.BUTTON_NEGATIVE;
import static android.content.DialogInterface.BUTTON_NEUTRAL;
import static android.content.DialogInterface.BUTTON_POSITIVE;

public class KinPermissionManager {
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
    public Single<Boolean> getUsersAgreementToKin(Context context) {
        if (!settingsManager.needsToOptIn(context)) {
            return Single.just(settingsManager.isOptedIn(context));
        }

        return optIn(context);
    }

    public Single<Boolean> optIn(Context context) {
        return Single.create(emitter ->
                OptInDialog.show(context, button -> {
                    if (emitter.isDisposed()) {
                        return;
                    }

                    // Return if the user agreed or not
                    // If it was dismissed, ask next time they open the app
                    switch (button) {
                        case BUTTON_POSITIVE:
                            settingsManager.setIsOptedIn(context, true);
                            settingsManager.setHasAgreedToAutoPay(context, true);
                            emitter.onSuccess(true);
                            break;

                        case BUTTON_NEGATIVE:
                            settingsManager.setIsOptedIn(context, false);
                            settingsManager.setHasAgreedToAutoPay(context, false);
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

    public Single<Boolean> optOut(Context context) {
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.lbl_kin_opt_out)
                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                        settingsManager.setIsOptedIn(context, false);
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
                    .setCancelable(false)
                    .create()
                    .show();
        });
    }

    public Single<Boolean> confirmDonation(Context context) {
        if (settingsManager.hasAgreedToAutoPay(context)) {
            return Single.just(true);
        }

        // This is a bit weird, but the layout of AlertDialogs is
        // (Neutral) | (Negative) | (Positive) so this will give us Opt-out | <spacing> | Yes
        return Single.create(emitter -> {
            new AlertDialog.Builder(context)
                    .setIcon(R.drawable.ic_kin_logo_large_purple)
                    .setTitle(context.getString(R.string.title_donate_kin))
                    .setMessage(R.string.lbl_kin_pay)
                    .setNeutralButton(R.string.lbl_opt_out, (dialog, which) -> {
                        settingsManager.setHasAgreedToAutoPay(context, false);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(false);
                        }
                    })
                    .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                        settingsManager.setHasAgreedToAutoPay(context, true);
                        if (!emitter.isDisposed()) {
                            emitter.onSuccess(true);
                        }
                    })
                    .setCancelable(false)
                    .create()
                    .show();
        });
    }
}
