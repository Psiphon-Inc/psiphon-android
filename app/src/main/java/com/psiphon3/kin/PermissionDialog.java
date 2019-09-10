package com.psiphon3.kin;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Button;

import com.psiphon3.subscription.R;

import io.reactivex.SingleEmitter;

public class PermissionDialog extends Dialog implements View.OnClickListener {

    private final Context context;
    private final SingleEmitter<Boolean> emitter;

    PermissionDialog(Context context, SingleEmitter<Boolean> emitter) {
        super(context);
        this.context = context;
        this.emitter = emitter;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_kin_onboarding);

        Button button = findViewById(R.id.btn_kin_agree);
        if (button != null) {
            button.setOnClickListener(this);
        }

        button = findViewById(R.id.btn_kin_cancel);
        if (button != null) {
            button.setOnClickListener(this);
        }
    }

    @Override
    public void onClick(View v) {
        boolean hasAgreedToKin = R.id.btn_kin_agree == v.getId();
        KinPermissionManager.setHasAgreedToKin(context, true);
        emitter.onSuccess(hasAgreedToKin);
        dismiss();
    }
}