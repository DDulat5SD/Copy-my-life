package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "routine_id", nullable = false)
    private Routine routine;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepeatType repeatType;

    // Set on a single event that replaces one changed occurrence of a series;
    // it goes when its series is deleted. Null on every other event.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "series_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CalendarEvent series;

    // The series date this event replaces.
    @Column(name = "original_date")
    private LocalDate originalDate;

    public CalendarEvent() {
    }

    public CalendarEvent(User user, Routine routine, LocalDate startDate, LocalTime startTime, RepeatType repeatType) {
        this.user = user;
        this.routine = routine;
        this.startDate = startDate;
        this.startTime = startTime;
        this.repeatType = repeatType;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }
    public void setUser(User user) {
        this.user = user;
    }

    public Routine getRoutine() {
        return routine;
    }
    public void setRoutine(Routine routine) {
        this.routine = routine;
    }

    public LocalDate getStartDate() {
        return startDate;
    }
    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }
    
    public LocalTime getStartTime() {
        return startTime;
    }
    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public RepeatType getRepeatType() {
        return repeatType;
    }
    public void setRepeatType(RepeatType repeatType) {
        this.repeatType = repeatType;
    }

    public CalendarEvent getSeries() {
        return series;
    }

    public void setSeries(CalendarEvent series) {
        this.series = series;
    }

    public LocalDate getOriginalDate() {
        return originalDate;
    }

    public void setOriginalDate(LocalDate originalDate) {
        this.originalDate = originalDate;
    }

    public boolean isRepeating() {
        return repeatType != null && repeatType != RepeatType.NONE;
    }

    // Whether the series would place an occurrence on this date, ignoring cancelled days.
    public boolean occursOn(LocalDate date) {
        if (date.isBefore(startDate)) {
            return false;
        }

        return switch (repeatType) {
            case NONE -> date.equals(startDate);
            case DAILY -> true;
            case WEEKLY -> ChronoUnit.DAYS.between(startDate, date) % 7 == 0;
        };
    }
}
