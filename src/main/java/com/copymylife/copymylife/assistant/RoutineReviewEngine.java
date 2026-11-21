package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.ResolvedPreferences.Setting;
import com.copymylife.copymylife.assistant.ResolvedPreferences.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Reviews one routine against the user's preferences, their measured habit lengths and fixed
// scheduling rules, and proposes changes. Each issue says what was found, from what data
// ("basis": MEASURED runs, the user's PREFERENCE, a RULE of thumb, or the language MODEL), the
// suggested change and its effect. Proposals are worked out one after another on a copy of the
// routine, so together they never overlap; nothing is saved here.
// Like CopyMyLife itself, an activity without repeat days happens every day, and one that runs
// past midnight continues on the next day.
public final class RoutineReviewEngine {

    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    // Below this the language model finds a goal and an activity no closer than unrelated texts
    // (the same threshold as similar activities in ai/ai_engine.py).
    public static final double GOAL_MATCH = 0.45;
    private static final int MAX_PUSH = 6;

    // id is the routine habit id; a proposed new activity has a negative id until it is saved.
    public record Activity(long id, String title, ActivityKind kind, boolean kindRecognised, int minutes,
                           Integer start, List<String> days, boolean sharedHabit) {

        List<String> plannedDays() {
            return days == null || days.isEmpty() ? DAYS : days;
        }

        Activity with(Integer newStart, int newMinutes, List<String> newDays) {
            return new Activity(id, title, kind, kindRecognised, newMinutes, newStart, newDays, sharedHabit);
        }
    }

    public record GoalCheck(String goal, String closestActivity, double similarity) {
    }

    public record Input(
            List<Activity> activities,
            ResolvedPreferences preferences,
            Map<Long, HabitDurationAnalyzer.Insight> insights,
            List<GoalCheck> goals,
            String goalsUnavailable
    ) {
    }

    // type: MOVE (new start, maybe new days), RESIZE (new length) or ADD (a new break or meal).
    public record Change(String id, String type, long activityId, String title, Integer fromStart, Integer toStart,
                         Integer fromMinutes, Integer toMinutes, List<String> days, ActivityKind kind) {
    }

    public record Issue(String code, String severity, String title, String evidence, String basis,
                        String suggestion, String impact, List<String> changeIds) {
    }

    public record Result(List<Issue> issues, List<Change> changes, List<Activity> current, List<Activity> proposed,
                         List<String> checked, List<String> notChecked) {
    }

    private final ResolvedPreferences prefs;
    private final List<Activity> original;
    // The routine with the changes proposed so far.
    private final List<Activity> work;
    private final List<Change> changes = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private long nextNewId = -1;

    private RoutineReviewEngine(ResolvedPreferences prefs, List<Activity> activities) {
        this.prefs = prefs;
        this.original = List.copyOf(activities);
        this.work = new ArrayList<>(activities);
    }

    public static Result review(Input input) {
        RoutineReviewEngine engine = new RoutineReviewEngine(input.preferences(), input.activities());
        List<String> checked = new ArrayList<>();
        List<String> notChecked = new ArrayList<>();
        boolean anyTimed = input.activities().stream().anyMatch(a -> a.start() != null);

        if (input.activities().isEmpty()) {
            return new Result(List.of(new Issue("EMPTY", "INFO", "This routine has no activities yet",
                    "There is nothing to review.", "RULE", "Add activities, or let the assistant generate a routine.",
                    null, List.of())), List.of(), List.of(), List.of(), List.of(), List.of("Everything: no activities"));
        }

        if (anyTimed) {
            engine.overlaps();
            engine.sleepWindow();
            checked.add("Time conflicts");
            checked.add("Wake-up and sleep window");
        }
        engine.durations(input.insights());
        checked.add("Planned lengths against your measured runs");
        if (anyTimed) {
            engine.breaks();
            engine.meals();
            checked.add("Breaks between activities");
            checked.add("Meal times");
        }
        engine.untimed();
        engine.workload();
        checked.add("Workload per day");

        if (input.goals() != null) {
            engine.goals(input.goals());
            checked.add("Your goals (multilingual language model)");
        } else if (input.goalsUnavailable() != null) {
            notChecked.add("Your goals: " + input.goalsUnavailable());
        }
        if (!anyTimed) {
            notChecked.add("Time conflicts, breaks and meal times: no activity has a start time yet");
        }

        if (engine.issues.isEmpty()) {
            engine.issues.add(new Issue("ALL_GOOD", "INFO", "No problems found", "Every check above passed.", "RULE",
                    "Nothing to change.", null, List.of()));
        }

        return new Result(engine.issues, engine.changes, input.activities(), sorted(engine.work), checked, notChecked);
    }

    // Applies chosen changes to a routine's activities, for a preview or before saving.
    public static List<Activity> apply(List<Activity> activities, List<Change> chosen) {
        List<Activity> result = new ArrayList<>(activities);
        for (Change change : chosen) {
            if ("ADD".equals(change.type())) {
                result.add(new Activity(change.activityId(), change.title(), change.kind(), false,
                        change.toMinutes(), change.toStart(), change.days(), false));
                continue;
            }
            int index = indexOf(result, change.activityId());
            if (index < 0) {
                throw new IllegalArgumentException("A change names an activity that is not in this routine");
            }
            Activity activity = result.get(index);
            result.set(index, "RESIZE".equals(change.type())
                    ? activity.with(activity.start(), change.toMinutes(), activity.days())
                    : activity.with(change.toStart(), activity.minutes(), change.days() == null ? activity.days() : change.days()));
        }
        return sorted(result);
    }

    // Every pair of timed activities that share a minute on some day, in words.
    public static List<String> conflicts(List<Activity> activities) {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, List<String>> pair : overlapPairs(activities, null).entrySet()) {
            found.add(pair.getKey() + " on " + dayNames(pair.getValue()));
        }
        return found;
    }

    // ---- checks ----

    private void overlaps() {
        Set<String> handled = new LinkedHashSet<>();

        // Worked out again after each move, so a moved activity is never reported twice.
        for (int round = 0; round < 20; round++) {
            Map.Entry<String, List<String>> pair = overlapPairs(work, null).entrySet().stream()
                    .filter(entry -> !handled.contains(entry.getKey()))
                    .findFirst().orElse(null);
            if (pair == null) {
                return;
            }
            handled.add(pair.getKey());
            String[] ids = pair.getKey().split("\\|");
            Activity a = find(Long.parseLong(ids[0]));
            Activity b = find(Long.parseLong(ids[1]));
            Activity later = b.start() >= a.start() ? b : a;
            Activity earlier = later == b ? a : b;
            String evidence = describe(earlier) + " and " + describe(later) + " overlap on " + dayNames(pair.getValue()) + ".";
            List<String> ids2 = new ArrayList<>();
            String suggestion = "Move one of them; the routine can't be started while two activities share the same minutes.";
            String impact = null;
            Integer to = freeStart(later, later.minutes(), earlier.start() + earlier.minutes(), true);
            if (to != null) {
                ids2.add(move(later, to, later.plannedDays()));
                suggestion = "Move " + later.title() + " to " + Clock.format(to) + ", the next free time.";
                impact = later.title() + ": " + Clock.format(later.start()) + " → " + Clock.format(to) + ".";
            }
            issues.add(new Issue("OVERLAP", "ERROR", "Two activities overlap", evidence, "RULE", suggestion, impact, ids2));
        }
    }

    private void sleepWindow() {
        for (Activity activity : List.copyOf(work)) {
            if (activity.start() == null) {
                continue;
            }
            int start = Clock.inWindow(activity.start(), prefs.dayStart());
            if (start + activity.minutes() <= prefs.dayEnd()) {
                continue;
            }
            boolean early = activity.start() < prefs.wake().value() && start >= prefs.dayEnd();
            String evidence = describe(activity) + (early
                    ? " starts before your wake-up time " + Clock.format(prefs.dayStart())
                    : " ends after your sleep time " + Clock.format(prefs.dayEnd()))
                    + " (" + prefs.wake().detail() + "; " + prefs.sleep().detail() + ").";
            int preferred = early ? prefs.from() : prefs.to() - activity.minutes();
            Integer to = freeStart(activity, activity.minutes(), preferred, false);
            List<String> ids = new ArrayList<>();
            String suggestion = "There is no free time for it inside your waking hours; shorten it or move something else.";
            String impact = null;
            if (to != null) {
                ids.add(move(activity, to, activity.plannedDays()));
                suggestion = "Move " + activity.title() + " to " + Clock.format(to) + ".";
                impact = activity.title() + ": " + Clock.format(activity.start()) + " → " + Clock.format(to) + ".";
            }
            issues.add(new Issue("SLEEP_WINDOW", "WARNING", "Outside your waking hours", evidence,
                    sourceBasis(prefs.wake()), suggestion, impact, ids));
        }
    }

    private void durations(Map<Long, HabitDurationAnalyzer.Insight> insights) {
        for (Activity activity : List.copyOf(work)) {
            HabitDurationAnalyzer.Insight insight = insights.get(activity.id());
            if (insight == null || insight.suggestedMinutes() == null) {
                continue;
            }
            boolean longer = insight.status() == HabitDurationAnalyzer.Status.OFTEN_LONGER;
            String title = longer ? "You usually need more time for " + activity.title()
                    : "You usually need less time for " + activity.title();

            if (activity.sharedHabit()) {
                issues.add(new Issue("DURATION", "INFO", title, insight.evidence(), "MEASURED", insight.message()
                        + " This habit is also used in another routine, so its length can't be changed from here.",
                        null, List.of()));
                continue;
            }

            int to = insight.suggestedMinutes();
            Activity current = find(activity.id());
            List<String> ids = new ArrayList<>();
            List<String> moved = new ArrayList<>();

            if (current.start() == null || to < current.minutes()) {
                ids.add(resize(current, to));
            } else {
                List<Activity> before = List.copyOf(work);
                set(current.with(current.start(), to, current.days()));
                List<String> pushed = pushConflicts(current.id(), moved);
                if (pushed == null) {
                    work.clear();
                    work.addAll(before);
                    issues.add(new Issue("DURATION", "INFO", title, insight.evidence(), "MEASURED", insight.message()
                            + " There is no free time after it for a longer session, so change the times yourself.", null, List.of()));
                    continue;
                }
                set(current);
                ids.add(resize(current, to));
                for (String id : pushed) {
                    ids.add(id);
                }
            }

            String impact = activity.title() + ": " + current.minutes() + " → " + to + " min"
                    + (moved.isEmpty() ? "" : "; " + String.join("; ", moved)) + ".";
            issues.add(new Issue("DURATION", "WARNING", title, insight.evidence(), "MEASURED",
                    insight.message(), impact, ids));
        }
    }

    private void breaks() {
        int every = prefs.breakEvery().value();
        int length = prefs.breakMinutes().value();
        // A run is known by the activities in it, so the same run on several days is one issue.
        Set<String> seen = new LinkedHashSet<>();

        for (String day : DAYS) {
            for (int round = 0; round < 10; round++) {
                List<Activity> run = longRun(day, every, length, seen);
                if (run == null) {
                    break;
                }
                seen.add(key(run));
                proposeBreak(run, run.stream().mapToInt(Activity::minutes).sum(), every, length, day);
            }
        }
    }

    // The first run of activities on a day (closer together than a break, no meal or break in
    // between) that grows past the break interval, up to the activity that makes it too long.
    private List<Activity> longRun(String day, int every, int length, Set<String> seen) {
        List<Activity> run = new ArrayList<>();
        int runMinutes = 0;
        Integer runEnd = null;

        for (Activity activity : dayList(day)) {
            int start = Clock.inWindow(activity.start(), prefs.dayStart());
            if (activity.kind().isRest()) {
                run = new ArrayList<>();
                runMinutes = 0;
                runEnd = null;
                continue;
            }
            if (runEnd == null || start - runEnd >= length) {
                run = new ArrayList<>();
                runMinutes = 0;
            }
            run.add(activity);
            runMinutes += activity.minutes();
            runEnd = start + activity.minutes();

            if (runMinutes > every && run.size() > 1) {
                if (!seen.contains(key(run))) {
                    return run;
                }
                // Already reported: count on from this activity.
                run = new ArrayList<>(List.of(activity));
                runMinutes = activity.minutes();
            }
        }
        return null;
    }

    private static String key(List<Activity> run) {
        return String.join(",", run.stream().map(a -> String.valueOf(a.id())).toList());
    }

    private void proposeBreak(List<Activity> run, int runMinutes, int every, int length, String day) {
        Activity last = run.get(run.size() - 1);
        Activity before = run.get(run.size() - 2);
        String names = String.join(", ", run.stream().map(Activity::title).toList());
        String evidence = names + " follow each other for " + Clock.length(runMinutes) + " without a planned break on "
                + dayName(day) + " (break after " + every + " min: " + prefs.breakEvery().detail() + ").";
        String title = "Several activities in a row without a break";

        Activity current = find(last.id());
        Activity previous = find(before.id());
        int breakStart = previous.start() + previous.minutes();
        int needed = breakStart + length;
        List<Activity> snapshot = List.copyOf(work);
        List<String> moved = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        boolean fits = true;

        if (current.start() < needed) {
            Integer target = Clock.roundUp5(needed);
            if (!inWindow(target, current.minutes())) {
                fits = false;
            } else {
                set(current.with(target, current.minutes(), current.days()));
                List<String> pushed = pushConflicts(current.id(), moved);
                if (pushed == null) {
                    fits = false;
                } else {
                    set(current);
                    ids.add(move(current, target, current.plannedDays()));
                    ids.addAll(pushed);
                    moved.add(0, current.title() + " " + Clock.format(current.start()) + " → " + Clock.format(target));
                }
            }
        }

        if (fits) {
            List<String> breakDays = commonDays(previous, current);
            Activity breakActivity = new Activity(nextNewId, "Break", ActivityKind.BREAK, false, length, breakStart, breakDays, false);
            if (!overlapPairs(withAdded(breakActivity), null).keySet().stream().allMatch(key -> overlapPairs(work, null).containsKey(key))) {
                fits = false;
            } else {
                ids.add(add(breakActivity));
            }
        }

        if (!fits) {
            work.clear();
            work.addAll(snapshot);
            changes.removeIf(change -> ids.contains(change.id()));
            issues.add(new Issue("NO_BREAK", "WARNING", title, evidence, sourceBasis(prefs.breakEvery()),
                    "Add a " + length + "-minute break before " + last.title() + "; there is not enough free time after it to"
                            + " move it, so this needs a manual change.", null, List.of()));
            return;
        }

        String impact = "A " + length + "-minute break at " + Clock.format(breakStart)
                + (moved.isEmpty() ? "" : "; " + String.join("; ", moved)) + ".";
        issues.add(new Issue("NO_BREAK", "WARNING", title, evidence, sourceBasis(prefs.breakEvery()),
                "Add a " + length + "-minute break before " + last.title() + ".", impact, ids));
    }

    private void meals() {
        for (String meal : RoutinePlanningEngine.MEALS) {
            Setting setting = prefs.meal(meal);
            if (setting.source() == Source.DEFAULT) {
                continue;
            }
            int preferred = setting.value();
            int length = prefs.mealMinutes().value();
            String name = meal.substring(0, 1).toUpperCase(Locale.ROOT) + meal.substring(1);

            Activity existing = work.stream()
                    .filter(a -> a.start() != null && a.kind() == ActivityKind.MEAL)
                    .filter(a -> meal.equals(ActivityKind.mealOf(a.title()))
                            || Math.abs(minuteDistance(a.start(), preferred)) <= 60)
                    .findFirst().orElse(null);

            if (existing != null) {
                int distance = Math.abs(minuteDistance(existing.start(), preferred));
                if (distance > 30 && meal.equals(ActivityKind.mealOf(existing.title()))) {
                    Integer to = freeStart(existing, existing.minutes(), preferred, false);
                    List<String> ids = new ArrayList<>();
                    String impact = null;
                    if (to != null && Math.abs(minuteDistance(to, preferred)) < distance) {
                        ids.add(move(existing, to, existing.plannedDays()));
                        impact = existing.title() + ": " + Clock.format(existing.start()) + " → " + Clock.format(to) + ".";
                    }
                    issues.add(new Issue("MEAL_TIME", "INFO", name + " is far from your usual time",
                            existing.title() + " is planned at " + Clock.format(existing.start()) + "; your " + meal + " time is "
                                    + Clock.format(preferred) + " (" + setting.detail() + ").", sourceBasis(setting),
                            ids.isEmpty() ? "Keep it, or move it yourself; there is no free time closer to " + Clock.format(preferred) + "."
                                    : "Move " + existing.title() + " closer to " + Clock.format(preferred) + ".", impact, ids));
                }
                continue;
            }

            Activity blocking = work.stream()
                    .filter(a -> a.start() != null && !a.kind().isRest())
                    .filter(a -> sharesMinutes(a, preferred, length))
                    .findFirst().orElse(null);
            if (blocking == null) {
                continue;
            }

            String evidence = "Your preferred " + meal + " time (" + Clock.format(preferred) + ", " + setting.detail()
                    + ") conflicts with " + describe(blocking) + ".";
            Activity mealActivity = new Activity(nextNewId, name, ActivityKind.MEAL, false, length, null, blocking.days(), false);
            Integer to = freeStart(mealActivity, length, preferred, false, 90);
            List<String> ids = new ArrayList<>();
            String suggestion = "There is no free time within 90 minutes of " + Clock.format(preferred)
                    + "; move " + blocking.title() + " or plan " + meal + " yourself.";
            String impact = null;
            if (to != null) {
                ids.add(add(mealActivity.with(to, length, blocking.days())));
                suggestion = "Add " + name + " at " + Clock.format(to) + (to == preferred ? "" : ", the closest free time") + ".";
                impact = "New activity " + name + " " + Clock.format(to) + "–" + Clock.format(to + length)
                        + "; the routine grows by " + length + " min.";
            }
            issues.add(new Issue("MEAL_CONFLICT", "WARNING", "Your " + meal + " time conflicts with an activity", evidence,
                    sourceBasis(setting), suggestion, impact, ids));
        }
    }

    private void untimed() {
        List<Activity> untimed = work.stream().filter(a -> a.start() == null).toList();
        boolean allUntimed = untimed.size() == work.size();
        if (untimed.isEmpty()) {
            return;
        }

        // Timed activities block their minutes on every day, so the new times are free all week.
        Timeline week = new Timeline();
        for (Activity activity : work) {
            if (activity.start() != null) {
                int start = Clock.inWindow(activity.start(), prefs.dayStart());
                week.block(new Timeline.Slot(start, start + activity.minutes(), activity.kind(), activity.title()));
            }
        }

        int every = prefs.breakEvery().value();
        int breakLength = prefs.breakMinutes().value();
        int gap = prefs.intensity().gapMinutes();
        List<String> ids = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        int cursor = Clock.roundUp5(prefs.from());

        for (Activity activity : untimed) {
            Integer placed = null;
            for (int start = cursor; start + activity.minutes() <= prefs.to(); start += 5) {
                if (!week.freeWithGap(start, start + activity.minutes(), gap)) {
                    continue;
                }
                int run = week.runBefore(start, breakLength);
                if (!activity.kind().isRest() && run > 0 && run + activity.minutes() > every) {
                    Timeline.Slot previous = week.before(start);
                    int breakStart = previous.end();
                    int after = Clock.roundUp5(breakStart + breakLength);
                    if (after + activity.minutes() > prefs.to() || !week.free(breakStart, after)
                            || !week.freeWithGap(after, after + activity.minutes(), gap)) {
                        continue;
                    }
                    week.add(new Timeline.Slot(breakStart, breakStart + breakLength, ActivityKind.BREAK, "Break"));
                    ids.add(add(new Activity(nextNewId, "Break", ActivityKind.BREAK, false, breakLength,
                            breakStart % Clock.DAY, List.of(), false)));
                    lines.add("Break " + Clock.format(breakStart));
                    start = after;
                }
                placed = start;
                break;
            }
            if (placed == null) {
                missed.add(activity.title());
                continue;
            }
            week.add(new Timeline.Slot(placed, placed + activity.minutes(), activity.kind(), activity.title()));
            ids.add(move(activity, placed % Clock.DAY, List.copyOf(DAYS)));
            lines.add(activity.title() + " " + Clock.format(placed) + "–" + Clock.format(placed + activity.minutes()));
            cursor = Clock.roundUp5(placed + activity.minutes());
        }

        String evidence = untimed.size() + (untimed.size() == 1 ? " activity has" : " activities have")
                + " no start time, so " + (allUntimed ? "time conflicts, breaks and meals can't be checked."
                : "they are left out of the time checks.");
        String suggestion = ids.isEmpty() ? "There is no free time for them between " + Clock.format(prefs.from()) + " and "
                + Clock.format(prefs.to()) + "; set times yourself."
                : "Give them times one after another from " + Clock.format(prefs.from()) + ", every day, with breaks as you prefer."
                + (missed.isEmpty() ? "" : " No room for: " + String.join(", ", missed) + ".");
        issues.add(new Issue("UNTIMED", "INFO", "Activities without a start time", evidence, "RULE", suggestion,
                ids.isEmpty() ? null : String.join("; ", lines) + ".", ids));
    }

    // On the routine as it is now, not as proposed.
    private void workload() {
        int window = prefs.dayEnd() - prefs.dayStart();
        int limit = prefs.intensity().focusLimitMinutes();
        Map<String, List<String>> tooLong = new LinkedHashMap<>();
        Map<String, List<String>> tooMuchFocus = new LinkedHashMap<>();

        for (String day : DAYS) {
            List<Activity> planned = original.stream().filter(a -> a.plannedDays().contains(day)).toList();
            int total = planned.stream().mapToInt(Activity::minutes).sum();
            int focus = planned.stream().filter(a -> a.kind().isFocus()).mapToInt(Activity::minutes).sum();
            if (total > window) {
                tooLong.computeIfAbsent(Clock.length(total), key -> new ArrayList<>()).add(day);
            }
            if (focus > limit) {
                tooMuchFocus.computeIfAbsent(Clock.length(focus), key -> new ArrayList<>()).add(day);
            }
        }

        tooLong.forEach((total, days) -> issues.add(new Issue("WORKLOAD", "ERROR", "More planned than your waking hours",
                total + " of activities on " + dayNames(days) + ", but you are awake for " + Clock.length(window) + ".",
                sourceBasis(prefs.wake()), "Shorten or remove activities, or plan some of them on other days.", null, List.of())));
        tooMuchFocus.forEach((focus, days) -> issues.add(new Issue("WORKLOAD", "WARNING", "A heavy study and work load",
                focus + " of study and work on " + dayNames(days) + " (recognised from the activity titles), more than the "
                        + Clock.length(limit) + " a " + prefs.intensity().label() + " day aims for. That limit is a starting value, not a rule.",
                "RULE", "Consider fewer or shorter focus activities, or choose another intensity in My Preferences.", null, List.of())));
    }

    private void goals(List<GoalCheck> goals) {
        for (GoalCheck goal : goals) {
            if (goal.similarity() >= GOAL_MATCH) {
                continue;
            }
            issues.add(new Issue("GOAL", "INFO", "A goal without a matching activity",
                    "No activity is close in meaning to your goal \"" + goal.goal() + "\""
                            + (goal.closestActivity() == null ? "." : " (closest: " + goal.closestActivity()
                            + ", similarity " + String.format(Locale.ROOT, "%.2f", goal.similarity()) + ")."),
                    "MODEL", "If this routine should serve that goal, add an activity for it.", null, List.of()));
        }
    }

    // ---- moving things ----

    // Moves activities that now overlap the given one later, one after another, as long as they
    // stay inside the waking hours; null (and nothing kept) when that is impossible.
    private List<String> pushConflicts(long id, List<String> moved) {
        List<String> ids = new ArrayList<>();
        List<Change> made = new ArrayList<>();
        Set<Long> pushedIds = new LinkedHashSet<>();
        pushedIds.add(id);

        for (int round = 0; round < MAX_PUSH; round++) {
            Map<String, List<String>> pairs = overlapPairs(work, pushedIds);
            if (pairs.isEmpty()) {
                return ids;
            }
            String key = pairs.keySet().iterator().next();
            String[] parts = key.split("\\|");
            Activity a = find(Long.parseLong(parts[0]));
            Activity b = find(Long.parseLong(parts[1]));
            Activity anchor = pushedIds.contains(a.id()) ? a : b;
            Activity other = anchor == a ? b : a;
            int anchorStart = Clock.inWindow(anchor.start(), prefs.dayStart());
            int otherStart = Clock.inWindow(other.start(), prefs.dayStart());
            if (otherStart < anchorStart || pushedIds.contains(other.id())) {
                changes.removeAll(made);
                return null;
            }
            int target = Clock.roundUp5(anchorStart + anchor.minutes());
            if (!inWindow(target, other.minutes())) {
                changes.removeAll(made);
                return null;
            }
            String id2 = move(other, target % Clock.DAY, other.plannedDays());
            made.add(changes.get(changes.size() - 1));
            ids.add(id2);
            moved.add(other.title() + " " + Clock.format(other.start()) + " → " + Clock.format(target));
            pushedIds.add(other.id());
        }
        changes.removeAll(made);
        return null;
    }

    // The free start for an activity closest to preferred (later first), counting every day it
    // is planned; null if there is none within maxShift minutes inside the available hours.
    private Integer freeStart(Activity activity, int minutes, int preferred, boolean laterOnly) {
        return freeStart(activity, minutes, preferred, laterOnly, Clock.DAY);
    }

    private Integer freeStart(Activity activity, int minutes, int preferred, boolean laterOnly, int maxShift) {
        int from = Clock.inWindow(preferred, prefs.dayStart());
        for (int shift = 0; shift <= maxShift; shift += 5) {
            for (int candidate : laterOnly ? new int[]{from + shift} : new int[]{from + shift, from - shift}) {
                int rounded = Clock.roundUp5(candidate);
                if (rounded < prefs.from() || rounded + minutes > prefs.to()) {
                    continue;
                }
                Activity trial = activity.with(rounded % Clock.DAY, minutes, activity.days());
                List<Activity> others = new ArrayList<>(work);
                others.removeIf(a -> a.id() == activity.id());
                others.add(trial);
                if (overlapPairs(others, Set.of(activity.id())).isEmpty()) {
                    return rounded % Clock.DAY;
                }
            }
        }
        return null;
    }

    private boolean inWindow(int startInDay, int minutes) {
        int start = Clock.inWindow(startInDay, prefs.dayStart());
        return start >= prefs.from() && start + minutes <= prefs.to();
    }

    private String move(Activity activity, int to, List<String> days) {
        Activity current = find(activity.id());
        String id = "c" + (changes.size() + 1);
        changes.add(new Change(id, "MOVE", current.id(), current.title(), current.start(), to, current.minutes(),
                current.minutes(), days, current.kind()));
        set(current.with(to, current.minutes(), days));
        return id;
    }

    private String resize(Activity activity, int minutes) {
        Activity current = find(activity.id());
        String id = "c" + (changes.size() + 1);
        changes.add(new Change(id, "RESIZE", current.id(), current.title(), current.start(), current.start(),
                current.minutes(), minutes, current.days(), current.kind()));
        set(current.with(current.start(), minutes, current.days()));
        return id;
    }

    private String add(Activity activity) {
        String id = "c" + (changes.size() + 1);
        Activity added = new Activity(nextNewId--, activity.title(), activity.kind(), false, activity.minutes(),
                activity.start(), activity.days(), false);
        changes.add(new Change(id, "ADD", added.id(), added.title(), null, added.start(), null, added.minutes(),
                added.days(), added.kind()));
        work.add(added);
        return id;
    }

    // ---- helpers ----

    // Pairs "idA|idB" of activities sharing a minute, with the days; with only, pairs that involve one of those ids.
    private static Map<String, List<String>> overlapPairs(List<Activity> activities, Set<Long> only) {
        Map<String, List<String>> pairs = new LinkedHashMap<>();
        List<Activity> timed = sorted(activities).stream().filter(a -> a.start() != null).toList();

        for (int i = 0; i < timed.size(); i++) {
            for (int j = i + 1; j < timed.size(); j++) {
                Activity a = timed.get(i);
                Activity b = timed.get(j);
                if (only != null && !only.contains(a.id()) && !only.contains(b.id())) {
                    continue;
                }
                for (int day = 0; day < DAYS.size(); day++) {
                    if (sharesDay(a, b, day)) {
                        pairs.computeIfAbsent(a.id() + "|" + b.id(), key -> new ArrayList<>()).add(DAYS.get(day));
                    }
                }
            }
        }
        return pairs;
    }

    private static boolean sharesDay(Activity a, Activity b, int day) {
        for (int[] x : segments(a)) {
            for (int[] y : segments(b)) {
                if (x[0] == day && y[0] == day && x[1] < y[2] && y[1] < x[2]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<int[]> segments(Activity activity) {
        List<int[]> result = new ArrayList<>();
        int start = activity.start();
        int end = start + activity.minutes();
        for (String day : activity.plannedDays()) {
            int index = DAYS.indexOf(day);
            result.add(new int[]{index, start, Math.min(end, Clock.DAY)});
            if (end > Clock.DAY) {
                result.add(new int[]{(index + 1) % DAYS.size(), 0, end - Clock.DAY});
            }
        }
        return result;
    }

    private boolean sharesMinutes(Activity activity, int start, int length) {
        int from = Clock.inWindow(activity.start(), prefs.dayStart());
        int at = Clock.inWindow(start, prefs.dayStart());
        return from < at + length && at < from + activity.minutes();
    }

    private List<Activity> dayList(String day) {
        return work.stream()
                .filter(a -> a.start() != null && a.plannedDays().contains(day))
                .sorted(Comparator.comparingInt(a -> Clock.inWindow(a.start(), prefs.dayStart())))
                .toList();
    }

    private List<Activity> withAdded(Activity activity) {
        List<Activity> list = new ArrayList<>(work);
        list.add(activity);
        return list;
    }

    private static List<String> commonDays(Activity a, Activity b) {
        List<String> days = a.plannedDays().stream().filter(b.plannedDays()::contains).toList();
        return days.size() == DAYS.size() ? List.of() : days;
    }

    private Activity find(long id) {
        return work.get(indexOf(work, id));
    }

    private void set(Activity activity) {
        work.set(indexOf(work, activity.id()), activity);
    }

    private static int indexOf(List<Activity> activities, long id) {
        for (int i = 0; i < activities.size(); i++) {
            if (activities.get(i).id() == id) {
                return i;
            }
        }
        return -1;
    }

    private static List<Activity> sorted(List<Activity> activities) {
        List<Activity> list = new ArrayList<>(activities);
        list.sort(Comparator.comparing(Activity::start, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Activity::id, Comparator.comparingLong(id -> id < 0 ? Long.MAX_VALUE + id : id)));
        return list;
    }

    private static int minuteDistance(int a, int b) {
        return Math.floorMod(a - b + 720, Clock.DAY) - 720;
    }

    private static String describe(Activity activity) {
        return activity.title() + " (" + Clock.format(activity.start()) + "–" + Clock.format(activity.start() + activity.minutes()) + ")";
    }

    private static String sourceBasis(Setting setting) {
        return setting.source() == Source.HISTORY ? "MEASURED" : setting.source() == Source.DEFAULT ? "RULE" : "PREFERENCE";
    }

    static String dayNames(List<String> days) {
        if (days.size() == DAYS.size()) {
            return "every day";
        }
        return String.join(", ", days.stream().map(RoutineReviewEngine::dayName).toList());
    }

    private static String dayName(String code) {
        return code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
    }
}
