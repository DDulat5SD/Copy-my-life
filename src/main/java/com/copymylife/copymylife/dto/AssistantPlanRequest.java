package com.copymylife.copymylife.dto;

import java.util.List;

// Routine assistant: what to plan. preferences override the saved ones for this plan only;
// commitments null means the saved ones; meals null means breakfast, lunch and dinner.
public record AssistantPlanRequest(
        AssistantPreferencesRequest preferences,
        List<AssistantPreferencesRequest.Commitment> commitments,
        List<Habit> habits,
        List<String> meals,
        Boolean splitFocus,
        List<String> days
) {

    // kind: STUDY, WORK, EXERCISE, MEAL, BREAK, PERSONAL or OTHER; priority: HIGH, MEDIUM or LOW.
    public record Habit(String title, Integer minutes, String kind, String priority, String preferredTime) {
    }
}
