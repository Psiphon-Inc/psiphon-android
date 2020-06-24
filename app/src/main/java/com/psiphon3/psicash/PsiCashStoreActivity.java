package com.psiphon3.psicash;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.jakewharton.rxrelay2.PublishRelay;
import com.jakewharton.rxrelay2.Relay;
import com.psiphon3.psicash.mvibase.MviView;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.text.NumberFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PsiCashStoreActivity extends LocalizedActivities.AppCompatActivity implements MviView<PsiCashIntent, PsiCashViewState> {
    public static final String PURCHASE_SPEEDBOOST_INTENT = "PURCHASE_SPEEDBOOST_INTENT";
    public static final String PURCHASE_SPEEDBOOST_DISTINGUISHER_EXTRA = "PURCHASE_SPEEDBOOST_DISTINGUISHER_EXTRA";
    public static final String PURCHASE_SPEEDBOOST_EXPECTED_PRICE_EXTRA = "PURCHASE_SPEEDBOOST_EXPECTED_PRICE_EXTRA";
    public static final String PURCHASE_SPEEDBOOST_TRANSACTION_CLASS_EXTRA = "PURCHASE_SPEEDBOOST_TRANSACTION_CLASS_EXTRA";
    public static final String PSICASH_CONNECT_PSIPHON_INTENT = "PSICASH_CONNECT_PSIPHON_INTENT";

    private PsiCashViewModel psiCashViewModel;

    private Relay<PsiCashIntent> intentsPublishRelay = PublishRelay.<PsiCashIntent>create().toSerialized();
    private PublishRelay<Pair<Integer, Integer>> balanceAnimationRelay = PublishRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    private View psicashStoreMainView;
    private View psicashNotAvailableWhileConnectingView;
    private View psicashNotAvailableWhileSubscribedView;
    private View progressOverlay;

    private ViewPager viewPager;
    private Disposable viewStatesDisposable;
    private int currentUiBalance = PsiCashViewState.PSICASH_IDLE_BALANCE;
    private TextView balanceLabel;
    private ImageView balanceIcon;
    private ViewGroup balanceLayout;


    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(R.layout.psicash_store_activity);

        psicashStoreMainView = findViewById(R.id.psicash_store_main);
        psicashNotAvailableWhileConnectingView = findViewById(R.id.psicash_store_not_available_while_connecting);
        psicashNotAvailableWhileSubscribedView = findViewById(R.id.psicash_store_not_available_while_subscribed);
        progressOverlay = findViewById(R.id.progress_overlay);
        balanceLabel = findViewById(R.id.psicash_balance_label);
        balanceIcon = findViewById(R.id.psicash_balance_icon);
        balanceLayout = findViewById(R.id.psicash_balance_layout);

        psiCashViewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(PsiCashViewModel.class);
        getLifecycle().addObserver(psiCashViewModel);

        // Pass the UI's intents to the view model
        psiCashViewModel.processIntents(intents());

        AtomicBoolean progressShowing = new AtomicBoolean(progressOverlay.getVisibility() == View.VISIBLE);
        compositeDisposable.add(psiCashViewModel.subscriptionStateFlowable()
                .distinctUntilChanged()
                .switchMap(subscriptionState -> {
                            if (subscriptionState.hasValidPurchase()) {
                                return Flowable.just(SceneState.NOT_AVAILABLE_WHILE_SUBSCRIBED);
                            }
                            return psiCashViewModel.tunnelStateFlowable()
                                    .filter(state -> !state.isUnknown())
                                    .distinctUntilChanged()
                                    .map(state -> {
                                        if (state.isRunning() && !state.connectionData().isConnected()) {
                                            return SceneState.NOT_AVAILABLE_WHILE_CONNECTING;
                                        }
                                        return SceneState.PSICASH_STORE;
                                    });
                        }
                )
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(sceneState -> {
                    if(isFinishing()) {
                        return;
                    }
                    if (progressShowing.compareAndSet(true, false)) {
                        progressOverlay.setVisibility(View.GONE);
                    }
                    if (sceneState == SceneState.NOT_AVAILABLE_WHILE_CONNECTING) {
                        psicashStoreMainView.setVisibility(View.GONE);
                        psicashNotAvailableWhileSubscribedView.setVisibility(View.GONE);
                        psicashNotAvailableWhileConnectingView.setVisibility(View.VISIBLE);
                    } else if (sceneState == SceneState.NOT_AVAILABLE_WHILE_SUBSCRIBED) {
                        psicashNotAvailableWhileConnectingView.setVisibility(View.GONE);
                        psicashStoreMainView.setVisibility(View.GONE);
                        psicashNotAvailableWhileSubscribedView.setVisibility(View.VISIBLE);
                    } else {
                        psicashNotAvailableWhileSubscribedView.setVisibility(View.GONE);
                        psicashNotAvailableWhileConnectingView.setVisibility(View.GONE);
                        psicashStoreMainView.setVisibility(View.VISIBLE);
                    }
                })
                .subscribe());

        // Balance label increase animations, executed sequentially
        compositeDisposable.add(balanceAnimationRelay
                .distinctUntilChanged()
                .concatMap(pair ->
                        balanceLabelAnimationObservable(pair.first, pair.second)
                )
                .subscribe(ValueAnimator::start, err -> {
                    Utils.MyLog.g("Balance label increase animation error: " + err);
                }));

        TabLayout tabLayout = findViewById(R.id.psicash_store_tablayout);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        PageAdapter pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount());
        viewPager = findViewById(R.id.psicash_store_viewpager);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));

        // Go to the tab specified in the opening intent extra
        int tabIndex = getIntent().getIntExtra("tabIndex", 0);
        if (tabIndex < pageAdapter.getCount()) {
            viewPager.setCurrentItem(tabIndex);
        }
    }

    @Override
    public Observable<PsiCashIntent> intents() {
        return intentsPublishRelay.hide();
    }

    @Override
    public void render(PsiCashViewState state) {
        if (!state.hasValidTokens()) {
            return;
        }
        updateUiBalanceIcon(state);
        updateUiBalanceLabel(state);
    }

    private void updateUiBalanceIcon(PsiCashViewState state) {
        if (state.pendingRefresh()) {
            balanceIcon.setImageLevel(1);
            balanceLayout.setClickable(true);
            balanceLayout.setOnClickListener(view -> {
                final Activity activity = PsiCashStoreActivity.this;
                if (activity.isFinishing()) {
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setIcon(R.drawable.psicash_coin)
                        .setTitle(activity.getString(R.string.psicash_generic_title))
                        .setMessage(activity.getString(R.string.psicash_out_of_date_dialog_message))
                        .setNeutralButton(R.string.label_ok, (dialog, which) -> {
                        })
                        .setCancelable(true)
                        .create()
                        .show();
            });
        } else {
            balanceIcon.setImageLevel(0);
            balanceLayout.setClickable(false);
            balanceLayout.setOnClickListener(null);
        }
    }

    void updateUiBalanceLabel(PsiCashViewState state) {
        if (currentUiBalance == state.uiBalance()) {
            return;
        }

        if (currentUiBalance == PsiCashViewState.PSICASH_IDLE_BALANCE) {
            final NumberFormat nf = NumberFormat.getInstance();
            balanceLabel.setText(nf.format(state.uiBalance()));
        } else {
            balanceAnimationRelay.accept(new Pair<>(currentUiBalance, state.uiBalance()));
        }
        currentUiBalance = state.uiBalance();
    }

    private Observable<ValueAnimator> balanceLabelAnimationObservable(int fromVal, int toVal) {
        return Observable.create(emitter -> {
            ValueAnimator valueAnimator = ValueAnimator.ofInt(fromVal, toVal);
            valueAnimator.setDuration(1000);
            final NumberFormat nf = NumberFormat.getInstance();
            valueAnimator.addUpdateListener(va ->
                    balanceLabel.setText(nf.format(va.getAnimatedValue())));
            valueAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationCancel(Animator animation) {
                    super.onAnimationCancel(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    if (!emitter.isDisposed()) {
                        emitter.onComplete();
                    }
                }
            });

            if (!emitter.isDisposed()) {
                emitter.onNext(valueAnimator);
            }
        });
    }

    private void bindViewState() {
        // Subscribe to the RewardedVideoViewModel and render every emitted state
        viewStatesDisposable = viewStatesDisposable();
    }

    private void unbindViewState() {
        if (viewStatesDisposable != null) {
            viewStatesDisposable.dispose();
        }
    }

    private Disposable viewStatesDisposable() {
        // Do not render PsiCash view states if user is subscribed
        return psiCashViewModel.subscriptionStateFlowable()
                .toObservable()
                .flatMap(s -> s.hasValidPurchase() ?
                        Observable.empty() :
                        psiCashViewModel.states())
                // make sure onError doesn't cut ahead of onNext with the observeOn overload
                .observeOn(AndroidSchedulers.mainThread(), true)
                .subscribe(this::render);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindViewState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        final AppPreferences mp = new AppPreferences(getApplicationContext());
        String psiCashCustomData = mp.getString(getApplicationContext().getString(R.string.persistentPsiCashCustomData), "");
        if (TextUtils.isEmpty(psiCashCustomData)) {
            Utils.MyLog.g("PsiCashStoreActivity error: PsiCash custom data is empty.");
            finish();
            return;
        }
        bindViewState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        compositeDisposable.dispose();
    }

    private enum SceneState {
        NOT_AVAILABLE_WHILE_CONNECTING, NOT_AVAILABLE_WHILE_SUBSCRIBED, PSICASH_STORE
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new PsiCashInAppPurchaseFragment();
                case 1:
                    return new PsiCashSpeedBoostPurchaseFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }
}
