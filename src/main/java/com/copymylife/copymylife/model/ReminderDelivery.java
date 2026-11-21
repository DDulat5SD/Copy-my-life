package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

// US-22: a reminder already handed to the user's browser, so each occurrence of an activity is
// shown once, whatever the number of tabs or devices. The key names the activity, its day and
// its time, so a rescheduled activity is reminded again at its new time.
@Entity
@Table(
        name = "reminder_deliveries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reminder_deliveries_user_key",
                columnNames = {"user_id", "reminder_key"}
        )
)
public class ReminderDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reminder_deliveries_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "reminder_key", nullable = false, length = 80)
    private String reminderKey;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    protected ReminderDelivery() {
    }

    public Long getId() {
        return id;
    }

    public String getReminderKey() {
        return reminderKey;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
