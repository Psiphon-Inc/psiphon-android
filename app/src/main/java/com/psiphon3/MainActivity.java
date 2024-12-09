/*
 * Copyright (c) 2023, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BulletSpan;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager.widget.ViewPager;

import com.android.billingclient.api.SkuDetails;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.tabs.TabLayout;
import com.psiphon3.billing.GooglePlayBillingHelper;
import com.psiphon3.billing.SubscriptionState;
import com.psiphon3.log.LogsMaintenanceWorker;
import com.psiphon3.log.MyLog;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.util.UiHelpers;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.psiphonlibrary.TunnelManager;
import com.psiphon3.psiphonlibrary.Utils;
import com.psiphon3.psiphonlibrary.VpnAppsUtils;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public class MainActivity extends LocalizedActivities.AppCompatActivity {

    public MainActivity() {
        Utils.initializeSecureRandom();
    }

    static final int REQUEST_CODE_PERMISSIONS = 103;
    static final int REQUEST_CODE_NOTIFICATION_RATIONALE = 104;
    static final int REQUEST_CODE_LOCATION_RATIONALE = 105;

    public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainActivity.PREVENT_AUTO_START";

    private static final String CURRENT_TAB = "currentTab";
    private static final int PAYMENT_CHOOSER_ACTIVITY = 20001;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Button toggleButton;
    private ProgressBar connectionProgressBar;
    private ViewGroup connectionWaitingNetworkIndicator;
    private Button openBrowserButton;
    private MainActivityViewModel viewModel;
    private Toast invalidProxySettingsToast;
    private AppPreferences multiProcessPreferences;
    private ViewPager viewPager;
    private PsiphonTabLayout tabLayout;
    private GooglePlayBillingHelper googlePlayBillingHelper;


    private boolean isFirstRun = true;
    private AlertDialog upstreamProxyErrorAlertDialog;
    private AlertDialog disallowedTrafficAlertDialog;
    private PurchaseRequiredDialog purchaseRequiredDialog;
    private MenuItem psiphonBumpHelpItem;
    private FloatingActionButton helpConnectFab;
    // Keeps track of the Psiphon Bump help state
    private PsiphonBumpHelpState psiphonBumpHelpState = PsiphonBumpHelpState.DISABLED;

    enum PsiphonBumpHelpState {
        DISABLED,
        NEED_SYSTEM_NFC,
        ENABLED
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean("isFirstRun", isFirstRun);
        super.onSaveInstanceState(outState);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.activity_main, menu);
        // Set up version label in the action bar
        TextView versionLabel = menu.getItem(1).getActionView().findViewById(R.id.toolbar_version_label);
        versionLabel.setText(String.format(Locale.US, "v. %s", EmbeddedValues.CLIENT_VERSION));
        // Psiphon Bump
        psiphonBumpHelpItem = menu.getItem(0);
        // Set up "Can Help" item state in the action bar
        updatePsiphonBumpHelpMenuItem(psiphonBumpHelpState);
        return true;
    }

    private void updatePsiphonBumpHelpMenuItem(PsiphonBumpHelpState psiphonBumpHelpState) {
        if (psiphonBumpHelpItem == null) {
            return;
        }
        switch (psiphonBumpHelpState) {
            case DISABLED:
                // Hide
                psiphonBumpHelpItem.setVisible(false);
                break;
            case NEED_SYSTEM_NFC:
                // Show "NFC disabled" icon
                psiphonBumpHelpItem.setIcon(R.drawable.ic_contactless_nfc_disabled);
                psiphonBumpHelpItem.setVisible(true);
                // Make clickable
                psiphonBumpHelpItem.setEnabled(true);
                // Show "Enable NFC" dialog when clicked
                psiphonBumpHelpItem.setOnMenuItemClickListener(item -> {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.psiphon_bump_need_system_nfc_title)
                            .setMessage(R.string.psiphon_bump_need_system_nfc_message)
                            .setPositiveButton(R.string.psiphon_bump_need_system_nfc_open_btn, (dialog, which) -> {
                                // Open system NFC settings screen
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                                    Intent intent = new Intent(Settings.ACTION_NFC_SETTINGS);
                                    startActivity(intent);
                                }
                            })
                            .setNegativeButton(R.string.close_btn_label, null)
                            .show();
                    return true;
                });
                break;
            case ENABLED:
                // Show "Can Help" icon
                psiphonBumpHelpItem.setIcon(R.drawable.ic_contactless);
                psiphonBumpHelpItem.setVisible(true);
                // Make not clickable
                psiphonBumpHelpItem.setEnabled(false);
                psiphonBumpHelpItem.setOnMenuItemClickListener(null);
                break;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            isFirstRun = savedInstanceState.getBoolean("isFirstRun", isFirstRun);
        }

        setContentView(R.layout.main_activity);

        setRequestedOrientation (ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        helpConnectFab = findViewById(R.id.help_connect_fab);

        EmbeddedValues.initialize(getApplicationContext());
        multiProcessPreferences = new AppPreferences(this);

        googlePlayBillingHelper = GooglePlayBillingHelper.getInstance(getApplicationContext());
        // Start observing live purchases updates
        googlePlayBillingHelper.startObservePurchasesUpdates();

        viewModel = new ViewModelProvider(this,
                new ViewModelProvider.AndroidViewModelFactory(getApplication()))
                .get(MainActivityViewModel.class);
        getLifecycle().addObserver(viewModel);

        // Schedule db maintenance
        LogsMaintenanceWorker.schedule(getApplicationContext());

        toggleButton = findViewById(R.id.toggleButton);
        connectionProgressBar = findViewById(R.id.connectionProgressBar);
        connectionWaitingNetworkIndicator = findViewById(R.id.connectionWaitingNetworkIndicator);
        ((AnimationDrawable) connectionWaitingNetworkIndicator.getBackground()).start();
        openBrowserButton = findViewById(R.id.openBrowserButton);
        toggleButton.setOnClickListener(v ->
                compositeDisposable.add(getTunnelServiceInteractor().tunnelStateFlowable()
                        .filter(state -> !state.isUnknown())
                        .take(1)
                        .doOnNext(state -> {
                            if (state.isRunning()) {
                                getTunnelServiceInteractor().stopTunnelService();
                            } else {
                                startTunnel();
                            }
                        })
                        .subscribe()));
        tabLayout = findViewById(R.id.main_activity_tablayout);
        tabLayout.addTab(tabLayout.newTab().setTag("home").setText(R.string.home_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("statistics").setText(R.string.statistics_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("settings").setText(R.string.settings_tab_name));
        tabLayout.addTab(tabLayout.newTab().setTag("logs").setText(R.string.logs_tab_name));
        PageAdapter pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount());

        viewPager = findViewById(R.id.tabs_view_pager);
        // Try and keep all pages of the view pager loaded. For 4 tabs in total the off screen pages
        // max is 3.
        viewPager.setOffscreenPageLimit(3);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int tabPosition = tab.getPosition();
                viewPager.setCurrentItem(tab.getPosition());
                multiProcessPreferences.put(CURRENT_TAB, tabPosition);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // Switch to last tab when view pager is ready
        viewPager.post(() ->
                viewPager.setCurrentItem(multiProcessPreferences.getInt(CURRENT_TAB, 0), false));

        // Handle current intent only if we are not recreating from saved state
        if (savedInstanceState == null) {
            // Schedule handling current intent when the main view is fully inflated
            getWindow().getDecorView().post(() -> HandleCurrentIntent(getIntent()));

            // Also run permissions check once per app creation and request them if needed.
            // Check suggested workflow for details:
            // https://developer.android.com/training/permissions/requesting#workflow_for_requesting_permissions
            checkPermissions();

            // If we are on Android pre-M or already have coarse location permission, start location
            // update.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.ACCESS_COARSE_LOCATION) == PermissionChecker.PERMISSION_GRANTED) {
                Location.runCurrentLocationUpdate(this);
            }
        }
    }

    @Override
    public void onDestroy() {
        compositeDisposable.dispose();
        googlePlayBillingHelper.stopObservePurchasesUpdates();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelInvalidProxySettingsToast();
        compositeDisposable.clear();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            // There could be multiple permissions requested, check if we were granted a location
            // one and start location update if so.
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION) &&
                        grantResults[i] == PermissionChecker.PERMISSION_GRANTED) {
                    Location.runCurrentLocationUpdate(this);
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        googlePlayBillingHelper.queryAllPurchases();
        googlePlayBillingHelper.queryAllSkuDetails();

        // Observe tunnel state changes to update UI
        compositeDisposable.add(getTunnelServiceInteractor().tunnelStateFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(this::updateServiceStateUI)
                .subscribe());

        // If device supports Psiphon Bump observe tunnel state and update NFC UI and HCE state accordingly
        if (Utils.supportsPsiphonBump(this)) {
            compositeDisposable.add(getTunnelServiceInteractor().tunnelStateFlowable()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnNext(this::updatePsiphonBumpState)
                    // disable Psiphon Bump HCE and hide help connect FAB when this subscription is
                    // disposed.
                    .doOnCancel(() -> {
                        updatePsiphonBumpHceState(false);
                        helpConnectFab.setVisibility(View.GONE);
                    })
                    .subscribe());
        }

        // Observe custom proxy validation results to show a toast for invalid ones
        compositeDisposable.add(viewModel.customProxyValidationResultFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(isValidResult -> {
                    if (!isValidResult) {
                        cancelInvalidProxySettingsToast();
                        invalidProxySettingsToast = Toast.makeText(this,
                                R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                        invalidProxySettingsToast.show();
                    }
                })
                .subscribe());

        // Observe link clicks in the modal web view to open in the external browser
        // NOTE: do not PsiCash modify links clicked from the view
        compositeDisposable.add(viewModel.externalBrowserUrlFlowable()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(url -> displayBrowser(this, url, false))
                .subscribe());

        // Check if user data collection disclosure needs to be shown followed by the unsafe traffic
        // alerts preference check and then check if the tunnel should be started automatically
        compositeDisposable.add(
                vpnServiceDataCollectionDisclosureCompletable()
                        .andThen(unsafeTrafficAlertsCompletable())
                        .andThen(autoStartMaybe())
                        .doOnSuccess(__ -> startTunnel())
                        .subscribe());
    }

    // Check runtime permissions and show rationales if needed.
    // When we are done with the rationales return granted permissions.
    private  void checkPermissions() {
        // Runtime permissions are only needed on Android M+ (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> permissionsToRequest = new ArrayList<>();

            // Check if notification permission is granted on Android 13+ (API 33+)
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
                    // Check if we should show a rationale for notification permission
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                        // Start notification rationale activity and abort further permission checks and requests.
                        // We will run permission check again when the rationale activity is finished.
                        startActivityForResult(
                                new Intent(this, NotificationPermissionRationaleActivity.class),
                                REQUEST_CODE_NOTIFICATION_RATIONALE);
                        return;
                    }
                }
            }

            // Check if we need coarse location permission
            final AppPreferences mp = new AppPreferences(getApplicationContext());
            int deviceLocationPrecision =  mp.getInt(getString(R.string.deviceLocationPrecisionParameter), 0);

            if (deviceLocationPrecision > 0 && deviceLocationPrecision <= 12 &&
                    ContextCompat.checkSelfPermission(this,
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                // Check if we should show a rationale for location permission
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    // Start location rationale activity and abort further permission checks and requests.
                    // We will run permissions check again the rationale activity is finished.
                    startActivityForResult(
                            new Intent(this, LocationPermissionRationaleActivity.class),
                            REQUEST_CODE_LOCATION_RATIONALE);
                    return;
                }
            }

            // Request permissions if needed
            if (permissionsToRequest.size() > 0) {
                requestPermissions(permissionsToRequest.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
            }
        }
    }

    // Completes right away if unsafe traffic alerts preference exists, otherwise displays an alert
    // and waits until the user picks an answer and preference is stored, then completes.
    Completable unsafeTrafficAlertsCompletable() {
        return Completable.create(emitter -> {
            try {
                multiProcessPreferences.getBoolean(getString(R.string.unsafeTrafficAlertsPreference));
                if (!emitter.isDisposed()) {
                    emitter.onComplete();
                }
            } catch (ItemNotFoundException e) {
                LayoutInflater inflater = this.getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.unsafe_traffic_alert_prompt_layout, null);
                TextView tv = dialogView.findViewById(R.id.textViewMore);
                tv.append(String.format(Locale.US, "\n%s", getString(R.string.AboutMalAwareLink)));
                Linkify.addLinks(tv, Linkify.WEB_URLS);

                final AlertDialog alertDialog = new AlertDialog.Builder(this)
                        .setCancelable(false)
                        .setTitle(R.string.unsafe_traffic_alert_prompt_title)
                        .setView(dialogView)
                        // Only emit a completion event if we have a positive or negative response
                        .setPositiveButton(R.string.lbl_yes,
                                (dialog, whichButton) -> {
                                    multiProcessPreferences.put(getString(R.string.unsafeTrafficAlertsPreference), true);
                                    if (!emitter.isDisposed()) {
                                        emitter.onComplete();
                                    }
                                })
                        .setNegativeButton(R.string.lbl_no,
                                (dialog, whichButton) -> {
                                    multiProcessPreferences.put(getString(R.string.unsafeTrafficAlertsPreference), false);
                                    if (!emitter.isDisposed()) {
                                        emitter.onComplete();
                                    }
                                })
                        .show();
                // Also dismiss the alert when subscription is disposed, for example, on orientation
                // change or when the app is backgrounded.
                emitter.setCancellable(() -> {
                    if (alertDialog != null && alertDialog.isShowing()) {
                        alertDialog.dismiss();
                    }
                });
            }
        })
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    // Completes right away if VPN service data collection disclosure has been accepted, otherwise
    // displays a prompt and waits until the user accepts and preference is stored, then completes.
    Completable vpnServiceDataCollectionDisclosureCompletable() {
        return Completable.create(emitter -> {
            if (multiProcessPreferences.getBoolean(getString(R.string.vpnServiceDataCollectionDisclosureAccepted), false) &&
                    !emitter.isDisposed()) {
                emitter.onComplete();
            }
            View dialogView = getLayoutInflater().inflate(R.layout.vpn_data_collection_disclosure_prompt_layout, null);

            String topMessage = String.format(getString(R.string.vpn_data_collection_disclosure_top), getString(R.string.app_name_psiphon_pro));

            SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
            spannableStringBuilder.append(topMessage);
            spannableStringBuilder.append("\n\n");
            SpannableString bp = new SpannableString(getString(R.string.vpn_data_collection_disclosure_bp1));
            bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannableStringBuilder.append(bp);
            spannableStringBuilder.append("\n\n");
            bp = new SpannableString(getString(R.string.vpn_data_collection_disclosure_bp2));
            bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannableStringBuilder.append(bp);
            spannableStringBuilder.append("\n\n");
            bp = new SpannableString(getString(R.string.vpn_data_collection_disclosure_bp3));
            bp.setSpan(new BulletSpan(15), 0, bp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannableStringBuilder.append(bp);
            ((TextView)dialogView.findViewById(R.id.textView)).setText(spannableStringBuilder);

            final AlertDialog alertDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.vpn_data_collection_disclosure_prompt_title)
                    .setView(dialogView)
                    // Only emit a completion event if we have a positive response
                    .setPositiveButton(R.string.vpn_data_collection_disclosure_accept_btn_text,
                            (dialog, whichButton) -> {
                                multiProcessPreferences.put(getString(R.string.vpnServiceDataCollectionDisclosureAccepted), true);
                                if (!emitter.isDisposed()) {
                                    emitter.onComplete();
                                }
                            })
                    .show();
            // Also dismiss the alert when subscription is disposed, for example, on orientation
            // change or when the app is backgrounded.
            emitter.setCancellable(() -> {
                if (alertDialog != null && alertDialog.isShowing()) {
                    alertDialog.dismiss();
                }
            });
        })
                .subscribeOn(AndroidSchedulers.mainThread());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PAYMENT_CHOOSER_ACTIVITY) {
            if (resultCode == RESULT_OK) {
                // if data intent is not present it means the payment chooser activity closed due to
                // IAB failure, show "Subscription options not available" toast and return.
                if(data == null) {
                    showToast(R.string.subscription_options_currently_not_available);
                    return;
                }
                String skuString = data.getStringExtra(PaymentChooserActivity.USER_PICKED_SKU_DETAILS_EXTRA);
                String oldSkuString = data.getStringExtra(PaymentChooserActivity.USER_OLD_SKU_EXTRA);
                String oldPurchaseToken = data.getStringExtra(PaymentChooserActivity.USER_OLD_PURCHASE_TOKEN_EXTRA);
                try {
                    if (TextUtils.isEmpty(skuString)) {
                        throw new IllegalArgumentException("SKU is empty.");
                    }
                    SkuDetails skuDetails = new SkuDetails(skuString);
                    compositeDisposable.add(googlePlayBillingHelper.launchFlow(this, oldSkuString, oldPurchaseToken, skuDetails)
                            .doOnError(err -> {
                                // Show "Subscription options not available" toast.
                                showToast(R.string.subscription_options_currently_not_available);
                            })
                            .onErrorComplete()
                            .subscribe());
                } catch (JSONException | IllegalArgumentException e) {
                    MyLog.e("MainActivity::onActivityResult purchase SKU error: " + e);
                    // Show "Subscription options not available" toast.
                    showToast(R.string.subscription_options_currently_not_available);
                }
            } else {
                MyLog.i("MainActivity::onActivityResult: PaymentChooserActivity: canceled");
            }
        }
        if (requestCode == REQUEST_CODE_NOTIFICATION_RATIONALE ||
                requestCode == REQUEST_CODE_LOCATION_RATIONALE) {
            // If we are returning from a permission rationale activity, run permissions check
            // when we resume again since the previous check may have been interrupted.
            checkPermissions();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        HandleCurrentIntent(intent);
    }

    public static void openPaymentChooserActivity(FragmentActivity activity, int tabIndex) {
        try {
            Intent intent = new Intent(activity, PaymentChooserActivity.class);
            intent.putExtra("tabIndex", tabIndex);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivityForResult(intent, PAYMENT_CHOOSER_ACTIVITY);
        } catch(RuntimeException ignored) {
        }
    }

    private void updateServiceStateUI(final TunnelState tunnelState) {
        if (tunnelState.isUnknown()) {
            openBrowserButton.setEnabled(false);
            toggleButton.setEnabled(false);
            toggleButton.setText(getText(R.string.waiting));
            connectionProgressBar.setVisibility(View.INVISIBLE);
            connectionWaitingNetworkIndicator.setVisibility(View.INVISIBLE);
        } else if (tunnelState.isRunning()) {
            toggleButton.setEnabled(true);
            toggleButton.setText(getText(R.string.stop));
            if (tunnelState.connectionData().isConnected()) {
                openBrowserButton.setEnabled(true);
                connectionProgressBar.setVisibility(View.INVISIBLE);
                connectionWaitingNetworkIndicator.setVisibility(View.INVISIBLE);

                ArrayList<String> homePages = tunnelState.connectionData().homePages();
                final String url;
                if (homePages != null && homePages.size() > 0) {
                    url = homePages.get(0);
                } else {
                    url = null;
                }
                openBrowserButton.setOnClickListener(view -> displayBrowser(this, url));
            } else {
                openBrowserButton.setEnabled(false);
                boolean waitingForNetwork =
                        tunnelState.connectionData().networkConnectionState() ==
                                TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK;
                connectionWaitingNetworkIndicator.setVisibility(waitingForNetwork ? View.VISIBLE : View.INVISIBLE);
                connectionProgressBar.setVisibility(waitingForNetwork ? View.INVISIBLE : View.VISIBLE);
            }
        } else {
            // Service not running
            toggleButton.setText(getText(R.string.start));
            toggleButton.setEnabled(true);
            openBrowserButton.setEnabled(false);
            connectionProgressBar.setVisibility(View.INVISIBLE);
            connectionWaitingNetworkIndicator.setVisibility(View.INVISIBLE);
        }
    }

    // update NFC UI
    private void updatePsiphonBumpState(final TunnelState tunnelState) {
            switch (tunnelState.status()) {
                case RUNNING:
                    TunnelState.ConnectionData connectionData = tunnelState.connectionData();
                    updatePsiphonBumpHceState(connectionData.isConnected());
                    if (connectionData.isConnected()) {
                        helpConnectFab.setVisibility(View.GONE);
                        helpConnectFab.setOnClickListener(null);
                    } else {
                        boolean waitingForNetwork =
                                tunnelState.connectionData().networkConnectionState() ==
                                        TunnelState.ConnectionData.NetworkConnectionState.WAITING_FOR_NETWORK;
                        helpConnectFab.setVisibility(waitingForNetwork ? View.INVISIBLE : View.VISIBLE);
                        helpConnectFab.setOnClickListener(waitingForNetwork ? null : view -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                Intent intent = new Intent(this, PsiphonBumpNfcReaderActivity.class);
                                startActivity(intent);
                            }
                        });
                    }
                    break;
                case STOPPED:
                case UNKNOWN:
                    updatePsiphonBumpHceState(false);
                    helpConnectFab.setVisibility(View.GONE);
                    helpConnectFab.setOnClickListener(null);
                    break;
            }
    }

    // Dynamically register and unregister "Psiphon Nfc" AID for NFC emulation
    // and update Psiphon Bump help UI
    private void updatePsiphonBumpHceState(boolean isConnected) {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        PackageManager pm = getPackageManager();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                nfcAdapter != null &&
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            CardEmulation cardEmulation = CardEmulation.getInstance(nfcAdapter);
            if (isConnected) {
                cardEmulation.registerAidsForService(new ComponentName(this, PsiphonHostApduService.class),
                        CardEmulation.CATEGORY_OTHER,
                        Collections.singletonList("50736970686f6e4e6663")); // "PsiphonNfc" hex-encoded
                if (nfcAdapter.isEnabled()) {
                    psiphonBumpHelpState = PsiphonBumpHelpState.ENABLED;
                } else {
                    psiphonBumpHelpState = PsiphonBumpHelpState.NEED_SYSTEM_NFC;
                }
            } else {
                cardEmulation.removeAidsForService(new ComponentName(this, PsiphonHostApduService.class),
                        CardEmulation.CATEGORY_OTHER);
                psiphonBumpHelpState = PsiphonBumpHelpState.DISABLED;
            }
        } else {
            psiphonBumpHelpState = PsiphonBumpHelpState.DISABLED;
        }

        // Update the UI
        updatePsiphonBumpHelpMenuItem(psiphonBumpHelpState);
    }

    private void displayBrowser(Context context, String urlString) {
        // Override landing page URL if there is a static landing page URL in the build config
        if (BuildConfig.STATIC_LANDING_PAGE_URL != null) {
            urlString  = BuildConfig.STATIC_LANDING_PAGE_URL;
        }
        // PsiCash modify URLs by default
        displayBrowser(context, urlString, true);
    }

    private void displayBrowser(Context context, String urlString, boolean shouldPsiCashModifyUrls) {
        if (shouldPsiCashModifyUrls) {
            // Add PsiCash parameters
            urlString = PsiCashModifyUrl(urlString);
        }

        // TODO: support multiple home pages in whole device mode. This is
        // disabled due to the case where users haven't set a default browser
        // and will get the prompt once per home page.

        // Prepare browser starting intent.
        Intent browserIntent = new Intent();
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Try and start a browser that is not excluded from VPN tunneling with an explicit intent
        // first.

        if (TextUtils.isEmpty(urlString)) {
            // If URL is empty, just start the app.
            browserIntent.setAction(Intent.ACTION_MAIN);
        } else {
            // If URL is not empty, start the app and load the URL
            browserIntent.setAction(Intent.ACTION_VIEW);
            browserIntent.setData(Uri.parse(urlString));
        }

        // LinkedHashSet maintains FIFO order and does not allow duplicates.
        Set<String> browserIdsSet = new LinkedHashSet<>();

        // Put Brave first in the ordered set.
        browserIdsSet.add("com.brave.browser");

        // Add all resolved browsers to the set preserving the order.
        browserIdsSet.addAll(VpnAppsUtils.getInstalledWebBrowserPackageIds(getPackageManager()));

        // Put Chrome at the end if it is not already in the set.
        browserIdsSet.add("com.android.chrome");

        // If we have a candidate then set the app package ID for the browser intent and try to
        // start the app with the intent right away.
        for (String id : browserIdsSet) {
            // Note that VpnAppsUtils.isTunneledAppId(...) will return true as long as the app is not
            // excluded from VPN in the settings, even if the app is not installed!
            if (VpnAppsUtils.isTunneledAppId(context, id)) {
                browserIntent.setPackage(id);
                try {
                    context.startActivity(browserIntent);
                    // Return immediately if success.
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // Continue looping if error.
                }
            }
        }

        // We don't have an explicit package ID for the browser intent at this point - let the
        // system handle it with an implicit intent.
        // Note that the browser picked by the system will be most likely not tunneled.

        // Remove the package ID and set intent's action to ACTION_VIEW.
        browserIntent.setPackage(null);
        browserIntent.setAction(Intent.ACTION_VIEW);

        // Specify the URL to load.
        // Since there is no explicit package ID the URL cannot be empty. In this case try loading
        // a special URL 'about:blank'.
        if (!TextUtils.isEmpty(urlString)) {
            browserIntent.setData(Uri.parse(urlString));
        } else {
            browserIntent.setData(Uri.parse("about:blank"));
        }

        try {
            context.startActivity(browserIntent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Fail silently.
        }
    }

    private void HandleCurrentIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        // Handle external deep links first
        // Examples:
        // psiphon://settings
        // psiphon://settings/vpn
        // psiphon://psicash
        // psiphon://psicash/
        // psiphon://psicash/buy
        // psiphon://psicash/speedboost
        // psiphon://psicash/speedboost/extras
        // psiphon://subscribe
        // psiphon://subscribe/timepass
        // psiphon://subscribe/subscription
        if (handleDeepLinkIntent(intent)) {
            return;
        }

        // MainActivity is exposed to other apps because it is declared as an entry point activity of the app in the manifest.
        // For the purpose of handling internal intents, such as handshake, etc., from the tunnel service we have declared a not
        // exported activity alias 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler' that should act as a proxy for MainActivity.
        // We expect our own intents have a component set to 'com.psiphon3.psiphonlibrary.TunnelIntentsHandler', all other intents
        // should be ignored.
        ComponentName tunnelIntentsActivityComponentName =
                new ComponentName(this, "com.psiphon3.psiphonlibrary.TunnelIntentsHandler");
        if (!tunnelIntentsActivityComponentName.equals(intent.getComponent())) {
            return;
        }

        if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_HANDSHAKE)) {
            Bundle data = intent.getExtras();
            if (data != null) {
                ArrayList<String> homePages = data.getStringArrayList(TunnelManager.DATA_TUNNEL_STATE_HOME_PAGES);
                if (homePages != null && homePages.size() > 0) {
                    String url = homePages.get(0);
                    // Check whether the URL should be opened in an internal modal WebView container
                    // or in an external browser.
                    if (shouldLoadInEmbeddedWebView(url)) {
                        openModalWebView(url);
                    } else {
                        displayBrowser(this, url);
                    }
                }
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SELECTED_REGION_NOT_AVAILABLE)) {
            // At this point the service should be stopped and the persisted region selection set
            // to PsiphonConstants.REGION_CODE_ANY by TunnelManager, so we only need to update the
            // region selection UI.

            // Switch to settings tab
            selectTabByTag("settings");
            // Signal Rx subscription in the options tab to update available regions list
            viewModel.signalAvailableRegionsUpdate();

            // Show "Selected region unavailable" toast
            Toast toast = Toast.makeText(this, R.string.selected_region_currently_not_available, Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_VPN_REVOKED)) {
            showVpnAlertDialog(R.string.StatusActivity_VpnRevokedTitle, R.string.StatusActivity_VpnRevokedMessage);
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_SHOW_PURCHASE_PROMPT)) {
            if (!isFinishing()) {
                if (purchaseRequiredDialog != null && purchaseRequiredDialog.isShowing()) {
                    // Already showing, do nothing
                    return;
                }
                // Cancel disallowed traffic alert if it is showing
                if (disallowedTrafficAlertDialog != null && disallowedTrafficAlertDialog.isShowing()) {
                    disallowedTrafficAlertDialog.dismiss();
                }

                purchaseRequiredDialog = new PurchaseRequiredDialog.Builder(this, this)
                        .setDisconnectTunnelRunnable(() -> compositeDisposable.add(
                                getTunnelServiceInteractor().tunnelStateFlowable()
                                        .filter(state -> !state.isUnknown())
                                        .firstOrError()
                                        .doOnSuccess(state -> {
                                            if (state.isRunning()) {
                                                getTunnelServiceInteractor().stopTunnelService();
                                            }
                                        })
                                        .subscribe()
                        ))
                        .setSpeedBoostClickListener(v -> {
                            UiHelpers.openPsiCashStoreActivity(this,
                                    getResources().getInteger(R.integer.speedBoostTabIndex));
                            purchaseRequiredDialog.dismiss();
                        })
                        .setSubscribeClickListener(v -> {
                            MainActivity.openPaymentChooserActivity(MainActivity.this,
                                    getResources().getInteger(R.integer.subscriptionTabIndex));
                            purchaseRequiredDialog.dismiss();
                        })
                        .show();
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_DISALLOWED_TRAFFIC)) {
            // Do not show disallowed traffic alert if purchase required prompt is showing
            if (purchaseRequiredDialog != null && purchaseRequiredDialog.isShowing()) {
                return;
            }
            if (!isFinishing()) {
                LayoutInflater inflater = this.getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.disallowed_traffic_alert_layout, null);
                disallowedTrafficAlertDialog = new AlertDialog.Builder(this)
                        .setCancelable(true)
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(R.string.disallowed_traffic_alert_notification_title)
                        .setView(dialogView)
                        .setNeutralButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.btn_get_subscription, (dialog, which) -> {
                            MainActivity.openPaymentChooserActivity(MainActivity.this,
                                    getResources().getInteger(R.integer.subscriptionTabIndex));
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.btn_get_speed_boost, (dialog, which) -> {
                            UiHelpers.openPsiCashStoreActivity(this,
                                    getResources().getInteger(R.integer.speedBoostTabIndex));
                            dialog.dismiss();
                        })
                        .create();
                disallowedTrafficAlertDialog.show();
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_UNSAFE_TRAFFIC)) {
            // Unsafe traffic intent from service notification
            if (!isFinishing()) {
                // Get subject and action URLs from the intent
                Bundle extras = intent.getExtras();
                ArrayList<String> unsafeTrafficSubjects = null;
                ArrayList<String> unsafeTrafficActionUrls = null;
                if (extras != null) {
                    unsafeTrafficSubjects = extras.getStringArrayList(TunnelManager.DATA_UNSAFE_TRAFFIC_SUBJECTS_LIST);
                    unsafeTrafficActionUrls = extras.getStringArrayList(TunnelManager.DATA_UNSAFE_TRAFFIC_ACTION_URLS_LIST);
                }

                LayoutInflater inflater = this.getLayoutInflater();
                View dialogView = inflater.inflate(R.layout.unsafe_traffic_alert_layout, null);
                TextView tv = dialogView.findViewById(R.id.textView);
                if (unsafeTrafficSubjects != null) {
                    tv.append(String.format(Locale.US, "\n"));
                    for (String unsafeTrafficSubject : unsafeTrafficSubjects) {
                        tv.append(String.format(Locale.US, "%s\n", unsafeTrafficSubject));
                    }
                }
                if (unsafeTrafficActionUrls != null) {
                    for (String unsafeTrafficActionUrl : unsafeTrafficActionUrls) {
                        tv.append(String.format(Locale.US, "\n%s", unsafeTrafficActionUrl));
                    }
                }
                Linkify.addLinks(tv, Linkify.WEB_URLS);

                new AlertDialog.Builder(this)
                        .setCancelable(true)
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(R.string.unsafe_traffic_alert_dialog_title)
                        .setView(dialogView)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        } else if (0 == intent.getAction().compareTo(TunnelManager.INTENT_ACTION_UPSTREAM_PROXY_ERROR)) {
            // Switch to Logs tab where upstream proxy error(s) will be posted and show a generic
            // upstream proxy alert dialog once.
            selectTabByTag("logs");
            if ((upstreamProxyErrorAlertDialog == null || !upstreamProxyErrorAlertDialog.isShowing()) && !isFinishing()) {
                upstreamProxyErrorAlertDialog = new AlertDialog.Builder(this)
                        .setCancelable(true)
                        .setIcon(R.drawable.ic_psiphon_alert_notification)
                        .setTitle(R.string.upstream_proxy_error_alert_title)
                        .setMessage(R.string.upstream_proxy_error_alert_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                upstreamProxyErrorAlertDialog.show();
            }
        }
    }

    private boolean handleDeepLinkIntent(@NonNull Intent intent) {
        final String FWD_SLASH = "/";

        final String PSIPHON_SCHEME = "psiphon";

        final String PSICASH_HOST = "psicash";
        final String PSICASH_PATH_BUY = "/buy";
        final String PSICASH_PATH_SPEEDBOOST = "/speedboost";

        final String SETTINGS_HOST = "settings";
        final String SETTINGS_PATH_VPN = "/vpn";
        final String SETTINGS_PATH_PROXY = "/proxy";
        final String SETTINGS_PATH_MORE_OPTIONS = "/more-options";

        final String SUBSCRIBE_HOST = "subscribe";
        final String SUBSCRIPTION_PATH_SUBSCRIPTION = "/subscription";
        final String SUBSCRIPTION_PATH_TIMEPASS = "/timepass";

        Uri intentUri = intent.getData();
        // Check if this is a deep link intent we can handle
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) ||
                intentUri == null ||
                !PSIPHON_SCHEME.equals(intentUri.getScheme())) {
            // Intent not handled
            return false;
        }

        String path = intentUri.getPath();

        switch (intentUri.getHost()) {
            case PSICASH_HOST:
                // Default tab is 'Add PsiCash'
                int tabIndex = getResources().getInteger(R.integer.psiCashTabIndex);

                if (path != null) {
                    if (path.equals(PSICASH_PATH_BUY) || path.startsWith(PSICASH_PATH_BUY + FWD_SLASH)) {
                        // If the uri path is "/buy" or "/buy/.*" then navigate to Add PsiCash tab,
                        tabIndex = getResources().getInteger(R.integer.psiCashTabIndex);
                    } else if (path.equals(PSICASH_PATH_SPEEDBOOST) || path.startsWith(PSICASH_PATH_SPEEDBOOST + FWD_SLASH)) {
                        // The path is "/speedboost" or "/speedboost/.*" - navigate to SpeedBoost tab
                        tabIndex = getResources().getInteger(R.integer.speedBoostTabIndex);
                    }
                }

                UiHelpers.openPsiCashStoreActivity(this, tabIndex);
                // intent handled
                return true;

            case SUBSCRIBE_HOST:
                // Default tab is 'Subscription'
                tabIndex = getResources().getInteger(R.integer.subscriptionTabIndex);

                if (path != null) {
                    if (path.equals(SUBSCRIPTION_PATH_SUBSCRIPTION) || path.startsWith(SUBSCRIPTION_PATH_SUBSCRIPTION + FWD_SLASH)) {
                        // If the uri path is "/subscription" or "/subscription/.*" then navigate to the Subscription tab,
                        tabIndex = getResources().getInteger(R.integer.subscriptionTabIndex);
                    } else if (path.equals(SUBSCRIPTION_PATH_TIMEPASS) || path.startsWith(SUBSCRIPTION_PATH_TIMEPASS + FWD_SLASH)) {
                        // The path is "/timepass" or "/timepass/.*" - navigate to the Time Pass tab
                        tabIndex = getResources().getInteger(R.integer.timePassTabIndex);
                    }
                }

                MainActivity.openPaymentChooserActivity(this, tabIndex);
                // intent handled
                return true;

            case SETTINGS_HOST:
                selectTabByTag("settings");
                if (path != null) {
                    // If uri path is "/vpn" or "/vpn/.*" then signal to navigate to VPN settings screen.
                    // If the path is "/proxy" or "/proxy/.*" then signal to navigate to Proxy settings screen.
                    // If the path is "/more-options" or "/more-options/.*" then signal to navigate to More Options screen.
                    if (path.equals(SETTINGS_PATH_VPN) || path.startsWith(SETTINGS_PATH_VPN + FWD_SLASH)) {
                        viewModel.signalOpenVpnSettings();
                    } else if (path.equals(SETTINGS_PATH_PROXY) || path.startsWith(SETTINGS_PATH_PROXY + FWD_SLASH)) {
                        viewModel.signalOpenProxySettings();
                    } else if (path.equals(SETTINGS_PATH_MORE_OPTIONS) || path.startsWith(SETTINGS_PATH_MORE_OPTIONS)) {
                        viewModel.signalOpenMoreOptions();
                    }
                }
                // intent handled
                return true;
        }
        // intent not handled
        return false;
    }

    @Override
    public void startTunnel() {
        // Don't start if custom proxy settings is selected and values are invalid
        if (!viewModel.validateCustomProxySettings()) {
            return;
        }
        super.startTunnel();
    }

    private void cancelInvalidProxySettingsToast() {
        if (invalidProxySettingsToast != null) {
            View toastView = invalidProxySettingsToast.getView();
            if (toastView != null) {
                if (toastView.isShown()) {
                    invalidProxySettingsToast.cancel();
                }
            }
        }
    }

    private void preventAutoStart() {
        isFirstRun = false;
    }

    private boolean shouldAutoStart() {
        return isFirstRun &&
                !getIntent().getBooleanExtra(INTENT_EXTRA_PREVENT_AUTO_START, false);
    }

    // Returns an object only if tunnel should be auto-started,
    // completes with no value otherwise.
    private Maybe<Object> autoStartMaybe() {
        return Maybe.create(emitter -> {
            boolean shouldAutoStart = shouldAutoStart();
            preventAutoStart();
            if (!emitter.isDisposed()) {
                if (shouldAutoStart) {
                    emitter.onSuccess(new Object());
                } else {
                    emitter.onComplete();
                }
            }
        }).flatMap(autoStart -> {
            // If this is a first app run then check subscription state and
            // return a value if user has a valid purchase or if IAB check failed,
            // the IAB status check will be triggered again in onResume
            return googlePlayBillingHelper.subscriptionStateFlowable()
                    .firstOrError()
                    .flatMapMaybe(subscriptionState -> {
                        if (subscriptionState.hasValidPurchase()
                                || subscriptionState.status() == SubscriptionState.Status.IAB_FAILURE) {
                            return Maybe.just(new Object());
                        }
                        return Maybe.empty();
                    });
        });
    }

    public static boolean shouldLoadInEmbeddedWebView(String url) {
        for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
            if (url.contains(homeTabUrlExclusion)) {
                return false;
            }
        }
        return true;
    }

    private void showToast(int stringResId) {
        Toast toast = Toast.makeText(this, stringResId, Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }

    private String PsiCashModifyUrl(String originalUrlString) {
        if (TextUtils.isEmpty(originalUrlString)) {
            return originalUrlString;
        }

        try {
            return PsiCashClient.getInstance(getApplicationContext()).modifiedHomePageURL(originalUrlString);
        } catch (PsiCashException e) {
            MyLog.e("PsiCash: error modifying home page: " + e);
        }
        return originalUrlString;
    }

    public void selectTabByTag(@NonNull Object tag) {
        viewPager.post(() -> {
            for (int i = 0; i < tabLayout.getTabCount(); i++) {
                TabLayout.Tab tab = tabLayout.getTabAt(i);
                if (tab != null) {
                    Object tabTag = tabLayout.getTabAt(i).getTag();
                    if (tag.equals(tabTag)) {
                        viewPager.setCurrentItem(i, true);
                    }
                }
            }
        });
    }

    private void openModalWebView(String url) {
        if (isFinishing()) {
            return;
        }

        try {
            LayoutInflater inflater = LayoutInflater.from(this);
            View webViewContainer = inflater.inflate(R.layout.embedded_webview_layout, null);

            final WebView webView = webViewContainer.findViewById(R.id.sponsorWebView);
            final ProgressBar progressBar = webViewContainer.findViewById(R.id.sponsorWebViewProgressBar);

            SponsorHomePage sponsorHomePage = new SponsorHomePage(webView, progressBar);
            sponsorHomePage.setOnUrlClickListener(u -> viewModel.signalExternalBrowserUrl(u));

            final AlertDialog alertDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setTitle(R.string.waiting) // start with "Wating..." title
                    .setView(webViewContainer)
                    .setPositiveButton(R.string.label_close,
                            (dialog, whichButton) -> {
                            })
                    .setOnDismissListener(dialog -> sponsorHomePage.destroy())
                    .show();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(alertDialog.getWindow().getAttributes());
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            alertDialog.getWindow().setAttributes(lp);

            sponsorHomePage.setOnTitleChangedListener(alertDialog::setTitle);

            // Add PsiCash parameters to the original URL.
            final String psiCashModifyUrl = PsiCashModifyUrl(url);

            sponsorHomePage.load(psiCashModifyUrl);
        } catch (RuntimeException ignored) {
        }
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
            this.numOfTabs = numOfTabs;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return new HomeTabFragment();
                case 1:
                    return new StatisticsTabFragment();
                case 2:
                    return new OptionsTabFragment();
                case 3:
                    return new LogsTabFragment();
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
