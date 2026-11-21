package com.copymylife.copymylife.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class HabitCreateRequest {

    @NotBlank
    private String title;

    private String description;

    private Integer durationMinutes;

    // Optional plan for this habit inside the routine it is created in.
    private String startTime;
    private List<String> repeatDays;
    private String notes;

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

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
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
