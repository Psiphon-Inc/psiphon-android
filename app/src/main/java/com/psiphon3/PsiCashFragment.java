package com.psiphon3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.arch.lifecycle.ViewModelProviders;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
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
import java.util.concurrent.atomic.AtomicBoolean;

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
    private Relay<PsiphonAdManager.SubscriptionStatus> subscriptionStatusBehaviorRelay;


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
    private Disposable loadVideoAdsDisposable;
    private boolean shouldAutoPlayVideo;

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
        subscriptionStatusBehaviorRelay = BehaviorRelay.<PsiphonAdManager.SubscriptionStatus>create().toSerialized();
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
    }

    @Override
    public void onPause() {
        super.onPause();
        unbind();
        shouldAutoPlayVideo = false;
    }

    @Override
    public void onResume() {
        super.onResume();
        bind();
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

        // Load rewarded videos intent
        if (loadVideoAdsDisposable == null || loadVideoAdsDisposable.isDisposed()) {
            loadVideoAdsDisposable = loadVideoAdsSubscription();
        }

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
    
    private Disposable loadVideoAdsSubscription() {
        // Do not load ads if user is subscribed
        final Observable<PsiphonAdManager.SubscriptionStatus> isSubscriberObservable =
                subscriptionStatusBehaviorRelay
                        .hide()
                        .filter(s -> s == PsiphonAdManager.SubscriptionStatus.SUBSCRIBER);

        return RxView.clicks(loadWatchRewardedVideoBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .takeWhile(__ -> hasValidTokens())
                .takeUntil(isSubscriberObservable)
                .switchMap(__ -> {
                    keepLoadingVideos.set(true);
                    return connectionStateObservable()
                            .distinctUntilChanged()
                            // React to connection state changes until the load video process
                            // terminates with either success or error
                            .takeWhile(ignore -> keepLoadingVideos.get())
                            .map(PsiCashIntent.LoadVideoAd::create);
                })
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
        updateUiChargeBar(state);
        updateUiProgressView(state);
        updateUiPsiCashErrorMessage(state);
        updateUiRewardedVideoButton(state);
    }

    private void updateUiRewardedVideoButton(PsiCashViewState state) {
        if(state.videoIsLoading()) {
            // reset this flag if application enters background between
            // the LOADING and LOADED state.
            shouldAutoPlayVideo = true;
            loadWatchRewardedVideoBtn.setEnabled(false);
            return;
        }
        if(state.videoIsLoaded()) {
            // success or error should stop the load video subscription
            keepLoadingVideos.set(false);
            loadWatchRewardedVideoBtn.setEnabled(true);

            if (shouldAutoPlayVideo) {
                if(!RewardedVideoClient.getInstance().playRewardedVideo()) {
                    loadWatchRewardedVideoBtn.setText("Watch video & earn PsiCash");
                }
            } else {
                loadWatchRewardedVideoBtn.setText("Watch video & earn PsiCash");
            }
            return;
        }
        if(state.videoIsPlaying()) {
            loadWatchRewardedVideoBtn.setEnabled(false);
            return;
        }
        if(state.videoIsFinished()) {
            loadWatchRewardedVideoBtn.setEnabled(true);
            loadWatchRewardedVideoBtn.setText("Watch moar!");
            return;
        }
        if(state.videoError() != null) {
            // success or error should stop the load video subscription
            keepLoadingVideos.set(false);
            loadWatchRewardedVideoBtn.setText("No videos available, try again");
            loadWatchRewardedVideoBtn.setEnabled(true);
            return;
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
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
            psiCashChargeProgressTextView.setVisibility(View.INVISIBLE);
            buySpeedBoostBtn.setVisibility(View.VISIBLE);
            buySpeedBoostBtn.setTag(R.id.hasActiveSpeedBoostTag, true);
        } else {
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

                    psiCashChargeProgressTextView.setText(String.format(Locale.US, "Charging Speed Boost %d%s", chargePercentage, "%"));
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
                    buySpeedBoostBtn.setText("1h of speed boost available");
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
        subscriptionStatusBehaviorRelay.accept(status);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
        if (loadVideoAdsDisposable != null) {
            loadVideoAdsDisposable.dispose();
        }
    }
}