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
 */

package com.psiphon3.unlockui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.psiphon3.UnlockOptions;

public abstract class UnlockOptionHandler {
    protected final UnlockOptions.UnlockEntry entry;
    protected final String key;
    protected final Runnable dismissDialogRunnable;
    protected View inflatedView;

    public UnlockOptionHandler(String key, UnlockOptions.UnlockEntry entry, Runnable dismissDialogRunnable) {
        this.key = key;
        this.entry = entry;
        this.dismissDialogRunnable = dismissDialogRunnable;
    }

    public final View getView(ViewGroup parent) {
        if (inflatedView == null) {
            inflatedView = createView(parent);
            // Always return a view, even if it's not displayable
            inflatedView.setVisibility(entry.isDisplayable() ? View.VISIBLE : View.GONE);
        }
        return inflatedView;
    }

    protected abstract View createView(ViewGroup parent);

    public void onShowDialog() {
    }

    public void onResume() {
    }

    public void onPause() {
    }

    public void onDismissDialog() {
    }

    public int getPriority() {
        return entry.priority;
    }

    public String getKey() {
        return key;
    }

    protected LayoutInflater getLayoutInflater(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext());
    }
}