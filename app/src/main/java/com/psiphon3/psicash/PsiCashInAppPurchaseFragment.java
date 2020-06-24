package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.Scene;
import androidx.transition.TransitionManager;

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.Currency;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashInAppPurchaseFragment extends Fragment {
    private PsiCashViewModel psiCashViewModel;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Scene sceneBuyPsiCashFromPlayStore;
    private Scene sceneConnectToFinishPsiCashPurchase;
    private Scene sceneWaitFinishPsiCashPurchase;
    private Disposable purchaseDisposable;
    private RewardedVideoHelper rewardedVideoHelper;
    private Disposable rewardedVideoDisposable;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        final Context ctx = getContext();
        psiCashViewModel = new ViewModelProvider(getActivity()).get(PsiCashViewModel.class);
        rewardedVideoHelper = new RewardedVideoHelper(getActivity(), amount -> {
            // Store the reward amount and refresh PsiCash view state when video is rewarded.
            try {
                PsiCashClient.getInstance(ctx).putVideoReward(amount);
                psiCashViewModel.processIntents(Observable.just(PsiCashIntent.GetPsiCash.create()));
            } catch (PsiCashException ignored) {
            }
        });

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        ViewGroup sceneRoot = view.findViewById(R.id.scene_root);
        View progressOverlay = view.findViewById(R.id.progress_overlay);

        sceneBuyPsiCashFromPlayStore = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_buy_from_playstore_scene, ctx);
        sceneConnectToFinishPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_connect_to_finish_purchase_scene, ctx);
        sceneWaitFinishPsiCashPurchase = Scene.getSceneForLayout(sceneRoot, R.layout.psicash_wait_to_finish_purchase_scene, ctx);

        sceneBuyPsiCashFromPlayStore.setEnterAction(() -> {
            LinearLayout containerLayout = sceneBuyPsiCashFromPlayStore.getSceneRoot().findViewById(R.id.psicash_purchase_options_container);

            // Set up the rewarded video FREE row
            final View rewardedVideoRowView = inflater.inflate(R.layout.psicash_purchase_template, container, false);
            // Show the PsiCash amount per rewarded video
            ((TextView) rewardedVideoRowView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(R.string.psicash_purchase_free_amount);

            // Show "FREE" label on the button that loads and plays the video
            final Button videoBtn = rewardedVideoRowView.findViewById(R.id.psicash_purchase_sku_item_price);
            videoBtn.setEnabled(false);
            videoBtn.setText(R.string.psicash_purchase_free_button_price);

            // Make sure the progressbar over the button is not visible
            final ProgressBar videoProgress = rewardedVideoRowView.findViewById(R.id.progress_bar_over_sku_button);
            videoProgress.setVisibility(View.GONE);

            containerLayout.addView(rewardedVideoRowView);

            // Enable the rewarded video button only when tunnel is stopped, otherwise display
            // "Videos available only when the Psiphon is not running" and disable the button.
            compositeDisposable.add(psiCashViewModel.tunnelStateFlowable()
                    // Only react to distinct tunnel states that are not UNKNOWN
                    .filter(tunnelState -> !tunnelState.isUnknown())
                    .distinctUntilChanged()
                    .doOnNext(tunnelState -> {
                        if (tunnelState.isStopped()) {
                            videoBtn.setEnabled(true);
                            ((TextView) rewardedVideoRowView
                                    .findViewById(R.id.psicash_purchase_sku_item_description))
                                    .setText(R.string.psicash_purchase_free_description);
                            final AtomicBoolean shouldAutoPlayVideo = new AtomicBoolean(true);
                            Completable btnClickCompletable = Completable.create(emitter -> {
                                if (!emitter.isDisposed()) {
                                    videoBtn.setOnClickListener(v -> emitter.onComplete());
                                }
                            }).observeOn(AndroidSchedulers.mainThread());
                            if (rewardedVideoDisposable == null || rewardedVideoDisposable.isDisposed()) {
                                // Trigger first ad loading with a button click
                                rewardedVideoDisposable =
                                        btnClickCompletable.andThen(
                                                rewardedVideoHelper
                                                        .getVideoObservable(psiCashViewModel.tunnelStateFlowable())
                                                        // Keep loading ads if getVideoObservable completes normally
                                                        // which is after the ad is closed.
                                                        // In case of ad load error set UI to 'disabled' state and
                                                        // complete this subscription with onErrorResumeNext()
                                                        .repeat()
                                        )
                                                .doOnError(err -> {
                                                    videoBtn.setEnabled(false);
                                                    videoProgress.setVisibility(View.GONE);
                                                    videoBtn.setOnClickListener(null);
                                                    Toast toast = Toast.makeText(getActivity(),
                                                            ((PsiCashException.Video) err).getUIMessage(ctx), Toast.LENGTH_SHORT);
                                                    positionToast(toast, rewardedVideoRowView, getActivity().getWindow());
                                                    toast.show();
                                                })
                                                // Complete in case of error
                                                .onErrorResumeNext(Observable.empty())
                                                .doOnNext(rewardedVideoPlayable -> {
                                                    final Activity activity = getActivity();
                                                    if (activity != null && !activity.isFinishing()) {
                                                        switch (rewardedVideoPlayable.state()) {
                                                            case READY:
                                                                videoProgress.setVisibility(View.GONE);
                                                                if (shouldAutoPlayVideo.compareAndSet(true, false)) {
                                                                    rewardedVideoPlayable.play(activity);
                                                                } else {
                                                                    videoBtn.setEnabled(true);
                                                                    videoBtn.setOnClickListener(v ->
                                                                            rewardedVideoPlayable.play(activity));
                                                                }
                                                                break;
                                                            case LOADING:
                                                                videoProgress.setVisibility(View.VISIBLE);
                                                                videoBtn.setEnabled(false);
                                                                videoBtn.setOnClickListener(null);
                                                                break;
                                                            default:
                                                                videoProgress.setVisibility(View.GONE);
                                                                videoBtn.setEnabled(false);
                                                                videoBtn.setOnClickListener(null);
                                                                break;
                                                        }
                                                    }
                                                })
                                                .subscribe();
                                compositeDisposable.add(rewardedVideoDisposable);
                            }
                        } else {
                            ((TextView) rewardedVideoRowView
                                    .findViewById(R.id.psicash_purchase_sku_item_description))
                                    .setText(R.string.psicash_purchase_free_not_available_not_running_description);
                            videoBtn.setEnabled(false);
                            videoBtn.setOnClickListener(null);
                            if (rewardedVideoDisposable != null && !rewardedVideoDisposable.isDisposed()) {
                                rewardedVideoDisposable.dispose();
                            }
                        }
                    })
                    .subscribe());

            compositeDisposable.add(psiCashViewModel.getPsiCashSkus()
                    .doOnSuccess(skuDetailsList -> {
                        progressOverlay.setVisibility(View.GONE);
                        Collections.sort(skuDetailsList, (skuDetails1, skuDetails2) -> {
                            if (skuDetails1.getPriceAmountMicros() > skuDetails2.getPriceAmountMicros()) {
                                return 1;
                            } else if (skuDetails1.getPriceAmountMicros() < skuDetails2.getPriceAmountMicros()) {
                                return -1;
                            } else {
                                return 0;
                            }
                        });

                        NumberFormat nf = NumberFormat.getInstance();

                        for (SkuDetails skuDetails : skuDetailsList) {
                            int itemValue = 0;
                            try {
                                itemValue = GooglePlayBillingHelper.IAB_PSICASH_SKUS_TO_VALUE.get(skuDetails.getSku());
                            } catch (NullPointerException e) {
                                Utils.MyLog.g("PsiCashStoreActivity: error getting price for sku: " + skuDetails.getSku());
                                continue;
                            }
                            String itemTitle = nf.format(itemValue);
                            final View psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, container, false);

                            ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_title)).setText(itemTitle);
                            ((TextView) psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_description)).setText(skuDetails.getDescription());

                            final Button btn = psicashPurchaseItemView.findViewById(R.id.psicash_purchase_sku_item_price);
                            final ProgressBar progress = psicashPurchaseItemView.findViewById(R.id.progress_bar_over_sku_button);

                            // skuDetails.getPrice() returns a differently looking string than the one
                            // we get by using priceFormatter below, so for consistency we'll use
                            // priceFormatter everywhere.
                            // If the formatting for priceText fails, use skuDetails.getPrice() as default
                            String priceText = skuDetails.getPrice();
                            try {
                                Currency currency = Currency.getInstance(skuDetails.getPriceCurrencyCode());
                                NumberFormat priceFormatter = NumberFormat.getCurrencyInstance();
                                priceFormatter.setCurrency(currency);
                                priceText = priceFormatter.format(skuDetails.getPriceAmountMicros() / 1000000.0f);
                            } catch (IllegalArgumentException e) {
                                // do nothing
                            }
                            btn.setText(priceText);
                            btn.setOnClickListener(v -> {
                                if (purchaseDisposable == null || purchaseDisposable.isDisposed()) {
                                    final Activity activity = getActivity();
                                    if (activity.hasWindowFocus() && !activity.isFinishing()) {
                                        purchaseDisposable =
                                                GooglePlayBillingHelper.getInstance(activity.getApplicationContext())
                                                        .launchFlow(activity, skuDetails)
                                                        .doOnError(err -> {
                                                            Toast toast = Toast.makeText(getActivity(),
                                                                    R.string.psicash_purchase_not_available_error_message,
                                                                    Toast.LENGTH_SHORT);
                                                            positionToast(toast, psicashPurchaseItemView, getActivity().getWindow());
                                                            toast.show();
                                                        })
                                                        .onErrorComplete()
                                                        .doOnSubscribe(__ -> {
                                                            btn.setEnabled(false);
                                                            progress.setVisibility(View.VISIBLE);
                                                        })
                                                        .doOnComplete(() -> {
                                                            btn.setEnabled(true);
                                                            progress.setVisibility(View.GONE);
                                                        })
                                                        .subscribe();
                                        compositeDisposable.add(purchaseDisposable);
                                    }
                                }
                            });

                            containerLayout.addView(psicashPurchaseItemView);
                        }
                    })
                    .subscribe());
        });

        sceneConnectToFinishPsiCashPurchase.setEnterAction(
                () -> {
                    progressOverlay.setVisibility(View.GONE);
                    Button connectBtn = view.findViewById(R.id.connect_psiphon_btn);
                    connectBtn.setOnClickListener(v -> {
                        final Activity activity = getActivity();
                        try {
                            Intent data = new Intent(PsiCashStoreActivity.PSICASH_CONNECT_PSIPHON_INTENT);
                            activity.setResult(Activity.RESULT_OK, data);
                            activity.finish();
                        } catch (NullPointerException ignored) {
                        }
                    });
                });

        sceneWaitFinishPsiCashPurchase.setEnterAction(
                () -> progressOverlay.setVisibility(View.GONE));

        compositeDisposable.add(psiCashViewModel.purchaseListFlowable()
                .switchMap(purchaseList -> {
                            for (Purchase purchase : purchaseList) {
                                if (GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                                    return psiCashViewModel.tunnelStateFlowable()
                                            .filter(state -> !state.isUnknown())
                                            .distinctUntilChanged()
                                            .map(tunnelState ->
                                                    tunnelState.isStopped() ?
                                                            SceneState.CONNECT_TO_FINISH :
                                                            SceneState.WAIT_TO_FINISH);
                                }
                            }
                            return Flowable.just(SceneState.BUY_FROM_PLAYSTORE);
                        }
                )
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(sceneState -> {
                    if (sceneState == SceneState.CONNECT_TO_FINISH)
                        TransitionManager.go(sceneConnectToFinishPsiCashPurchase);
                    else if (sceneState == SceneState.WAIT_TO_FINISH) {
                        TransitionManager.go(sceneWaitFinishPsiCashPurchase);
                    } else if (sceneState == SceneState.BUY_FROM_PLAYSTORE) {
                        TransitionManager.go(sceneBuyPsiCashFromPlayStore);
                    }
                })
                .subscribe());

        return view;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    public static void positionToast(Toast toast, View view, Window window) {
        Rect rect = new Rect();
        window.getDecorView().getWindowVisibleDisplayFrame(rect);
        int[] viewLocation = new int[2];
        view.getLocationInWindow(viewLocation);
        int viewLeft = viewLocation[0] - rect.left;
        int viewTop = viewLocation[1] - rect.top;

        DisplayMetrics metrics = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.UNSPECIFIED);
        int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.UNSPECIFIED);
        toast.getView().measure(widthMeasureSpec, heightMeasureSpec);
        int toastWidth = toast.getView().getMeasuredWidth();
        int toastHeight = toast.getView().getMeasuredHeight();

        int toastX = viewLeft + (view.getWidth() - toastWidth) / 2;
        int toastY = (viewTop + view.getHeight() / 2) - toastHeight / 2;

        toast.setGravity(Gravity.START | Gravity.TOP, toastX, toastY);
    }

    private enum SceneState {
        WAIT_TO_FINISH, BUY_FROM_PLAYSTORE, CONNECT_TO_FINISH
    }
}
