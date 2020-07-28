package com.psiphon3.psicash;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.gridlayout.widget.GridLayout;
import androidx.lifecycle.ViewModelProvider;
import androidx.transition.Scene;
import androidx.transition.TransitionManager;
import androidx.viewpager.widget.ViewPager;

import com.psiphon3.TunnelState;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.psicashlib.PsiCashLib;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.functions.BiFunction;


public class PsiCashSpeedBoostPurchaseFragment extends Fragment {
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private PsiCashViewModel psiCashViewModel;

    private Scene sceneSpeedBoostUnavailable;
    private Scene sceneBuySpeedBoost;
    private Scene sceneActiveSpeedBoost;
    private final Handler handler = new Handler();
    private int[] backgrounds = new int[]{
            R.drawable.speedboost_background_orange,
            R.drawable.speedboost_background_pink,
            R.drawable.speedboost_background_purple,
            R.drawable.speedboost_background_blue,
            R.drawable.speedboost_background_light_blue,
            R.drawable.speedboost_background_mint,
            R.drawable.speedboost_background_orange_2,
            R.drawable.speedboost_background_yellow,
            R.drawable.speedboost_background_fluoro_green,
    };

    static public final Map<String, Integer> PSICASH_SKUS_TO_HOURS;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("1hr", 1);
        m.put("2hr", 2);
        m.put("3hr", 3);
        m.put("4hr", 4);
        m.put("5hr", 5);
        m.put("6hr", 6);
        m.put("7hr", 7);
        m.put("8hr", 8);
        m.put("9hr", 9);
        PSICASH_SKUS_TO_HOURS = Collections.unmodifiableMap(m);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        FragmentActivity fragmentActivity = getActivity();
        psiCashViewModel = new ViewModelProvider(fragmentActivity,
                new ViewModelProvider.AndroidViewModelFactory(fragmentActivity.getApplication()))
                .get(PsiCashViewModel.class);

        View view = inflater.inflate(R.layout.psicash_store_scene_container_fragment, container, false);

        ViewGroup sceneRoot = view.findViewById(R.id.scene_root);
        View progressOverlay = view.findViewById(R.id.progress_overlay);

        Context ctx = getContext();
        sceneSpeedBoostUnavailable = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_not_available_scene, ctx);
        sceneBuySpeedBoost = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_main_scene, ctx);
        sceneActiveSpeedBoost = Scene.getSceneForLayout(sceneRoot, R.layout.purchase_speedboost_already_active_scene, ctx);

        sceneSpeedBoostUnavailable.setEnterAction(() -> {
            progressOverlay.setVisibility(View.GONE);
            Button connectBtn = sceneSpeedBoostUnavailable.getSceneRoot().findViewById(R.id.connect_psiphon_btn);
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

        sceneBuySpeedBoost.setEnterAction(() -> {
            AtomicBoolean progressShowing = new AtomicBoolean(progressOverlay.getVisibility() == View.VISIBLE);
            compositeDisposable.add(psiCashViewModel.states()
                    .observeOn(AndroidSchedulers.mainThread())
                    .filter(psiCashViewState -> psiCashViewState.purchasePrices() != null &&
                            psiCashViewState.uiBalance() != PsiCashViewState.PSICASH_IDLE_BALANCE)
                    .map(psiCashViewState -> {
                        List<PsiCashLib.PurchasePrice> priceList = psiCashViewState.purchasePrices();
                        if (priceList == null) {
                            priceList = Collections.emptyList();
                        }
                        return new Pair<>(psiCashViewState.uiBalance(), priceList);
                    })
                    .distinctUntilChanged((a, b) -> a.first.equals(b.first) &&
                            a.second.size() == b.second.size())
                    .subscribe(
                            pair -> {
                                if (progressShowing.compareAndSet(true, false)) {
                                    progressOverlay.setVisibility(View.GONE);
                                }
                                int balance = pair.first;
                                List<PsiCashLib.PurchasePrice> purchasePriceList = pair.second;
                                populateSpeedBoostPurchases(view, balance, purchasePriceList);
                            },
                            err -> Utils.MyLog.g("PurchaseSpeedBoostFragment: error getting PsiCash view state: " + err)
                    ));
        });

        sceneActiveSpeedBoost.setEnterAction(() -> progressOverlay.setVisibility(View.GONE));

        compositeDisposable.add(Observable.combineLatest(
                psiCashViewModel.tunnelStateFlowable()
                        .toObservable()
                        .filter(state -> !state.isUnknown())
                        .distinctUntilChanged(),
                psiCashViewModel.states()
                        .map(psiCashViewState -> {
                            if (psiCashViewState.purchase() == null) {
                                return false;
                            }
                            Date expiryDate = psiCashViewState.purchase().expiry;
                            if (expiryDate != null) {
                                long millisDiff = expiryDate.getTime() - new Date().getTime();
                                if (millisDiff > 0) {
                                    // (Re)schedule state refresh after expiry
                                    handler.removeCallbacksAndMessages(null);
                                    handler.postDelayed(() -> psiCashViewModel
                                                    .processIntents(Observable.just(PsiCashIntent.GetPsiCash.create())),
                                            millisDiff);
                                    return true;
                                }
                            }
                            return false;
                        })
                        .distinctUntilChanged(),
                ((BiFunction<TunnelState, Boolean, android.util.Pair>) android.util.Pair::new))
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(pair -> {
                    TunnelState state = (TunnelState) pair.first;
                    boolean hasActiveSpeedBoost = (boolean) pair.second;
                    if (hasActiveSpeedBoost) {
                        TransitionManager.go(sceneActiveSpeedBoost);
                    } else if (state.isStopped()) {
                        TransitionManager.go(sceneSpeedBoostUnavailable);
                    } else if (state.isRunning() && state.connectionData().isConnected()) {
                        TransitionManager.go(sceneBuySpeedBoost);
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

    private void populateSpeedBoostPurchases(View view, int balance, @NonNull List<PsiCashLib.PurchasePrice> purchasePriceList) {
        final Activity activity = getActivity();
        if (activity == null) {
            return;
        }

        final int columnCount;
        int orientation = getResources().getConfiguration().orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            columnCount = 5;
        } else {
            columnCount = 3;
        }

        GridLayout containerLayout = view.findViewById(R.id.purchase_speedboost_grid);
        containerLayout.removeAllViews();
        containerLayout.setColumnCount(columnCount);

        Drawable buttonDrawable = activity.getResources().getDrawable(R.drawable.psicash_coin);
        buttonDrawable.setBounds(0,
                0,
                (int) (buttonDrawable.getIntrinsicWidth() * 0.7),
                (int) (buttonDrawable.getIntrinsicHeight() * 0.7));

        for (PsiCashLib.PurchasePrice price : purchasePriceList) {
            final String durationString = getDurationString(price.distinguisher, activity.getResources());
            if (durationString == null) {
                // Skip if we the distinguisher is not in the hardcoded set of PsiCash SKUs
                continue;
            }
            LinearLayout speedboostItemLayout = (LinearLayout) activity.getLayoutInflater().inflate(R.layout.speedboost_button_template, null);
            RelativeLayout relativeLayout = speedboostItemLayout.findViewById(R.id.speedboost_relative_layout);

            final int priceInteger = (int) (Math.floor((long) (price.price / 1e9)));
            int drawableResId = getSpeedBoostPurchaseDrawableResId(priceInteger);
            relativeLayout.setBackgroundResource(drawableResId);

            TextView durationLabel = speedboostItemLayout.findViewById(R.id.speedboost_purchase_label);
            durationLabel.setText(durationString);

            Button button = speedboostItemLayout.findViewById(R.id.speedboost_purchase_button);

            button.setCompoundDrawables(buttonDrawable, null, null, null);

            String priceTag = String.format(Locale.US, "%d", priceInteger);
            button.setText(priceTag);

            if (balance >= priceInteger) {
                button.setEnabled(true);
                speedboostItemLayout.setOnClickListener(v -> {
                    String confirmationMessage = String.format(
                            activity.getString(R.string.confirm_speedboost_purchase_alert),
                            durationString,
                            priceInteger
                    );

                    new AlertDialog.Builder(activity)
                            .setIcon(R.drawable.psicash_coin)
                            .setTitle(activity.getString(R.string.speed_boost_button_caption))
                            .setMessage(confirmationMessage)
                            .setNegativeButton(R.string.lbl_no, (dialog, which) -> {
                            })
                            .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                                try {
                                    Intent data = new Intent(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_INTENT);
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_DISTINGUISHER_EXTRA, price.distinguisher);
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_TRANSACTION_CLASS_EXTRA, price.transactionClass);
                                    data.putExtra(PsiCashStoreActivity.PURCHASE_SPEEDBOOST_EXPECTED_PRICE_EXTRA, price.price);
                                    activity.setResult(Activity.RESULT_OK, data);
                                    activity.finish();
                                } catch (NullPointerException ignored) {
                                }
                            })
                            .setCancelable(true)
                            .create()
                            .show();
                });
            } else {
                button.setEnabled(false);
                speedboostItemLayout.setOnClickListener(v -> {
                    new AlertDialog.Builder(activity)
                            .setIcon(R.drawable.psicash_coin)
                            .setTitle(activity.getString(R.string.speed_boost_button_caption))
                            .setMessage(activity.getString(R.string.speed_boost_insufficient_balance_alert))
                            .setNegativeButton(R.string.lbl_no, (dialog, which) -> {})
                            .setPositiveButton(R.string.lbl_yes, (dialog, which) -> {
                                final ViewPager viewPager = activity.findViewById(R.id.psicash_store_viewpager);
                                viewPager.setCurrentItem(getResources().getInteger(R.integer.psiCashTabIndex));
                            })
                            .setCancelable(true)
                            .create()
                            .show();
                });
            }
            DisplayMetrics metrics = new DisplayMetrics();
            getActivity().getWindow().getWindowManager().getDefaultDisplay().getMetrics(metrics);

            float ratio = 248.0f / 185.0f;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = metrics.widthPixels / columnCount;
            params.height = (int) (params.width * ratio);
            speedboostItemLayout.setLayoutParams(params);
            containerLayout.addView(speedboostItemLayout);
        }
    }

    private String getDurationString(String distinguisher, Resources resources) {
        if (TextUtils.isEmpty(distinguisher)) {
            return null;
        }
        Integer duration = PSICASH_SKUS_TO_HOURS.get(distinguisher);
        if (duration == null) {
            return null;
        }
        return resources.getQuantityString(R.plurals.hours_of_speed_boost, duration, duration);
    }

    private int getSpeedBoostPurchaseDrawableResId(int priceValue) {
        int index = ((priceValue / 100) - 1) % backgrounds.length;
        return backgrounds[index];
    }
}
