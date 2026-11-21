package com.copymylife.copymylife.dto;

// US-18: which of the caller's own routines one copied activity is added to.
public class HabitCopyRequest {

    private Long targetRoutineId;

    public Long getTargetRoutineId() {
        return targetRoutineId;
    }

    public void setTargetRoutineId(Long targetRoutineId) {
        this.targetRoutineId = targetRoutineId;
    }
}
