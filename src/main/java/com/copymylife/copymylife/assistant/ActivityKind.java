package com.copymylife.copymylife.assistant;

import java.util.List;
import java.util.Locale;

// What an activity is for, as far as the scheduling rules care. The generator gets the kind from
// the user; for habits that already exist it is recognised from the title by the words below
// (English, Russian and Kazakh), and the result says so wherever it matters.
public enum ActivityKind {
    STUDY,
    WORK,
    EXERCISE,
    MEAL,
    BREAK,
    PERSONAL,
    COMMITMENT,
    OTHER;

    private static final List<String> MEAL_WORDS = List.of(
            "breakfast", "lunch", "dinner", "supper", "brunch", "meal", "snack", "eat ",
            "завтрак", "обед", "ужин", "перекус", "таңғы ас", "түскі ас", "кешкі ас", "тамақ");
    private static final List<String> BREAK_WORDS = List.of(
            "break", "rest", "pause", "nap", "relax",
            "перерыв", "отдых", "передышка", "үзіліс", "демалыс");
    private static final List<String> EXERCISE_WORDS = List.of(
            "gym", "workout", "exercise", "training", "run", "jog", "yoga", "stretch", "swim", "fitness",
            "cardio", "walk", "sport", "pilates", "cycling", "bike", "running", "jogging", "walking", "swimming",
            "спорт", "трениров", "зарядк", "бег", "йога", "растяжк", "фитнес", "прогулк",
            "жаттығу", "жүгіру", "серуен");
    private static final List<String> STUDY_WORDS = List.of(
            "study", "learn", "lesson", "homework", "course", "revision", "practice", "lecture", "exam",
            "java", "python", "english", "math", "mathematics", "coding", "programming",
            "учеб", "учить", "урок", "домашн", "курс", "лекци", "экзамен",
            "оқу", "сабақ", "үй жұмысы", "дәріс", "емтихан");
    private static final List<String> WORK_WORDS = List.of(
            "work", "job", "meeting", "office", "project", "email", "focus",
            "работ", "встреч", "офис", "проект", "жұмыс", "кездесу", "жоба");

    public boolean isFocus() {
        return this == STUDY || this == WORK;
    }

    // Meals and breaks are rest: a run of activities without one is what the break rules measure.
    public boolean isRest() {
        return this == MEAL || this == BREAK;
    }

    public static ActivityKind parse(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown activity kind \"" + value + "\"");
        }
    }

    // A guess from the title for habits that were made without a kind. Meals and breaks are tried
    // first, so "Lunch break" is a meal and "Study break" is a break.
    public static ActivityKind recognise(String title) {
        String text = " " + (title == null ? "" : title.toLowerCase(Locale.ROOT)) + " ";

        if (contains(text, MEAL_WORDS)) {
            return MEAL;
        }
        if (contains(text, BREAK_WORDS)) {
            return BREAK;
        }
        if (contains(text, EXERCISE_WORDS)) {
            return EXERCISE;
        }
        if (contains(text, STUDY_WORDS)) {
            return STUDY;
        }
        if (contains(text, WORK_WORDS)) {
            return WORK;
        }
        return OTHER;
    }

    // Which meal a title names, or null.
    public static String mealOf(String title) {
        String text = title == null ? "" : title.toLowerCase(Locale.ROOT);

        if (text.contains("breakfast") || text.contains("завтрак") || text.contains("таңғы ас")) {
            return "breakfast";
        }
        if (text.contains("lunch") || text.contains("обед") || text.contains("түскі ас")) {
            return "lunch";
        }
        if (text.contains("dinner") || text.contains("supper") || text.contains("ужин") || text.contains("кешкі ас")) {
            return "dinner";
        }
        return null;
    }

    private static boolean contains(String text, List<String> words) {
        for (String word : words) {
            // Short words must stand alone, so "run" does not match "brunch" and "eat" not "great".
            if (word.length() <= 4 && !word.contains(" ")) {
                if (text.matches("(?s).*(^|[^\\p{L}])" + word + "([^\\p{L}]|$).*")) {
                    return true;
                }
            } else if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
