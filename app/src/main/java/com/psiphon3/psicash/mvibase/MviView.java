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

package com.psiphon3.psicash.mvibase;

import io.reactivex.Observable;

// Based on https://github.com/oldergod/android-architecture/tree/todo-mvi-rxjava
/**
 * Object representing a UI that will
 * a) emit its intents to a view model,
 * b) subscribes to a view model for rendering its UI.
 *
 * @param <I> Top class of the {@link MviIntent} that the {@link MviView} will be emitting.
 * @param <S> Top class of the {@link MviViewState} the {@link MviView} will be subscribing to.
 */
public interface MviView<I extends MviIntent, S extends MviViewState> {
    /**
     * Unique {@link Observable<I>} used by the {@link MviViewModel}
     * to listen to the {@link MviView}.
     * All the {@link MviView}'s {@link MviIntent}s must go through this {@link Observable<I>}.
     */
    Observable<I> intents();

    /**
     * Entry point for the {@link MviView} to render itself based on a {@link MviViewState}.
     */
    void render(S state);
}
