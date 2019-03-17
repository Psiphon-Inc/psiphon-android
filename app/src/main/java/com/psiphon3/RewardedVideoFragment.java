package com.psiphon3;

import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.jakewharton.rxbinding2.view.RxView;
import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.psicash.PsiCashClient;
import com.psiphon3.psicash.psicash.PsiCashException;
import com.psiphon3.psicash.rewardedvideo.Intent;
import com.psiphon3.psicash.rewardedvideo.RewardListener;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoClient;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoViewModel;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoViewModelFactory;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoViewState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class RewardedVideoFragment extends Fragment implements MviView<Intent, RewardedVideoViewState> {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button loadWatchRewardedVideoBtn;
    private BehaviorRelay<TunnelState> tunnelConnectionStateBehaviourRelay;

    private boolean shouldAutoLoadNextVideo = false;
    private RewardedVideoViewModel rewardedVideoViewModel;
    private boolean shouldAutoPlay;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.rewarded_video_fragment, container, false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RewardListener rewardListener = (context, reward) -> {
            try {
                // Store the reward amount
                PsiCashClient.getInstance(context).putVideoReward(reward);
                // Send broadcast to iPsiCash fragment to pull PsiCash local state
                android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT);
                LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
            } catch (PsiCashException e) {
                Utils.MyLog.g("Failed to store video reward: " + e);
            }
        };

        rewardedVideoViewModel = ViewModelProviders.of(this, new RewardedVideoViewModelFactory(getActivity().getApplication(), rewardListener))
                .get(RewardedVideoViewModel.class);

        tunnelConnectionStateBehaviourRelay = BehaviorRelay.create();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        RewardedVideoClient.getInstance().initWithActivity(getActivity());

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

        // Subscribe to the RewardedVideoViewModel and render every emitted state
        compositeDisposable.add(rewardedVideoViewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render));

        // Pass the UI's intents to the RewardedVideoViewModel
        rewardedVideoViewModel.processIntents(intents());
    }

    private void unbind() {
        compositeDisposable.clear();
    }

    @Override
    public Observable<Intent> intents() {
        return loadVideoIntent();
    }


    private Observable<Intent> loadVideoIntent() {
        shouldAutoPlay = false;
        final Observable <Object> loadVideo;

        if (shouldAutoLoadNextVideo){
            loadVideo = Observable.just(1);
        } else {
            loadVideo = RxView.clicks(loadWatchRewardedVideoBtn)
                    .debounce(200, TimeUnit.MILLISECONDS)
                    .doOnNext(__ -> shouldAutoPlay = true);
        }

        return Observable.combineLatest(
                loadVideo,
                hasValidTokensObservable(),
                connectionStateObservable(),
                (ignore1, ignore2, s) -> s)
                .filter(state -> !state.isRunning()
                        || (state.isRunning() && state.connectionData().isConnected()))
                .map(Intent.LoadVideoAd::create);

    }

    private Observable<Boolean> hasValidTokensObservable() {
        return Observable.fromCallable(() -> PsiCashClient.getInstance(getContext()).hasValidTokens())
                .doOnError(err -> Log.d("PsiCash", this.getClass().getSimpleName() + err))
                .onErrorResumeNext(Observable.just(Boolean.FALSE))
                .filter(s -> s);
    }

    private Observable<TunnelState> connectionStateObservable() {
        return tunnelConnectionStateBehaviourRelay
                .hide()
                .distinctUntilChanged();
    }

    @Override
    public void render(RewardedVideoViewState state) {
        shouldAutoLoadNextVideo = state.shouldAutoLoadOnNextForeground();

        if(state.inFlight()) {
            loadWatchRewardedVideoBtn.setText("in flight");
            loadWatchRewardedVideoBtn.setEnabled(false);
            return;
        }

        if(state.error() != null) {
            // reset autoPlay state if error
            shouldAutoPlay = false;
            loadWatchRewardedVideoBtn.setText(state.error().getMessage());
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

    public void onTunnelConnectionState(TunnelState status) {
        tunnelConnectionStateBehaviourRelay.accept(status);
    }
}
