package com.psiphon3;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.arch.lifecycle.ViewModelProviders;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.psicash.ExpiringPurchaseListener;
import com.psiphon3.psicash.psicash.Intent;
import com.psiphon3.psicash.psicash.PsiCashClient;
import com.psiphon3.psicash.psicash.PsiCashException;
import com.psiphon3.psicash.psicash.PsiCashViewModel;
import com.psiphon3.psicash.psicash.PsiCashViewModelFactory;
import com.psiphon3.psicash.psicash.PsiCashViewState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.TunnelState;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

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

public class PsiCashFragment extends Fragment implements MviView<Intent, PsiCashViewState> {
    private static final String TAG = "PsiCashFragment";
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashViewModel psiCashViewModel;

    private Relay<Intent> intentsPublishRelay;
    private Relay<TunnelState> tunnelConnectionStateBehaviorRelay;

    private TextView balanceLabel;
    private Button buySpeedBoostBtn;
    private CountDownTimer countDownTimer;
    private ProgressBar purchaseProgress;
    private int currentUiBalance;
    private boolean animateOnBalanceChange = false;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.psicash_fragment, container, false);
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ExpiringPurchaseListener expiringPurchaseListener = (context, purchase) -> {
            // Store authorization from the purchase
            Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization);
            Authorization.storeAuthorization(context, authorization);

            // Send broadcast to restart the tunnel
            android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
            LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
        };

        psiCashViewModel = ViewModelProviders.of(this, new PsiCashViewModelFactory(getActivity().getApplication(), expiringPurchaseListener))
                .get(PsiCashViewModel.class);
        intentsPublishRelay = PublishRelay.<Intent>create().toSerialized();
        tunnelConnectionStateBehaviorRelay = BehaviorRelay.<TunnelState>create().toSerialized();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).registerReceiver(broadcastReceiver, intentFilter);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        purchaseProgress = getActivity().findViewById(R.id.speedboost_purchase_progress);
        purchaseProgress.setIndeterminate(true);
        buySpeedBoostBtn = getActivity().findViewById(R.id.purchase_speedboost_btn);
        balanceLabel = getActivity().findViewById(R.id.psicash_balance_label);
        balanceLabel.setText("0");
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

        // Remove purchases when app is foregrounded and tunnel is not connected
        // or connection status changes from connected to disconnected
        compositeDisposable.add(removePurchasesDisposable());

        // Get PsiCash tokens when tunnel connects if there are none
        compositeDisposable.add(getPsiCashTokensDisposable());
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
                        Intent.PurchaseSpeedBoost.create(state,
                                (PsiCashLib.PurchasePrice) buySpeedBoostBtn.getTag(R.id.speedBoostPrice),
                                (boolean) buySpeedBoostBtn.getTag(R.id.hasActiveSpeedBoostTag)))
                .subscribe(intentsPublishRelay);
    }

    private Disposable getPsiCashLocalDisposable() {
        return Observable.just(Intent.GetPsiCashLocal.create())
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
                .map(Intent.GetPsiCashRemote::create)
                .subscribe(intentsPublishRelay);
    }

    private Disposable removePurchasesDisposable() {
        return connectionStateObservable()
                .filter(state -> !state.isRunning())
                .flatMap(__ -> {
                    List<String> purchasesToRemove = new ArrayList<>();
                    // remove purchases that are not in the authorizations database.
                    try {
                        // get all persisted authorizations as base64 encoded strings
                        List<String> authorizationsAsString = new ArrayList<>();
                        for (Authorization a : Authorization.geAllPersistedAuthorizations(getContext())) {
                            authorizationsAsString.add(a.base64EncodedAuthorization());
                        }

                        List<PsiCashLib.Purchase> purchases = PsiCashClient.getInstance(getContext()).getPurchases();
                        if(purchases.size() == 0) {
                            return Observable.empty();
                        }

                        // build a list of purchases to remove by cross referencing
                        // each purchase authorization against the authorization strings list
                        for (PsiCashLib.Purchase purchase : purchases) {
                            if (!authorizationsAsString.contains(purchase.authorization)) {
                                purchasesToRemove.add(purchase.id);
                            }
                        }
                        if (purchasesToRemove.size() > 0) {
                            return Observable.just(Intent.RemovePurchases.create(purchasesToRemove));
                        }
                    } catch (PsiCashException e) {
                        Utils.MyLog.g("Error removing expired purchases: " + e);
                    }
                    return Observable.empty();
                })
                .subscribe(intentsPublishRelay);
    }

    private void unbind() {
        compositeDisposable.clear();
    }

    private Observable<TunnelState> connectionStateObservable() {
        return tunnelConnectionStateBehaviorRelay
                .hide()
                .distinctUntilChanged();
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT)) {
                    intentsPublishRelay.accept(Intent.GetPsiCashLocal.create());
                    // TODO: sync balance with the server after some reasonable amount of time if tunnel is connected?
                }
            }
        }
    };

    @Override
    public Observable<Intent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        Log.d(TAG, "render: " + state);
        updateUiBalanceLabel(state);
        updateUiBuyButton(state);
        updateUiPurchaseProgress(state);
        updateUiErrorMessage(state);
    }

    private void updateUiErrorMessage(PsiCashViewState state) {
        Throwable error = state.error();
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
            intentsPublishRelay.accept(Intent.ClearErrorState.create());
            View view = getActivity().findViewById(R.id.psicashTab);
            if (view == null) {
                return;
            }
            Snackbar.make(view, errorMessage, Snackbar.LENGTH_LONG).show();
        }
    }

    private void updateUiPurchaseProgress(PsiCashViewState state) {
        if (state.purchaseInFlight()) {
            purchaseProgress.setVisibility(View.VISIBLE);
        } else {
            purchaseProgress.setVisibility(View.INVISIBLE);
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
                    buySpeedBoostBtn.setEnabled(false);
                    buySpeedBoostBtn.setText(String.format(Locale.US, "%d%s", state.uiBalance(), "%"));
                } else {
                    buySpeedBoostBtn.setEnabled(true);
                    buySpeedBoostBtn.setText("purchase speed boost");
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
                intentsPublishRelay.accept(Intent.GetPsiCashLocal.create());
            }
        }.start();
    }

    public void onTunnelConnectionState(TunnelState status) {
        tunnelConnectionStateBehaviorRelay.accept(status);
    }
}