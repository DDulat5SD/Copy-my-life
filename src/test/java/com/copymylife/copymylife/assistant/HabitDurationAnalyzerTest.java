package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.HabitDurationAnalyzer.Decision;
import com.copymylife.copymylife.assistant.HabitDurationAnalyzer.Insight;
import com.copymylife.copymylife.assistant.HabitDurationAnalyzer.Observation;
import com.copymylife.copymylife.assistant.HabitDurationAnalyzer.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routine assistant, feature D: planned versus actual length, from the run timer or a length the
 * user entered, never from a habit that was only marked done; one long run is not a pattern.
 */
class HabitDurationAnalyzerTest {

    private static final Instant START = Instant.parse("2026-11-01T08:00:00Z");

    private static Observation timer(int day, int minutes) {
        return new Observation(LocalDate.of(2026, 11, day), minutes, null, START.plusSeconds(day * 86400L));
    }

    private static Observation entered(int day, int minutes) {
        return new Observation(LocalDate.of(2026, 11, day), null, minutes, START.plusSeconds(day * 86400L));
    }

    private static Observation markedOnly(int day) {
        return new Observation(LocalDate.of(2026, 11, day), null, null, START.plusSeconds(day * 86400L));
    }

    @Test
    void repeatedOverrunsSuggestTheUsualLength() {
        Insight insight = HabitDurationAnalyzer.analyze("Java Study", 30,
                List.of(timer(1, 45), timer(2, 44), timer(3, 47), timer(4, 31), timer(5, 45)), null);
        assertEquals(Status.OFTEN_LONGER, insight.status());
        assertEquals(45, insight.usualMinutes());
        assertEquals(45, insight.suggestedMinutes());
        assertEquals(4, insight.longerRuns());
        assertEquals("You often spend about 45 minutes on Java Study, although 30 minutes is scheduled (4 of your last 5"
                + " measured runs took longer). Would you like to increase it to 45 minutes?", insight.message());
        assertTrue(insight.evidence().contains("5 by the run timer"), insight.evidence());
    }

    @Test
    void oneOverrunIsNotProof() {
        Insight one = HabitDurationAnalyzer.analyze("Java Study", 30, List.of(timer(1, 45)), null);
        assertEquals(Status.NOT_ENOUGH_DATA, one.status());
        assertNull(one.suggestedMinutes());

        Insight mixed = HabitDurationAnalyzer.analyze("Java Study", 30,
                List.of(timer(1, 45), timer(2, 31), timer(3, 30), timer(4, 32)), null);
        assertEquals(Status.ON_PLAN, mixed.status(), "one long run among four is no pattern");
        assertNull(mixed.suggestedMinutes());
    }

    @Test
    void habitsOnlyMarkedDoneHaveNoLength() {
        Insight insight = HabitDurationAnalyzer.analyze("Reading", 20, List.of(markedOnly(1), markedOnly(2), markedOnly(3)), null);
        assertEquals(Status.NO_DATA, insight.status());
        assertEquals(0, insight.measuredRuns());
        assertNull(insight.usualMinutes());
        assertTrue(insight.message().contains("No measured time yet"), insight.message());
    }

    @Test
    void aForgottenTimerIsLeftOutAndEnteredLengthsWin() {
        Insight insight = HabitDurationAnalyzer.analyze("Stretch", 10,
                List.of(timer(1, 240), entered(2, 10), entered(3, 11), entered(4, 10)), null);
        assertEquals(1, insight.leftOut());
        assertEquals(3, insight.enteredRuns());
        assertEquals(Status.ON_PLAN, insight.status());
    }

    @Test
    void onlyEnteredLengthsCanShowAShorterHabit() {
        Insight insight = HabitDurationAnalyzer.analyze("Meditation", 30,
                List.of(entered(1, 15), entered(2, 20), entered(3, 15)), null);
        assertEquals(Status.OFTEN_SHORTER, insight.status());
        assertEquals(15, insight.suggestedMinutes());
        assertTrue(insight.message().contains("shorten it to 15 minutes"));
    }

    @Test
    void aDeclinedSuggestionWaitsForNewRuns() {
        List<Observation> runs = new ArrayList<>(List.of(timer(1, 45), timer(2, 45), timer(3, 45)));
        Decision kept = new Decision(false, 45, 30, START.plusSeconds(3 * 86400L + 3600), LocalDate.of(2026, 11, 4));

        Insight resting = HabitDurationAnalyzer.analyze("Java Study", 30, runs, kept);
        assertEquals(Status.KEPT_BY_YOU, resting.status());
        assertNull(resting.suggestedMinutes());
        assertTrue(resting.message().contains("2026-11-04") && resting.message().contains("0 so far"), resting.message());

        runs.addAll(List.of(timer(5, 46), timer(6, 44)));
        assertEquals(Status.KEPT_BY_YOU, HabitDurationAnalyzer.analyze("Java Study", 30, runs, kept).status());
        runs.add(timer(7, 45));
        Insight again = HabitDurationAnalyzer.analyze("Java Study", 30, runs, kept);
        assertEquals(Status.OFTEN_LONGER, again.status(), "three new measured runs bring the question back");
        assertEquals(45, again.suggestedMinutes());
    }

    @Test
    void newBehaviourReplacesOldOverTime() {
        List<Observation> runs = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            runs.add(timer(day, 45));
        }
        assertEquals(Status.OFTEN_LONGER, HabitDurationAnalyzer.analyze("Java Study", 30, runs, null).status());
        for (int day = 7; day <= 16; day++) {
            runs.add(timer(day, 31));
        }
        assertEquals(Status.ON_PLAN, HabitDurationAnalyzer.analyze("Java Study", 30, runs, null).status(),
                "only the latest runs count, so a changed habit changes the advice");
    }
}
