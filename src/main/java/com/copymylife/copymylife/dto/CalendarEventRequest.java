package com.copymylife.copymylife.dto;

import com.copymylife.copymylife.model.RepeatType;

import java.time.LocalDate;
import java.time.LocalTime;

public class CalendarEventRequest {

    private Long routineId;
    private LocalDate day;
    private LocalTime time;
    private RepeatType repeat;

    public Long getRoutineId() {
        return routineId;
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
    
}