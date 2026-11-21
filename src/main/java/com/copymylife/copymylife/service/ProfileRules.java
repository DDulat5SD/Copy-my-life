package com.copymylife.copymylife.service;

import java.util.regex.Pattern;

// Limits for the personal profile. A username keeps the value it already has even when it
// would not pass these rules today; a new or changed one must.
final class ProfileRules {

    static final int MIN_NAME_LENGTH = 2;
    static final int MAX_NAME_LENGTH = 60;
    static final int MAX_PROFESSION_LENGTH = 100;
    static final int MAX_TEXT_LENGTH = 500;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]{3,30}");

    private ProfileRules() {
    }

    static String name(String value) {
        String name = value == null ? "" : value.trim();

        if (name.length() < MIN_NAME_LENGTH) {
            throw new IllegalArgumentException("Please enter your name (at least " + MIN_NAME_LENGTH + " characters)");
        }

        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Your name must be at most " + MAX_NAME_LENGTH + " characters");
        }

        return name;
    }

    // "@ada" and "ada" are the same username; an empty one is no username at all.
    static String username(String value) {
        if (value == null) {
            return null;
        }

        String username = value.trim();

        if (username.startsWith("@")) {
            username = username.substring(1).trim();
        }

        return username.isEmpty() ? null : username;
    }

    static void requireValidUsername(String username) {
        if (username != null && !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "A username has 3 to 30 letters, numbers, dots, dashes or underscores"
            );
        }
    }

    static String text(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String text = value.trim();

        if (text.length() > maxLength) {
            throw new IllegalArgumentException(label + " must be at most " + maxLength + " characters");
        }

        return text;
    }
}
