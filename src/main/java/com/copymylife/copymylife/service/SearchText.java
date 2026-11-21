package com.copymylife.copymylife.service;

import java.text.Normalizer;
import java.util.Locale;

// US-05: turns what someone typed into a search into the LIKE pattern the queries compare
// with LOWER(column). Case and the spaces around or between words do not matter, Kazakh and
// Russian letters included, and % or _ are searched as themselves.
final class SearchText {

    static final int MAX_LENGTH = 100;

    private SearchText() {
    }

    // Lower case, NFKC, with every run of white space as one space; "" for nothing.
    static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String text = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('’', '\'')
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);

        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Please keep the search to at most " + MAX_LENGTH + " characters");
        }

        return text;
    }

    // "%text%" with \ % and _ escaped for LIKE ... ESCAPE '\'; "%" matches everything.
    static String containsPattern(String normalized) {
        return "%" + normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
