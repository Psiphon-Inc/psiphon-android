/*
 * Copyright (c) 2021, Psiphon Inc.
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

package com.psiphon3.psicash.account;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.psiphon3.MainActivity;
import com.psiphon3.subscription.R;

public class PsiphonNotConnectedFragment extends Fragment {
    public PsiphonNotConnectedFragment() {
        super(R.layout.psicash_account_psiphon_not_connected_fragment);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((PsiCashAccountActivity) requireActivity()).hideProgress();
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
