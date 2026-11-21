package com.copymylife.copymylife.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Limits for every place a user creates or edits a habit. Title and description
// match the database columns; a single habit cannot last longer than a day.
final class HabitRules {

    static final int MAX_TITLE_LENGTH = 255;
    static final int MAX_DESCRIPTION_LENGTH = 500;
    static final int MAX_DURATION_MINUTES = 1440;

    private HabitRules() {
    }

    static void validate(String title, String description, Integer durationMinutes) {
        if (title == null || title.isBlank()) {
            throw invalid("Habit title is required");
        }

        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw invalid("Habit title must be at most " + MAX_TITLE_LENGTH + " characters");
        }

        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw invalid("Habit description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        validateDuration(durationMinutes);
    }

    static void validateDuration(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0) {
            throw invalid("Habit duration must be greater than 0");
        }

        if (durationMinutes > MAX_DURATION_MINUTES) {
            throw invalid("Habit duration must be at most " + MAX_DURATION_MINUTES + " minutes");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
