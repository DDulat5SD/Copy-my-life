package com.copymylife.copymylife.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "habit_completions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_habit_completions_run_step",
                columnNames = {"routine_completion_id", "routine_habit_id"}
        )
)
public class HabitCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "habit_id", nullable = false)
    private Habit habit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_habit_id")
    private RoutineHabit routineHabit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_completion_id")
    private RoutineCompletion routineCompletion;

    @Column(name = "completed_date", nullable = false)
    private LocalDate completedDate;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // Null on rows written before statuses existed; see currentStatus().
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private HabitCompletionStatus status;

    // The habit's planned length when it was started, so later edits of the plan don't rewrite
    // history; null on rows written before the routine assistant.
    @Column(name = "planned_minutes")
    private Integer plannedMinutes;

    // How long the habit really took, as the user entered it after completing it; null when not
    // entered. Only the user sets it; it is never derived from the plan.
    @Column(name = "reported_minutes")
    private Integer reportedMinutes;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Habit getHabit() {
        return habit;
    }

    public void setHabit(Habit habit) {
        this.habit = habit;
    }

    public LocalDate getCompletedDate() {
        return completedDate;
    }

    public void setCompletedDate(LocalDate completedDate) {
        this.completedDate = completedDate;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public RoutineCompletion getRoutineCompletion() {
        return routineCompletion;
    }

    public void setRoutineCompletion(RoutineCompletion routineCompletion) {
        this.routineCompletion = routineCompletion;
    }

    public RoutineHabit getRoutineHabit() {
        return routineHabit;
    }

    public void setRoutineHabit(RoutineHabit routineHabit) {
        this.routineHabit = routineHabit;
    }

    public HabitCompletionStatus getStatus() {
        return status;
    }

    public void setStatus(HabitCompletionStatus status) {
        this.status = status;
    }

    public Integer getPlannedMinutes() {
        return plannedMinutes;
    }

    public void setPlannedMinutes(Integer plannedMinutes) {
        this.plannedMinutes = plannedMinutes;
    }

    public Integer getReportedMinutes() {
        return reportedMinutes;
    }

    public void setReportedMinutes(Integer reportedMinutes) {
        this.reportedMinutes = reportedMinutes;
    }

    // Older rows only have timestamps: a completion time means completed, otherwise it was started.
    public HabitCompletionStatus currentStatus() {
        if (status != null) {
            return status;
        }

        return completedAt != null
                ? HabitCompletionStatus.COMPLETED
                : HabitCompletionStatus.IN_PROGRESS;
    }
}
