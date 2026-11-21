package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalDate;

// One day taken out of a repeating calendar event: deleted on its own, or replaced
// by a changed single event. The rest of the series is untouched.
@Entity
@Table(
        name = "calendar_event_exceptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_calendar_exception_day",
                columnNames = {"event_id", "occurrence_date"}
        )
)
public class CalendarEventException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CalendarEvent event;

    @Column(name = "occurrence_date", nullable = false)
    private LocalDate occurrenceDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CalendarEventException() {
    }

    public CalendarEventException(CalendarEvent event, LocalDate occurrenceDate) {
        this.event = event;
        this.occurrenceDate = occurrenceDate;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public CalendarEvent getEvent() {
        return event;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
