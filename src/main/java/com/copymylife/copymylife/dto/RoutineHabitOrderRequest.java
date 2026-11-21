
package com.copymylife.copymylife.dto;

import java.util.List;

public class RoutineHabitOrderRequest {

    private List<Long> habitIds;

    public List<Long> getHabitIds() {
        return habitIds;
    }

    public void setHabitIds(List<Long> habitIds) {
        this.habitIds = habitIds;
    }
}
