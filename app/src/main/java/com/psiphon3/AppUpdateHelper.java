package com.psiphon3;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallException;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallErrorCode;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.android.schedulers.AndroidSchedulers;

public class AppUpdateHelper {

    public enum UpdateAvailabilityResult {
        IMMEDIATE_UPDATE_SHOWN,
        FLEXIBLE_UPDATE_SHOWN,
        NO_UPDATE_AVAILABLE,
        UPDATE_CHECK_FAILED,
        USER_CANCELLED,
        FAILED_TO_LAUNCH
    }

    public enum UpdateStateResult {
        RESTART_SNACKBAR_SHOWN,
        NO_ACTION_NEEDED
    }

    private static final int RC_APP_UPDATE = 1001;

    private static final String PREFS_KEY = "APP_UPDATE_PREFS";
    private static final String LAST_PROMPTED_UPDATE = "LAST_PROMPTED_UPDATE"; // "version_staleness"

    @Nullable
    private Snackbar restartSnackbar;

    @Nullable
    private  InstallStateUpdatedListener installListener;

    // Simple policy - replace later with server config if needed
    // TODO: make configurable via server pushed config
    public static class UpdatePolicy {
        public final int stalenessThresholdDays;
        public final int highPriorityThreshold;

        public UpdatePolicy(int stalenessThresholdDays, int highPriorityThreshold) {
            this.stalenessThresholdDays = stalenessThresholdDays;
            this.highPriorityThreshold = highPriorityThreshold;
        }
    }

    private final AppUpdateManager updateManager;
    private final AppCompatActivity activity;
    private final SharedPreferences prefs;
    private final @NonNull View snackBarAnchor;
    private final UpdatePolicy policy;

    private final AtomicBoolean updateFlowInFlight = new AtomicBoolean(false);

    // Track pending update flow to emit result when UI completes
    @Nullable
    private volatile SingleEmitter<UpdateAvailabilityResult> pendingAvailabilityEmitter;
    @Nullable
    private volatile UpdateAvailabilityResult pendingAvailabilityResult;

    public AppUpdateHelper(@NonNull AppCompatActivity activity,
                           @NonNull View snackbarAnchor) {
        this(activity,
                AppUpdateManagerFactory.create(activity),
                activity.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE),
                snackbarAnchor);
    }

    @VisibleForTesting
    AppUpdateHelper(@NonNull AppCompatActivity activity,
                    @NonNull AppUpdateManager updateManager,
                    @NonNull SharedPreferences prefs,
                    @NonNull View snackbarAnchor) {
        this.activity = activity;
        this.updateManager = updateManager;
        this.prefs = prefs;
        this.snackBarAnchor = snackbarAnchor;
        // Default policy: 30 days staleness or priority >= 5 triggers forced update
        this.policy = new UpdatePolicy(/*staleness*/30, /*priority*/5);

        this.installListener = state -> {
            final int status = state.installStatus();
            MyLog.i("AppUpdateHelper: install state update: status=" + status +
                    ", bytes=" + state.bytesDownloaded() + "/" + state.totalBytesToDownload());
            if (status == InstallStatus.DOWNLOADED) {
                activity.runOnUiThread(this::showRestartSnackbar);
            }
        };

        try {
            updateManager.registerListener(installListener);
            MyLog.i("AppUpdateHelper: registered install state listener");
        } catch (Exception e) {
            MyLog.w("AppUpdateHelper: failed to register install listener: " + e);
        }
    }

    // Check for new app updates - show UI if appropriate - emits after UI completes
    public Single<UpdateAvailabilityResult> checkForNewUpdates() {
        return Single.<AppUpdateInfo>create(emitter ->
                        updateManager.getAppUpdateInfo()
                                .addOnSuccessListener(info -> { if (!emitter.isDisposed()) emitter.onSuccess(info); })
                                .addOnFailureListener(e -> { if (!emitter.isDisposed()) emitter.onError(e); }))
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(this::processNewUpdate)
                .onErrorReturn(error -> {
                    if (error instanceof InstallException &&
                            ((InstallException) error).getErrorCode() == InstallErrorCode.ERROR_APP_NOT_OWNED) {
                        MyLog.i("AppUpdateHelper: skipping update availability check - app not installed from Play");
                        return UpdateAvailabilityResult.NO_UPDATE_AVAILABLE;
                    }
                    MyLog.e("AppUpdateHelper: failed to check for updates", error);
                    return UpdateAvailabilityResult.UPDATE_CHECK_FAILED;
                });
    }

    // Check and handle existing update states on resume - emits after UI completes
    public Single<UpdateStateResult> checkUpdateState() {
        return Single.<AppUpdateInfo>create(emitter ->
                        updateManager.getAppUpdateInfo()
                                .addOnSuccessListener(info -> { if (!emitter.isDisposed()) emitter.onSuccess(info); })
                                .addOnFailureListener(e -> { if (!emitter.isDisposed()) emitter.onError(e); }))
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap(this::handleExistingUpdateState)
                .onErrorReturn(error -> {
                    if (error instanceof InstallException &&
                            ((InstallException) error).getErrorCode() == InstallErrorCode.ERROR_APP_NOT_OWNED) {
                        MyLog.i("AppUpdateHelper: skipping update state check - app not installed from Play");
                        return UpdateStateResult.NO_ACTION_NEEDED;
                    }
                    MyLog.e("AppUpdateHelper: failed to check update state", error);
                    return UpdateStateResult.NO_ACTION_NEEDED;
                });
    }

    private Single<UpdateAvailabilityResult> processNewUpdate(AppUpdateInfo info) {
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            MyLog.i("AppUpdateHelper: no updates available");
            return Single.just(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
        }

        int versionCode = info.availableVersionCode();
        int priority = info.updatePriority();
        Integer stalenessDays = info.clientVersionStalenessDays();
        int staleness = stalenessDays != null ? stalenessDays : 0;

        boolean isForced = shouldForceUpdate(priority, staleness);

        if (isForced) {
            return startBestEffortForcedUpdate(info);
        } else if (shouldShowOptionalUpdate(versionCode, staleness)) {
            return showOptionalUpdate(info, versionCode, staleness);
        } else {
            MyLog.i("AppUpdateHelper: skipping update - already prompted for: " + versionCode + "_" + staleness);
            return Single.just(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
        }
    }

    private Single<UpdateStateResult> handleExistingUpdateState(AppUpdateInfo info) {
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            showRestartSnackbar();
            return Single.just(UpdateStateResult.RESTART_SNACKBAR_SHOWN);
        }
        return Single.just(UpdateStateResult.NO_ACTION_NEEDED);
    }

    private Single<UpdateAvailabilityResult> startBestEffortForcedUpdate(AppUpdateInfo info) {
        if (info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            return startUpdateFlowAndWait(info, AppUpdateType.IMMEDIATE, UpdateAvailabilityResult.IMMEDIATE_UPDATE_SHOWN, null);
        } else if (info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            return startUpdateFlowAndWait(info, AppUpdateType.FLEXIBLE, UpdateAvailabilityResult.FLEXIBLE_UPDATE_SHOWN, null);
        }
        MyLog.w("AppUpdateHelper: no update types allowed for forced update");
        return Single.just(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
    }

    private Single<UpdateAvailabilityResult> showOptionalUpdate(AppUpdateInfo info, int versionCode, int staleness) {
        if (!info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
            MyLog.w("AppUpdateHelper: flexible update not allowed for version: " + versionCode);
            return Single.just(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
        }
        return startUpdateFlowAndWait(
                info,
                AppUpdateType.FLEXIBLE,
                UpdateAvailabilityResult.FLEXIBLE_UPDATE_SHOWN,
                () -> recordUpdatePrompt(versionCode, staleness)
        );
    }

    // Start update flow and wait for completion via activity result
    private Single<UpdateAvailabilityResult> startUpdateFlowAndWait(AppUpdateInfo info,
                                                                    @AppUpdateType int type,
                                                                    UpdateAvailabilityResult expectedResult,
                                                                    @Nullable Runnable onLaunched) {
        return Single.create(emitter -> {
            if (!updateFlowInFlight.compareAndSet(false, true)) {
                MyLog.i("AppUpdateHelper: startUpdateFlow skipped - flow already in flight");
                emitter.onSuccess(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
                return;
            }

            SingleEmitter<UpdateAvailabilityResult> previousEmitter = pendingAvailabilityEmitter;
            if (previousEmitter != null && !previousEmitter.isDisposed()) {
                previousEmitter.onSuccess(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
            }

            pendingAvailabilityEmitter = emitter;
            pendingAvailabilityResult = expectedResult;

            emitter.setCancellable(() -> {
                if (pendingAvailabilityEmitter == emitter) {
                    pendingAvailabilityEmitter = null;
                    pendingAvailabilityResult = null;
                }
            });

            if (!startUpdateFlow(info, type)) {
                pendingAvailabilityEmitter = null;
                pendingAvailabilityResult = null;
                updateFlowInFlight.set(false);
                emitter.onSuccess(UpdateAvailabilityResult.FAILED_TO_LAUNCH);
                return;
            }

            if (onLaunched != null) {
                try {
                    onLaunched.run();
                } catch (Exception e) {
                    MyLog.e("AppUpdateHelper: onLaunched callback error: " + e);
                }
            }
        });
    }

    private boolean startUpdateFlow(AppUpdateInfo info, @AppUpdateType int type) {
        try {
            updateManager.startUpdateFlowForResult(
                    info,
                    type,
                    activity,
                    RC_APP_UPDATE
            );
            MyLog.i("AppUpdateHelper: started update flow, type=" + getUpdateTypeName(type) +
                    ", requestCode=" + RC_APP_UPDATE);
            return true;
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to start update flow: " + e);
            return false;
        }
    }

    // Completes the pending Single(s) waiting for the update activity result
    public void handleUpdateActivityResult(int requestCode, int resultCode) {
        if (requestCode != RC_APP_UPDATE) return;

        MyLog.i("AppUpdateHelper: update activity result code=" + resultCode);
        updateFlowInFlight.set(false);

        // Availability emitter
        SingleEmitter<UpdateAvailabilityResult> availabilityEmitter = pendingAvailabilityEmitter;
        if (availabilityEmitter != null && !availabilityEmitter.isDisposed()) {
            UpdateAvailabilityResult toEmit =
                    (resultCode == Activity.RESULT_OK) ?
                            (pendingAvailabilityResult != null ? pendingAvailabilityResult : UpdateAvailabilityResult.NO_UPDATE_AVAILABLE)
                            : (resultCode == Activity.RESULT_CANCELED) ? UpdateAvailabilityResult.USER_CANCELLED
                            : UpdateAvailabilityResult.NO_UPDATE_AVAILABLE;

            pendingAvailabilityEmitter = null;
            pendingAvailabilityResult = null;
            availabilityEmitter.onSuccess(toEmit);
        }
    }

    private boolean shouldForceUpdate(int priority, int staleness) {
        return priority >= policy.highPriorityThreshold || staleness >= policy.stalenessThresholdDays;
    }

    private boolean shouldShowOptionalUpdate(int versionCode, int staleness) {
        // Check if we have already prompted for this version/staleness combination
        // Prevent from prompting again on the same day if the version has not changed
        String currentUpdate = versionCode + "_" + staleness;
        String lastPromptedUpdate = prefs.getString(LAST_PROMPTED_UPDATE, "");
        return !currentUpdate.equals(lastPromptedUpdate);
    }

    private void recordUpdatePrompt(int versionCode, int staleness) {
        String currentUpdate = versionCode + "_" + staleness;
        boolean ok = prefs.edit().putString(LAST_PROMPTED_UPDATE, currentUpdate).commit();
        MyLog.i("AppUpdateHelper: recorded update prompt: " + currentUpdate + " - committed=" + ok);
    }

    private String getUpdateTypeName(@AppUpdateType int type) {
        if (type == AppUpdateType.IMMEDIATE) return "IMMEDIATE";
        if (type == AppUpdateType.FLEXIBLE) return "FLEXIBLE";
        return "UNKNOWN";
    }

    private void showRestartSnackbar() {
        try {
            if (restartSnackbar != null && (restartSnackbar.isShown() || restartSnackbar.isShownOrQueued())) {
                return;
            }

            MyLog.i("AppUpdateHelper: Update ready to install - showing restart snackbar");

            restartSnackbar = Snackbar
                    .make(snackBarAnchor, R.string.app_update_ready_to_install, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.app_update_restart, v -> completeUpdate());

            restartSnackbar.addCallback(new Snackbar.Callback() {
                @Override public void onDismissed(Snackbar bar, int event) {
                    MyLog.i("AppUpdateHelper: snackbar dismissed, event=" + event);
                    restartSnackbar = null;
                }
            });

            restartSnackbar.show();
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to show restart snackbar: " + e);
        }
    }

    private void completeUpdate() {
        try {
            updateManager.completeUpdate();
        } catch (Exception e) {
            MyLog.e("AppUpdateHelper: failed to complete update: " + e);
            showGenericUpdateError();
        }
    }

    private void showGenericUpdateError() {
        Toast.makeText(activity, R.string.app_update_error, Toast.LENGTH_SHORT).show();
    }

    public void onDestroy() {
        SingleEmitter<UpdateAvailabilityResult> availabilityEmitter = pendingAvailabilityEmitter;
        if (availabilityEmitter != null && !availabilityEmitter.isDisposed()) {
            pendingAvailabilityEmitter = null;
            availabilityEmitter.onSuccess(UpdateAvailabilityResult.NO_UPDATE_AVAILABLE);
        }

        if (installListener != null) {
            try {
                updateManager.unregisterListener(installListener);
                MyLog.i("AppUpdateHelper: unregistered install state listener");
            } catch (Exception e) {
                MyLog.w("AppUpdateHelper: failed to unregister install listener: " + e);
            }
            installListener = null;
        }

        if (restartSnackbar != null) {
            try {
                restartSnackbar.dismiss();
            } catch (Exception ignored) {
            }
            restartSnackbar = null;
        }

        updateFlowInFlight.set(false);
    }
}
