package com.copymylife.copymylife.assistant;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The end-of-day summary, from the runs the user tracked that day and nothing else. A habit
// marked completed has a length only if the run timer measured it or the user entered one;
// otherwise the summary says the length is unknown instead of assuming the plan.
public final class DailySummaryEngine {

    // How far from its planned start a habit may begin and still count as "as scheduled".
    static final int ON_TIME_MINUTES = 10;
    static final int LATE_MINUTES = 15;

    public record HabitResult(
            long routineId,
            String routineTitle,
            long routineHabitId,
            String title,
            String status,
            int plannedMinutes,
            Integer timerMinutes,
            Integer reportedMinutes,
            Integer plannedStart,
            Integer actualStart
    ) {

        Integer actualMinutes() {
            return reportedMinutes != null ? reportedMinutes : timerMinutes;
        }

        String actualSource() {
            return reportedMinutes != null ? "ENTERED" : timerMinutes != null ? "TIMER" : null;
        }
    }

    public record RunResult(long runId, long routineId, String routineTitle, String status, List<HabitResult> habits) {
    }

    public record Streak(String title, int currentDays) {
    }

    // Completion rates over the 7 days up to the summary day and the 7 days before; null without records.
    public record Trend(Integer lastRate, int lastPlanned, Integer previousRate, int previousPlanned) {
    }

    public record Input(
            LocalDate date,
            boolean today,
            List<RunResult> runs,
            List<Streak> streaks,
            Trend trend,
            Map<Long, HabitDurationAnalyzer.Insight> insights,
            Map<Long, Integer> recentSkips
    ) {
    }

    public record Counts(int planned, int completed, int skipped, int postponed, int notFinished, int notStarted,
                         int completionRate, int timed, int timedByTimer, int timedByYou) {
    }

    public record Overrun(String title, int plannedMinutes, int actualMinutes, int overMinutes, String source) {
    }

    public record Summary(
            LocalDate date,
            String status,
            Counts counts,
            List<String> highlights,
            List<RunResult> runs,
            List<Overrun> overruns,
            List<String> consistent,
            String timing,
            String trend,
            List<String> suggestions,
            List<String> notes
    ) {
    }

    private DailySummaryEngine() {
    }

    public static Summary summarize(Input input) {
        String day = input.today() ? "Today" : "On " + input.date();
        List<String> notes = List.of(
                "Only habits of routines you started that day are counted.",
                "A length comes from the run timer (Start to Complete, including any wait before you pressed Complete)"
                        + " or from a length you entered. A habit that was only marked done has no length.");

        if (input.runs().isEmpty()) {
            return new Summary(input.date(), "NO_RUNS", new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                    List.of(day + " you did not start any routine, so there is nothing to summarise."),
                    List.of(), List.of(), List.of(), null, trendText(input.trend()), List.of(), notes);
        }

        int planned = 0, completed = 0, skipped = 0, postponed = 0, notFinished = 0, notStarted = 0;
        int timed = 0, byTimer = 0, byYou = 0;
        List<String> skippedTitles = new ArrayList<>();
        List<String> onTime = new ArrayList<>();
        List<String> late = new ArrayList<>();
        List<Overrun> overruns = new ArrayList<>();
        Set<String> completedTitles = new LinkedHashSet<>();
        List<String> suggestions = new ArrayList<>();

        for (RunResult run : input.runs()) {
            for (HabitResult habit : run.habits()) {
                planned++;
                switch (habit.status()) {
                    case "COMPLETED" -> completed++;
                    case "SKIPPED" -> {
                        skipped++;
                        skippedTitles.add(habit.title());
                    }
                    case "POSTPONED" -> postponed++;
                    case "NOT_FINISHED", "IN_PROGRESS" -> notFinished++;
                    default -> notStarted++;
                }

                if (!"COMPLETED".equals(habit.status())) {
                    continue;
                }
                completedTitles.add(habit.title());

                Integer actual = habit.actualMinutes();
                if (actual != null) {
                    timed++;
                    if (habit.reportedMinutes() != null) {
                        byYou++;
                    } else {
                        byTimer++;
                    }
                    int over = actual - habit.plannedMinutes();
                    if (over >= HabitDurationAnalyzer.margin(habit.plannedMinutes())) {
                        overruns.add(new Overrun(habit.title(), habit.plannedMinutes(), actual, over, habit.actualSource()));
                    }
                }

                if (habit.plannedStart() != null && habit.actualStart() != null) {
                    int shift = Math.floorMod(habit.actualStart() - habit.plannedStart() + 720, Clock.DAY) - 720;
                    if (Math.abs(shift) <= ON_TIME_MINUTES) {
                        onTime.add(habit.title());
                    } else if (shift >= LATE_MINUTES) {
                        late.add(habit.title() + " started " + shift + " min later than planned ("
                                + Clock.format(habit.actualStart()) + " instead of " + Clock.format(habit.plannedStart()) + ").");
                    }
                }
            }
        }

        int rate = planned == 0 ? 0 : Math.round(completed * 100f / planned);
        List<String> highlights = new ArrayList<>();
        highlights.add(day + " you completed " + completed + " out of " + planned + (planned == 1 ? " habit" : " habits")
                + " (" + rate + "%).");
        if (skipped > 0) {
            highlights.add("You skipped " + count(skipped) + ": " + String.join(", ", skippedTitles) + ".");
        }
        if (postponed > 0) {
            highlights.add(count(postponed) + (postponed == 1 ? " was" : " were") + " postponed and not finished that day.");
        }
        if (notFinished > 0) {
            highlights.add(count(notFinished) + (notFinished == 1 ? " was" : " were") + " started but not completed.");
        }
        if (notStarted > 0) {
            highlights.add(count(notStarted) + (notStarted == 1 ? " was" : " were") + " not started.");
        }
        for (Overrun overrun : overruns) {
            highlights.add(overrun.title() + " exceeded its planned duration by " + overrun.overMinutes() + " minutes ("
                    + overrun.actualMinutes() + " min " + ("ENTERED".equals(overrun.source()) ? "as you entered" : "by the run timer")
                    + ", " + overrun.plannedMinutes() + " min planned).");
        }
        onTime.stream().limit(3).forEach(title -> highlights.add("You completed " + title + " as scheduled."));
        highlights.addAll(late.stream().limit(3).toList());

        String timing = completed == 0 ? null
                : "Lengths are known for " + timed + " of " + count(completed).replace(" habit", " completed habit")
                + (timed == 0 ? "" : " (" + byTimer + " by the run timer, " + byYou + " entered by you)") + "."
                + (completed - timed > 0 ? " For the other " + (completed - timed) + " only the completion is recorded, so no length is assumed." : "");

        List<String> consistent = input.streaks().stream()
                .filter(streak -> streak.currentDays() >= 3 && completedTitles.contains(streak.title()))
                .limit(5)
                .map(streak -> streak.title() + ": " + streak.currentDays() + " days in a row")
                .toList();

        // Suggestions: repeated patterns first; a single long run is only pointed out.
        Set<Long> suggested = new LinkedHashSet<>();
        for (RunResult run : input.runs()) {
            for (HabitResult habit : run.habits()) {
                HabitDurationAnalyzer.Insight insight = input.insights().get(habit.routineHabitId());
                if (insight != null && insight.suggestedMinutes() != null && suggested.add(habit.routineHabitId())) {
                    suggestions.add(insight.message());
                }
            }
        }
        for (Overrun overrun : overruns) {
            boolean repeated = input.runs().stream().flatMap(run -> run.habits().stream())
                    .filter(habit -> habit.title().equals(overrun.title()))
                    .anyMatch(habit -> suggested.contains(habit.routineHabitId()));
            if (!repeated) {
                suggestions.add(overrun.title() + " ran " + overrun.overMinutes() + " min over "
                        + (input.today() ? "today" : "that day") + ". Consider leaving room after it"
                        + " or moving your next session; a longer plan is suggested only if this happens in most of at least "
                        + HabitDurationAnalyzer.MIN_RUNS + " measured runs.");
            }
        }
        for (RunResult run : input.runs()) {
            for (HabitResult habit : run.habits()) {
                Integer skips = input.recentSkips().get(habit.routineHabitId());
                if ("SKIPPED".equals(habit.status()) && skips != null && skips >= 2) {
                    suggestions.add("You skipped " + habit.title() + " " + skips + " times in the last 14 days."
                            + " Consider a shorter version, another time of day, or pausing it.");
                }
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("No changes suggested from these records.");
        }

        return new Summary(input.date(), "OK", new Counts(planned, completed, skipped, postponed, notFinished, notStarted,
                rate, timed, byTimer, byYou), highlights, input.runs(), overruns, consistent, timing,
                trendText(input.trend()), suggestions, notes);
    }

    private static String trendText(Trend trend) {
        if (trend == null || trend.lastRate() == null || trend.previousRate() == null) {
            return "Not enough history for a trend yet: it needs tracked habits in both of the last two weeks.";
        }
        int change = trend.lastRate() - trend.previousRate();
        String direction = change > 0 ? "up from " : change < 0 ? "down from " : "the same as ";
        return "Over the last 7 days you completed " + trend.lastRate() + "% of " + trend.lastPlanned()
                + " tracked habits, " + direction + trend.previousRate() + "% (" + trend.previousPlanned()
                + " habits) in the 7 days before.";
    }

    private static String count(int number) {
        return number + (number == 1 ? " habit" : " habits");
    }
}
