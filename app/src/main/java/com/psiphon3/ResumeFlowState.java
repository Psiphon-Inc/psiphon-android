/*
 * Copyright (c) 2025, Psiphon Inc.
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

package com.psiphon3;

import androidx.annotation.NonNull;

public final class ResumeFlowState {
    public final boolean promptsShown;
    public final boolean unlockShown;
    public final boolean adsShown;
    public final boolean flexibleUpdateShown;
    public final boolean immediateUpdateShown;
    public final boolean restartSnackbarShown;
    public final boolean autoStartTriggered;

    private ResumeFlowState(boolean promptsShown, boolean unlockShown, boolean adsShown,
                            boolean flexibleUpdateShown, boolean immediateUpdateShown,
                            boolean restartSnackbarShown, boolean autoStartTriggered) {
        // Constructor is private to enforce the use of factory methods.
        this.promptsShown = promptsShown;
        this.unlockShown = unlockShown;
        this.adsShown = adsShown;
        this.flexibleUpdateShown = flexibleUpdateShown;
        this.immediateUpdateShown = immediateUpdateShown;
        this.restartSnackbarShown = restartSnackbarShown;
        this.autoStartTriggered = autoStartTriggered;
    }

    public static ResumeFlowState initial() {
        return new ResumeFlowState(false, false, false, false, false, false, false);
    }

    public ResumeFlowState withPromptsShown() {
        return new ResumeFlowState(true, unlockShown, adsShown,
                flexibleUpdateShown, immediateUpdateShown, restartSnackbarShown, autoStartTriggered);
    }

    public ResumeFlowState withUnlockShown() {
        return new ResumeFlowState(promptsShown, true, adsShown,
                flexibleUpdateShown, immediateUpdateShown, restartSnackbarShown, autoStartTriggered);
    }

    public ResumeFlowState withAdsShown() {
        return new ResumeFlowState(promptsShown, unlockShown, true,
                flexibleUpdateShown, immediateUpdateShown, restartSnackbarShown, autoStartTriggered);
    }

    public ResumeFlowState withFlexibleUpdateShown() {
        return new ResumeFlowState(promptsShown, unlockShown, adsShown,
                true, immediateUpdateShown, restartSnackbarShown, autoStartTriggered);
    }

    public ResumeFlowState withImmediateUpdateShown() {
        return new ResumeFlowState(promptsShown, unlockShown, adsShown,
                flexibleUpdateShown, true, restartSnackbarShown, autoStartTriggered);
    }

    public ResumeFlowState withRestartSnackbarShown() {
        return new ResumeFlowState(promptsShown, unlockShown, adsShown,
                flexibleUpdateShown, immediateUpdateShown, true, autoStartTriggered);  // ← SET restartSnackbarShown to true
    }

    public ResumeFlowState withAutoStartTriggered() {
        return new ResumeFlowState(promptsShown, unlockShown, adsShown,
                flexibleUpdateShown, immediateUpdateShown, restartSnackbarShown,true);
    }

    public boolean shouldSkipAds() {
        return promptsShown || unlockShown;
    }

    public boolean shouldSkipUpdateAvailabilityCheck() {
        return promptsShown || unlockShown;
    }

    public boolean shouldSkipAutoStart() {
        return unlockShown || immediateUpdateShown || flexibleUpdateShown || restartSnackbarShown;
    }

    @NonNull
    @Override
    public String toString() {
        return "ResumeFlowState{" +
                "promptsShown=" + promptsShown +
                ", unlockShown=" + unlockShown +
                ", adsShown=" + adsShown +
                ", flexibleUpdateShown=" + flexibleUpdateShown +
                ", immediateUpdateShown=" + immediateUpdateShown +
                ", restartSnackbarShown=" + restartSnackbarShown +
                ", autoStartTriggered=" + autoStartTriggered +
                '}';
    }
}
