package com.copymylife.copymylife.service;

// Limits for creating and editing a routine. A routine fits in one day; title and
// description stay within what the pages and the database columns can hold.
final class RoutineRules {

    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 500;
    static final int MAX_CATEGORY_LENGTH = 255;
    static final int MAX_DURATION_MINUTES = 1440;

    private RoutineRules() {
    }

    static void validate(String title, String description, String category, Integer durationMinutes) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Routine title is required");
        }

        if (title.trim().length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Routine title must be at most " + MAX_TITLE_LENGTH + " characters");
        }

        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Choose a category");
        }

        if (category.trim().length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("Category must be at most " + MAX_CATEGORY_LENGTH + " characters");
        }

        if (durationMinutes == null || durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "Routine duration must be between 1 and " + MAX_DURATION_MINUTES + " minutes (24 hours)"
            );
        }
    }
}
