/*
 * Copyright (c) 2022, Psiphon Inc.
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
 */

package com.psiphon3;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

import com.psiphon3.subscription.R;

public class PurchaseRequiredDialog {
    private final Dialog dialog;
    private final Button subscribeBtn;
    private final Button speedBoostBtn;
    private final Button disconnectBtn;

    public Button getSubscribeBtn() {
        return subscribeBtn;
    }

    public Button getSpeedBoostBtn() {
        return speedBoostBtn;
    }

    public Button getDisconnectBtn() {
        return disconnectBtn;
    }

    public PurchaseRequiredDialog(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);

        View contentView = inflater.inflate(R.layout.purchase_required_prompt_layout, null);
        subscribeBtn = contentView.findViewById(R.id.btn_subscribe);
        speedBoostBtn = contentView.findViewById(R.id.btn_speedboost);
        disconnectBtn = contentView.findViewById(R.id.btn_disconnect);

        dialog = new Dialog(context, R.style.Theme_NoTitleDialog_Transparent);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialog.setCancelable(false);
        dialog.setContentView(contentView);
    }

    public void show() {
        dialog.show();
        // Full screen resize
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(dialog.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.MATCH_PARENT;
        lp.height = WindowManager.LayoutParams.MATCH_PARENT;
        dialog.getWindow().setAttributes(lp);
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }
    public void dismiss() {
        dialog.dismiss();
    }
}
