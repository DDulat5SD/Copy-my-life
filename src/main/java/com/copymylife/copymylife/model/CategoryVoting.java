package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

// US-28: a routine's one voting period. The first vote opens it; when closesAt has passed the
// votes are counted once, closedAt is set and the winning category is kept with its name then.
@Entity
@Table(
        name = "routine_category_votings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_category_votings_routine",
                columnNames = "routine_id"
        )
)
public class CategoryVoting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_id", nullable = false, foreignKey = @ForeignKey(name = "fk_category_votings_routine"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Routine routine;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_category_id", foreignKey = @ForeignKey(name = "fk_category_votings_winner"))
    private Category winnerCategory;

    @Column(name = "winner_name", length = 40)
    private String winnerName;

    public Long getId() {
        return id;
    }

    public Routine getRoutine() {
        return routine;
    }

    public void setRoutine(Routine routine) {
        this.routine = routine;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public void setClosesAt(Instant closesAt) {
        this.closesAt = closesAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Category getWinnerCategory() {
        return winnerCategory;
    }

    public void setWinnerCategory(Category winnerCategory) {
        this.winnerCategory = winnerCategory;
    }

    public String getWinnerName() {
        return winnerName;
    }

    public void setWinnerName(String winnerName) {
        this.winnerName = winnerName;
    }

    public boolean isClosed() {
        return closedAt != null;
    }
}
