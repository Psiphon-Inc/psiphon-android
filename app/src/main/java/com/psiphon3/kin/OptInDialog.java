package com.psiphon3.kin;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import com.psiphon3.subscription.R;

public class OptInDialog extends Dialog implements View.OnClickListener, DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {
    private final Context context;
    private final OnCloseListener closeListener;

    private OptInDialog(Context context, OnCloseListener closeListener) {
        super(context);
        this.context = context;
        this.closeListener = closeListener;
    }

    public static void show(Context context, OnCloseListener closeListener) {
        OptInDialog optInDialog = new OptInDialog(context, closeListener);
        optInDialog.setCancelable(false);
        optInDialog.setCanceledOnTouchOutside(false);
        optInDialog.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_kin_onboarding);

        Window w = getWindow();
        if (w != null) {
            w.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }

        // Set up the on click listener for the buttons
        Button button = findViewById(R.id.btn_kin_agree);
        if (button != null) {
            button.setOnClickListener(this);
        }

        button = findViewById(R.id.btn_kin_disagree);
        if (button != null) {
            button.setOnClickListener(this);
        }

        // Make the links in the text view clickable
        TextView textView = findViewById(R.id.txt_kin_onboarding);
        SpannableString spannableString = new SpannableString(context.getText(R.string.lbl_kin_onboard_explanation));
        Linkify.addLinks(spannableString, Linkify.WEB_URLS);
        textView.setText(spannableString);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public void onClick(View view) {
        closeListener.closedBy(view.getId() == R.id.btn_kin_agree ? BUTTON_POSITIVE : BUTTON_NEGATIVE);
        dismiss();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        // Count this as no decision
        closeListener.closedBy(BUTTON_NEUTRAL);
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        // Count this as no decision
        closeListener.closedBy(BUTTON_NEUTRAL);
    }

    public interface OnCloseListener {
        void closedBy(int button);
    }
}