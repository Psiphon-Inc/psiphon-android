package com.psiphon3;

import android.content.Context;

import androidx.annotation.NonNull;

import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import java.util.Locale;
import java.util.Objects;

public class RateLimitHelper {
    private static final String KEY_UPSTREAM_RATE_LIMIT = "rate_limit_upstream";
    private static final String KEY_DOWNSTREAM_RATE_LIMIT = "rate_limit_downstream";

     // Stores rate limits in BYTES per second as reported by the tunnel core.
    public static void setRateLimits(Context context, long upstreamBytesPerSec, long downstreamBytesPerSec) {
        // Ignore negative values, which mean the rate limit is not set or not available.
        if (upstreamBytesPerSec < 0 || downstreamBytesPerSec < 0) {
            return;
        }
        AppPreferences mp = new AppPreferences(context);
        mp.put(KEY_UPSTREAM_RATE_LIMIT, upstreamBytesPerSec);
        mp.put(KEY_DOWNSTREAM_RATE_LIMIT, downstreamBytesPerSec);
    }

    public static Display getDisplay(Context context) {
        long up = getStoredBytesPerSecond(context, KEY_UPSTREAM_RATE_LIMIT);
        long down = getStoredBytesPerSecond(context, KEY_DOWNSTREAM_RATE_LIMIT);

        String upLabel = formatRate(context, up);
        String downLabel = formatRate(context, down);
        boolean same = (up == down);
        String combined = same ? upLabel : null;

        return new Display(same, upLabel, downLabel, combined);
    }

    private static long getStoredBytesPerSecond(Context context, String key) {
        AppPreferences mp = new AppPreferences(context);
        return mp.getLong(key, -1);
    }

    private static String formatRate(Context context, long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return context.getString(R.string.speed_rate_limit_not_available);
        }

        if (bytesPerSecond == 0) {
            return context.getString(R.string.speed_rate_limit_no_limit);
        }

        long value = bytesPerSecond * 8L;
        int unit = 1000;

        int exp = 0;
        double scaled = value;

        if (value >= unit) {
            exp = (int) (Math.log(value) / Math.log(unit));
            scaled = value / Math.pow(unit, exp);
        }

        String valueStr = String.format(Locale.getDefault(), "%.1f", scaled);
        String unitStr = getUnitLabel(context, exp);

        return context.getString(R.string.rate_with_unit_template, valueStr, unitStr);
    }

    private static String getUnitLabel(@NonNull Context context, int exp) {
        if (exp > 5) exp = 5;

        int[] bitUnits = {
                R.string.rate_unit_bps_bits,
                R.string.rate_unit_kbps_bits,
                R.string.rate_unit_mbps_bits,
                R.string.rate_unit_gbps_bits,
                R.string.rate_unit_tbps_bits,
                R.string.rate_unit_pbps_bits
        };

        return context.getString(bitUnits[exp]);
    }

    public static class Display {
        public final boolean isSymmetric;
        public final String up;
        public final String down;
        public final String combined;

        public Display(boolean isSymmetric, String up, String down, String combined) {
            this.isSymmetric = isSymmetric;
            this.up = up;
            this.down = down;
            this.combined = combined;
        }

        public static Display initial(Context context) {
            return new Display(true, null, null, context.getString(R.string.speed_rate_limit_not_available));
        }

        // Override equals and hashCode for proper comparison when used in the Rx chains with distinctUntilChanged
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Display display = (Display) o;
            return isSymmetric == display.isSymmetric &&
                    Objects.equals(up, display.up) &&
                    Objects.equals(down, display.down) &&
                    Objects.equals(combined, display.combined);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isSymmetric, up, down, combined);
        }
    }
}
