package com.psiphon3;

import android.arch.lifecycle.ViewModelProviders;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jakewharton.rxbinding2.view.RxView;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.psicash.Intent;
import com.psiphon3.psicash.psicash.PsiCashClient;
import com.psiphon3.psicash.psicash.PsiCashError;
import com.psiphon3.psicash.psicash.ExpiringPurchaseListener;
import com.psiphon3.psicash.psicash.PsiCashViewModel;
import com.psiphon3.psicash.psicash.PsiCashViewModelFactory;
import com.psiphon3.psicash.psicash.PsiCashViewState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.subscription.R;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class PsiCashFragment extends Fragment implements MviView<Intent, PsiCashViewState> {
    private static final String TAG = "PsiCashFragment";
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashViewModel psiCashViewModel;

    private Relay<Intent> intentsPublishRelay;
    private Relay<TunnelConnectionStatus> tunnelConnectionStatusBehaviourRelay;

    private TextView balanceLabel;
    private TextView countDownLabel;
    private Button buySpeedBoostBtn;
    private CountDownTimer countDownTimer;
    private ProgressBar purchaseProgress;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.psicash_fragment, container, false);
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ExpiringPurchaseListener purchaseListener = (context, purchase) -> {
            // Store authorization from the purchase
            Authorization authorization = Authorization.fromBase64Encoded(purchase.authorization);
            Authorization.storeAuthorization(context, authorization);

            // Send broadcast to restart the tunnel
            android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
            LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
        };

        psiCashViewModel = ViewModelProviders.of(this, new PsiCashViewModelFactory(getActivity().getApplication(), purchaseListener))
                .get(PsiCashViewModel.class);
        intentsPublishRelay = PublishRelay.<Intent>create().toSerialized();
        tunnelConnectionStatusBehaviourRelay = BehaviorRelay.<TunnelConnectionStatus>create().toSerialized();

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
        balanceLabel = getActivity().findViewById(R.id.balance_label);
        countDownLabel = getActivity().findViewById(R.id.countdown_label);
    }

    @Override
    public void onResume() {
        removeExpiredPurchases();
        super.onResume();
        bind();
    }

    @Override
    public void onPause() {
        super.onPause();
        unbind();
    }

    private void bind() {
        compositeDisposable.clear();

        // Subscribe to the RewardedVideoViewModel and render every emitted state
        compositeDisposable.add(psiCashViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render));
        // Pass the UI's intents to the RewardedVideoViewModel
        psiCashViewModel.processIntents(intents());


        compositeDisposable.add(RxView.clicks(buySpeedBoostBtn)
                .debounce(200, TimeUnit.MILLISECONDS)
                .withLatestFrom(connectionStatusObservable(), (__, s) ->
                        Intent.PurchaseSpeedBoost.create(s.connectionStatus(), (PsiCashLib.PurchasePrice) buySpeedBoostBtn.getTag()))
                .subscribe(intentsPublishRelay));

        compositeDisposable.add(connectionStatusObservable()
                .subscribe(intentsPublishRelay));

    }

    private void unbind() {
        compositeDisposable.clear();
    }

    private Observable<Intent.ConnectionStatus> connectionStatusObservable() {
        return tunnelConnectionStatusBehaviourRelay.hide()
                .map(s -> Intent.ConnectionStatus.create(s));
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action == BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT) {
                    intentsPublishRelay.accept(Intent.GetPsiCashLocal.create());
                }
            }
        }
    };

    @Override
    public Observable intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {

        Date nextPurchaseExpiryDate = state.nextPurchaseExpiryDate();
        if (nextPurchaseExpiryDate != null && new Date().before(nextPurchaseExpiryDate)) {
            long millisDiff = nextPurchaseExpiryDate.getTime() - new Date().getTime();
            startActiveSpeedBoostCountDown(millisDiff);
        } else {
            countDownLabel.setVisibility(View.GONE);
        }

        PsiCashLib.PurchasePrice purchasePrice = state.purchasePrice();
        buySpeedBoostBtn.setTag(purchasePrice);

        Log.d(TAG, "render: " + state);

        // TODO current locale?
        balanceLabel.setText(String.format(Locale.US, "Balance: %.2f | Reward: %.2f",
                state.balance() / 1e9,
                (float)state.reward()));

        if (state.purchaseInFlight()) {
            purchaseProgress.setVisibility(View.VISIBLE);
        } else {
            purchaseProgress.setVisibility(View.INVISIBLE);
        }

        buySpeedBoostBtn.setEnabled(!state.purchaseInFlight());


        Throwable error = state.error();
        if (error != null) {
            String errorMessage;

            if (error instanceof PsiCashError) {
                PsiCashError e = (PsiCashError) error;
                errorMessage = e.getUIMessage();
            } else {
                // TODO: log and show 'unknown error' to the user
                errorMessage = error.toString();
            }

            uiNotification(errorMessage);
        }
    }

    private void startActiveSpeedBoostCountDown(long millisDiff) {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        countDownLabel.setVisibility(View.VISIBLE);
        buySpeedBoostBtn.setEnabled(false);

        countDownTimer = new CountDownTimer(millisDiff, 1000) {
            @Override
            public void onTick(long l) {
                long millis = l;
                // TODO: use default locale?
                String hms = String.format(Locale.US, "%02d:%02d:%02d", TimeUnit.MILLISECONDS.toHours(millis),
                        TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis)),
                        TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)));
                countDownLabel.setText(hms);
            }

            @Override
            public void onFinish() {
                countDownLabel.setVisibility(View.INVISIBLE);
                buySpeedBoostBtn.setEnabled(true);
                // update local state
                intentsPublishRelay.accept(Intent.GetPsiCashLocal.create());
            }
        }.start();
    }

    private void uiNotification(String message) {
        View view = getView();
        if (view == null) return;
        Snackbar.make(view, message, Snackbar.LENGTH_LONG).show();

        // clear view state error immediately
        intentsPublishRelay.accept(Intent.ClearErrorState.create());
    }

    private void removeExpiredPurchases() {
        List<String> purchasesToRemove = Authorization.getRemovedSpeedBoostAuthorizationIds(getContext());
        PsiCashClient.getInstance(getContext()).removePurchases(purchasesToRemove);
        Authorization.clearRemovedSpeedBoostAuthorizationIds(getContext());
    }



    public void onTunnelConnectionStatus(TunnelConnectionStatus status) {
        tunnelConnectionStatusBehaviourRelay.accept(status);
    }
}