package com.copymylife.copymylife.dto;

import java.util.List;

// Routine assistant planning preferences, as saved in My Preferences or sent with one plan.
// Times are "HH:mm"; every field is optional. restFrequency: FREQUENT, NORMAL or RARE;
// intensity: LIGHT, BALANCED or INTENSE.
public record AssistantPreferencesRequest(
        String wake,
        String sleep,
        String availableFrom,
        String availableTo,
        String breakfast,
        String lunch,
        String dinner,
        Integer mealMinutes,
        String exercise,
        Integer focusBlockMinutes,
        Integer breakEveryMinutes,
        Integer breakMinutes,
        String restFrequency,
        String intensity,
        List<Commitment> commitments
) {

    // A fixed part of the day, such as classes or work: nothing is planned across it.
    public record Commitment(String title, String start, String end) {
    }
}
