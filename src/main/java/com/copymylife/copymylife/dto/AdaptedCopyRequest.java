package com.copymylife.copymylife.dto;

import java.util.List;

// US-19: a copy of a routine with each of its activities adapted before it is saved. Every
// activity of the source is given once, by its routine habit id, with the plan it should have.
public class AdaptedCopyRequest {

    private List<Activity> activities;

    public List<Activity> getActivities() {
        return activities;
    }

    public void setActivities(List<Activity> activities) {
        this.activities = activities;
    }

    public static class Activity {

        private Long routineHabitId;
        private String startTime;
        private Integer durationMinutes;
        private List<String> repeatDays;
        private String notes;

        public Long getRoutineHabitId() {
            return routineHabitId;
        }

        public void setRoutineHabitId(Long routineHabitId) {
            this.routineHabitId = routineHabitId;
        }

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
}
