package com.psiphon3.psicash.store;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.SkuDetails;
import com.psiphon3.MainActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.PurchaseState;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.RewardedVideoHelper;
import com.psiphon3.psicash.account.PsiCashAccountActivity;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class AddPsiCashTabFragment extends Fragment {
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private View progressOverlay;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.psicash_store_tab_fragment, container, false);
        progressOverlay = view.findViewById(R.id.progress_overlay);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Flowable<TunnelState> tunnelStateFlowable = ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable();
        Flowable<List<Purchase>> purchaseListFlowable = GooglePlayBillingHelper.getInstance(requireContext())
                .purchaseStateFlowable()
                .map(PurchaseState::purchaseList)
                .distinctUntilChanged();

        compositeDisposable.add(purchaseListFlowable
                .switchMap(purchaseList -> {
                            for (Purchase purchase : purchaseList) {
                                if (GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                                    return tunnelStateFlowable
                                            .filter(state -> !state.isUnknown())
                                            .distinctUntilChanged()
                                            .map(tunnelState ->
                                                    tunnelState.isStopped() ?
                                                            SceneState.CONNECT_TO_FINISH :
                                                            SceneState.WAIT_TO_FINISH);
                                }
                            }
                            return Flowable.just(SceneState.PLAYSTORE);
                        }
                )
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(sceneState -> {
                    FragmentActivity activity = getActivity();
                    if (activity == null || activity.isFinishing() || !isAdded()) {
                        return;
                    }

                    FragmentTransaction transaction = getChildFragmentManager()
                            .beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);

                    switch (sceneState) {
                        case CONNECT_TO_FINISH:
                            transaction.replace(R.id.root_fragment_container, new ConnectToFinishPurchaseFragment());
                            break;
                        case WAIT_TO_FINISH:
                            transaction.replace(R.id.root_fragment_container, new WaitToFinishPurchaseFragment());
                            break;
                        case PLAYSTORE:
                            transaction.replace(R.id.root_fragment_container, new PlayStoreFragment());
                            break;
                        default:
                            throw new IllegalStateException(getClass().getName() + ": illegal sceneState: " + sceneState);

                    }

                    transaction.commitAllowingStateLoss();
                })
                .subscribe());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private void hideProgress() {
        if (progressOverlay != null) {
            progressOverlay.setVisibility(View.GONE);
        }
    }

    private enum SceneState {
        WAIT_TO_FINISH, PLAYSTORE, CONNECT_TO_FINISH
    }

    public static class PlayStoreFragment extends Fragment {
        private static final String NEXT_ACCOUNT_CREATE_ASK_TIME_MILLIS = "NEXT_ACCOUNT_CREATE_ASK_TIME_MILLIS";
        private final long TIME_1_WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000L;

        private final CompositeDisposable compositeDisposable = new CompositeDisposable();
        private final AtomicBoolean isAccount = new AtomicBoolean(false);
        private Disposable rewardedVideoDisposable;
        private Disposable purchaseDisposable;
        private ViewGroup noAccountDisclaimerView;

        public PlayStoreFragment() {
            super(R.layout.psicash_playstore_fragment);
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

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            noAccountDisclaimerView = view.findViewById(R.id.psicash_store_no_account_disclaimer);

            Flowable<TunnelState> tunnelStateFlowable = ((PsiCashStoreActivity) requireActivity()).tunnelStateFlowable();

            final PsiCashStoreViewModel psiCashViewModel = new ViewModelProvider(requireActivity(),
                    new ViewModelProvider.AndroidViewModelFactory(requireActivity().getApplication()))
                    .get(PsiCashStoreViewModel.class);

            RewardedVideoHelper rewardedVideoHelper = new RewardedVideoHelper(getActivity(), amount -> {
                // Store the reward amount and refresh PsiCash view state when video is rewarded.
                try {
                    PsiCashClient.getInstance(requireContext()).putVideoReward(amount);
                    psiCashViewModel.processIntents(Observable.just(PsiCashStoreIntent.GetPsiCash.create(tunnelStateFlowable)));
                } catch (PsiCashException ignored) {
                }
            });

            LayoutInflater inflater = LayoutInflater.from(requireContext());
            LinearLayout containerLayout = view.findViewById(R.id.psicash_purchase_options_container);

            // Set up the rewarded video FREE row
            final View rewardedVideoRowView = inflater.inflate(R.layout.psicash_purchase_template, null);
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

            // Show or hide the 'no account' disclaimer
            compositeDisposable.add(psiCashViewModel.states()
                    .filter(state -> !state.psiCashTransactionInFlight())
                    .filter(state -> state.psiCashModel() != null)
                    .map(PsiCashStoreViewState::isAccount)
                    .doOnNext(isAccount -> {
                        this.isAccount.set(isAccount);
                        noAccountDisclaimerView.setVisibility(isAccount ? View.GONE : View.VISIBLE);
                    })
                    .subscribe());

            // Enable the rewarded video button only when tunnel is stopped, otherwise display
            // "Videos available only when the Psiphon is not running" and disable the button.
            compositeDisposable.add(tunnelStateFlowable
                    // Only react to distinct tunnel states that are not UNKNOWN
                    .filter(tunnelState -> !tunnelState.isUnknown())
                    .distinctUntilChanged()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext(tunnelState -> {
                        if (tunnelState.isStopped()) {
                            videoBtn.setEnabled(true);
                            ((TextView) rewardedVideoRowView
                                    .findViewById(R.id.psicash_purchase_sku_item_description))
                                    .setText(R.string.psicash_purchase_free_description);
                            videoBtn.setOnClickListener(v -> {
                                if (rewardedVideoDisposable != null && !rewardedVideoDisposable.isDisposed()) {
                                    return;
                                }

                                final AtomicBoolean shouldAutoPlayVideo = new AtomicBoolean(true);
                                // Complete immediately if continueToSignInPromptSingle returns true
                                rewardedVideoDisposable = continueToSignInPromptSingle()
                                        .flatMapObservable(done -> done ?
                                                Observable.empty() :
                                                rewardedVideoHelper.getVideoObservable(tunnelStateFlowable)
                                                        // Keep loading ads if getVideoObservable completes normally
                                                        // which is after the ad is closed.
                                                        // In case of ad load error set UI to 'disabled' state and
                                                        // complete this subscription with onErrorResumeNext()
                                                        .repeat()
                                                        .doOnError(err -> {
                                                            videoBtn.setEnabled(false);
                                                            videoProgress.setVisibility(View.GONE);
                                                            videoBtn.setOnClickListener(null);
                                                            Toast toast = Toast.makeText(getActivity(),
                                                                    ((PsiCashException.Video) err).getUIMessage(requireContext()), Toast.LENGTH_SHORT);
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
                                                                            videoBtn.setOnClickListener(ignored ->
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
                                                        }))
                                        .subscribe();


                            });

/*
                                // Trigger first ad loading with a button click
                                rewardedVideoDisposable = rewardedVideoHelper.getVideoObservable(tunnelStateFlowable)
                                        // Keep loading ads if getVideoObservable completes normally
                                        // which is after the ad is closed.
                                        // In case of ad load error set UI to 'disabled' state and
                                        // complete this subscription with onErrorResumeNext()
                                        .repeat()
                                        .doOnError(err -> {
                                            videoBtn.setEnabled(false);
                                            videoProgress.setVisibility(View.GONE);
                                            videoBtn.setOnClickListener(null);
                                            Toast toast = Toast.makeText(getActivity(),
                                                    ((PsiCashException.Video) err).getUIMessage(requireContext()), Toast.LENGTH_SHORT);
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

 */
                        } else {
                            ((TextView) rewardedVideoRowView
                                    .findViewById(R.id.psicash_purchase_sku_item_description))
                                    .setText(R.string.psicash_purchase_free_not_available_not_running_description);
                            videoBtn.setEnabled(false);
                            videoBtn.setOnClickListener(null);
                            if (rewardedVideoDisposable != null) {
                                rewardedVideoDisposable.dispose();
                            }
                        }
                    })
                    .subscribe());

            // PsiCash
            Single<List<SkuDetails>> getPsiCashSkus = GooglePlayBillingHelper.getInstance(requireContext())
                    .allSkuDetailsSingle()
                    .toObservable()
                    .flatMap(Observable::fromIterable)
                    .filter(skuDetails -> {
                        String sku = skuDetails.getSku();
                        return GooglePlayBillingHelper.IAB_PSICASH_SKUS_TO_VALUE.containsKey(sku);
                    })
                    .toList();

            compositeDisposable.add(getPsiCashSkus
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(skuDetailsList -> {
                        AddPsiCashTabFragment addPsiCashTabFragment = ((AddPsiCashTabFragment) getParentFragment());
                        if (addPsiCashTabFragment != null) {
                            addPsiCashTabFragment.hideProgress();
                        }

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
                            final View psicashPurchaseItemView = inflater.inflate(R.layout.psicash_purchase_template, null);

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
                                    if (activity == null || activity.isFinishing() || !isAdded()) {
                                        return;
                                    }
                                    // Complete immediately if continueToSignInPromptSingle returns true
                                    purchaseDisposable = continueToSignInPromptSingle()
                                            .flatMapCompletable(done ->
                                                    done ? Completable.complete() :
                                                            GooglePlayBillingHelper.getInstance(activity.getApplicationContext())
                                                                    .launchFlow(activity, skuDetails))
                                            .doOnError(err -> {
                                                Toast toast = Toast.makeText(getActivity(),
                                                        R.string.psicash_purchase_not_available_error_message,
                                                        Toast.LENGTH_SHORT);
                                                positionToast(toast, psicashPurchaseItemView, activity.getWindow());
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
                                            }).subscribe();
                                    compositeDisposable.add(purchaseDisposable);
                                }
                            });

                            containerLayout.addView(psicashPurchaseItemView);
                        }
                    })
                    .subscribe());
        }

        // Create account / sign in prompt dialog wrapped in Single
        // If returns true on success the downstream should complete the chain immediately
        private Single<Boolean> continueToSignInPromptSingle() {
            if (isAccount.get()) {
                return Single.just(false);
            }
            final SharedPreferences sharedPreferences =
                    requireContext().getSharedPreferences(PsiCashClient.PSICASH_PREFERENCES_KEY,
                            Context.MODE_PRIVATE);

            long nextAskTime = sharedPreferences.getLong(NEXT_ACCOUNT_CREATE_ASK_TIME_MILLIS, 0);

            if (nextAskTime > System.currentTimeMillis()) {
                return Single.just(false);
            }
            return Single.create(emitter -> {
                AtomicBoolean createAccount = new AtomicBoolean(true);
                AlertDialog alertDialog = new AlertDialog.Builder(requireActivity())
                        .setTitle(R.string.psicash_account_generic_title)
                        .setIcon(R.drawable.psicash_coin)
                        .setView(View.inflate(new ContextThemeWrapper(requireContext(),
                                        R.style.Theme_AppCompat_Dialog_Alert),
                                R.layout.psicash_create_account_prompt_layout,
                                null))
                        .setNegativeButton(R.string.psicash_continue_without_account_lbl, (dialog, which) -> {
                            createAccount.set(false);
                        })
                        .setPositiveButton(R.string.psicash_account_continue_to_sign_in_lbl, (dialog, which) -> {
                            createAccount.set(true);
                            try {
                                UiHelpers.openPsiCashAccountActivity(requireActivity(),
                                        PsiCashAccountActivity.CallerActivity.PSICASH_STORE);
                            } catch (RuntimeException ignored) {
                            }
                        })
                        .setNeutralButton(R.string.psicash_cancel_lbl, ((dialog, which) -> {
                        }))
                        .create();
                alertDialog.setOnDismissListener(dialog -> {
                    boolean dontRemind = ((CheckBox) alertDialog
                            .findViewById(R.id.psicash_create_account_remind_checkbox))
                            .isChecked();
                    sharedPreferences
                            .edit()
                            .putLong(NEXT_ACCOUNT_CREATE_ASK_TIME_MILLIS,
                                    dontRemind ? System.currentTimeMillis() + TIME_1_WEEK_MILLIS : 0)
                            .apply();
                    if (!emitter.isDisposed()) {
                        emitter.onSuccess(createAccount.get());
                    }
                });
                alertDialog.show();
            });
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            compositeDisposable.dispose();
        }
    }

    public static class WaitToFinishPurchaseFragment extends Fragment {
        public WaitToFinishPurchaseFragment() {
            super(R.layout.psicash_wait_to_finish_purchase_fragment);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            AddPsiCashTabFragment addPsiCashTabFragment =
                    (AddPsiCashTabFragment) getParentFragment();
            if (addPsiCashTabFragment != null) {
                addPsiCashTabFragment.hideProgress();
            }
        }
    }

    public static class ConnectToFinishPurchaseFragment extends Fragment {
        public ConnectToFinishPurchaseFragment() {
            super(R.layout.psicash_connect_to_finish_purchase_fragment);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            AddPsiCashTabFragment addPsiCashTabFragment =
                    (AddPsiCashTabFragment) getParentFragment();
            if (addPsiCashTabFragment != null) {
                addPsiCashTabFragment.hideProgress();
            }

            Button connectBtn = view.findViewById(R.id.continue_button);
            connectBtn.setOnClickListener(v -> {
                try {
                    Intent data = new Intent(MainActivity.PSICASH_CONNECT_PSIPHON_INTENT_ACTION);
                    requireActivity().setResult(Activity.RESULT_OK, data);
                    requireActivity().finish();
                } catch (RuntimeException ignored) {
                }
            });
        }
    }
}
