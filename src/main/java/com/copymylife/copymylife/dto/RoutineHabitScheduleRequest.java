package com.copymylife.copymylife.dto;

import java.util.List;

// Replaces a routine habit's plan. A missing duration keeps the current one.
public class RoutineHabitScheduleRequest {

    private String startTime;
    private Integer durationMinutes;
    private List<String> repeatDays;
    private String notes;

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
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
