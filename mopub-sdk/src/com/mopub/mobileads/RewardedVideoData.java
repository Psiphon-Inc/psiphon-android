package com.mopub.mobileads;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Used to manage the mapping between MoPub ad unit ids and third-party ad network ids for rewarded videos.
 */
class RewardedVideoData {
    @NonNull
    private final Map<String, CustomEventRewardedVideo> mAdUnitToCustomEventMap;
    @NonNull
    private final Map<TwoPartKey, Set<String>> mCustomEventToMoPubIdMap;
    @NonNull
    private final Set<CustomEventRewardedVideo.CustomEventRewardedVideoListener> mAdNetworkListeners;

    RewardedVideoData() {
        mAdUnitToCustomEventMap = new TreeMap<String, CustomEventRewardedVideo>();
        mCustomEventToMoPubIdMap = new HashMap<TwoPartKey, Set<String>>();
        mAdNetworkListeners = new HashSet<CustomEventRewardedVideo.CustomEventRewardedVideoListener>();
    }

    @Nullable
    CustomEventRewardedVideo getCustomEvent(@NonNull String moPubId) {
        return mAdUnitToCustomEventMap.get(moPubId);
    }

    @NonNull
    Set<String> getMoPubIdsForAdNetwork(
            @NonNull Class<? extends CustomEventRewardedVideo> customEventClass,
            @Nullable String adNetworkId) {
        if (adNetworkId == null) {
            final Set<String> allIds = new HashSet<String>();
            for (final Map.Entry<TwoPartKey, Set<String>> entry : mCustomEventToMoPubIdMap.entrySet()) {
                final Class<?> clazz = entry.getKey().customEventClass;
                if (customEventClass == clazz) {
                    allIds.addAll(entry.getValue());
                }
            }
            return allIds;
        } else {
            final TwoPartKey key = new TwoPartKey(customEventClass, adNetworkId);
            return mCustomEventToMoPubIdMap.containsKey(key)
                    ? mCustomEventToMoPubIdMap.get(key)
                    : Collections.<String>emptySet();
        }
    }

    void updateAdUnitCustomEventMapping(
            @NonNull String moPubId,
            @NonNull CustomEventRewardedVideo customEvent,
            @Nullable CustomEventRewardedVideo.CustomEventRewardedVideoListener listener,
            @NonNull String adNetworkId) {
        mAdUnitToCustomEventMap.put(moPubId, customEvent);
        mAdNetworkListeners.add(listener);
        associateCustomEventWithMoPubId(customEvent.getClass(), adNetworkId, moPubId);
    }

    void associateCustomEventWithMoPubId(
            @NonNull Class<? extends CustomEventRewardedVideo> customEventClass,
            @NonNull String adNetworkId,
            @NonNull String moPubId) {
        final TwoPartKey newCustomEventMapping = new TwoPartKey(customEventClass, adNetworkId);

        // Remove previous mapping for this moPubId
        final Iterator<Map.Entry<TwoPartKey, Set<String>>> entryIterator =
                mCustomEventToMoPubIdMap.entrySet().iterator();
        while (entryIterator.hasNext()) {
            final Map.Entry<TwoPartKey, Set<String>> entry = entryIterator.next();

            if (!entry.getKey().equals(newCustomEventMapping)) {
                if (entry.getValue().contains(moPubId)) {
                    entry.getValue().remove(moPubId);
                    // Ensure that entries containing empty Sets are completely removed from the Map
                    if (entry.getValue().isEmpty()) {
                        entryIterator.remove();
                    }

                    // moPubIds can exist at most once in the Map values, so break upon finding a match
                    break;
                }
            }
        }

        // Add a new mapping if necessary.
        Set<String> moPubIds = mCustomEventToMoPubIdMap.get(newCustomEventMapping);
        if (moPubIds == null) {
            moPubIds = new HashSet<String>();
            mCustomEventToMoPubIdMap.put(newCustomEventMapping, moPubIds);
        }
        moPubIds.add(moPubId);
    }

    private static class TwoPartKey extends Pair<Class<? extends CustomEventRewardedVideo>, String> {
        @NonNull
        final Class<? extends CustomEventRewardedVideo> customEventClass;
        @NonNull
        final String adNetworkId;

        public TwoPartKey(
                @NonNull final Class<? extends CustomEventRewardedVideo> customEventClass,
                @NonNull final String adNetworkId) {
            super(customEventClass, adNetworkId);

            this.customEventClass = customEventClass;
            this.adNetworkId = adNetworkId;
        }
    }
}
