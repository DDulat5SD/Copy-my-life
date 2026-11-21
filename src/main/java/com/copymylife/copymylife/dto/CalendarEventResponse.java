package com.copymylife.copymylife.dto;

import com.copymylife.copymylife.model.RepeatType;

import java.time.LocalDate;
import java.time.LocalTime;

public class CalendarEventResponse {

    private Long id;
    private Long routineId;
    private String title;
    private String category;
    private Integer durationMinutes;
    private LocalDate day;
    private LocalTime time;
    private RepeatType repeat;
    private boolean repeating;
    private Long seriesId;
    private LocalDate originalDay;

    public CalendarEventResponse(Long id, Long routineId, String title, String category, Integer durationMinutes, LocalDate day, LocalTime time, RepeatType repeat) {
        this.id = id;
        this.routineId = routineId;
        this.title = title;
        this.category = category;
        this.durationMinutes = durationMinutes;
        this.day = day;
        this.time = time;
        this.repeat = repeat;
    }

    public Long getId() {
        return id;
    }
    public Long getRoutineId() {
        return routineId;
    }

    public String getTitle() {
        return title;
    }
    public String getCategory() {
        return category;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }
    public LocalDate getDay() {
        return day;
    }

    public LocalTime getTime() {
        return time;
    }
    public RepeatType getRepeat() {
        return repeat;
    }

    public boolean isRepeating() {
        return repeating;
    }

    public void setRepeating(boolean repeating) {
        this.repeating = repeating;
    }

    public Long getSeriesId() {
        return seriesId;
    }

    public void setSeriesId(Long seriesId) {
        this.seriesId = seriesId;
    }

    public LocalDate getOriginalDay() {
        return originalDay;
    }

    public void setOriginalDay(LocalDate originalDay) {
        this.originalDay = originalDay;
    }
}
