package com.copymylife.copymylife.dto;

public class SavedHabitResponse {

    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;

    private Long routineId;
    private String routineTitle;
    private String category;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) { this.durationMinutes = durationMinutes; }

    public Long getRoutineId() { return routineId; }
    public void setRoutineId(Long routineId) { this.routineId = routineId; }

    public String getRoutineTitle() { return routineTitle; }
    public void setRoutineTitle(String routineTitle) { this.routineTitle = routineTitle; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
}