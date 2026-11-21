package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

// US-22: whether the user wants reminders and how many minutes before an activity. One row per
// user; a user without a row has the defaults (ReminderService).
@Entity
@Table(
        name = "reminder_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_reminder_settings_user", columnNames = "user_id")
)
public class ReminderSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // One row per user, kept by uk_reminder_settings_user (named so the manual SQL matches).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reminder_settings_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "lead_minutes", nullable = false)
    private int leadMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReminderSettings() {
    }

    public ReminderSettings(User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLeadMinutes() {
        return leadMinutes;
    }

    public void setLeadMinutes(int leadMinutes) {
        this.leadMinutes = leadMinutes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
