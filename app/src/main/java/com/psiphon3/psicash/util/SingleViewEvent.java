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

package com.psiphon3.psicash.util;

import androidx.core.util.Consumer;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicBoolean;

public class SingleViewEvent<T> {
    private final T payload;
    @NotNull
    private final AtomicBoolean isConsumed = new AtomicBoolean(false);

    public SingleViewEvent(T payload) {
        this.payload = payload;
    }

    public final void consume(@NotNull Consumer<T> action) {
        if (!isConsumed.getAndSet(true)) {
            action.accept(payload);
        }
    }
}
