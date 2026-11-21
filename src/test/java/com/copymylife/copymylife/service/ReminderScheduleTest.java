package com.copymylife.copymylife.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-22: a reminder is due from the chosen minutes before an activity until five minutes after it
 * starts, with the activity's time read as the user's own wall-clock time.
 */
class ReminderScheduleTest {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");   // UTC+5

    private static ReminderSchedule.Slot at(String date, String time) {
        return new ReminderSchedule.Slot("habit:1:" + date + "T" + time, "HABIT", "Stretch", 1L, "Morning",
                LocalDate.parse(date), LocalTime.parse(time));
    }

    @Test
    void dueFromTheLeadTimeUntilShortlyAfterTheStart() {
        ReminderSchedule.Slot slot = at("2026-11-18", "07:30");   // 02:30Z
        assertFalse(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:14:59Z"), 15));
        assertTrue(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:15:00Z"), 15));
        assertTrue(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:30:00Z"), 15));
        assertTrue(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:35:00Z"), 15));
        assertFalse(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:35:01Z"), 15));
        assertFalse(ReminderSchedule.due(slot, ALMATY, Instant.parse("2026-11-18T02:20:00Z"), 5), "5 minutes before only");
    }

    @Test
    void theSameTimeIsDueAtADifferentMomentInEachZone() {
        ReminderSchedule.Slot slot = at("2026-11-18", "07:30");
        Instant now = Instant.parse("2026-11-18T02:20:00Z");
        assertTrue(ReminderSchedule.due(slot, ALMATY, now, 15));
        assertFalse(ReminderSchedule.due(slot, ZoneId.of("Europe/London"), now, 15), "07:30 in London is 07:30Z");
        assertTrue(ReminderSchedule.due(slot, ZoneId.of("Europe/London"), Instant.parse("2026-11-18T07:20:00Z"), 15));
    }

    @Test
    void anActivityJustAfterMidnightIsRemindedTheEveningBefore() {
        Instant now = Instant.parse("2026-11-18T18:55:00Z");   // 23:55 in Almaty
        assertTrue(ReminderSchedule.daysAround(ALMATY, now).contains(LocalDate.parse("2026-11-19")));
        assertTrue(ReminderSchedule.due(at("2026-11-19", "00:05"), ALMATY, now, 15));
        assertFalse(ReminderSchedule.due(at("2026-11-18", "00:05"), ALMATY, now, 15), "not the one of that morning");
        // Already the next day in Kiritimati (UTC+14).
        assertEquals(LocalDate.parse("2026-11-19"),
                ReminderSchedule.daysAround(ZoneId.of("Pacific/Kiritimati"), now).get(1));
    }

    @Test
    void aTimeSkippedByADaylightSavingChangeMovesForward() {
        // 02:30 does not exist in New York on 8 March 2026; clocks show 03:30 EDT, 07:30Z.
        ReminderSchedule.Slot slot = at("2026-03-08", "02:30");
        ZoneId newYork = ZoneId.of("America/New_York");
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), ReminderSchedule.startOf(slot, newYork));
        assertTrue(ReminderSchedule.due(slot, newYork, Instant.parse("2026-03-08T07:20:00Z"), 15));
    }

    @Test
    void minutesUntilTheStartAreRoundedUp() {
        ReminderSchedule.Slot slot = at("2026-11-18", "07:30");
        assertEquals(10, ReminderSchedule.minutesUntil(slot, ALMATY, Instant.parse("2026-11-18T02:20:00Z")));
        assertEquals(10, ReminderSchedule.minutesUntil(slot, ALMATY, Instant.parse("2026-11-18T02:20:30Z")));
        assertEquals(0, ReminderSchedule.minutesUntil(slot, ALMATY, Instant.parse("2026-11-18T02:30:00Z")));
        assertEquals(-2, ReminderSchedule.minutesUntil(slot, ALMATY, Instant.parse("2026-11-18T02:32:10Z")));
    }

    @Test
    void aRescheduledActivityHasANewKey() {
        LocalDate day = LocalDate.parse("2026-11-18");
        assertNotEquals(ReminderSchedule.habitKey(7, day, LocalTime.parse("07:30")),
                ReminderSchedule.habitKey(7, day, LocalTime.parse("08:00")));
        assertNotEquals(ReminderSchedule.habitKey(7, day, LocalTime.parse("07:30")),
                ReminderSchedule.eventKey(7, day, LocalTime.parse("07:30")));
        assertTrue(ReminderSchedule.eventKey(123456789, day, LocalTime.parse("23:59")).length() <= 80);
    }
}
