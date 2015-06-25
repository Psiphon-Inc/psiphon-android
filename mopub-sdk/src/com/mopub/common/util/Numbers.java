package com.mopub.common.util;

public class Numbers {
    private Numbers() {}

    public static Double parseDouble(final Object value) throws ClassCastException {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                return Double.valueOf((String) value);
            } catch (NumberFormatException e) {
                throw new ClassCastException("Unable to parse " + value + " as double.");
            }
        } else {
            throw new ClassCastException("Unable to parse " + value + " as double.");
        }
    }
}
