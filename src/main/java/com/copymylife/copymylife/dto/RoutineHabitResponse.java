package com.copymylife.copymylife.dto;

import java.time.Instant;
import java.util.List;

public class RoutineHabitResponse {

    private Long id;
    private Long routineHabitId;
    private Long habitId;
    private String title;
    private String description;
    private Integer durationMinutes;
    private Integer position;
    private boolean paused;
    private Instant pausedAt;
    private String startTime;
    private String endTime;
    private boolean endsNextDay;
    private List<String> repeatDays = List.of();
    private String notes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoutineHabitId() {
        return routineHabitId;
    }

    public void setRoutineHabitId(Long routineHabitId) {
        this.routineHabitId = routineHabitId;
    }

    public Long getHabitId() {
        return habitId;
    }

    public void setHabitId(Long habitId) {
        this.habitId = habitId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public void setPausedAt(Instant pausedAt) {
        this.pausedAt = pausedAt;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public boolean isEndsNextDay() {
        return endsNextDay;
    }

    public void setEndsNextDay(boolean endsNextDay) {
        this.endsNextDay = endsNextDay;
    }

    public List<String> getRepeatDays() {
        return repeatDays;
    }

    public void setRepeatDays(List<String> repeatDays) {
        this.repeatDays = repeatDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
