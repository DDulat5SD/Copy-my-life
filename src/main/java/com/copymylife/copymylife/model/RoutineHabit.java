package com.copymylife.copymylife.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalTime;

@Entity
@Table(name = "routine_habits")
public class RoutineHabit {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "routine_id",
            nullable = false
    )
    private Routine routine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "habit_id",
            nullable = false
    )
    private Habit habit;

    @Column(name = "position", nullable = false)
    private Integer position;

    // Set while the owner has paused this habit in this routine; it keeps its position meanwhile.
    @Column(name = "paused_at")
    private Instant pausedAt;

    // The habit's own plan inside this routine; null on habits planned before schedules existed.
    @Column(name = "start_time")
    private LocalTime startTime;

    // Weekday codes in weekday order, e.g. "MON,WED,FRI".
    @Column(name = "repeat_days", length = 27)
    private String repeatDays;

    @Column(name = "notes", length = 500)
    private String notes;

    // Set when the owner deletes a habit that has completion history. The row stays only so
    // finished runs and statistics keep pointing at it; the routine no longer has this habit.
    @Column(name = "removed_at")
    private Instant removedAt;

    public Long getId() {
        return id;
    }

    public Routine getRoutine() {
        return routine;
    }

    public void setRoutine(
            Routine routine
    ) {
        this.routine = routine;
    }

    public Habit getHabit() {
        return habit;
    }

    public void setHabit(
            Habit habit
    ) {
        this.habit = habit;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(
            Integer position
    ) {
        this.position = position;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public boolean isPaused() {
        return pausedAt != null;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public String getRepeatDays() {
        return repeatDays;
    }

    public void setRepeatDays(String repeatDays) {
        this.repeatDays = repeatDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(Instant removedAt) {
        this.removedAt = removedAt;
    }

    public boolean isRemoved() {
        return removedAt != null;
    }
}
