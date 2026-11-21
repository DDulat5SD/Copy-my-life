package com.copymylife.copymylife.dto;

import java.time.LocalDate;
import java.util.List;

public class ProgressResponse {

    private String period;
    private LocalDate from;
    private LocalDate to;
    private List<ProgressItemResponse> routines;
    private List<ProgressItemResponse> habits;

    // US-21: the user's today, streaks as of today, and the week of the chosen day; all from
    // the stored tracking records.
    private LocalDate today;
    private boolean hasTracking;
    private Streak streak;
    private List<HabitStreak> habitStreaks;
    private Week week;
    private Status status;

    public ProgressResponse(String period, LocalDate from, LocalDate to, List<ProgressItemResponse> routines, List<ProgressItemResponse> habits) {
        this.period = period;
        this.from = from;
        this.to = to;
        this.routines = routines;
        this.habits = habits;
    }

    // Days in a row with a completion; the last day one was completed, null if never.
    public record Streak(int currentDays, int bestDays, LocalDate lastCompletedDate) {
    }

    // US-07: the status from every habit completed so far, whatever period is shown. The next* fields
    // and activitiesToNextLevel are null at the last level; progressPercent is toward the next level.
    public record Status(int level, String name, long completedActivities, int levelStartsAt,
                         Integer nextLevel, String nextName, Integer nextLevelAt, Long activitiesToNextLevel,
                         int progressPercent, boolean maxLevel) {
    }

    public record HabitStreak(long habitId, String title, int currentDays, int bestDays, LocalDate lastCompletedDate) {
    }

    // Monday to Sunday. completed and tracked add up the days; percentage is null with nothing tracked.
    public record Week(LocalDate from, LocalDate to, int completed, int tracked, Integer percentage, List<Day> days) {
    }

    // Habits completed, skipped, postponed and in progress that day, each habit once; tracked is
    // all of them. percentage is completed out of tracked, null for a day without records or to come.
    public record Day(LocalDate date, String day, int completed, int skipped, int postponed, int inProgress,
                      int tracked, Integer percentage, boolean today, boolean future) {
    }

    public String getPeriod() { return period; }

    public LocalDate getFrom() { return from; }

    public LocalDate getTo() { return to; }

    public List<ProgressItemResponse> getRoutines() { return routines; }

    public List<ProgressItemResponse> getHabits() { return habits; }

    public LocalDate getToday() { return today; }

    public void setToday(LocalDate today) { this.today = today; }

    public boolean isHasTracking() { return hasTracking; }

    public void setHasTracking(boolean hasTracking) { this.hasTracking = hasTracking; }

    public Streak getStreak() { return streak; }

    public void setStreak(Streak streak) { this.streak = streak; }

    public List<HabitStreak> getHabitStreaks() { return habitStreaks; }

    public void setHabitStreaks(List<HabitStreak> habitStreaks) { this.habitStreaks = habitStreaks; }

    public Week getWeek() { return week; }

    public void setWeek(Week week) { this.week = week; }

    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }
}
