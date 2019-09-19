package com.psiphon3.kin;

import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.R;

public class KinActivity extends LocalizedActivities.AppCompatActivity {

    private static final int FUND_AMOUNT = 715;

    private SettingsManager settingsManager;
    private KinPermissionManager kinPermissionManager;
    private KinManager kinManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kin);

        settingsManager = new SettingsManager();
        kinPermissionManager = new KinPermissionManager(settingsManager);

        kinManager = KinManager.getInstance(this);
        kinManager.isReadyObservable()
                .doOnNext(ready -> {
                    if (ready) {
                        showOptedInUI();
                    }
                })
                .subscribe();
    }

    public void onAutoPaySwitchClick(View v) {
        Switch autoPaySwitch = (Switch) v;
        boolean isChecked = autoPaySwitch.isChecked();
        if (isChecked) {
            kinPermissionManager.confirmAutoPaySwitch(this).doOnSuccess(autoPaySwitch::setChecked).subscribe();
        } else {
            settingsManager.setHasAgreedToAutoPay(this, false);
        }
    }

    public void onOptOutClick(View v) {
        kinManager.optOut(this)
                .doOnSuccess(optedOut -> {
                    if (optedOut) {
                        showOptedOutUI();
                    }
                })
                .subscribe();
    }

    public void onOptInClick(View v) {
        kinManager.optIn(this)
                .doOnSuccess(optedIn -> {
                    if (optedIn) {
                        showOptedInUI();
                    }
                })
                .subscribe();
    }

    private void showOptedInUI() {
        kinManager.getCurrentBalance()
                .doOnSuccess(balance -> {
                    TextView textView = findViewById(R.id.txt_kin_balance_value);
                    if (textView == null) {
                        return;
                    }

                    textView.setText(String.format("%s", FUND_AMOUNT - balance.intValue()));
                })
                .subscribe();

        // TODO: Group the two UI's to make toggling easier
        Switch autoPaySwitch = findViewById(R.id.switch_auto_pay);
        autoPaySwitch.setChecked(settingsManager.hasAgreedToAutoPay(this));
        autoPaySwitch.setVisibility(View.VISIBLE);

        findViewById(R.id.txt_kin_balance_label).setVisibility(View.VISIBLE);
        findViewById(R.id.txt_kin_balance_value).setVisibility(View.VISIBLE);
        findViewById(R.id.btn_opt_out).setVisibility(View.VISIBLE);

        findViewById(R.id.btn_opt_in).setVisibility(View.GONE);
    }

    private void showOptedOutUI() {
        findViewById(R.id.switch_auto_pay).setVisibility(View.GONE);
        findViewById(R.id.txt_kin_balance_label).setVisibility(View.GONE);
        findViewById(R.id.txt_kin_balance_value).setVisibility(View.GONE);
        findViewById(R.id.btn_opt_out).setVisibility(View.GONE);

        findViewById(R.id.btn_opt_in).setVisibility(View.VISIBLE);
    }
}
