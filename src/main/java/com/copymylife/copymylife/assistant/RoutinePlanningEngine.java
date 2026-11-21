package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.ResolvedPreferences.Setting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Builds a day plan by fixed rules, the same answer for the same input. Order of work:
// 1. fixed commitments block their time;
// 2. meals go at the preferred time, or the closest free time within two hours;
// 3. activities with a time of their own (a preferred time, or exercise near the preferred
//    exercise time) go as close to it as there is room;
// 4. the rest go in priority order into the earliest free time within the available hours,
//    study and work split into sessions no longer than the focus block, with a break before
//    an activity that would make a run without rest longer than the break interval.
// Nothing is ever placed across another activity, outside the available hours (meals: outside
// wake-up to sleep) or across a commitment; what does not fit is listed with the reason.
// Each choice is written down as a reason the user can read.
public final class RoutinePlanningEngine {

    public enum Priority {
        HIGH,
        MEDIUM,
        LOW;

        public static Priority parse(String value) {
            if (value == null || value.isBlank()) {
                return MEDIUM;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Priority must be HIGH, MEDIUM or LOW");
            }
        }
    }

    public record Commitment(String title, int start, int end) {
    }

    public record HabitRequest(String title, int minutes, ActivityKind kind, Priority priority, Integer preferredTime) {
    }

    public record PlanRequest(
            ResolvedPreferences preferences,
            List<Commitment> commitments,
            List<HabitRequest> habits,
            Set<String> meals,
            boolean splitFocus,
            HistorySignals history
    ) {
    }

    // start and end are minutes of the plan's day (above 1439 = after midnight). save says whether
    // the page ticks it for saving: commitments are shown for context and are not habits.
    public record PlannedActivity(String title, ActivityKind kind, int start, int end, boolean fixed,
                                  boolean save, String reason) {

        public int minutes() {
            return end - start;
        }
    }

    public record Unscheduled(String title, int minutes, String reason) {
    }

    public record PlanResult(
            List<PlannedActivity> activities,
            List<Unscheduled> unscheduled,
            List<String> explanations,
            List<String> warnings,
            int plannedMinutes,
            int focusMinutes
    ) {
    }

    public static final List<String> MEALS = List.of("breakfast", "lunch", "dinner");
    private static final int MEAL_SHIFT = 120;
    private static final int DAY_SHIFT = Clock.DAY;
    // A requested length that differs this much from the usual one is pointed out.
    private static final int HINT_MINUTES = 5;

    private RoutinePlanningEngine() {
    }

    public static PlanResult plan(PlanRequest request) {
        ResolvedPreferences prefs = request.preferences();
        Timeline day = new Timeline();
        List<PlannedActivity> activities = new ArrayList<>();
        List<Unscheduled> unscheduled = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int gap = prefs.intensity().gapMinutes();
        int breakEvery = prefs.breakEvery().value();
        int breakMinutes = prefs.breakMinutes().value();

        explanations.add("Your day runs from " + Clock.format(prefs.dayStart()) + " to " + Clock.format(prefs.dayEnd())
                + " (" + source(prefs.wake()) + "; " + source(prefs.sleep()) + "), and activities are planned between "
                + Clock.format(prefs.from()) + " and " + Clock.format(prefs.to()) + ".");

        // 1. Commitments.
        for (Commitment commitment : request.commitments()) {
            int start = Clock.inWindow(commitment.start(), prefs.dayStart());
            int end = start + Math.floorMod(commitment.end() - commitment.start(), Clock.DAY);
            day.block(new Timeline.Slot(start, end, ActivityKind.COMMITMENT, commitment.title()));
            activities.add(new PlannedActivity(commitment.title(), ActivityKind.COMMITMENT, start, end, true, false,
                    "Your fixed commitment; nothing else is planned across it."));

            if (start < prefs.dayStart() || end > prefs.dayEnd()) {
                warnings.add("\"" + commitment.title() + "\" (" + Clock.format(start) + "–" + Clock.format(end)
                        + ") lies partly outside your waking hours.");
            }
        }

        // 2. Meals.
        for (String meal : MEALS) {
            if (!request.meals().contains(meal)) {
                continue;
            }
            Setting time = prefs.meal(meal);
            int preferred = prefs.inDay(time);
            int length = prefs.mealMinutes().value();
            Integer start = day.nearestFree(preferred, length, prefs.dayStart(), prefs.dayEnd(), MEAL_SHIFT, 0);
            String name = capitalise(meal);

            if (start == null) {
                unscheduled.add(new Unscheduled(name, length, "No free " + length + " minutes within two hours of "
                        + Clock.format(preferred) + " (" + source(time) + ")."));
                continue;
            }

            day.add(new Timeline.Slot(start, start + length, ActivityKind.MEAL, name));
            String reason = start == preferred
                    ? name + " at " + Clock.format(start) + ": " + source(time) + "."
                    : name + " at " + Clock.format(start) + " instead of " + Clock.format(preferred) + " ("
                    + source(time) + "), the closest free time" + blockerText(request.commitments(), preferred, length, prefs) + ".";
            activities.add(new PlannedActivity(name, ActivityKind.MEAL, start, start + length, false, true, reason));
            explanations.add(reason);
        }

        // 3. Activities with a time of their own, then 4. the others by priority.
        List<HabitRequest> anchored = new ArrayList<>();
        List<HabitRequest> flexible = new ArrayList<>();

        for (HabitRequest habit : request.habits()) {
            hint(habit, request.history(), explanations);
            if (habit.preferredTime() != null || (habit.kind() == ActivityKind.EXERCISE && prefs.exercise().value() != null)) {
                anchored.add(habit);
            } else {
                flexible.add(habit);
            }
        }

        Comparator<HabitRequest> byPriority = Comparator.comparing(HabitRequest::priority);
        anchored.sort(byPriority);
        flexible.sort(byPriority);

        for (HabitRequest habit : anchored) {
            boolean own = habit.preferredTime() != null;
            int preferred = Clock.inWindow(own ? habit.preferredTime() : prefs.exercise().value(), prefs.dayStart());
            String why = own ? "the time you asked for" : source(prefs.exercise()) + " for exercise";
            Integer start = day.nearestFree(preferred, habit.minutes(), prefs.from(), prefs.to(), DAY_SHIFT, gap);

            if (start == null) {
                start = day.nearestFree(preferred, habit.minutes(), prefs.from(), prefs.to(), DAY_SHIFT, 0);
            }
            if (start == null) {
                unscheduled.add(new Unscheduled(habit.title(), habit.minutes(), noRoom(habit.minutes(), prefs)));
                continue;
            }

            day.add(new Timeline.Slot(start, start + habit.minutes(), habit.kind(), habit.title()));
            String reason = start == preferred
                    ? habit.title() + " at " + Clock.format(start) + ": " + why + "."
                    : habit.title() + " at " + Clock.format(start) + ", the closest free time to " + Clock.format(preferred)
                    + " (" + why + ").";
            activities.add(new PlannedActivity(habit.title(), habit.kind(), start, start + habit.minutes(), false, true, reason));
            explanations.add(reason);
        }

        boolean splitAny = false;

        for (HabitRequest habit : flexible) {
            List<Integer> sessions = request.splitFocus() && habit.kind().isFocus()
                    ? sessions(habit.minutes(), prefs.focusBlock().value())
                    : List.of(habit.minutes());

            for (int index = 0; index < sessions.size(); index++) {
                int length = sessions.get(index);
                String title = sessions.size() == 1 ? habit.title()
                        : habit.title() + " (" + (index + 1) + "/" + sessions.size() + ")";
                PlannedActivity placed = placeFlexible(day, title, habit.kind(), length, prefs, gap, breakEvery,
                        breakMinutes, activities);

                if (placed == null) {
                    int left = sessions.subList(index, sessions.size()).stream().mapToInt(Integer::intValue).sum();
                    unscheduled.add(new Unscheduled(title, left, noRoom(length, prefs)));
                    break;
                }
            }

            if (sessions.size() > 1) {
                splitAny = true;
                explanations.add(habit.title() + " (" + Clock.length(habit.minutes()) + ") is split into "
                        + sessions.size() + " sessions, because your longest focus session is "
                        + prefs.focusBlock().value() + " min (" + source(prefs.focusBlock()) + ").");
            }
        }

        if (!flexible.isEmpty()) {
            explanations.add("Other activities are placed by priority (high first) into the earliest free time"
                    + (gap > 0 ? ", " + gap + " min apart for a " + prefs.intensity().label() + " day" : "")
                    + ". A " + breakMinutes + "-minute break comes before any activity that would make more than "
                    + breakEvery + " min without rest (" + source(prefs.breakEvery()) + ").");
        }
        if (!splitAny && request.splitFocus() && flexible.stream().anyMatch(h -> h.kind().isFocus())) {
            explanations.add("Study and work sessions fit within your " + prefs.focusBlock().value()
                    + "-minute focus block, so none was split.");
        }

        activities.sort(Comparator.comparingInt(PlannedActivity::start).thenComparingInt(PlannedActivity::end));
        checkNoOverlap(activities);
        longRunWarnings(day, breakEvery, breakMinutes, warnings);

        int planned = activities.stream().filter(a -> a.kind() != ActivityKind.COMMITMENT).mapToInt(PlannedActivity::minutes).sum();
        int focus = activities.stream().filter(a -> a.kind().isFocus()).mapToInt(PlannedActivity::minutes).sum();

        if (focus > prefs.intensity().focusLimitMinutes()) {
            warnings.add("This plan has " + Clock.length(focus) + " of study and work, more than the "
                    + Clock.length(prefs.intensity().focusLimitMinutes()) + " a " + prefs.intensity().label()
                    + " day aims for. That limit is a starting value, not a rule; lower priorities or choose another intensity.");
        }
        if (!unscheduled.isEmpty()) {
            warnings.add(unscheduled.size() == 1 ? "One activity did not fit; see below."
                    : unscheduled.size() + " activities did not fit; see below.");
        }

        return new PlanResult(activities, unscheduled, explanations, warnings, planned, focus);
    }

    // Splits a study or work activity into equal sessions no longer than the focus block.
    static List<Integer> sessions(int minutes, int block) {
        int count = (minutes + block - 1) / block;
        List<Integer> sessions = new ArrayList<>();
        int base = minutes / count;
        int extra = minutes % count;
        for (int i = 0; i < count; i++) {
            sessions.add(base + (i < extra ? 1 : 0));
        }
        return sessions;
    }

    private static PlannedActivity placeFlexible(Timeline day, String title, ActivityKind kind, int length,
                                                 ResolvedPreferences prefs, int gap, int breakEvery, int breakMinutes,
                                                 List<PlannedActivity> activities) {
        for (int start = Clock.roundUp5(prefs.from()); start + length <= prefs.to(); start += 5) {
            if (!day.freeWithGap(start, start + length, gap)) {
                continue;
            }

            boolean rests = kind.isRest();
            int run = day.runBefore(start, breakMinutes);

            if (rests || run == 0 || run + length <= breakEvery) {
                return put(day, activities, title, kind, start, length, null);
            }

            // A break first: right after the run, then the activity after the break.
            Timeline.Slot previous = day.before(start);
            int breakStart = previous.end();
            int after = Clock.roundUp5(breakStart + breakMinutes);

            if (after + length <= prefs.to() && day.free(breakStart, after) && day.freeWithGap(after, after + length, gap)) {
                day.add(new Timeline.Slot(breakStart, breakStart + breakMinutes, ActivityKind.BREAK, "Break"));
                activities.add(new PlannedActivity("Break", ActivityKind.BREAK, breakStart, breakStart + breakMinutes,
                        false, true, "A " + breakMinutes + "-minute break after " + Clock.length(run)
                        + " of activities without rest."));
                return put(day, activities, title, kind, after, length, null);
            }
        }
        return null;
    }

    private static PlannedActivity put(Timeline day, List<PlannedActivity> activities, String title, ActivityKind kind,
                                       int start, int length, String reason) {
        day.add(new Timeline.Slot(start, start + length, kind, title));
        PlannedActivity activity = new PlannedActivity(title, kind, start, start + length, false, true,
                reason == null ? "The earliest free time for it." : reason);
        activities.add(activity);
        return activity;
    }

    private static void hint(HabitRequest habit, HistorySignals history, List<String> explanations) {
        if (history == null) {
            return;
        }
        HistorySignals.LengthSignal usual = history.usualLengths().get(habit.title().strip().toLowerCase(Locale.ROOT));

        if (usual == null || usual.observations() < PersonalizationEngine.MIN_OBSERVATIONS) {
            return;
        }
        int difference = usual.minutes() - habit.minutes();

        if (Math.abs(difference) >= HINT_MINUTES && Math.abs(difference) * 5 >= habit.minutes()) {
            explanations.add("Your records show about " + usual.minutes() + " min for " + habit.title() + " ("
                    + usual.observations() + " measured runs); this plan keeps the " + habit.minutes()
                    + " min you asked for. Change it in the preview if you like.");
        }
    }

    // A last check: the rules above never overlap two activities, and this makes sure of it.
    static void checkNoOverlap(List<PlannedActivity> activities) {
        for (int i = 0; i < activities.size(); i++) {
            for (int j = i + 1; j < activities.size(); j++) {
                PlannedActivity a = activities.get(i);
                PlannedActivity b = activities.get(j);
                boolean bothFixed = a.fixed() && b.fixed();
                if (!bothFixed && a.start() < b.end() && b.start() < a.end()) {
                    throw new IllegalStateException("\"" + a.title() + "\" and \"" + b.title() + "\" overlap");
                }
            }
        }
    }

    private static void longRunWarnings(Timeline day, int breakEvery, int breakMinutes, List<String> warnings) {
        List<Timeline.Slot> slots = day.slots();
        for (Timeline.Slot slot : slots) {
            Timeline.Slot previous = day.before(slot.start());
            boolean startsRun = previous == null || slot.start() - previous.end() >= breakMinutes || previous.kind().isRest();
            if (slot.kind().isRest() || !startsRun) {
                continue;
            }
            int run = slot.length() + day.runAfter(slot.end(), breakMinutes);
            if (run > breakEvery && run > slot.length()) {
                warnings.add("From " + Clock.format(slot.start()) + " there are " + Clock.length(run)
                        + " of activities without a break, and there was no room to add one.");
            }
        }
    }

    private static String noRoom(int minutes, ResolvedPreferences prefs) {
        return "No free " + Clock.length(minutes) + " between " + Clock.format(prefs.from()) + " and "
                + Clock.format(prefs.to()) + " after the activities above.";
    }

    private static String blockerText(List<Commitment> commitments, int preferred, int length, ResolvedPreferences prefs) {
        for (Commitment commitment : commitments) {
            int start = Clock.inWindow(commitment.start(), prefs.dayStart());
            int end = start + Math.floorMod(commitment.end() - commitment.start(), Clock.DAY);
            if (start < preferred + length && preferred < end) {
                return " because " + commitment.title() + " runs " + Clock.format(start) + "–" + Clock.format(end);
            }
        }
        return "";
    }

    // Where a value came from, in words: "your saved preference", "default 13:00", ...
    static String source(Setting setting) {
        return setting.detail();
    }

    private static String capitalise(String word) {
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }
}
