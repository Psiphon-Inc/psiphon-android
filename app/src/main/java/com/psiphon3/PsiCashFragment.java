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

package com.psiphon3;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.design.widget.SwipeDismissBehavior;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jakewharton.rxbinding2.view.RxView;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.PsiCashIntent;
import com.psiphon3.psicash.PsiCashListener;
import com.psiphon3.psicash.PsiCashViewModel;
import com.psiphon3.psicash.PsiCashViewModelFactory;
import com.psiphon3.psicash.PsiCashViewState;
import com.psiphon3.psicash.RewardedVideoClient;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;

public class PsiCashFragment extends Fragment implements MviView<PsiCashIntent, PsiCashViewState> {
    private static final String TAG = "PsiCashFragment";
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable viewStatesDisposable;
    private PsiCashViewModel psiCashViewModel;

    private Relay<PsiCashIntent> intentsPublishRelay;
    private Relay<TunnelState> tunnelConnectionStateBehaviorRelay;
    private Relay<PsiphonAdManager.SubscriptionStatus> subscriptionStatusBehaviorRelay;
    private Relay<LifeCycleEvent> lifecyclePublishRelay;


    private TextView balanceLabel;
    private Button buySpeedBoostBtn;
    private CountDownTimer countDownTimer;
    private ProgressBar progressBar;
    private int currentUiBalance;
    private boolean animateOnBalanceChange = false;

    private Button loadWatchRewardedVideoBtn;
    private TextView psiCashChargeProgressTextView;
    private View psiCashLayout;
    private AtomicBoolean keepLoadingVideos = new AtomicBoolean(false);
    private final AtomicBoolean shouldGetPsiCashRemote = new AtomicBoolean(false);
    private boolean shouldAutoPlayVideo;
    private ActiveSpeedBoostListener activeSpeedBoostListener;

    private enum LifeCycleEvent {ON_RESUME, ON_PAUSE, ON_STOP, ON_START}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        psiCashLayout = inflater.inflate(R.layout.psicash_fragment, container, false);
        return  psiCashLayout;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PsiCashListener psiCashListener = new PsiCashListener() {
            @Override
            public void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase) {
                // Store authorization from the purchase
                Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization.encoded);
                Authorization.storeAuthorization(context, authorization);

                // Send broadcast to restart the tunnel
                android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
                LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
            }

            @Override
            public void onNewReward(Context context, long reward) {
                try {
                    // Store the reward amount
                    PsiCashClient.getInstance(context).putVideoReward(reward);
                    // reload local PsiCash to update the view with the new reward amount
                    intentsPublishRelay.accept(PsiCashIntent.GetPsiCashLocal.create());
                } catch (PsiCashException e) {
                    Utils.MyLog.g("Failed to store video reward: " + e);
                }
            }
        };

        intentsPublishRelay = PublishRelay.<PsiCashIntent>create().toSerialized();
        lifecyclePublishRelay = PublishRelay.<LifeCycleEvent>create().toSerialized();
        tunnelConnectionStateBehaviorRelay = BehaviorRelay.<TunnelState>create().toSerialized();
        subscriptionStatusBehaviorRelay = BehaviorRelay.<PsiphonAdManager.SubscriptionStatus>create().toSerialized();

        psiCashViewModel = ViewModelProviders.of(this, new PsiCashViewModelFactory(getActivity().getApplication(), psiCashListener))
                .get(PsiCashViewModel.class);

        // Pass the UI's intents to the view model
        psiCashViewModel.processIntents(intents());

    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        RewardedVideoClient.getInstance().initWithActivity(getActivity());
        progressBar = getActivity().findViewById(R.id.progress_view);
        progressBar.setIndeterminate(true);
        buySpeedBoostBtn = getActivity().findViewById(R.id.purchase_speedboost_btn);
        buySpeedBoostBtn.setVisibility(View.INVISIBLE);
        psiCashChargeProgressTextView = getActivity().findViewById(R.id.psicash_balance_progress);
        psiCashChargeProgressTextView.setVisibility(View.INVISIBLE);
        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);
        balanceLabel.setText("0");
        loadWatchRewardedVideoBtn = getActivity().findViewById(R.id.load_watch_rewarded_video_btn);
        // Load video button clicks
        compositeDisposable.add(loadVideoAdsDisposable());
        // Buy speed boost button events.
        compositeDisposable.add(buySpeedBoostClicksDisposable());
        // Unconditionally get latest local PsiCash state when app is foregrounded
        compositeDisposable.add(getPsiCashLocalDisposable());
        // Get PsiCash tokens when tunnel connects if there are none
        compositeDisposable.add(getPsiCashTokensDisposable());
        // check if there are purchases to be removed when app is foregrounded
        compositeDisposable.add(removePurchasesDisposable());
        // try and get PsiCash state from remote on next foreground
        // if a home page had been opened and tunnel is up
        compositeDisposable.add(getOpenedHomePageRewardDisposable());
    }

    private Disposable removePurchasesDisposable() {
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_RESUME)
                .doOnNext(__ -> {
                    // Check if there are possibly expired purchases to remove in case activity
                    // was in the background when MSG_AUTHORIZATIONS_REMOVED was sent by the service
                    final AppPreferences mp = new AppPreferences(getContext());
                    if (mp.getBoolean(this.getString(R.string.persistentAuthorizationsRemovedFlag), false)) {
                        mp.put(this.getString(R.string.persistentAuthorizationsRemovedFlag), false);
                        removePurchases();
                    }
                })
                .subscribe();
    }

    @Override
    public void onPause() {
        super.onPause();

        shouldAutoPlayVideo = false;

        unbindViewState();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_PAUSE);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindViewState();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_RESUME);
    }

    @Override
    public void onStop() {
        super.onStop();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_STOP);
    }

    @Override
    public void onStart() {
        super.onStart();
        lifecyclePublishRelay.accept(LifeCycleEvent.ON_START);
    }

    private void bindViewState() {
        // Subscribe to the RewardedVideoViewModel and render every emitted state
        viewStatesDisposable = viewStatesDisposable();
    }

    private void unbindViewState() {
        if (viewStatesDisposable != null) {
            viewStatesDisposable.dispose();
        }
    }

    private Disposable viewStatesDisposable() {
        // Do not render PsiCash view states if user is subscribed
        return subscriptionStatusObservable()
                .flatMap(s -> s == PsiphonAdManager.SubscriptionStatus.SUBSCRIBER ?
                        Observable.empty() :
                        psiCashViewModel.states())
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render);
    }

    private Disposable buySpeedBoostClicksDisposable() {
        return RxView.clicks(buySpeedBoostBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .withLatestFrom(tunnelConnectionStateObservable(), (__, state) ->
                        PsiCashIntent.PurchaseSpeedBoost.create(state,
                                (PsiCashLib.PurchasePrice) buySpeedBoostBtn.getTag(R.id.speedBoostPrice),
                                (boolean) buySpeedBoostBtn.getTag(R.id.hasActiveSpeedBoostTag)))
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashLocalDisposable() {
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_RESUME)
                .map(__ -> PsiCashIntent.GetPsiCashLocal.create())
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashTokensDisposable() {
        // If PsiCash doesn't have valid tokens get them from the server once the tunnel is connected
        return tunnelConnectionStateObservable()
                .filter(state -> state.isRunning() && state.connectionData().isConnected())
                .takeWhile(__ -> {
                    try {
                        if (!PsiCashClient.getInstance(getContext()).hasValidTokens()) {
                            return true;
                        }
                    } catch (PsiCashException e) {
                        // Complete stream if error
                        Utils.MyLog.g("PsiCashClient::hasValidTokens error: " + e);
                        return false;
                    }
                    return false;
                })
                .map(PsiCashIntent.GetPsiCashRemote::create)
                .subscribe(intentsPublishRelay);
    }

    private Disposable getOpenedHomePageRewardDisposable() {
        // If we detect that a home page has been opened we will try to get latest PsiCash state
        // from the PsiCash server but only if tunnel is up an running.
        return lifeCycleEventsObservable()
                .filter(s -> s == LifeCycleEvent.ON_START && shouldGetPsiCashRemote.getAndSet(false))
                .switchMap(__ -> tunnelConnectionStateObservable()
                        .take(1)
                        .filter(state -> state.isRunning() && state.connectionData().isConnected())
                        .map(PsiCashIntent.GetPsiCashRemote::create))
                .subscribe(intentsPublishRelay);
    }

    private boolean hasValidTokens() {
        try {
            return PsiCashClient.getInstance(getContext()).hasValidTokens();
        } catch (PsiCashException e) {
            Utils.MyLog.g("PsiCash hasValidTokens() error: " + e);
            return false;
        }
    }

    public void removePurchases() {
        List<String> purchasesToRemove = new ArrayList<>();
        // remove purchases that are not in the authorizations database.
        try {
            // get all persisted authorizations as base64 encoded strings
            List<String> authorizationsAsString = new ArrayList<>();
            for (Authorization a : Authorization.geAllPersistedAuthorizations(getContext())) {
                authorizationsAsString.add(a.base64EncodedAuthorization());
            }
            List<PsiCashLib.Purchase> purchases = PsiCashClient.getInstance(getContext()).getPurchases();
            if (purchases.size() == 0) {
                return;
            }
            // build a list of purchases to remove by cross referencing
            // each purchase authorization against the authorization strings list
            for (PsiCashLib.Purchase purchase : purchases) {
                if (!authorizationsAsString.contains(purchase.authorization)) {
                    purchasesToRemove.add(purchase.id);
                }
            }
            if (purchasesToRemove.size() > 0) {
                intentsPublishRelay.accept(PsiCashIntent.RemovePurchases.create(purchasesToRemove));
            }
        } catch (PsiCashException e) {
            Utils.MyLog.g("Error removing expired purchases: " + e);
        }
    }

    private Observable<TunnelState> tunnelConnectionStateObservable() {
        return tunnelConnectionStateBehaviorRelay
                .hide()
                .distinctUntilChanged();
    }

    private Observable<LifeCycleEvent> lifeCycleEventsObservable() {
        return lifecyclePublishRelay
                .hide()
                .distinctUntilChanged();
    }

    private Disposable loadVideoAdsDisposable() {
       return RxView.clicks(loadWatchRewardedVideoBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .takeWhile(click -> hasValidTokens())
                .switchMap(click -> {
                    keepLoadingVideos.set(true);
                    // React to both - a connection state changes until the load video process
                    // terminates with either success or error AND to the subscription status changes.
                    return Observable.combineLatest(tunnelConnectionStateObservable(),
                            subscriptionStatusObservable(),
                            ((BiFunction<TunnelState, PsiphonAdManager.SubscriptionStatus, Pair>) Pair::new))
                            .switchMap(pair -> {
                                TunnelState s = (TunnelState) pair.first;
                                PsiphonAdManager.SubscriptionStatus subscriptionStatus = (PsiphonAdManager.SubscriptionStatus) pair.second;
                                if (subscriptionStatus == PsiphonAdManager.SubscriptionStatus.SUBSCRIBER) {
                                    // set a flag to stop the outer subscription if user is subscribed
                                    // and complete this inner subscription right away.
                                    keepLoadingVideos.set(false);
                                    return Observable.empty();
                                }
                                return Observable.just(s);
                            })
                            // complete the subscription if we were signaled that the video has
                            // loaded or failed OR if the user is subscribed.
                            .takeWhile(__ -> keepLoadingVideos.get());
                })
                .map(PsiCashIntent.LoadVideoAd::create)
                .subscribe(intentsPublishRelay);
    }

    private Observable<PsiphonAdManager.SubscriptionStatus> subscriptionStatusObservable() {
        return subscriptionStatusBehaviorRelay
                .hide()
                .distinctUntilChanged();
    }

    @Override
    public Observable<PsiCashIntent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        Throwable psiCashStateError = state.error();
        if(psiCashStateError == null) {
            updateUiBalanceLabel(state);
            updateUiChargeBar(state);
            updateUiProgressView(state);
            updateUiRewardedVideoButton(state);
        } else {
            updateUiPsiCashError(psiCashStateError);
        }
    }

    private void updateUiRewardedVideoButton(PsiCashViewState state) {
        if(state.videoIsLoading()) {
            // Reset auto play flag if application enters background between the LOADING and LOADED state.
            shouldAutoPlayVideo = true;
            loadWatchRewardedVideoBtn.setEnabled(false);
        } else if(state.videoIsLoaded()) {
            // Success or error should stop the load video subscription.
            keepLoadingVideos.set(false);
            loadWatchRewardedVideoBtn.setEnabled(true);
            if (shouldAutoPlayVideo) {
                RewardedVideoClient.getInstance().playRewardedVideo();
            }
        } else if(state.videoIsPlaying()) {
            loadWatchRewardedVideoBtn.setEnabled(false);
        } else if(state.videoIsFinished()) {
            loadWatchRewardedVideoBtn.setEnabled(true);
        }
    }

    private void updateUiPsiCashError(Throwable error) {
        if (error instanceof PsiCashException.Video) {
            // Success or error should stop the load video subscription.
            keepLoadingVideos.set(false);
            loadWatchRewardedVideoBtn.setEnabled(true);
        }

        // Clear view state error immediately.
        intentsPublishRelay.accept(PsiCashIntent.ClearErrorState.create());

        String errorMessage;
        if (error instanceof PsiCashException) {
            PsiCashException e = (PsiCashException) error;
            errorMessage = e.getUIMessage(getActivity());
        } else {
            Utils.MyLog.g("Unexpected PsiCash error: " + error.toString());
            errorMessage = getString(R.string.unexpected_error_occured_send_feedback_message);
        }

        Snackbar snackbar = Snackbar.make(getActivity().findViewById(R.id.psicash_coordinator_layout), errorMessage, Snackbar.LENGTH_LONG);

        // center the message in the text view
        TextView tv = snackbar.getView().findViewById(android.support.design.R.id.snackbar_text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        } else {
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
        }

        snackbar.addCallback(new Snackbar.Callback() {
            @Override
            public void onShown(Snackbar sb) {
                super.onShown(sb);
                View snackBarView = sb.getView();
                final ViewGroup.LayoutParams lp = snackBarView.getLayoutParams();
                if (lp instanceof CoordinatorLayout.LayoutParams) {
                    final CoordinatorLayout.LayoutParams layoutParams = (CoordinatorLayout.LayoutParams) lp;
                    CoordinatorLayout.Behavior behavior = layoutParams.getBehavior();
                    if(behavior instanceof SwipeDismissBehavior){
                        ((SwipeDismissBehavior) behavior).setSwipeDirection(SwipeDismissBehavior.SWIPE_DIRECTION_ANY);
                    }
                    layoutParams.setBehavior(behavior);
                }
            }
        });

        snackbar.show();
    }

    private void updateUiProgressView(PsiCashViewState state) {
        if (state.purchaseInFlight() || state.videoIsLoading()) {
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void updateUiChargeBar(PsiCashViewState state) {
        PsiCashLib.PurchasePrice purchasePrice = state.purchasePrice();
        buySpeedBoostBtn.setTag(R.id.speedBoostPrice, purchasePrice);
        buySpeedBoostBtn.setEnabled(!state.purchaseInFlight());
        Date nextPurchaseExpiryDate = state.nextPurchaseExpiryDate();
        if (nextPurchaseExpiryDate != null && new Date().before(nextPurchaseExpiryDate)) {
            if(activeSpeedBoostListener != null ) {
                activeSpeedBoostListener.onActiveSpeedBoost(Boolean.TRUE);
            }
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
            psiCashChargeProgressTextView.setVisibility(View.INVISIBLE);
            buySpeedBoostBtn.setVisibility(View.VISIBLE);
            buySpeedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, true);
        } else {
            if(activeSpeedBoostListener != null ) {
                activeSpeedBoostListener.onActiveSpeedBoost(Boolean.FALSE);
            }
            buySpeedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, false);
            if(purchasePrice != null && purchasePrice.price != 0) {
                if (purchasePrice.price / 1e9 > state.uiBalance()) {
                    buySpeedBoostBtn.setVisibility(View.INVISIBLE);
                    psiCashChargeProgressTextView.setVisibility(View.VISIBLE);

                    int chargePercentage = (int) Math.floor(state.uiBalance() / (purchasePrice.price / 1e9) * 100);

                    Drawable d = psiCashChargeProgressTextView.getBackground();
                    d.setLevel(chargePercentage * 100);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                        psiCashChargeProgressTextView.setBackground(d);
                    } else {
                        psiCashChargeProgressTextView.setBackgroundDrawable(d);
                    }

                    psiCashChargeProgressTextView.setText(String.format(Locale.US , "%s %d%%",
                            getString(R.string.charging_speed_boost_percents_label),
                            chargePercentage));

                    psiCashLayout.setOnTouchListener((view, motionEvent) -> {
                        ObjectAnimator
                                .ofFloat(view, "translationX", 0, 25, -25, 25, -25,15, -15, 6, -6, 0)
                                .setDuration(500)
                                .start();
                        return true;
                    });
                } else {
                    buySpeedBoostBtn.setVisibility(View.VISIBLE);
                    psiCashChargeProgressTextView.setVisibility(View.INVISIBLE);
                    buySpeedBoostBtn.setText(R.string.one_h_of_speed_boost_available_button);
                    psiCashLayout.setOnTouchListener((view, motionEvent) -> false);
                }
            }
        }
    }

    private void updateUiBalanceLabel(PsiCashViewState state) {
        if(!animateOnBalanceChange) {
            animateOnBalanceChange = state.animateOnNextBalanceChange();
            balanceLabel.setText(String.format(Locale.US, "%d", state.uiBalance()));
            // update view's current balance
            currentUiBalance = state.uiBalance();
            return;
        }

        int balanceDelta = state.uiBalance() - currentUiBalance;
        // Nothing changed, bail
        if (balanceDelta == 0) {
            return;
        }
        // Animate value increase
        ValueAnimator valueAnimator = ValueAnimator.ofInt(currentUiBalance, state.uiBalance());
        valueAnimator.setDuration(1000);
        valueAnimator.addUpdateListener(valueAnimator1 ->
                balanceLabel.setText(valueAnimator1.getAnimatedValue().toString()));
        valueAnimator.start();

        // floating balance delta animation
        TextView floatingDeltaTextView = new TextView(getContext());
        floatingDeltaTextView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        floatingDeltaTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        floatingDeltaTextView.setText(String.format(Locale.US, "%s%d", balanceDelta > 0 ? "+" : "", balanceDelta));

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, 0);
        lp.gravity = Gravity.CENTER_HORIZONTAL;

        ViewGroup viewGroup = (ViewGroup)psiCashLayout;

        viewGroup.addView(floatingDeltaTextView, 0, lp);
        setAllParentsClip(floatingDeltaTextView, false);

        viewGroup.bringChildToFront(floatingDeltaTextView);
        viewGroup.invalidate();

        floatingDeltaTextView.animate()
                .scaleX(3f).scaleY(3f)
                .alpha(0f)
                .setDuration(2000)
                .translationY(-100f)
                .translationX(50f)
                .setListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animator) {

                    }

                    @Override
                    public void onAnimationEnd(Animator animator) {
                        setAllParentsClip(floatingDeltaTextView, true);
                        viewGroup.removeView(floatingDeltaTextView);
                    }

                    @Override
                    public void onAnimationCancel(Animator animator) {

                    }

                    @Override
                    public void onAnimationRepeat(Animator animator) {

                    }
                })
                .start();

        // update view's current balance value
        currentUiBalance = state.uiBalance();
    }

    private void setAllParentsClip(View view, boolean enabled) {
        ViewParent viewParent = view.getParent();
        while (viewParent instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup)viewParent;
            viewGroup.setClipToPadding(enabled);
            viewGroup.setClipChildren(enabled);
            viewParent = viewGroup.getParent();
        }
    }

    private void startActiveSpeedBoostCountDown(long millisDiff) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(millisDiff, 1000) {
            @Override
            public void onTick(long l) {
                if(!isAdded()) {
                    // Do nothing if not attached to the Activity
                    return;
                }
                String hms = String.format(Locale.US, "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(l),
                        TimeUnit.MILLISECONDS.toMinutes(l) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(l)),
                        TimeUnit.MILLISECONDS.toSeconds(l) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(l)));
                buySpeedBoostBtn.setText(String.format(Locale.US, "%s - %s",
                        getString(R.string.speed_boost_active_label), hms));
            }

            @Override
            public void onFinish() {
                // update local state
                intentsPublishRelay.accept(PsiCashIntent.GetPsiCashLocal.create());
            }
        }.start();
    }

    public void onTunnelConnectionState(TunnelState status) {
        tunnelConnectionStateBehaviorRelay.accept(status);
    }

    void onSubscriptionStatus(PsiphonAdManager.SubscriptionStatus status) {
        subscriptionStatusBehaviorRelay.accept(status);
    }

    void onOpenHomePage() {
        shouldGetPsiCashRemote.set(true);
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        unbindViewState();
        super.onDestroy();
    }

    public void setActiveSpeedBoostListener(ActiveSpeedBoostListener listener) {
        this.activeSpeedBoostListener = listener;
    }

    public interface ActiveSpeedBoostListener {
        void onActiveSpeedBoost(Boolean value);
    }
}