package com.copymylife.copymylife.service;

// The four password requirements shown on the sign-up page, used for new and reset passwords.
public final class PasswordRules {

    public static final String MESSAGE = "Your password must meet all 4 requirements.";

    private PasswordRules() {
    }

    public static boolean isStrong(String password) {
        if (password == null) {
            return false;
        }

        boolean hasLength = password.length() >= 8;
        boolean hasLetter = password.codePoints().anyMatch(Character::isLetter);
        boolean hasNumber = password.chars().anyMatch(c -> c >= '0' && c <= '9');
        boolean hasSpecial = password.chars().anyMatch(c -> "!#$%^&*_".indexOf(c) >= 0);

        return hasLength && hasLetter && hasNumber && hasSpecial;
    }
}
