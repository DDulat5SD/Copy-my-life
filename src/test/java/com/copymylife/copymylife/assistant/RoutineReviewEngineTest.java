package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.RoutineReviewEngine.Activity;
import com.copymylife.copymylife.assistant.RoutineReviewEngine.Change;
import com.copymylife.copymylife.assistant.RoutineReviewEngine.Issue;
import com.copymylife.copymylife.assistant.RoutineReviewEngine.Result;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routine assistant, feature C: Review My Routine finds conflicts, missing breaks, meal and sleep
 * clashes, heavy days and lengths that differ from measured runs, and proposes changes that
 * together never overlap. Nothing is applied by the review itself.
 */
class RoutineReviewEngineTest {

    private static int t(String time) {
        return Clock.parse(time, "time");
    }

    private static Activity timed(long id, String title, String start, int minutes) {
        return new Activity(id, title, ActivityKind.recognise(title), true, minutes, t(start), List.of(), false);
    }

    private static Activity untimed(long id, String title, int minutes) {
        return new Activity(id, title, ActivityKind.recognise(title), true, minutes, null, List.of(), false);
    }

    private static ResolvedPreferences prefs(Integer lunch, String rest) {
        return PersonalizationEngine.resolve(null, new PreferenceInput(t("07:00"), t("23:00"), null, null, null, lunch,
                null, null, null, null, null, null, rest, null), null);
    }

    private static Result review(List<Activity> activities, ResolvedPreferences prefs, Map<Long, HabitDurationAnalyzer.Insight> insights) {
        return RoutineReviewEngine.review(new RoutineReviewEngine.Input(activities, prefs, insights, null, "not checked in this test"));
    }

    private static Issue issue(Result result, String code) {
        return result.issues().stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + result.issues()));
    }

    private static List<Change> changesOf(Result result, Issue issue) {
        return result.changes().stream().filter(c -> issue.changeIds().contains(c.id())).toList();
    }

    @Test
    void consecutiveActivitiesWithoutABreakGetOne() {
        List<Activity> routine = List.of(timed(1, "Java Study", "08:00", 60), timed(2, "Math homework", "09:00", 60),
                timed(3, "English lesson", "10:00", 60));
        Result result = review(routine, prefs(null, "NORMAL"), Map.of());

        Issue issue = issue(result, "NO_BREAK");
        assertEquals("PREFERENCE", issue.basis());
        assertTrue(issue.evidence().contains("Java Study, Math homework follow each other for 2 h without a planned break"), issue.evidence());
        List<Change> changes = changesOf(result, issue);
        assertTrue(changes.stream().anyMatch(c -> c.type().equals("ADD") && c.kind() == ActivityKind.BREAK && c.toStart() == t("09:00")));
        assertTrue(changes.stream().anyMatch(c -> c.type().equals("MOVE") && c.activityId() == 2 && c.toStart() == t("09:10")), changes.toString());
        assertEquals(List.of(), RoutineReviewEngine.conflicts(result.proposed()), "the proposal itself never overlaps");
        assertEquals(List.of(), RoutineReviewEngine.conflicts(RoutineReviewEngine.apply(routine, result.changes())));
        assertEquals(t("08:00"), routine.get(0).start(), "the routine itself is untouched");
    }

    @Test
    void lunchTimeConflictIsShownWithASlot() {
        List<Activity> routine = List.of(timed(1, "Java Study", "13:30", 60), timed(2, "Reading", "16:00", 30));
        Result result = review(routine, prefs(t("14:00"), null), Map.of());

        Issue issue = issue(result, "MEAL_CONFLICT");
        assertTrue(issue.evidence().contains("Your preferred lunch time (14:00, your saved preference) conflicts with Java Study (13:30–14:30)"),
                issue.evidence());
        Change add = changesOf(result, issue).get(0);
        assertEquals("ADD", add.type());
        assertEquals(ActivityKind.MEAL, add.kind());
        assertEquals(t("14:30"), add.toStart(), "the closest free time to 14:00");
        assertEquals(List.of(), RoutineReviewEngine.conflicts(result.proposed()));
    }

    @Test
    void defaultMealTimesAreNotTreatedAsTheUsersOwn() {
        Result result = review(List.of(timed(1, "Java Study", "12:30", 60)), prefs(null, null), Map.of());
        assertTrue(result.issues().stream().noneMatch(i -> i.code().startsWith("MEAL")), result.issues().toString());
    }

    @Test
    void overlapsAndSleepWindowAreFound() {
        List<Activity> routine = List.of(timed(1, "Gym", "18:00", 60), timed(2, "Reading", "18:30", 30),
                timed(3, "Late series", "23:30", 60));
        Result result = review(routine, prefs(null, null), Map.of());

        Issue overlap = issue(result, "OVERLAP");
        assertEquals("ERROR", overlap.severity());
        assertTrue(overlap.evidence().contains("Gym (18:00–19:00) and Reading (18:30–19:00) overlap on every day"), overlap.evidence());
        Issue sleep = issue(result, "SLEEP_WINDOW");
        assertTrue(sleep.evidence().contains("Late series (23:30–00:30) ends after your sleep time 23:00"), sleep.evidence());
        assertEquals(List.of(), RoutineReviewEngine.conflicts(result.proposed()));
        assertTrue(result.proposed().stream().allMatch(a -> a.start() + a.minutes() <= t("23:00")), result.proposed().toString());
    }

    @Test
    void measuredOverrunsResizeAndMakeRoom() {
        HabitDurationAnalyzer.Insight insight = HabitDurationAnalyzer.analyze("Java Study", 30, List.of(
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 1), 45, null, Instant.parse("2026-11-01T10:00:00Z")),
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 2), 45, null, Instant.parse("2026-11-02T10:00:00Z")),
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 3), 46, null, Instant.parse("2026-11-03T10:00:00Z"))), null);
        List<Activity> routine = List.of(timed(1, "Java Study", "08:00", 30), timed(2, "Walk", "08:30", 20));
        Result result = review(routine, prefs(null, "RARE"), Map.of(1L, insight));

        Issue issue = issue(result, "DURATION");
        assertEquals("MEASURED", issue.basis());
        assertTrue(issue.suggestion().contains("increase it to 45 minutes"));
        List<Change> changes = changesOf(result, issue);
        assertTrue(changes.stream().anyMatch(c -> c.type().equals("RESIZE") && c.toMinutes() == 45));
        assertTrue(changes.stream().anyMatch(c -> c.type().equals("MOVE") && c.activityId() == 2 && c.toStart() == t("08:45")),
                "the next activity moves so the longer one fits: " + changes);
        assertTrue(issue.impact().contains("Java Study: 30 → 45 min") && issue.impact().contains("Walk 08:30 → 08:45"), issue.impact());
    }

    @Test
    void aSharedHabitIsNotResized() {
        HabitDurationAnalyzer.Insight insight = HabitDurationAnalyzer.analyze("Java Study", 30, List.of(
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 1), 45, null, null),
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 2), 45, null, null),
                new HabitDurationAnalyzer.Observation(LocalDate.of(2026, 11, 3), 45, null, null)), null);
        Activity shared = new Activity(1, "Java Study", ActivityKind.STUDY, true, 30, t("08:00"), List.of(), true);
        Result result = review(List.of(shared), prefs(null, null), Map.of(1L, insight));
        Issue issue = issue(result, "DURATION");
        assertTrue(issue.changeIds().isEmpty());
        assertTrue(issue.suggestion().contains("also used in another routine"));
    }

    @Test
    void untimedActivitiesGetTimesWithBreaks() {
        List<Activity> routine = List.of(untimed(1, "Java Study", 60), untimed(2, "Python practice", 60), untimed(3, "Reading", 30));
        Result result = review(routine, prefs(null, "FREQUENT"), Map.of());

        Issue issue = issue(result, "UNTIMED");
        assertTrue(issue.evidence().contains("time conflicts, breaks and meals can't be checked"));
        assertTrue(result.proposed().stream().allMatch(a -> a.start() != null), result.proposed().toString());
        assertTrue(result.proposed().stream().anyMatch(a -> a.kind() == ActivityKind.BREAK));
        assertEquals(List.of(), RoutineReviewEngine.conflicts(result.proposed()));
        assertTrue(result.notChecked().stream().anyMatch(n -> n.contains("no activity has a start time")));
    }

    @Test
    void tooMuchForTheDayIsAnError() {
        List<Activity> routine = List.of(untimed(1, "Study", 600), untimed(2, "Work", 600));
        Issue issue = issue(review(routine, prefs(null, null), Map.of()), "WORKLOAD");
        assertEquals("ERROR", issue.severity());
        assertTrue(issue.evidence().contains("20 h of activities on every day, but you are awake for 16 h"), issue.evidence());
    }

    @Test
    void repeatDaysAreRespected() {
        Activity monday = new Activity(1, "Gym", ActivityKind.EXERCISE, true, 60, t("18:00"), List.of("MON"), false);
        Activity tuesday = new Activity(2, "Swimming", ActivityKind.EXERCISE, true, 60, t("18:00"), List.of("TUE"), false);
        Result result = review(List.of(monday, tuesday), prefs(null, null), Map.of());
        assertTrue(result.issues().stream().noneMatch(i -> i.code().equals("OVERLAP")), "different days never clash");
    }

    @Test
    void goalsWithoutAnActivityAreNamedFromTheModel() {
        Result result = RoutineReviewEngine.review(new RoutineReviewEngine.Input(List.of(timed(1, "Gym", "18:00", 60)),
                prefs(null, null), Map.of(), List.of(new RoutineReviewEngine.GoalCheck("learn Spanish", "Gym", 0.12),
                new RoutineReviewEngine.GoalCheck("get fit", "Gym", 0.71)), null));
        List<Issue> goals = result.issues().stream().filter(i -> i.code().equals("GOAL")).toList();
        assertEquals(1, goals.size());
        assertEquals("MODEL", goals.get(0).basis());
        assertTrue(goals.get(0).evidence().contains("learn Spanish") && goals.get(0).evidence().contains("0.12"));
    }

    @Test
    void aGoodRoutineSaysSo() {
        Result result = review(List.of(timed(1, "Gym", "18:00", 60), timed(2, "Reading", "20:00", 30)), prefs(null, null), Map.of());
        assertEquals(List.of("ALL_GOOD"), result.issues().stream().map(Issue::code).toList());
        assertTrue(result.changes().isEmpty());
    }
}
