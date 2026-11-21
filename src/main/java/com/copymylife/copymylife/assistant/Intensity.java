package com.copymylife.copymylife.assistant;

import java.util.Locale;

// How full a day may be. The gap is left between flexible activities; the focus limit is a
// starting value for a warning, not a measured or scientific limit.
public enum Intensity {
    LIGHT(10, 4 * 60),
    BALANCED(5, 6 * 60),
    INTENSE(0, 9 * 60);

    private final int gapMinutes;
    private final int focusLimitMinutes;

    Intensity(int gapMinutes, int focusLimitMinutes) {
        this.gapMinutes = gapMinutes;
        this.focusLimitMinutes = focusLimitMinutes;
    }

    public int gapMinutes() {
        return gapMinutes;
    }

    public int focusLimitMinutes() {
        return focusLimitMinutes;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Intensity parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Intensity must be LIGHT, BALANCED or INTENSE");
        }
    }
}
