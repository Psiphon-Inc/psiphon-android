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
 * Object that will subscribes to a {@link MviView}'s {@link MviIntent}s,
 * process it and emit a {@link MviViewState} back.
 *
 * @param <I> Top class of the {@link MviIntent} that the {@link MviViewModel} will be subscribing
 *            to.
 * @param <S> Top class of the {@link MviViewState} the {@link MviViewModel} will be emitting.
 */
public interface MviViewModel<I extends MviIntent, S extends MviViewState> {
    void processIntents(Observable<I> intents);

    Observable<S> states();
}
