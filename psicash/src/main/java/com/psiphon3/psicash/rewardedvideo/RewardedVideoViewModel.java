package com.psiphon3.psicash.rewardedvideo;

import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.support.annotation.NonNull;
import android.util.Log;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.mvibase.MviAction;
import com.psiphon3.psicash.mvibase.MviIntent;
import com.psiphon3.psicash.mvibase.MviResult;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psicash.mvibase.MviViewModel;
import com.psiphon3.psicash.mvibase.MviViewState;
import com.psiphon3.psicash.util.TunnelConnectionStatus;

import io.reactivex.Observable;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.schedulers.Schedulers;

public class RewardedVideoViewModel extends AndroidViewModel implements MviViewModel {
    private static final String TAG = "PsiCashVideoViewModel";
    /**
     * The Reducer is where {@link MviViewState}, that the {@link MviView} will use to
     * render itself, are created.
     * It takes the last cached {@link MviViewState}, the latest {@link MviResult} and
     * creates a new {@link MviViewState} by only updating the related fields.
     * This is basically like a big switch statement of all possible types for the {@link MviResult}
     */

    private static BiFunction<RewardedVideoViewState, Result, RewardedVideoViewState> reducer =
            (previousState, result) -> {
                Log.d(TAG, "reducer: result: " + result);
                RewardedVideoViewState.Builder stateBuilder = previousState.withLastState();
                if (result instanceof Result.VideoReady) {
                    Result.VideoReady loadResult = (Result.VideoReady) result;
                    switch (loadResult.status()) {
                        case SUCCESS:
                            return stateBuilder
                                    .videoPlayRunnable(loadResult.model().videoPlayRunnable())
                                    .build();
                        case FAILURE:
                            return stateBuilder
                                    .videoPlayRunnable(null)
                                    .build();
                        case IN_FLIGHT:
                            return stateBuilder
                                    .videoPlayRunnable(null)
                                    .build();
                    }

                } else if (result instanceof Result.Reward) {
                    return stateBuilder
                            .addViewAction(RewardedVideoViewState.ViewAction.REWARD_ACTION)
                            .build();
                } else if (result instanceof Result.VideoClosed) {
                    return stateBuilder
                            .addViewAction(RewardedVideoViewState.ViewAction.LOAD_VIDEO_ACTION)
                            .build();
                }
                throw new IllegalArgumentException("Don't know how to reduce result: " + result);
            };
    private Observable<RewardedVideoViewState> rewardedVideoViewStateObservable;
    private CompositeDisposable compositeDisposable;
    private PublishRelay<Intent> intentPublishRelay;
    @NonNull
    private RewardedVideoActionProcessorHolder rewardedVideoActionProcessorHolder;

    public RewardedVideoViewModel(@NonNull Application application) {
        super(application);

        rewardedVideoActionProcessorHolder = new RewardedVideoActionProcessorHolder(application);
        intentPublishRelay = PublishRelay.create();
        rewardedVideoViewStateObservable = compose();
        compositeDisposable = new CompositeDisposable();
    }

    private Observable<RewardedVideoViewState> compose() {
        return intentPublishRelay
                .observeOn(Schedulers.computation())
                // Translate intents to actions, some intents may map to multiple actions
                .flatMap(this::actionFromIntent)
                .compose(rewardedVideoActionProcessorHolder.actionProcessor)
                // Cache each state and pass it to the reducer to create a new state from
                // the previous cached one and the latest Result emitted from the action processor.
                // The Scan operator is used here for the caching.
                .scan(RewardedVideoViewState.idle(), reducer)
                // When a reducer just emits previousState, there's no reason to call render. In fact,
                // redrawing the UI in cases like this can cause jank (e.g. messing up snackbar animations
                // by showing the same snackbar twice in rapid succession).
                .distinctUntilChanged()
                // Emit the last one event of the stream on subscription
                // Useful when a View rebinds to the RewardedVideoViewModel after rotation.
                .replay(1)
                // Create the stream on creation without waiting for anyone to subscribe
                // This allows the stream to stay alive even when the UI disconnects and
                // match the stream's lifecycle to the RewardedVideoViewModel's one.
                .autoConnect(0);
    }

    /**
     * Translate an {@link MviIntent} to an {@link MviAction}.
     * Used to decouple the UI and the business logic to allow easy testings and reusability.
     */
    private Observable<Action> actionFromIntent(MviIntent intent) {
        if (intent instanceof Intent.LoadVideoAd) {
            Intent.LoadVideoAd i = (Intent.LoadVideoAd) intent;
            final TunnelConnectionStatus status = i.connectionStatus();
            return Observable.just(Action.LoadVideoAd.create(status));
        }
        throw new IllegalArgumentException("Do not know how to treat this intent " + intent);
    }

    @Override
    public void processIntents(Observable intents) {
        compositeDisposable.clear();
        compositeDisposable.add(intents
                .doOnNext(s -> Log.d(TAG, "processIntent: " + s))
                .doOnError(err -> Log.d(TAG, "processIntent error: " + err))
                .subscribe(intentPublishRelay));
    }

    @Override
    public Observable<RewardedVideoViewState> states() {
        return rewardedVideoViewStateObservable;
    }

}
