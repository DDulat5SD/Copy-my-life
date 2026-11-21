package com.copymylife.copymylife.dto;

import java.util.List;

// Routine assistant: review changes the user approved, to preview or to apply. type is MOVE,
// RESIZE or ADD; activityId is the routine habit id (ignored for ADD); days empty = every day.
public record AssistantChangesRequest(List<Change> changes) {

    public record Change(String id, String type, Long activityId, String title, String toStart, Integer toMinutes,
                         List<String> days, String kind) {
    }
}
