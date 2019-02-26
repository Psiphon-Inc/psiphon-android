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

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
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
import com.psiphon3.psicash.util.TunnelConnectionState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class RewardedVideoFragment extends Fragment implements MviView<Intent, RewardedVideoViewState> {
    enum LoadVideoIntent {LOAD_VIDEO_INTENT};

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button watchRewardedVideoBtn;
    private Relay<TunnelConnectionState> tunnelConnectionStateBehaviourRelay;
    private Relay<LoadVideoIntent> loadVideoActionPublishRelay;

    private RewardedVideoViewModel rewardedVideoViewModel;

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

        tunnelConnectionStateBehaviourRelay = BehaviorRelay.<TunnelConnectionState>create().toSerialized();
        loadVideoActionPublishRelay = PublishRelay.<LoadVideoIntent>create().toSerialized();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        RewardedVideoClient.getInstance().initWithActivity(getActivity());
        watchRewardedVideoBtn = getActivity().findViewById(R.id.watch_rewardedvideo_btn);
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
        return Observable.combineLatest(
                hasValidTokensObservable(),
                loadVideoActionPublishObservable()
                        .startWith(LoadVideoIntent.LOAD_VIDEO_INTENT),
                connectionStateObservable(),
                (ignore1, ignore2, s) -> Intent.LoadVideoAd.create(s));
    }

    private Observable<LoadVideoIntent> loadVideoActionPublishObservable() {
        return loadVideoActionPublishRelay.hide();
    }

    private Observable<Boolean> hasValidTokensObservable() {
        return Observable.fromCallable(() -> PsiCashClient.getInstance(getContext()).hasValidTokens())
                .doOnError(err -> Log.d("PsiCash", this.getClass().getSimpleName() + err))
                .onErrorResumeNext(Observable.just(Boolean.FALSE))
                .filter(s -> s);
    }

    private Observable<TunnelConnectionState> connectionStateObservable() {
        return tunnelConnectionStateBehaviourRelay
                .hide()
                .distinctUntilChanged();
    }

    @Override
    public void render(RewardedVideoViewState state) {
        Runnable videoPlayRunnable = state.videoPlayRunnable();
        watchRewardedVideoBtn.setEnabled(videoPlayRunnable != null);
        watchRewardedVideoBtn.setOnClickListener(view -> {
            if (videoPlayRunnable != null) {
                videoPlayRunnable.run();
            }
        });
    }

    private void sendGotRewardForVideoIntent() {
        android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
    }

    public void onTunnelConnectionState(TunnelConnectionState status) {
        tunnelConnectionStateBehaviourRelay.accept(status);
    }
}
