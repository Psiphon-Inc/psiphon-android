/*
 *
 * Copyright (c) 2019, Psiphon Inc.
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

package com.psiphon3.psicash;

import android.app.Application;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.support.annotation.NonNull;

public class PsiCashViewModelFactory implements ViewModelProvider.Factory {

    private final Application application;
    private final PsiCashListener psiCashListener;

    public PsiCashViewModelFactory(@NonNull Application application, @NonNull PsiCashListener listener) {
        this.application = application;
        this.psiCashListener = listener;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(PsiCashViewModel.class)) {
            return (T) new PsiCashViewModel(application, psiCashListener);
        }
        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}
