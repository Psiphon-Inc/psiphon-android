package com.psiphon3;

import android.arch.lifecycle.ViewModelProviders;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.rewardedvideo.Intent;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoViewModel;
import com.psiphon3.psicash.rewardedvideo.RewardedVideoViewState;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psicash.util.TunnelConnectionStatus;
import com.psiphon3.subscription.R;

import java.util.List;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class RewardedVideoFragment extends Fragment implements MviView<Intent, RewardedVideoViewState> {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button watchRewardedVideoBtn;
    private Relay<TunnelConnectionStatus> tunnelConnectionStatusBehaviourRelay;
    private Relay<RewardedVideoViewState.ViewAction> loadVideoActionPublishRelay;

    private RewardedVideoViewModel viewModel;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.rewarded_video_fragment, container, false);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = ViewModelProviders.of(this).get(RewardedVideoViewModel.class);

        tunnelConnectionStatusBehaviourRelay = BehaviorRelay.<TunnelConnectionStatus>create().toSerialized();
        loadVideoActionPublishRelay = PublishRelay.<RewardedVideoViewState.ViewAction>create().toSerialized();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
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
        compositeDisposable.add(viewModel.states()
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render));

        // Pass the UI's intents to the RewardedVideoViewModel
        viewModel.processIntents(intents());
    }

    private void unbind() {
        compositeDisposable.clear();
    }


    @Override
    public Observable<Intent> intents() {
        return Observable.combineLatest(loadVideoActionPublishObservable()
                        .startWith(RewardedVideoViewState.ViewAction.LOAD_VIDEO_ACTION)
                , connectionStatusObservable(),
                (__, s) -> Intent.LoadVideoAd.create(s));
    }

    private Observable<RewardedVideoViewState.ViewAction> loadVideoActionPublishObservable() {
        return loadVideoActionPublishRelay.hide();
    }

    private Observable<TunnelConnectionStatus> connectionStatusObservable() {
        return tunnelConnectionStatusBehaviourRelay.hide();
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

        List<RewardedVideoViewState.ViewAction> actions = state.viewActions();
        if (actions != null) {
            for (RewardedVideoViewState.ViewAction action : actions) {
                performAction(action);
            }
        }
    }

    private void performAction(RewardedVideoViewState.ViewAction action) {
        switch (action) {
            case REWARD_ACTION:
                sendGotRewardForVideoIntent();
                break;
            case LOAD_VIDEO_ACTION:
                loadVideoActionPublishRelay.accept(action);
                break;
            default:
                throw new IllegalArgumentException("Unknown PsiCashViewState.ViewAction: " + action);
        }
    }

    private void sendGotRewardForVideoIntent() {
        android.content.Intent intent = new android.content.Intent(BroadcastIntent.GOT_REWARD_FOR_VIDEO_INTENT);
        LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(intent);
    }

    public void onTunnelConnectionStatus(TunnelConnectionStatus status) {
        tunnelConnectionStatusBehaviourRelay.accept(status);
    }
}
