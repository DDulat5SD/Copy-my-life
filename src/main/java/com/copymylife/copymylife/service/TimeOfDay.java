package com.copymylife.copymylife.service;

import java.time.LocalTime;
import java.util.Locale;

// US-30: the part of the day a routine's activity starts in. The day is counted from 04:00, so
// morning is 04:00-11:59, afternoon 12:00-16:59 and evening 17:00-03:59, and a routine that
// runs past midnight stays an evening one. This is not a routine's category, which its author
// picks and which may also be called Morning.
final class TimeOfDay {

    static final String MORNING = "morning";
    static final String AFTERNOON = "afternoon";
    static final String EVENING = "evening";

    private static final int DAY_STARTS = 4 * 60;
    private static final int AFTERNOON_STARTS = 12 * 60;
    private static final int EVENING_STARTS = 17 * 60;

    private TimeOfDay() {
    }

    static String of(LocalTime start) {
        int minute = start.getHour() * 60 + start.getMinute();

        if (minute >= DAY_STARTS && minute < AFTERNOON_STARTS) {
            return MORNING;
        }

        if (minute >= AFTERNOON_STARTS && minute < EVENING_STARTS) {
            return AFTERNOON;
        }

        return EVENING;
    }

    // Minutes since 04:00, to find a routine's first activity of the day: 23:30 comes before 00:30.
    static int sinceDayStarts(LocalTime start) {
        return Math.floorMod(start.getHour() * 60 + start.getMinute() - DAY_STARTS, 24 * 60);
    }

    // "morning", "afternoon" or "evening" as asked, in any letter case; null for every time.
    static String chosen(String value) {
        if (value == null) {
            return null;
        }

        String time = value.trim().toLowerCase(Locale.ROOT);

        return MORNING.equals(time) || AFTERNOON.equals(time) || EVENING.equals(time) ? time : null;
    }
}
