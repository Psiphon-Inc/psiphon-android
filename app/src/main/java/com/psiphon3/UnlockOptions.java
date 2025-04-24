package com.psiphon3;

import androidx.annotation.NonNull;

import com.jakewharton.rxrelay2.BehaviorRelay;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class UnlockOptions {
    public static final String CHECKER_SUBSCRIPTION = "Subscription";
    public static final String CHECKER_CONDUIT = "Conduit";

    private final Map<String, Supplier<Boolean>> checkers = new ConcurrentHashMap<>();
    private final BehaviorRelay<Object> checkersChangedRelay = BehaviorRelay.create();


    // Check if we need to show the unlock dialog
    public boolean unlockRequired() {
        if (checkers.isEmpty()) {
            // No checkers available, no need to show the unlock dialog
            return false;
        }
        for (Supplier<Boolean> checker : checkers.values()) {
            if (checker.get()) {
                // If any checker passed, we don't need to show the unlock dialog
                return false;
            }
        }

        // No checker passed, we need to show the unlock dialog
        return true;
    }

    public synchronized void setCheckers(@NonNull  Map<String, Supplier<Boolean>> checkersMap) {
        checkers.clear();
        checkers.putAll(checkersMap);

        // Notify that the checkers have changed
        checkersChangedRelay.accept(new Object());
    }

    public Set<String> getActiveUnlockOptions() {
        return new HashSet<>(checkers.keySet());
    }

    public boolean hasConduit() {
        return checkers.containsKey(CHECKER_CONDUIT);
    }

    public boolean hasSubscription() {
        return checkers.containsKey(CHECKER_SUBSCRIPTION);
    }

    public Flowable<Object> getCheckersChangedFlowable() {
        return checkersChangedRelay
                .hide()
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }
}