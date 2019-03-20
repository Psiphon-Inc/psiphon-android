package com.psiphon3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashFragment extends Fragment implements MviView<PsiCashIntent, PsiCashViewState> {
    private static final String TAG = "PsiCashFragment";
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashViewModel psiCashViewModel;

    private Relay<PsiCashIntent> intentsPublishRelay;
    private Relay<TunnelState> tunnelConnectionStateBehaviorRelay;
    private Relay<PsiphonAdManager.SubscriptionStatus> subscriptionStatusPublishRelay;


    private TextView balanceLabel;
    private Button buySpeedBoostBtn;
    private CountDownTimer countDownTimer;
    private ProgressBar progressBar;
    private int currentUiBalance;
    private boolean animateOnBalanceChange = false;

    private boolean shouldAutoLoadNextVideo = false;
    private boolean shouldAutoPlay = false;
    private Button loadWatchRewardedVideoBtn;



    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.psicash_fragment, container, false);
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PsiCashListener psiCashListener = new PsiCashListener() {
            @Override
            public void onNewExpiringPurchase(Context context, PsiCashLib.Purchase purchase) {
                // Store authorization from the purchase
                Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization);
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

        psiCashViewModel = ViewModelProviders.of(this, new PsiCashViewModelFactory(getActivity().getApplication(), psiCashListener))
                .get(PsiCashViewModel.class);
        intentsPublishRelay = PublishRelay.<PsiCashIntent>create().toSerialized();
        tunnelConnectionStateBehaviorRelay = BehaviorRelay.<TunnelState>create().toSerialized();
        subscriptionStatusPublishRelay = PublishRelay.<PsiphonAdManager.SubscriptionStatus>create().toSerialized();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        RewardedVideoClient.getInstance().initWithActivity(getActivity());
        progressBar = getActivity().findViewById(R.id.progress_view);
        progressBar.setIndeterminate(true);
        buySpeedBoostBtn = getActivity().findViewById(R.id.purchase_speedboost_btn);
        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);
        balanceLabel.setText("0");
        loadWatchRewardedVideoBtn = getActivity().findViewById(R.id.load_watch_rewarded_video_btn);
    }

    @Override
    public void onStart() {
        super.onStart();
        bind();
    }

    @Override
    public void onStop() {
        super.onStop();
        unbind();
    }

    private void bind() {
        compositeDisposable.clear();

        // Pass the UI's intents to the RewardedVideoViewModel
        psiCashViewModel.processIntents(intents());

        // Subscribe to the RewardedVideoViewModel and render every emitted state
        compositeDisposable.add(viewStatesDisposable());

        // Buy speed boost button events.
        compositeDisposable.add(buySpeedBoostClicksDisposable());

        // Unconditionally get latest local PsiCash state when app is foregrounded
        compositeDisposable.add(getPsiCashLocalDisposable());

        // Get PsiCash tokens when tunnel connects if there are none
        compositeDisposable.add(getPsiCashTokensDisposable());

        // Load rewarded videos
        compositeDisposable.add(loadVideoAdsDisposable());

        // Check if there are possibly expired purchases to remove in case activity
        // was in the background when MSG_AUTHORIZATIONS_REMOVED was sent by the service
        final AppPreferences mp = new AppPreferences(getContext());
        if(mp.getBoolean(this.getString(R.string.persistentAuthorizationsRemovedFlag), false)) {
            mp.put(this.getString(R.string.persistentAuthorizationsRemovedFlag), false);
            removePurchases();
        }

    }

    private Disposable viewStatesDisposable() {
        return psiCashViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render);
    }

    private Disposable buySpeedBoostClicksDisposable() {
        return RxView.clicks(buySpeedBoostBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .withLatestFrom(connectionStateObservable(), (__, state) ->
                        PsiCashIntent.PurchaseSpeedBoost.create(state,
                                (PsiCashLib.PurchasePrice) buySpeedBoostBtn.getTag(R.id.speedBoostPrice),
                                (boolean) buySpeedBoostBtn.getTag(R.id.hasActiveSpeedBoostTag)))
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashLocalDisposable() {
        return Observable.just(PsiCashIntent.GetPsiCashLocal.create())
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashTokensDisposable() {
        // If PsiCash doesn't have valid tokens get them from the server once the tunnel is connected
        return connectionStateObservable()
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

    // Do not load ads if user is subscribed
    private Disposable loadVideoAdsDisposable() {
        shouldAutoPlay = false;
        final Observable<Object> loadVideo;

        if (shouldAutoLoadNextVideo){
            loadVideo = subscriptionStatusPublishRelay
                    .filter(s -> s != PsiphonAdManager.SubscriptionStatus.SUBSCRIBER)
                    .map (s -> s);
        } else {
            loadVideo = Observable.combineLatest(RxView.clicks(loadWatchRewardedVideoBtn)
                            .debounce(200, TimeUnit.MILLISECONDS)
                            .doOnNext(__ -> shouldAutoPlay = true),
                    subscriptionStatusPublishRelay,
                    (__, subscriptionStatus) -> subscriptionStatus)
                    .filter(s -> s != PsiphonAdManager.SubscriptionStatus.SUBSCRIBER)
                    .map(s -> s);
        }

        return Observable.combineLatest(
                loadVideo,
                hasValidTokensObservable(),
                connectionStateObservable(),
                (ignore1, ignore2, s) -> s)
                .filter(state -> !state.isRunning()
                        || (state.isRunning() && state.connectionData().isConnected()))
                .map(PsiCashIntent.LoadVideoAd::create)
                .subscribe(intentsPublishRelay);

    }

    private Observable<Boolean> hasValidTokensObservable() {
        return Observable.fromCallable(() -> PsiCashClient.getInstance(getContext()).hasValidTokens())
                .doOnError(err -> Log.d("PsiCash", this.getClass().getSimpleName() + err))
                .onErrorResumeNext(Observable.just(Boolean.FALSE))
                .filter(s -> s);
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

    private void unbind() {
        compositeDisposable.clear();
    }

    private Observable<TunnelState> connectionStateObservable() {
        return tunnelConnectionStateBehaviorRelay
                .hide()
                .distinctUntilChanged();
    }

    @Override
    public Observable<PsiCashIntent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        Log.d(TAG, "render: " + state);
        updateUiBalanceLabel(state);
        updateUiBuyButton(state);
        updateUiProgressView(state);
        updateUiPsiCashErrorMessage(state);
        updateUiRewardedVideoButton(state);
    }

    private void updateUiRewardedVideoButton(PsiCashViewState state) {
        shouldAutoLoadNextVideo = state.shouldAutoLoadVideoOnNextForeground();
        if(state.videoInFlight()) {
            loadWatchRewardedVideoBtn.setText("loading");
            loadWatchRewardedVideoBtn.setEnabled(false);
            return;
        }
        if(state.videoError() != null) {
            // reset autoPlay state if error
            shouldAutoPlay = false;
            loadWatchRewardedVideoBtn.setText(state.videoError().getMessage());
            loadWatchRewardedVideoBtn.setEnabled(false);
            return;
        }

        Runnable videoPlayRunnable = state.videoPlayRunnable();
        if (videoPlayRunnable != null) {
            if(shouldAutoPlay) {
                shouldAutoPlay = false;
                videoPlayRunnable.run();
            } else {
                loadWatchRewardedVideoBtn.setEnabled(true);
                loadWatchRewardedVideoBtn.setText("Video is ready");
                loadWatchRewardedVideoBtn.setOnClickListener(view -> videoPlayRunnable.run());
            }
        }
    }

    private void updateUiPsiCashErrorMessage(PsiCashViewState state) {
        Throwable error = state.psiCashError();
        if (error != null) {
            String errorMessage;

            if (error instanceof PsiCashException) {
                PsiCashException e = (PsiCashException) error;
                errorMessage = e.getUIMessage();
            } else {
                // TODO: log and show 'unknown error' to the user
                errorMessage = error.toString();
            }

            // Clear view state error immediately
            intentsPublishRelay.accept(PsiCashIntent.ClearErrorState.create());
            View view = getActivity().findViewById(R.id.psicashTab);
            if (view == null) {
                return;
            }
            Snackbar.make(view, errorMessage, Snackbar.LENGTH_LONG).show();
        }
    }

    private void updateUiProgressView(PsiCashViewState state) {
        if (state.purchaseInFlight() || state.videoInFlight()) {
            progressBar.setVisibility(View.VISIBLE);
        } else {
            progressBar.setVisibility(View.INVISIBLE);
        }
    }

    private void updateUiBuyButton(PsiCashViewState state) {
        PsiCashLib.PurchasePrice purchasePrice = state.purchasePrice();
        buySpeedBoostBtn.setTag(R.id.speedBoostPrice, purchasePrice);
        buySpeedBoostBtn.setEnabled(!state.purchaseInFlight());
        Date nextPurchaseExpiryDate = state.nextPurchaseExpiryDate();
        if (nextPurchaseExpiryDate != null && new Date().before(nextPurchaseExpiryDate)) {
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
            buySpeedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, true);
        } else {
            buySpeedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, false);
            if(purchasePrice != null && purchasePrice.price != 0) {
                if (purchasePrice.price > state.uiBalance() * 1e9) {
                    buySpeedBoostBtn.setText(String.format(Locale.US, "%d%s", state.uiBalance(), "%"));
                    buySpeedBoostBtn.setOnTouchListener((view, motionEvent) -> {
                        ObjectAnimator
                                .ofFloat(view, "translationX", 0, 25, -25, 25, -25,15, -15, 6, -6, 0)
                                .setDuration(500)
                                .start();
                        return true;
                    });
                } else {
                    buySpeedBoostBtn.setText("Speed Boost Ready");
                    buySpeedBoostBtn.setOnTouchListener((view, motionEvent) -> false);
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


        TextView floatingDeltaTextView = new TextView(getContext());
        floatingDeltaTextView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        floatingDeltaTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        floatingDeltaTextView.setText(String.format(Locale.US, "%s%d", balanceDelta > 0 ? "+" : "", balanceDelta));

        ViewGroup rootView = getActivity().findViewById(android.R.id.content);

        Rect offsetViewBounds = new Rect();
        balanceLabel.getDrawingRect(offsetViewBounds);
        rootView.offsetDescendantRectToMyCoords(balanceLabel, offsetViewBounds);

        int relativeTop = offsetViewBounds.top;

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, relativeTop, 0, 0);
        lp.gravity = Gravity.CENTER_HORIZONTAL;

        rootView.addView(floatingDeltaTextView, lp);

        floatingDeltaTextView.animate()
                .scaleX(3f).scaleY(3f)
                .alpha(0f)
                .setDuration(2000)
                .translationY(-100f)
                .translationX(50f)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation, boolean isReverse) {
                        rootView.removeView(floatingDeltaTextView);
                    }
                })
                .start();

        // update view's current balance value
        currentUiBalance = state.uiBalance();
    }

    private void startActiveSpeedBoostCountDown(long millisDiff) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownTimer = new CountDownTimer(millisDiff, 1000) {
            @Override
            public void onTick(long l) {
                // TODO: use default locale?
                String hms = String.format(Locale.US, "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(l),
                        TimeUnit.MILLISECONDS.toMinutes(l) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(l)),
                        TimeUnit.MILLISECONDS.toSeconds(l) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(l)));
                buySpeedBoostBtn.setText(hms);
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
        subscriptionStatusPublishRelay.accept(status);
    }
}