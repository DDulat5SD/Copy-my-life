package com.copymylife.copymylife.assistant;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Times of day as minutes since midnight. A plan's day runs from wake-up to sleep, which may be
// after midnight, so minutes above 1439 are the early hours of the next day.
public final class Clock {

    public static final int DAY = 24 * 60;

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");

    private Clock() {
    }

    // "07:30" -> 450; null or blank -> null.
    public static Integer parse(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }

        Matcher matcher = TIME.matcher(value.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException(field + " must be a time of day such as 07:30");
        }

        return Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
    }

    public static String format(int minutes) {
        int inDay = Math.floorMod(minutes, DAY);
        return String.format("%02d:%02d", inDay / 60, inDay % 60);
    }

    public static String formatNullable(Integer minutes) {
        return minutes == null ? null : format(minutes);
    }

    // "1 h 30 min", "45 min".
    public static String length(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        return minutes % 60 == 0 ? minutes / 60 + " h" : minutes / 60 + " h " + minutes % 60 + " min";
    }

    // The same time of day placed inside a window that starts at dayStart and may pass midnight.
    public static int inWindow(int minute, int dayStart) {
        return minute < dayStart ? minute + DAY : minute;
    }

    public static int roundUp5(int minutes) {
        return (minutes + 4) / 5 * 5;
    }

    public static int round5(int minutes) {
        return Math.round(minutes / 5f) * 5;
    }
}
