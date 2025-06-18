package com.psiphon3;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.log.MyLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class UnlockOptions {
    public static final String UNLOCK_ENTRY_SUBSCRIPTION = "Subscription";
    public static final String UNLOCK_ENTRY_CONDUIT = "Conduit";
    public static final String APP_INSTALL_PREFIX = "AppInstall.";

    // Default priorities when not specified in JSON (the lower the number, the higher the priority)
    public static final int DEFAULT_CONDUIT_PRIORITY = 10;
    public static final int DEFAULT_SUBSCRIPTION_PRIORITY = 50;
    public static final int DEFAULT_APP_INSTALL_PRIORITY = 80;

    // File persistence constants
    private static final String UNLOCK_OPTIONS_FILE = "unlock_options.json";
    private static final String UNLOCK_OPTIONS_TEMP_FILE = "unlock_options_temp.json";
    private static final String UNLOCK_OPTIONS_LOCK_FILE = "unlock_options.lock";

    private final Map<String, UnlockEntry> entries = new ConcurrentHashMap<>();
    private final BehaviorRelay<Set<String>> entriesSetRelay = BehaviorRelay.create();

    public static class UnlockEntry {
        public final @Nullable Supplier<Boolean> checker;
        public final Boolean display;
        public final int priority;

        // Service-side constructor (with checker)
        public UnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display, int priority) {
            this.checker = checker;
            this.display = display;
            this.priority = priority;
        }

        // Client-side constructor (without checker) used to render the unlock dialog
        protected UnlockEntry(boolean display, int priority) {
            this.checker = null;
            this.display = display;
            this.priority = priority;
        }

        public boolean isDisplayable() {
            // If display is not set, we assume it should be displayed
            return display == null || display;
        }
    }

    public static class ConduitUnlockEntry extends UnlockEntry {
        public final String referrer;

        // Service-side constructor (with checker)
        public ConduitUnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display, int priority,
                                     String referrer) {
            super(checker, display, priority);
            this.referrer = referrer;
        }

        // Client-side constructor (without checker)
        private ConduitUnlockEntry(boolean display, int priority, String referrer) {
            super(display, priority);
            this.referrer = referrer;
        }
    }

    public static class AppInstallUnlockEntry extends UnlockEntry {
        public final String appId;
        public final String appName;
        public final String playStoreUrl;

        // Service-side constructor (with checker)
        public AppInstallUnlockEntry(@NonNull Supplier<Boolean> checker, @Nullable Boolean display, int priority,
                                     String appId, String appName, String playStoreUrl) {
            super(checker, display, priority);
            this.appId = appId;
            this.appName = appName;
            this.playStoreUrl = playStoreUrl;
        }

        // Client-side constructor (without checker)
        private AppInstallUnlockEntry(boolean display, int priority, String appId, String appName, String playStoreUrl) {
            super(display, priority);
            this.appId = appId;
            this.appName = appName;
            this.playStoreUrl = playStoreUrl;
        }
    }

    // Check if we need to show the unlock dialog (service-side only)
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
            // This will crash if the checker is null, which is expected, since we only call this method
            // on the service side where all entries should have a checker.
            if (entry.checker.get()) {
                // If any checker passed, we don't need to show the unlock dialog
                return false;
            }
        }

        // If no checkers passed and we have at least one displayable entry,
        // we need to show the unlock dialog
        return hasAtLeasOneDisplayableEntry;
    }

    public synchronized void setEntries(@NonNull Map<String, UnlockEntry> entryMap) {
        entries.clear();
        entries.putAll(entryMap);
        // Update the relay with the current set of checkers
        entriesSetRelay.accept(entryMap.keySet());
    }

    public synchronized void reset() {
        entries.clear();
    }

    // Convert current entries to JSON format for persistence
    public String toJsonString() {
        try {
            JSONObject jsonObject = new JSONObject();
            for (Map.Entry<String, UnlockEntry> entry : entries.entrySet()) {
                JSONObject entryJson = new JSONObject();
                entryJson.put("display", entry.getValue().isDisplayable());
                entryJson.put("priority", entry.getValue().priority);

                // Add additional fields for AppInstallUnlockEntry
                if (entry.getValue() instanceof AppInstallUnlockEntry) {
                    AppInstallUnlockEntry appEntry = (AppInstallUnlockEntry) entry.getValue();
                    entryJson.put("appId", appEntry.appId);
                    entryJson.put("appName", appEntry.appName);
                    entryJson.put("playStoreUrl", appEntry.playStoreUrl);
                }
                // Add additional fields for ConduitUnlockEntry
                if (entry.getValue() instanceof ConduitUnlockEntry) {
                    ConduitUnlockEntry conduitEntry = (ConduitUnlockEntry) entry.getValue();
                    if (conduitEntry.referrer != null) {
                        entryJson.put("referrer", conduitEntry.referrer);
                    }
                }

                jsonObject.put(entry.getKey(), entryJson);
            }
            return jsonObject.toString();
        } catch (JSONException e) {
            MyLog.e("UnlockOptions: failed to convert to JSON: " + e);
            return null;
        }
    }

    // Save unlock options to file
    public static void toFile(Context context, String jsonString) {
        if (jsonString == null) {
            MyLog.w("UnlockOptions: Cannot save null JSON string");
            return;
        }

        File tempFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_TEMP_FILE);
        File finalFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_FILE);
        File lockFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_LOCK_FILE);

        RandomAccessFile randomAccessFile = null;
        FileChannel channel = null;
        FileLock lock = null;

        try {
            randomAccessFile = new RandomAccessFile(lockFile, "rw");
            channel = randomAccessFile.getChannel();
            try {
                lock = channel.lock();
            } catch (OverlappingFileLockException e) {
                MyLog.e("UnlockOptions: Lock already held by this JVM: " + e);
                return;
            }

            try {
                // Write to temporary file first to ensure atomic update
                try (FileOutputStream fos = new FileOutputStream(tempFile);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8")) {
                    writer.write(jsonString);
                    writer.flush();
                    fos.getFD().sync();
                }

                // Atomic rename operation
                if (!tempFile.renameTo(finalFile)) {
                    MyLog.e("UnlockOptions: Failed to rename temp file to final file.");
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        } catch (IOException e) {
            MyLog.e("UnlockOptions: failed to save unlock options: " + e);
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to release lock: " + e);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to close channel: " + e);
                }
            }
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to close random access file: " + e);
                }
            }
        }
    }

    // Read unlock options from file - returns UnlockOptions instance
    @SuppressWarnings("resource")
    public static UnlockOptions fromFile(Context context) {
        File file = new File(context.getFilesDir(), UNLOCK_OPTIONS_FILE);
        File lockFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_LOCK_FILE);
        Map<String, UnlockEntry> entriesMap = new HashMap<>();

        RandomAccessFile randomAccessFile = null;
        FileChannel channel = null;
        FileLock lock = null;

        try {
            randomAccessFile = new RandomAccessFile(lockFile, "rw");
            channel = randomAccessFile.getChannel();
            try {
                lock = channel.lock(0L, Long.MAX_VALUE, true); // shared lock
            } catch (OverlappingFileLockException e) {
                MyLog.e("UnlockOptions: Read lock already held by this JVM: " + e);
                UnlockOptions unlockOptions = new UnlockOptions();
                unlockOptions.entries.putAll(entriesMap);
                return unlockOptions;
            }

            if (file.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line);
                    }

                    // Parse JSON and convert to Map
                    JSONObject jsonObject = new JSONObject(builder.toString());
                    Iterator<String> keys = jsonObject.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject entryJson = jsonObject.getJSONObject(key);
                        boolean display = entryJson.optBoolean("display", true);

                        // Get priority with appropriate defaults
                        int priority;
                        if (entryJson.has("priority")) {
                            priority = entryJson.getInt("priority");
                        } else {
                            // Set default priority based on entry type
                            if (UNLOCK_ENTRY_CONDUIT.equals(key)) {
                                priority = DEFAULT_CONDUIT_PRIORITY;
                            } else if (UNLOCK_ENTRY_SUBSCRIPTION.equals(key)) {
                                priority = DEFAULT_SUBSCRIPTION_PRIORITY;
                            } else if (key.startsWith(APP_INSTALL_PREFIX)) {
                                priority = DEFAULT_APP_INSTALL_PRIORITY;
                            } else {
                                priority = Integer.MAX_VALUE; // Lowest priority for unknown types
                            }
                        }

                        // Create appropriate entry type
                        if (key.startsWith(APP_INSTALL_PREFIX)) {
                            String appId = entryJson.optString("appId", "");
                            String appName = entryJson.optString("appName", "");
                            String playStoreUrl = entryJson.optString("playStoreUrl", "");
                            if (appId.isEmpty() || appName.isEmpty() || playStoreUrl.isEmpty()) {
                                MyLog.w("UnlockOptions: Skipping app install entry with empty fields: " + key);
                                continue;
                            }
                            entriesMap.put(key, new AppInstallUnlockEntry(display, priority, appId, appName, playStoreUrl));
                        } else if (UNLOCK_ENTRY_CONDUIT.equals(key)) {
                            String referrer = entryJson.optString("referrer", "");
                            entriesMap.put(key, new ConduitUnlockEntry(display, priority, referrer));
                        } else {
                            entriesMap.put(key, new UnlockEntry(display, priority));
                        }
                    }
                }
            }
        } catch (IOException | JSONException e) {
            MyLog.e("UnlockOptions: failed to read unlock options: " + e);
        } finally {
            if (lock != null) {
                try {
                    lock.release();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to release lock: " + e);
                }
            }
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to close channel: " + e);
                }
            }
            if (randomAccessFile != null) {
                try {
                    randomAccessFile.close();
                } catch (IOException e) {
                    MyLog.e("UnlockOptions: failed to close random access file: " + e);
                }
            }
        }

        UnlockOptions unlockOptions = new UnlockOptions();
        unlockOptions.entries.putAll(entriesMap);
        return unlockOptions;
    }

    // Clear persisted unlock options
    public static void clear(Context context) {
        File file = new File(context.getFilesDir(), UNLOCK_OPTIONS_FILE);
        File lockFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_LOCK_FILE);
        File tempFile = new File(context.getFilesDir(), UNLOCK_OPTIONS_TEMP_FILE);

        if (file.exists()) {
            if (!file.delete()) {
                MyLog.w("UnlockOptions: Failed to delete unlock options file");
            }
        }
        if (tempFile.exists()) {
            if (!tempFile.delete()) {
                MyLog.w("UnlockOptions: Failed to delete unlock options temp file");
            }
        }
        if (lockFile.exists()) {
            if (!lockFile.delete()) {
                MyLog.w("UnlockOptions: Failed to delete unlock options lock file");
            }
        }
    }

    public boolean hasConduitEntry() {
        return entries.containsKey(UNLOCK_ENTRY_CONDUIT);
    }

    public boolean hasSubscriptionEntry() {
        return entries.containsKey(UNLOCK_ENTRY_SUBSCRIPTION);
    }

    public boolean hasAppInstallEntries() {
        return entries.keySet().stream().anyMatch(key -> key.startsWith(APP_INSTALL_PREFIX));
    }

    public boolean isEntryDisplayable(String key) {
        UnlockEntry entry = entries.get(key);
        return entry != null && entry.isDisplayable();
    }

    public boolean hasDisplayableEntries() {
        return entries.values().stream().anyMatch(UnlockEntry::isDisplayable);
    }

    public Map<String, UnlockEntry> getAllEntries() {
        return new HashMap<>(entries);
    }

    public Flowable<Set<String>> getEntriesSetFlowable() {
        return entriesSetRelay
                .hide()
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }
}