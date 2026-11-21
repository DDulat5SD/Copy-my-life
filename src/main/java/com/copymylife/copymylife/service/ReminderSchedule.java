package com.copymylife.copymylife.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

// US-22: when a reminder is due. An activity's day and time are the user's own wall-clock time;
// the reminder is due from the chosen number of minutes before it until a few minutes after it
// starts, so a page opened just as it begins still shows it.
final class ReminderSchedule {

    static final List<Integer> LEAD_OPTIONS = List.of(5, 10, 15, 30, 60);
    static final int DEFAULT_LEAD_MINUTES = 15;
    static final boolean DEFAULT_ENABLED = true;
    static final Duration GRACE = Duration.ofMinutes(5);

    // One occurrence of an activity: a timed habit of a routine, or a routine on the calendar.
    record Slot(String key, String kind, String title, Long routineId, String routineTitle,
                LocalDate date, LocalTime time) {
    }

    private ReminderSchedule() {
    }

    // A gap at a daylight-saving change moves the time forward, as clocks do.
    static Instant startOf(Slot slot, ZoneId zone) {
        return LocalDateTime.of(slot.date(), slot.time()).atZone(zone).toInstant();
    }

    static boolean due(Slot slot, ZoneId zone, Instant now, int leadMinutes) {
        Instant start = startOf(slot, zone);
        return !now.isBefore(start.minus(Duration.ofMinutes(leadMinutes))) && !now.isAfter(start.plus(GRACE));
    }

    // Yesterday, today and tomorrow in the user's zone: enough for any window around midnight.
    static List<LocalDate> daysAround(ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        return List.of(today.minusDays(1), today, today.plusDays(1));
    }

    static String habitKey(long routineHabitId, LocalDate date, LocalTime time) {
        return "habit:" + routineHabitId + ":" + date + "T" + time;
    }

    static String eventKey(long eventId, LocalDate date, LocalTime time) {
        return "event:" + eventId + ":" + date + "T" + time;
    }

    // Whole minutes until the start, rounded up; 0 or less once it has started.
    static long minutesUntil(Slot slot, ZoneId zone, Instant now) {
        long seconds = Duration.between(now, startOf(slot, zone)).getSeconds();
        return seconds > 0 ? (seconds + 59) / 60 : -(-seconds / 60);
    }
}
