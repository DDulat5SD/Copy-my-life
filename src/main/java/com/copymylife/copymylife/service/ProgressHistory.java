package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ProgressResponse;
import com.copymylife.copymylife.model.HabitCompletionStatus;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import static com.copymylife.copymylife.model.HabitCompletionStatus.COMPLETED;
import static com.copymylife.copymylife.model.HabitCompletionStatus.IN_PROGRESS;
import static com.copymylife.copymylife.model.HabitCompletionStatus.POSTPONED;
import static com.copymylife.copymylife.model.HabitCompletionStatus.SKIPPED;

// US-21: streaks and the week, worked out from the stored tracking records alone.
//
// A streak is the number of calendar days in a row on which the habit was completed. Today still
// counts while it is not over, so a streak that ended yesterday is current until today ends. A day
// with no completion ends it, whether the habit was skipped, postponed, left unfinished or not
// planned that day: schedules are not kept for past days, so days off cannot be told apart from
// days missed, and are never filled in. The user's streak counts days with any habit completed.
//
// Each day of the week counts every habit once, by its best result that day (completed, then in
// progress, postponed, skipped), out of the habits tracked that day. A day without records, or one
// still to come, has no percentage rather than 0%.
final class ProgressHistory {

    private static final List<String> DAY_CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final List<HabitCompletionStatus> BEST_FIRST = List.of(COMPLETED, IN_PROGRESS, POSTPONED, SKIPPED);

    // One stored record: a habit's result on a day.
    record Mark(long habitId, String title, LocalDate day, HabitCompletionStatus status) {
    }

    private final LocalDate today;
    private final boolean tracked;
    private final Map<Long, String> titles = new HashMap<>();
    private final Map<Long, NavigableSet<LocalDate>> completedDays = new HashMap<>();
    private final NavigableSet<LocalDate> anyCompletedDays = new TreeSet<>();
    // day -> habit -> its best result that day
    private final Map<LocalDate, Map<Long, HabitCompletionStatus>> results = new HashMap<>();

    ProgressHistory(List<Mark> marks, LocalDate today) {
        this.today = today;
        this.tracked = !marks.isEmpty();

        for (Mark mark : marks) {
            titles.putIfAbsent(mark.habitId(), mark.title());

            results.computeIfAbsent(mark.day(), day -> new HashMap<>())
                    .merge(mark.habitId(), mark.status(), ProgressHistory::better);

            if (mark.status() == COMPLETED && !mark.day().isAfter(today)) {
                completedDays.computeIfAbsent(mark.habitId(), habit -> new TreeSet<>()).add(mark.day());
                anyCompletedDays.add(mark.day());
            }
        }
    }

    boolean hasTracking() {
        return tracked;
    }

    ProgressResponse.Streak streak() {
        return new ProgressResponse.Streak(
                current(anyCompletedDays),
                best(anyCompletedDays),
                anyCompletedDays.isEmpty() ? null : anyCompletedDays.last()
        );
    }

    // Every habit ever completed: current streaks first, then the best ones.
    List<ProgressResponse.HabitStreak> habitStreaks() {
        List<ProgressResponse.HabitStreak> streaks = new ArrayList<>();

        completedDays.forEach((habit, days) -> streaks.add(new ProgressResponse.HabitStreak(
                habit,
                titles.get(habit),
                current(days),
                best(days),
                days.last()
        )));

        streaks.sort(Comparator
                .comparingInt(ProgressResponse.HabitStreak::currentDays).reversed()
                .thenComparing(Comparator.comparingInt(ProgressResponse.HabitStreak::bestDays).reversed())
                .thenComparing(streak -> streak.title() == null ? "" : streak.title())
                .thenComparingLong(ProgressResponse.HabitStreak::habitId));

        return streaks;
    }

    // Monday to Sunday of the week the day is in.
    ProgressResponse.Week week(LocalDate anyDay) {
        LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<ProgressResponse.Day> days = new ArrayList<>();
        int completed = 0;
        int trackedHabits = 0;

        for (int i = 0; i < 7; i++) {
            LocalDate date = monday.plusDays(i);
            boolean future = date.isAfter(today);
            Map<Long, HabitCompletionStatus> day = future ? Map.of() : results.getOrDefault(date, Map.of());

            int done = count(day, COMPLETED);
            completed += done;
            trackedHabits += day.size();

            days.add(new ProgressResponse.Day(
                    date,
                    DAY_CODES.get(i),
                    done,
                    count(day, SKIPPED),
                    count(day, POSTPONED),
                    count(day, IN_PROGRESS),
                    day.size(),
                    percentage(done, day.size()),
                    date.equals(today),
                    future
            ));
        }

        return new ProgressResponse.Week(monday, monday.plusDays(6), completed, trackedHabits,
                percentage(completed, trackedHabits), days);
    }

    // Days in a row up to today, or up to yesterday while today has no completion yet.
    private int current(NavigableSet<LocalDate> days) {
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        int run = 0;

        while (days.contains(day)) {
            run++;
            day = day.minusDays(1);
        }

        return run;
    }

    private static int best(NavigableSet<LocalDate> days) {
        int best = 0;
        int run = 0;
        LocalDate previous = null;

        for (LocalDate day : days) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = day;
        }

        return best;
    }

    private static int count(Map<Long, HabitCompletionStatus> day, HabitCompletionStatus status) {
        return (int) day.values().stream().filter(status::equals).count();
    }

    private static Integer percentage(int completed, int total) {
        return total == 0 ? null : (int) Math.round(completed * 100.0 / total);
    }

    private static HabitCompletionStatus better(HabitCompletionStatus a, HabitCompletionStatus b) {
        return BEST_FIRST.indexOf(a) <= BEST_FIRST.indexOf(b) ? a : b;
    }
}
