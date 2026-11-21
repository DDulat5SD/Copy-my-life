package com.copymylife.copymylife.dto;

public class HabitResponse {

    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;
    private boolean completed;
    private int pausedRoutineCount;

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
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

    public boolean isCompleted() {
        return completed;
    }
    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public int getPausedRoutineCount() {
        return pausedRoutineCount;
    }
    public void setPausedRoutineCount(int pausedRoutineCount) {
        this.pausedRoutineCount = pausedRoutineCount;
    }
}
