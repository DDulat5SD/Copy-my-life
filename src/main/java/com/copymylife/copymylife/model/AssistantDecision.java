package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

// The user's answer to one routine assistant suggestion about a habit in a routine: kept
// (DECLINED) or changed (ACCEPTED). A declined suggestion is not repeated until new measured
// runs come in. kind is DURATION for now; more kinds can be added later.
@Entity
@Table(
        name = "assistant_decisions",
        indexes = @Index(name = "idx_assistant_decisions_user_link", columnList = "user_id, routine_habit_id")
)
public class AssistantDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_habit_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RoutineHabit routineHabit;

    @Column(name = "kind", length = 20, nullable = false)
    private String kind;

    @Column(name = "decision", length = 20, nullable = false)
    private String decision;

    @Column(name = "planned_minutes")
    private Integer plannedMinutes;

    @Column(name = "suggested_minutes")
    private Integer suggestedMinutes;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public RoutineHabit getRoutineHabit() {
        return routineHabit;
    }

    public void setRoutineHabit(RoutineHabit routineHabit) {
        this.routineHabit = routineHabit;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getPlannedMinutes() {
        return plannedMinutes;
    }

    public void setPlannedMinutes(Integer plannedMinutes) {
        this.plannedMinutes = plannedMinutes;
    }

    public Integer getSuggestedMinutes() {
        return suggestedMinutes;
    }

    public void setSuggestedMinutes(Integer suggestedMinutes) {
        this.suggestedMinutes = suggestedMinutes;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }
}
