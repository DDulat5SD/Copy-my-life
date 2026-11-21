package com.copymylife.copymylife.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The preferences one plan or review works with, each with where it came from, so every
// suggestion can say why: set for this request, saved by the user, seen in their own tracking
// records, or a default the user has not changed.
public record ResolvedPreferences(
        Setting wake,
        Setting sleep,
        Setting availableFrom,
        Setting availableTo,
        Setting breakfast,
        Setting lunch,
        Setting dinner,
        Setting mealMinutes,
        Setting exercise,
        Setting focusBlock,
        Setting breakEvery,
        Setting breakMinutes,
        Intensity intensity,
        Source intensitySource
) {

    public enum Source {
        REQUEST,
        SAVED,
        HISTORY,
        DEFAULT
    }

    // value is minutes (a time of day or a length); null only for "no preferred exercise time".
    public record Setting(Integer value, Source source, String detail) {
    }

    // The plan's day as minutes: from wake-up to sleep, which may be past midnight.
    public int dayStart() {
        return wake.value();
    }

    public int dayEnd() {
        return sleep.value() > wake.value() ? sleep.value() : sleep.value() + Clock.DAY;
    }

    public int from() {
        return Clock.inWindow(availableFrom.value(), dayStart());
    }

    public int to() {
        int to = Clock.inWindow(availableTo.value(), dayStart());
        return to == dayStart() ? dayEnd() : to;
    }

    // A meal or exercise time inside the plan's day, or null.
    public Integer inDay(Setting time) {
        return time.value() == null ? null : Clock.inWindow(time.value(), dayStart());
    }

    public Setting meal(String name) {
        return switch (name) {
            case "breakfast" -> breakfast;
            case "lunch" -> lunch;
            default -> dinner;
        };
    }

    // For the API and the page: one line per preference, with its source.
    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> lines = new ArrayList<>();
        line(lines, "wake", "Wake-up", Clock.formatNullable(wake.value()), wake);
        line(lines, "sleep", "Sleep", Clock.formatNullable(sleep.value()), sleep);
        line(lines, "availableFrom", "Available from", Clock.formatNullable(availableFrom.value()), availableFrom);
        line(lines, "availableTo", "Available until", Clock.formatNullable(availableTo.value()), availableTo);
        line(lines, "breakfast", "Breakfast", Clock.formatNullable(breakfast.value()), breakfast);
        line(lines, "lunch", "Lunch", Clock.formatNullable(lunch.value()), lunch);
        line(lines, "dinner", "Dinner", Clock.formatNullable(dinner.value()), dinner);
        line(lines, "mealMinutes", "Meal length", mealMinutes.value() + " min", mealMinutes);
        line(lines, "exercise", "Exercise time",
                exercise.value() == null ? "no preferred time" : Clock.format(exercise.value()), exercise);
        line(lines, "focusBlock", "Longest focus session", focusBlock.value() + " min", focusBlock);
        line(lines, "breakEvery", "Break after", breakEvery.value() + " min of activities", breakEvery);
        line(lines, "breakMinutes", "Break length", breakMinutes.value() + " min", breakMinutes);
        line(lines, "intensity", "Intensity", intensity.label(),
                new Setting(null, intensitySource, intensitySource == Source.DEFAULT ? "default" : null));
        return lines;
    }

    private static void line(List<Map<String, Object>> lines, String key, String label, String value, Setting setting) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("key", key);
        line.put("label", label);
        line.put("value", value);
        line.put("source", setting.source().name());
        line.put("detail", setting.detail());
        lines.add(line);
    }
}
