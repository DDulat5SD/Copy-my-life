package com.copymylife.copymylife.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

// US-22: one reminder. kind is HABIT (a timed activity of a routine) or ROUTINE (a routine on the
// calendar); minutesUntil is 0 or less once it has started; shown tells whether it was delivered.
public record ReminderResponse(String key, String kind, String title, Long routineId, String routineTitle,
                               LocalDate date, LocalTime time, Instant startsAt, long minutesUntil,
                               boolean shown) {
}
