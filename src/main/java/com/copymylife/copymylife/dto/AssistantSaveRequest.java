package com.copymylife.copymylife.dto;

import java.util.List;

// Routine assistant: a previewed (and maybe edited) plan the user chose to save as a new routine.
public record AssistantSaveRequest(
        String title,
        String description,
        String category,
        Boolean isPublic,
        List<String> days,
        List<Activity> activities
) {

    // startTime "HH:mm" or null; description optional.
    public record Activity(String title, String description, Integer minutes, String startTime) {
    }
}
