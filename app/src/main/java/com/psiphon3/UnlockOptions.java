package com.psiphon3;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jakewharton.rxrelay2.BehaviorRelay;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class UnlockOptions {
    public static final String UNLOCK_ENTRY_SUBSCRIPTION = "Subscription";
    public static final String UNLOCK_ENTRY_CONDUIT = "Conduit";

    private final Map<String, UnlockEntry> entries = new ConcurrentHashMap<>();
    private final BehaviorRelay<Set<String>> entriesSetRelay = BehaviorRelay.create();

    public static class UnlockEntry {
        public final Supplier<Boolean> checker;
        public final Boolean display;

        public UnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display) {
            this.checker = checker;
            this.display = display;
        }

        public boolean isDisplayable() {
            // If display is not set, we assume it should be displayed
            return display == null || display;
        }
    }

    // Check if we need to show the unlock dialog
    public boolean unlockRequired() {
        if (entries.isEmpty()) {
            // No checkers available, no need to show the unlock dialog
            return false;
        }

        boolean hasAtLeasOneDisplayableEntry = false;

        for (UnlockEntry entry : entries.values()) {
            if (entry.isDisplayable()) {
                hasAtLeasOneDisplayableEntry = true;
            }
            if (entry.checker.get()) {
                // If any checker passed, we don't need to show the unlock dialog
                return false;
            }
        }

        // If no checkers passed and we have at least one displayable entry,
        // we need to show the unlock dialog
        return hasAtLeasOneDisplayableEntry;
    }

    public synchronized void setEntries(@NonNull  Map<String, UnlockEntry> entryMap) {
        entries.clear();
        entries.putAll(entryMap);

        // Update the relay with the current set of checkers
        entriesSetRelay.accept(entryMap.keySet());
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        for (Map.Entry<String, UnlockEntry> entry : entries.entrySet()) {
            Bundle entryBundle = new Bundle();
            entryBundle.putBoolean("display", entry.getValue().isDisplayable());
            bundle.putBundle(entry.getKey(), entryBundle);
        }
        return bundle;
    }

    public static Map<String, Boolean> fromBundle(@Nullable Bundle bundle) {
        Map<String, Boolean> result = new HashMap<>();
        if (bundle == null) {
            return result;
        }
        for (String key : bundle.keySet()) {
            Bundle entry = bundle.getBundle(key);
            if (entry != null && entry.containsKey("display")) {
                result.put(key, entry.getBoolean("display"));
            }
        }
        return result;
    }

    public boolean hasConduitEntry() {
        return entries.containsKey(UNLOCK_ENTRY_CONDUIT);
    }

    public boolean hasSubscriptionEntry() {
        return entries.containsKey(UNLOCK_ENTRY_SUBSCRIPTION);
    }

    public Flowable<Set<String>> getEntriesSetFlowable() {
        return entriesSetRelay
                .hide()
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }
}