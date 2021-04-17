/*
 * Copyright (c) 2021, Psiphon Inc.
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

package com.psiphon3.psicash.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.behavior.SwipeDismissBehavior;
import com.google.android.material.snackbar.Snackbar;
import com.psiphon3.psicash.account.PsiCashAccountActivity;
import com.psiphon3.psicash.store.PsiCashStoreActivity;
import com.psiphon3.subscription.R;

import org.jetbrains.annotations.NotNull;

import java.text.NumberFormat;

import io.reactivex.Observable;

public class UiHelpers {

    @NotNull
    public static Snackbar getSnackbar(String messageText, View anchorView) {
        int timeOutMs = 4000;
        Snackbar snackbar = Snackbar.make(anchorView, messageText, timeOutMs);
        // Set Light background
        snackbar.getView().setBackgroundColor(Color.parseColor("#FFCFCFCF"));

        TextView tv = snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        // Set dark text
        tv.setTextColor(Color.parseColor("#FF121212"));
        // Make the message text view multiline and center the message
        tv.setMaxLines(5);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        } else {
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        // Set action button color
        TextView actionView = (TextView) snackbar.getView().findViewById(com.google.android.material.R.id.snackbar_action);
        actionView.setTextColor(Color.parseColor("#7f343b"));

        // Add 'Ok' dismiss action button.
        snackbar.setAction(R.string.psicash_snackbar_action_ok, view -> {
        });

        // Add swipe dismiss behaviour.
        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar sb) {
                super.onShown(sb);
                View snackBarView = sb.getView();
                final ViewGroup.LayoutParams lp = snackBarView.getLayoutParams();
                if (lp instanceof CoordinatorLayout.LayoutParams) {
                    final CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) lp;
                    CoordinatorLayout.Behavior behavior = layoutParams.getBehavior();
                    if (behavior instanceof SwipeDismissBehavior) {
                        ((SwipeDismissBehavior) behavior).setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY);
                    }
                    layoutParams.setBehavior(behavior);
                }
            }
        });
        return snackbar;
    }

    public static void openPsiCashStoreActivity(final FragmentActivity activity, int tabIndex) {
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, PsiCashStoreActivity.class);
        intent.putExtra("tabIndex", tabIndex);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivityForResult(intent, PsiCashStoreActivity.ACTIVITY_REQUEST_CODE);
    }

    public static void openPsiCashAccountActivity(final FragmentActivity activity,
                                                  PsiCashAccountActivity.CallerActivity callerActivity) {
        if (activity == null) {
            return;
        }
        Intent intent = new Intent(activity, PsiCashAccountActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (callerActivity != null) {
            intent.putExtra(PsiCashAccountActivity.CallerActivity.class.getName(), callerActivity);
        }
        activity.startActivityForResult(intent, PsiCashAccountActivity.ACTIVITY_REQUEST_CODE);
    }

    public static Observable<ValueAnimator> balanceLabelAnimationObservable(int fromVal, int toVal, final TextView view) {
        return Observable.create(emitter -> {
            ValueAnimator valueAnimator = ValueAnimator.ofInt(fromVal, toVal);
            valueAnimator.setDuration(600);
            final NumberFormat nf = NumberFormat.getInstance();
            valueAnimator.addUpdateListener(va ->
                    view.setText(nf.format(va.getAnimatedValue())));
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    view.requestLayout();
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    view.requestLayout();
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            });

            if (!emitter.isDisposed()) {
                emitter.onNext(valueAnimator);
            }
        });
    }
}
