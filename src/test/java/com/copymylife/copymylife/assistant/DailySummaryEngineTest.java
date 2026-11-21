package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.DailySummaryEngine.HabitResult;
import com.copymylife.copymylife.assistant.DailySummaryEngine.RunResult;
import com.copymylife.copymylife.assistant.DailySummaryEngine.Summary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routine assistant, feature E: the end-of-day summary uses only what was recorded, and never
 * gives a length to a habit that was only marked done.
 */
class DailySummaryEngineTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 14);

    private static HabitResult habit(long id, String title, String status, int planned, Integer timer, Integer entered,
                                     Integer plannedStart, Integer actualStart) {
        return new HabitResult(1, "Weekday", id, title, status, planned, timer, entered, plannedStart, actualStart);
    }

    private static Summary summarize(List<HabitResult> habits, Map<Long, HabitDurationAnalyzer.Insight> insights,
                                     Map<Long, Integer> skips, DailySummaryEngine.Trend trend) {
        return DailySummaryEngine.summarize(new DailySummaryEngine.Input(DAY, true,
                List.of(new RunResult(5, 1, "Weekday", "ENDED", habits)),
                List.of(new DailySummaryEngine.Streak("Morning exercise", 4)), trend, insights, skips));
    }

    @Test
    void aDayIsDescribedFromItsRecords() {
        List<HabitResult> habits = List.of(
                habit(1, "Morning exercise", "COMPLETED", 20, 21, null, 420, 423),
                habit(2, "Java Study", "COMPLETED", 30, 45, null, 480, 480),
                habit(3, "Reading", "SKIPPED", 20, null, null, null, null),
                habit(4, "Emails", "COMPLETED", 15, null, null, null, null),
                habit(5, "Walk", "COMPLETED", 30, null, 35, null, null),
                habit(6, "Guitar", "NOT_STARTED", 30, null, null, null, null),
                habit(7, "Stretch", "COMPLETED", 10, 10, null, null, null),
                habit(8, "Plan tomorrow", "COMPLETED", 10, 10, null, null, null));
        Summary summary = summarize(habits, Map.of(), Map.of(3L, 3), null);

        assertEquals("OK", summary.status());
        assertEquals("Today you completed 6 out of 8 habits (75%).", summary.highlights().get(0));
        assertEquals(6, summary.counts().completed());
        assertEquals(1, summary.counts().skipped());
        assertEquals(1, summary.counts().notStarted());
        assertTrue(summary.highlights().contains("Java Study exceeded its planned duration by 15 minutes (45 min by the run timer, 30 min planned)."),
                summary.highlights().toString());
        assertTrue(summary.highlights().contains("You completed Morning exercise as scheduled."));
        assertEquals(1, summary.overruns().size(), "Walk ran 5 min over a 30-min plan: within the margin");
        // Emails was only marked done: it has no length, and the summary says so.
        assertEquals(5, summary.counts().timed());
        assertEquals("Lengths are known for 5 of 6 completed habits (4 by the run timer, 1 entered by you). For the other 1"
                + " only the completion is recorded, so no length is assumed.", summary.timing());
        assertEquals(List.of("Morning exercise: 4 days in a row"), summary.consistent());
        assertTrue(summary.suggestions().stream().anyMatch(s -> s.startsWith("Java Study ran 15 min over today")), summary.suggestions().toString());
        assertTrue(summary.suggestions().stream().anyMatch(s -> s.contains("You skipped Reading 3 times in the last 14 days")));
        assertTrue(summary.trend().startsWith("Not enough history"));
    }

    @Test
    void aRepeatedOverrunBringsTheLengthSuggestion() {
        HabitDurationAnalyzer.Insight insight = HabitDurationAnalyzer.analyze("Java Study", 30, List.of(
                new HabitDurationAnalyzer.Observation(DAY.minusDays(2), 45, null, null),
                new HabitDurationAnalyzer.Observation(DAY.minusDays(1), 46, null, null),
                new HabitDurationAnalyzer.Observation(DAY, 45, null, null)), null);
        Summary summary = summarize(List.of(habit(2, "Java Study", "COMPLETED", 30, 45, null, null, null)),
                Map.of(2L, insight), Map.of(), new DailySummaryEngine.Trend(80, 10, 60, 10));
        assertTrue(summary.suggestions().get(0).contains("increase it to 45 minutes"), summary.suggestions().toString());
        assertEquals(1, summary.suggestions().size(), "no second, weaker suggestion for the same habit");
        assertEquals("Over the last 7 days you completed 80% of 10 tracked habits, up from 60% (10 habits) in the 7 days before.",
                summary.trend());
    }

    @Test
    void lateStartsAndUnfinishedHabitsAreNamed() {
        Summary summary = summarize(List.of(
                habit(1, "Gym", "COMPLETED", 60, 60, null, 18 * 60, 18 * 60 + 40),
                habit(2, "Cooking", "NOT_FINISHED", 30, null, null, null, null),
                habit(3, "Read", "POSTPONED", 20, null, null, null, null)), Map.of(), Map.of(), null);
        assertTrue(summary.highlights().contains("Gym started 40 min later than planned (18:40 instead of 18:00)."), summary.highlights().toString());
        assertTrue(summary.highlights().contains("1 habit was started but not completed."));
        assertTrue(summary.highlights().contains("1 habit was postponed and not finished that day."));
    }

    @Test
    void aDayWithoutRunsSaysSo() {
        Summary summary = DailySummaryEngine.summarize(new DailySummaryEngine.Input(DAY, false, List.of(), List.of(), null, Map.of(), Map.of()));
        assertEquals("NO_RUNS", summary.status());
        assertEquals("On 2026-11-14 you did not start any routine, so there is nothing to summarise.", summary.highlights().get(0));
    }
}
