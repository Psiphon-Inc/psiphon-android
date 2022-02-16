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

package com.psiphon3.psicash.store;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.psiphon3.log.MyLog;
import com.psiphon3.subscription.R;

public class InvalidPsiCashStateFragment extends Fragment {
    public InvalidPsiCashStateFragment() {
        super(R.layout.psicash_invalid_state_fragment);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((PsiCashStoreActivity) requireActivity()).hideProgress();

        // Log finish the activity with a fade out if we somehow ended up here
        MyLog.w("PsiCashStoreFragment: invalid state, finishing the container activity.");
        try {
            requireActivity().setResult(Activity.RESULT_OK);
            requireActivity().finish();
            requireActivity().overridePendingTransition(0, android.R.anim.fade_out);
        } catch (RuntimeException ignored) {
        }
    }
}
