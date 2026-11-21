package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.RoutinePlanningEngine.Commitment;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine.HabitRequest;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine.PlanRequest;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine.PlanResult;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine.PlannedActivity;
import com.copymylife.copymylife.assistant.RoutinePlanningEngine.Priority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Routine assistant, feature A/B: the deterministic day planner. Plans must never overlap, never
 * cross a commitment or leave the available hours, put meals and breaks where the user prefers,
 * and say why.
 */
class RoutinePlanningEngineTest {

    private static final Set<String> ALL_MEALS = Set.of("breakfast", "lunch", "dinner");

    private static PreferenceInput prefs(Integer wake, Integer sleep, Integer lunch, String rest, Integer exercise) {
        return new PreferenceInput(wake, sleep, null, null, null, lunch, null, null, exercise, null, null, null, rest, null);
    }

    private static int t(String time) {
        return Clock.parse(time, "time");
    }

    private static PlanResult plan(PreferenceInput input, List<Commitment> commitments, List<HabitRequest> habits, Set<String> meals) {
        ResolvedPreferences resolved = PersonalizationEngine.resolve(input, null, null);
        return RoutinePlanningEngine.plan(new PlanRequest(resolved, commitments, habits, meals, true, HistorySignals.NONE));
    }

    private static HabitRequest habit(String title, int minutes, ActivityKind kind) {
        return new HabitRequest(title, minutes, kind, Priority.MEDIUM, null);
    }

    private static PlannedActivity find(PlanResult result, String title) {
        return result.activities().stream().filter(a -> a.title().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError(title + " not in " + result.activities()));
    }

    private static void assertNoOverlap(PlanResult result) {
        List<PlannedActivity> list = result.activities();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                PlannedActivity a = list.get(i);
                PlannedActivity b = list.get(j);
                if (!(a.fixed() && b.fixed())) {
                    assertFalse(a.start() < b.end() && b.start() < a.end(), a + " overlaps " + b);
                }
            }
        }
    }

    @Test
    void theExampleRequestBecomesARealisticDay() {
        PlanResult result = plan(prefs(t("07:00"), t("23:00"), t("13:00"), "FREQUENT", null), List.of(),
                List.of(habit("Java Study", 120, ActivityKind.STUDY), habit("Gym", 60, ActivityKind.EXERCISE)), ALL_MEALS);

        assertNoOverlap(result);
        assertEquals(t("13:00"), find(result, "Lunch").start(), "lunch where the user asked");
        assertEquals(t("07:30"), find(result, "Breakfast").start(), "breakfast 30 min after waking by default");
        assertTrue(result.activities().stream().allMatch(a -> a.start() >= t("07:00") && a.end() <= t("23:00")));
        // Frequent breaks: a break after 45 min, sessions no longer than 45 min.
        assertTrue(result.activities().stream().filter(a -> a.title().startsWith("Java Study"))
                .allMatch(a -> a.minutes() <= 45), result.activities().toString());
        assertEquals(120, result.activities().stream().filter(a -> a.title().startsWith("Java Study"))
                .mapToInt(PlannedActivity::minutes).sum(), "nothing of the asked time is lost");
        assertTrue(result.activities().stream().anyMatch(a -> a.kind() == ActivityKind.BREAK));
        assertTrue(result.unscheduled().isEmpty(), result.unscheduled().toString());
        assertTrue(result.explanations().stream().anyMatch(e -> e.contains("frequent breaks")), result.explanations().toString());
        assertTrue(result.explanations().stream().anyMatch(e -> e.contains("Lunch at 13:00: set for this plan")));
    }

    @Test
    void nothingIsPlacedAcrossACommitment() {
        PlanResult result = plan(prefs(t("07:00"), t("22:00"), t("13:00"), null, null),
                List.of(new Commitment("University", t("09:00"), t("14:00"))),
                List.of(habit("Reading", 60, ActivityKind.PERSONAL), habit("Homework", 90, ActivityKind.STUDY)), ALL_MEALS);

        assertNoOverlap(result);
        PlannedActivity university = find(result, "University");
        assertTrue(university.fixed() && !university.save(), "a commitment is shown, not saved as a habit");
        for (PlannedActivity activity : result.activities()) {
            if (activity != university) {
                assertFalse(activity.start() < t("14:00") && t("09:00") < activity.end(), activity + " crosses University");
            }
        }
        PlannedActivity lunch = find(result, "Lunch");
        assertEquals(t("14:00"), lunch.start(), "lunch moves to the closest free time after the commitment");
        assertTrue(lunch.reason().contains("University runs 09:00–14:00"), lunch.reason());
    }

    @Test
    void activitiesStayInsideTheAvailableHours() {
        PreferenceInput input = new PreferenceInput(t("06:00"), t("23:00"), t("17:00"), t("21:00"), null, null, null,
                null, null, null, null, null, null, null);
        PlanResult result = plan(input, List.of(), List.of(habit("Guitar", 60, ActivityKind.PERSONAL),
                habit("Drawing", 60, ActivityKind.PERSONAL), habit("Long project", 200, ActivityKind.OTHER)), Set.of());

        assertNoOverlap(result);
        assertTrue(result.activities().stream().allMatch(a -> a.start() >= t("17:00") && a.end() <= t("21:00")), result.activities().toString());
        assertEquals(List.of("Long project"), result.unscheduled().stream().map(RoutinePlanningEngine.Unscheduled::title).toList(),
                "what does not fit is listed, not squeezed outside the hours");
        assertTrue(result.unscheduled().get(0).reason().contains("17:00") && result.unscheduled().get(0).reason().contains("21:00"));
    }

    @Test
    void lunchFollowsTheUserNotAFixedNoon() {
        PlanResult usual = plan(prefs(t("07:00"), t("23:00"), t("14:00"), null, null), List.of(),
                List.of(habit("Work", 60, ActivityKind.WORK)), Set.of("lunch"));
        assertEquals(t("14:00"), find(usual, "Lunch").start());

        ResolvedPreferences fromHistory = PersonalizationEngine.resolve(null, null, new HistorySignals(
                Map.of("lunch", new HistorySignals.TimeSignal(t("14:10"), 4)), Map.of()));
        PlanResult learned = RoutinePlanningEngine.plan(new PlanRequest(fromHistory, List.of(),
                List.of(habit("Work", 60, ActivityKind.WORK)), Set.of("lunch"), true, HistorySignals.NONE));
        PlannedActivity lunch = find(learned, "Lunch");
        assertEquals(t("14:10"), lunch.start());
        assertTrue(lunch.reason().contains("you usually start lunch around 14:10 (4 completed runs)"), lunch.reason());
    }

    @Test
    void exerciseGoesNearThePreferredTime() {
        PlanResult result = plan(prefs(t("07:00"), t("23:00"), null, null, t("18:00")),
                List.of(new Commitment("Work", t("09:00"), t("18:00"))),
                List.of(habit("Gym", 60, ActivityKind.EXERCISE), habit("Reading", 30, ActivityKind.PERSONAL)), Set.of());
        assertEquals(t("18:05"), find(result, "Gym").start(), "right after work, keeping the gap of a balanced day");
        assertNoOverlap(result);
    }

    @Test
    void frequentAndRareBreakersGetDifferentDays() {
        List<HabitRequest> habits = List.of(habit("Deep work", 180, ActivityKind.WORK));
        PlanResult frequent = plan(prefs(t("08:00"), t("22:00"), null, "FREQUENT", null), List.of(), habits, Set.of());
        PlanResult rare = plan(prefs(t("08:00"), t("22:00"), null, "RARE", null), List.of(), habits, Set.of());

        long frequentSessions = frequent.activities().stream().filter(a -> a.title().startsWith("Deep work")).count();
        long rareSessions = rare.activities().stream().filter(a -> a.title().startsWith("Deep work")).count();
        long frequentBreaks = frequent.activities().stream().filter(a -> a.kind() == ActivityKind.BREAK).count();
        long rareBreaks = rare.activities().stream().filter(a -> a.kind() == ActivityKind.BREAK).count();
        assertEquals(4, frequentSessions, frequent.activities().toString());
        assertEquals(2, rareSessions, rare.activities().toString());
        assertTrue(frequentBreaks > rareBreaks, frequentBreaks + " vs " + rareBreaks);
        assertTrue(rare.activities().stream().filter(a -> a.kind() == ActivityKind.BREAK).allMatch(a -> a.minutes() == 15));
    }

    @Test
    void highPriorityComesFirstAndRequestedTimesAreKept() {
        PlanResult result = plan(prefs(t("07:00"), t("23:00"), null, null, null), List.of(), List.of(
                new HabitRequest("Low task", 30, ActivityKind.OTHER, Priority.LOW, null),
                new HabitRequest("Important", 30, ActivityKind.OTHER, Priority.HIGH, null),
                new HabitRequest("Call mum", 20, ActivityKind.PERSONAL, Priority.MEDIUM, t("20:00"))), Set.of());
        assertTrue(find(result, "Important").start() < find(result, "Low task").start());
        assertEquals(t("20:00"), find(result, "Call mum").start());
    }

    @Test
    void sleepAfterMidnightIsOneDay() {
        PlanResult result = plan(prefs(t("10:00"), t("01:00"), null, null, null), List.of(),
                List.of(habit("Night study", 600, ActivityKind.OTHER), habit("Late reading", 120, ActivityKind.PERSONAL)), Set.of());
        assertNoOverlap(result);
        assertTrue(result.activities().stream().allMatch(a -> a.start() >= t("10:00") && a.end() <= t("01:00") + Clock.DAY));
    }

    @Test
    void aHeavyDayIsPointedOutNotHidden() {
        PlanResult result = plan(new PreferenceInput(t("06:00"), t("23:30"), null, null, null, null, null, null, null,
                null, null, null, null, Intensity.LIGHT), List.of(), List.of(habit("Study", 300, ActivityKind.STUDY)), Set.of());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("light day")), result.warnings().toString());
    }

    @Test
    void historyInformsButDoesNotOverrideTheAskedLength() {
        ResolvedPreferences prefs = PersonalizationEngine.resolve(null, null, null);
        HistorySignals history = new HistorySignals(Map.of(), Map.of("java study", new HistorySignals.LengthSignal(45, 5)));
        PlanResult result = RoutinePlanningEngine.plan(new PlanRequest(prefs, List.of(),
                List.of(habit("Java Study", 30, ActivityKind.STUDY)), Set.of(), true, history));
        assertEquals(30, find(result, "Java Study").minutes());
        assertTrue(result.explanations().stream().anyMatch(e -> e.contains("about 45 min for Java Study (5 measured runs)")),
                result.explanations().toString());
    }

    @Test
    void manyRandomDaysNeverOverlapOrLeaveTheWindow() {
        Random random = new Random(26);
        ActivityKind[] kinds = {ActivityKind.STUDY, ActivityKind.WORK, ActivityKind.EXERCISE, ActivityKind.PERSONAL, ActivityKind.OTHER};
        for (int round = 0; round < 300; round++) {
            int wake = 300 + random.nextInt(300);
            int sleep = (wake + 600 + random.nextInt(600)) % Clock.DAY;
            List<HabitRequest> habits = new ArrayList<>();
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                habits.add(new HabitRequest("A" + i, 5 + random.nextInt(150), kinds[random.nextInt(kinds.length)],
                        Priority.values()[random.nextInt(3)], random.nextInt(4) == 0 ? random.nextInt(Clock.DAY) : null));
            }
            List<Commitment> commitments = new ArrayList<>();
            if (random.nextBoolean()) {
                int start = wake + 60 + random.nextInt(240);
                commitments.add(new Commitment("Fixed", start % Clock.DAY, Math.min(start + 30 + random.nextInt(240), Clock.DAY - 1) % Clock.DAY));
            }
            commitments.removeIf(c -> c.end() <= c.start());
            PreferenceInput input = new PreferenceInput(wake, sleep, null, null, null, null, null, null,
                    random.nextBoolean() ? random.nextInt(Clock.DAY) : null, null, null, null,
                    new String[]{"FREQUENT", "NORMAL", "RARE"}[random.nextInt(3)], Intensity.values()[random.nextInt(3)]);
            ResolvedPreferences resolved = PersonalizationEngine.resolve(input, null, null);
            PlanResult result = RoutinePlanningEngine.plan(new PlanRequest(resolved, commitments, habits, ALL_MEALS, true, HistorySignals.NONE));

            assertNoOverlap(result);
            for (PlannedActivity activity : result.activities()) {
                if (activity.kind() == ActivityKind.COMMITMENT) {
                    continue;
                }
                assertTrue(activity.start() >= resolved.dayStart() && activity.end() <= resolved.dayEnd(), activity.toString());
                for (Commitment commitment : commitments) {
                    int start = Clock.inWindow(commitment.start(), resolved.dayStart());
                    int end = start + commitment.end() - commitment.start();
                    assertFalse(activity.start() < end && start < activity.end(), activity + " crosses " + commitment);
                }
            }
            int asked = habits.stream().mapToInt(HabitRequest::minutes).sum();
            int placed = result.activities().stream().filter(a -> a.title().startsWith("A")).mapToInt(PlannedActivity::minutes).sum();
            int missing = result.unscheduled().stream().filter(u -> u.title().startsWith("A")).mapToInt(RoutinePlanningEngine.Unscheduled::minutes).sum();
            assertEquals(asked, placed + missing, "every asked minute is placed or listed as not fitting");
        }
    }

    @Test
    void wrongPreferencesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PersonalizationEngine.resolve(prefs(t("07:00"), t("07:30"), null, null, null), null, null));
        assertThrows(IllegalArgumentException.class, () -> PersonalizationEngine.validate(
                new PreferenceInput(null, null, null, null, null, null, null, 0, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> PersonalizationEngine.validate(
                new PreferenceInput(null, null, null, null, null, null, null, null, null, null, null, null, "SOMETIMES", null)));
        assertThrows(IllegalArgumentException.class, () -> Clock.parse("25:00", "Wake-up time"));
    }
}
