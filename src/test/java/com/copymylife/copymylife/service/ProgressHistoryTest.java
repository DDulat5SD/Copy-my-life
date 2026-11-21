package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ProgressResponse;
import com.copymylife.copymylife.model.HabitCompletionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.copymylife.copymylife.model.HabitCompletionStatus.COMPLETED;
import static com.copymylife.copymylife.model.HabitCompletionStatus.IN_PROGRESS;
import static com.copymylife.copymylife.model.HabitCompletionStatus.POSTPONED;
import static com.copymylife.copymylife.model.HabitCompletionStatus.SKIPPED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-21: streaks and the week, from stored tracking marks only. A streak is the number of
 * calendar days in a row on which a habit was completed; today still counts while it is not
 * over, a day without a completion ends the streak, and days the habit was not planned are not
 * filled in. The calendar day of a moment comes from the user's time zone.
 */
class ProgressHistoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12); // a Thursday

    private final List<ProgressHistory.Mark> marks = new ArrayList<>();

    private void mark(long habit, LocalDate day, HabitCompletionStatus status) {
        marks.add(new ProgressHistory.Mark(habit, "Habit " + habit, day, status));
    }

    private ProgressHistory history() {
        return history(TODAY);
    }

    private ProgressHistory history(LocalDate today) {
        return new ProgressHistory(marks, today);
    }

    private ProgressResponse.HabitStreak streakOf(ProgressHistory history, long habit) {
        return history.habitStreaks().stream()
                .filter(streak -> streak.habitId() == habit)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no streak for " + habit + " in " + history.habitStreaks()));
    }

    // ---- streaks ----

    @Test
    void daysInARowMakeAStreak() {
        for (int back = 0; back < 4; back++) {
            mark(1, TODAY.minusDays(back), COMPLETED);
        }
        ProgressResponse.HabitStreak streak = streakOf(history(), 1);
        assertEquals(4, streak.currentDays());
        assertEquals(4, streak.bestDays());
        assertEquals(TODAY, streak.lastCompletedDate());
    }

    @Test
    void todayStillCountsWhileItIsNotOver() {
        mark(1, TODAY.minusDays(1), COMPLETED);
        mark(1, TODAY.minusDays(2), COMPLETED);
        assertEquals(2, streakOf(history(), 1).currentDays());
    }

    @Test
    void aWholeDayWithoutACompletionEndsTheStreak() {
        mark(1, TODAY.minusDays(2), COMPLETED);
        mark(1, TODAY.minusDays(3), COMPLETED);
        ProgressResponse.HabitStreak streak = streakOf(history(), 1);
        assertEquals(0, streak.currentDays());
        assertEquals(2, streak.bestDays());

        mark(1, TODAY, COMPLETED);
        assertEquals(1, streakOf(history(), 1).currentDays());
    }

    @Test
    void skippedPostponedOrUnfinishedDaysAreNotCompletedDays() {
        mark(1, TODAY.minusDays(3), COMPLETED);
        mark(1, TODAY.minusDays(2), SKIPPED);
        mark(1, TODAY.minusDays(1), POSTPONED);
        mark(1, TODAY, IN_PROGRESS);
        ProgressResponse.HabitStreak streak = streakOf(history(), 1);
        assertEquals(0, streak.currentDays());
        assertEquals(1, streak.bestDays());
        assertEquals(TODAY.minusDays(3), streak.lastCompletedDate());
    }

    @Test
    void aSkipOnTheSameDayAsACompletionDoesNotUndoIt() {
        mark(1, TODAY, SKIPPED);
        mark(1, TODAY, COMPLETED);
        mark(1, TODAY.minusDays(1), COMPLETED);
        assertEquals(2, streakOf(history(), 1).currentDays());
    }

    @Test
    void severalCompletionsOnOneDayCountAsOneDay() {
        mark(1, TODAY, COMPLETED);
        mark(1, TODAY, COMPLETED);
        mark(1, TODAY, COMPLETED);
        mark(1, TODAY.minusDays(1), COMPLETED);
        ProgressHistory history = history();
        assertEquals(2, streakOf(history, 1).currentDays());
        assertEquals(1, history.week(TODAY).days().get(3).completed());
    }

    @Test
    void daysTheHabitWasNotPlannedAreNotFilledIn() {
        // Planned on Monday and Wednesday only: two calendar days that are not in a row.
        mark(1, LocalDate.of(2026, 11, 9), COMPLETED);
        mark(1, LocalDate.of(2026, 11, 11), COMPLETED);
        ProgressResponse.HabitStreak streak = streakOf(history(), 1);
        assertEquals(1, streak.currentDays());
        assertEquals(1, streak.bestDays());
    }

    @Test
    void theUsersStreakCountsDaysWithAnyHabitCompleted() {
        mark(1, TODAY.minusDays(2), COMPLETED);
        mark(2, TODAY.minusDays(1), COMPLETED);
        mark(3, TODAY, COMPLETED);
        mark(1, TODAY.minusDays(9), COMPLETED);
        ProgressHistory history = history();
        assertEquals(3, history.streak().currentDays());
        assertEquals(3, history.streak().bestDays());
        assertEquals(TODAY, history.streak().lastCompletedDate());
        assertEquals(1, streakOf(history, 1).bestDays());
    }

    @Test
    void theBestStreakIsTheLongestRunInTheHistory() {
        for (int back = 10; back <= 15; back++) {
            mark(1, TODAY.minusDays(back), COMPLETED);
        }
        mark(1, TODAY, COMPLETED);
        ProgressResponse.HabitStreak streak = streakOf(history(), 1);
        assertEquals(1, streak.currentDays());
        assertEquals(6, streak.bestDays());
    }

    @Test
    void activeStreaksComeFirst() {
        mark(1, TODAY.minusDays(5), COMPLETED);
        mark(2, TODAY, COMPLETED);
        mark(2, TODAY.minusDays(1), COMPLETED);
        mark(3, TODAY, COMPLETED);
        List<Long> order = history().habitStreaks().stream().map(ProgressResponse.HabitStreak::habitId).toList();
        assertEquals(List.of(2L, 3L, 1L), order);
    }

    @Test
    void aStreakRunsOverMonthsYearsAndLeapDays() {
        mark(1, LocalDate.of(2026, 12, 30), COMPLETED);
        mark(1, LocalDate.of(2026, 12, 31), COMPLETED);
        mark(1, LocalDate.of(2027, 1, 1), COMPLETED);
        assertEquals(3, streakOf(history(LocalDate.of(2027, 1, 1)), 1).currentDays());

        marks.clear();
        mark(1, LocalDate.of(2028, 2, 28), COMPLETED);
        mark(1, LocalDate.of(2028, 2, 29), COMPLETED);
        mark(1, LocalDate.of(2028, 3, 1), COMPLETED);
        assertEquals(3, streakOf(history(LocalDate.of(2028, 3, 2)), 1).currentDays());
    }

    @Test
    void nothingTrackedIsZeroNotAGuess() {
        ProgressHistory history = history();
        assertFalse(history.hasTracking());
        assertEquals(0, history.streak().currentDays());
        assertEquals(0, history.streak().bestDays());
        assertNull(history.streak().lastCompletedDate());
        assertEquals(List.of(), history.habitStreaks());
        ProgressResponse.Week week = history.week(TODAY);
        assertEquals(0, week.completed());
        assertEquals(0, week.tracked());
        assertNull(week.percentage());
        week.days().forEach(day -> {
            assertEquals(0, day.completed());
            assertNull(day.percentage(), day.toString());
        });
    }

    @Test
    void onlySkipsAreTrackingButNoStreak() {
        mark(1, TODAY, SKIPPED);
        ProgressHistory history = history();
        assertTrue(history.hasTracking());
        assertEquals(0, history.streak().currentDays());
        assertEquals(List.of(), history.habitStreaks());
    }

    // ---- the week ----

    @Test
    void theWeekRunsMondayToSundayWithTheRecordsOfEachDay() {
        LocalDate monday = LocalDate.of(2026, 11, 9);
        mark(1, monday, COMPLETED);
        mark(2, monday, SKIPPED);
        mark(3, monday, POSTPONED);
        mark(1, monday.plusDays(1), COMPLETED);
        mark(2, monday.plusDays(1), COMPLETED);
        mark(2, monday.plusDays(1), COMPLETED);
        mark(1, monday.plusDays(3), IN_PROGRESS);

        ProgressResponse.Week week = history().week(TODAY);
        assertEquals(monday, week.from());
        assertEquals(monday.plusDays(6), week.to());
        assertEquals(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
                week.days().stream().map(ProgressResponse.Day::day).toList());

        ProgressResponse.Day mon = week.days().get(0);
        assertEquals(1, mon.completed());
        assertEquals(1, mon.skipped());
        assertEquals(1, mon.postponed());
        assertEquals(3, mon.tracked());
        assertEquals(33, mon.percentage());

        ProgressResponse.Day tue = week.days().get(1);
        assertEquals(2, tue.completed());
        assertEquals(2, tue.tracked());
        assertEquals(100, tue.percentage());

        ProgressResponse.Day wed = week.days().get(2);
        assertEquals(0, wed.tracked());
        assertNull(wed.percentage());
        assertFalse(wed.future());

        ProgressResponse.Day thu = week.days().get(3);
        assertTrue(thu.today());
        assertEquals(1, thu.inProgress());
        assertEquals(0, thu.percentage());

        ProgressResponse.Day fri = week.days().get(4);
        assertTrue(fri.future());
        assertNull(fri.percentage());

        assertEquals(3, week.completed());
        assertEquals(6, week.tracked());
        assertEquals(50, week.percentage());
    }

    @Test
    void sundayAndMondayBelongToDifferentWeeks() {
        LocalDate sunday = LocalDate.of(2026, 11, 15);
        assertEquals(LocalDate.of(2026, 11, 9), history().week(sunday).from());
        assertEquals(LocalDate.of(2026, 11, 16), history().week(sunday.plusDays(1)).from());
    }

    @Test
    void aWeekCanStartInDecemberAndEndInJanuary() {
        ProgressResponse.Week week = history(LocalDate.of(2027, 1, 1)).week(LocalDate.of(2026, 12, 31));
        assertEquals(LocalDate.of(2026, 12, 28), week.from());
        assertEquals(LocalDate.of(2027, 1, 3), week.to());
        assertTrue(week.days().get(4).today());
        assertTrue(week.days().get(5).future());
    }

    @Test
    void aLeapYearWeekHasTheTwentyNinthOfFebruary() {
        ProgressResponse.Week week = history(LocalDate.of(2028, 3, 5)).week(LocalDate.of(2028, 2, 29));
        assertEquals(LocalDate.of(2028, 2, 28), week.from());
        assertEquals(LocalDate.of(2028, 2, 29), week.days().get(1).date());
    }

    // ---- the calendar day of a moment ----

    @Test
    void theDayTurnsAtTheUsersMidnight() {
        UserTimeZone zones = new UserTimeZone("Asia/Almaty");
        // Almaty is UTC+5.
        assertEquals(LocalDate.of(2026, 11, 9), zones.today("Asia/Almaty", Instant.parse("2026-11-09T18:59:59Z")));
        assertEquals(LocalDate.of(2026, 11, 10), zones.today("Asia/Almaty", Instant.parse("2026-11-09T19:00:00Z")));
        // The same moment is another day in another zone.
        assertEquals(LocalDate.of(2026, 11, 9), zones.today("America/Los_Angeles", Instant.parse("2026-11-10T05:00:00Z")));
        assertEquals(LocalDate.of(2026, 11, 10), zones.today("Asia/Tokyo", Instant.parse("2026-11-09T15:00:00Z")));
    }

    @Test
    void daylightSavingMovesMidnightButNotTheDays() {
        UserTimeZone zones = new UserTimeZone("Asia/Almaty");
        // New York: UTC-5 before 8 March 2026, UTC-4 after.
        assertEquals(LocalDate.of(2026, 3, 7), zones.today("America/New_York", Instant.parse("2026-03-08T04:59:59Z")));
        assertEquals(LocalDate.of(2026, 3, 8), zones.today("America/New_York", Instant.parse("2026-03-08T05:00:00Z")));
        assertEquals(LocalDate.of(2026, 3, 8), zones.today("America/New_York", Instant.parse("2026-03-09T03:59:59Z")));
        assertEquals(LocalDate.of(2026, 3, 9), zones.today("America/New_York", Instant.parse("2026-03-09T04:00:00Z")));

        mark(1, LocalDate.of(2026, 3, 7), COMPLETED);
        mark(1, LocalDate.of(2026, 3, 8), COMPLETED);
        mark(1, LocalDate.of(2026, 3, 9), COMPLETED);
        assertEquals(3, streakOf(history(LocalDate.of(2026, 3, 9)), 1).currentDays());
    }

    @Test
    void anUnknownOrMissingZoneUsesAlmaty() {
        UserTimeZone zones = new UserTimeZone("Asia/Almaty");
        Instant evening = Instant.parse("2026-11-09T19:30:00Z");
        assertEquals(LocalDate.of(2026, 11, 10), zones.today("Mars/Olympus", evening));
        assertEquals(LocalDate.of(2026, 11, 10), zones.today(null, evening));
        assertEquals(LocalDate.of(2026, 11, 10), zones.today("", evening));
        assertEquals(LocalDate.of(2026, 11, 10), zones.today("x".repeat(80), evening));
    }
}
