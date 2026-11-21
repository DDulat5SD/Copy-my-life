package com.copymylife.copymylife.assistant;

import com.copymylife.copymylife.assistant.ResolvedPreferences.Setting;
import com.copymylife.copymylife.assistant.ResolvedPreferences.Source;

import java.util.Locale;
import java.util.Map;

// Decides which value each preference takes: what this request sets, then what the user saved,
// then what their own tracking records show often enough, then a default. History only fills
// gaps the user left open; it never overrides something the user chose.
public final class PersonalizationEngine {

    // A usual time needs at least this many completed runs before it is used.
    public static final int MIN_OBSERVATIONS = 3;

    private static final Map<String, int[]> REST_PRESETS = Map.of(
            "FREQUENT", new int[]{45, 10},
            "NORMAL", new int[]{90, 10},
            "RARE", new int[]{150, 15});

    private PersonalizationEngine() {
    }

    public static ResolvedPreferences resolve(PreferenceInput request, PreferenceInput saved, HistorySignals history) {
        PreferenceInput asked = request == null ? PreferenceInput.NONE : request;
        PreferenceInput kept = saved == null ? PreferenceInput.NONE : saved;
        HistorySignals seen = history == null ? HistorySignals.NONE : history;

        Setting wake = pick(asked.wake(), kept.wake(), null, 7 * 60, "default 07:00");
        Setting sleep = pick(asked.sleep(), kept.sleep(), null, 23 * 60, "default 23:00");
        Setting from = pick(asked.availableFrom(), kept.availableFrom(), null, wake.value(), "from wake-up");
        Setting to = pick(asked.availableTo(), kept.availableTo(), null, sleep.value(), "until sleep");

        Setting breakfast = pick(asked.breakfast(), kept.breakfast(), usual(seen, "breakfast", "breakfast"),
                (wake.value() + 30) % Clock.DAY, "default: 30 min after waking");
        Setting lunch = pick(asked.lunch(), kept.lunch(), usual(seen, "lunch", "lunch"), 13 * 60, "default 13:00");
        Setting dinner = pick(asked.dinner(), kept.dinner(), usual(seen, "dinner", "dinner"), 19 * 60, "default 19:00");
        Setting mealMinutes = pick(asked.mealMinutes(), kept.mealMinutes(), null, 30, "default 30 min");
        Setting exercise = pick(asked.exercise(), kept.exercise(), usual(seen, "exercise", "exercise"),
                null, "no preferred time: placed by priority");

        Setting[] rest = rest(asked, kept);
        Setting focus = asked.focusBlockMinutes() != null
                ? new Setting(asked.focusBlockMinutes(), Source.REQUEST, "set for this plan")
                : kept.focusBlockMinutes() != null
                ? new Setting(kept.focusBlockMinutes(), Source.SAVED, "your saved preference")
                : new Setting(rest[0].value(), rest[0].source(), "same as your break interval");

        Intensity intensity = asked.intensity() != null ? asked.intensity()
                : kept.intensity() != null ? kept.intensity() : Intensity.BALANCED;
        Source intensitySource = asked.intensity() != null ? Source.REQUEST
                : kept.intensity() != null ? Source.SAVED : Source.DEFAULT;

        ResolvedPreferences resolved = new ResolvedPreferences(wake, sleep, from, to, breakfast, lunch, dinner,
                mealMinutes, exercise, focus, rest[0], rest[1], intensity, intensitySource);
        checkWindow(resolved);
        return resolved;
    }

    // Field by field limits, for saved preferences and for one request alike.
    public static void validate(PreferenceInput input) {
        if (input == null) {
            return;
        }
        within(input.mealMinutes(), 5, 180, "Meal length");
        within(input.focusBlockMinutes(), 15, 240, "Focus session length");
        within(input.breakEveryMinutes(), 20, 300, "Break interval");
        within(input.breakMinutes(), 5, 60, "Break length");

        if (input.restFrequency() != null && !REST_PRESETS.containsKey(input.restFrequency())) {
            throw new IllegalArgumentException("Rest frequency must be FREQUENT, NORMAL or RARE");
        }
        if (input.wake() != null && input.wake().equals(input.sleep())) {
            throw new IllegalArgumentException("Wake-up and sleep time can't be the same");
        }
    }

    public static String normaliseRestFrequency(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static void checkWindow(ResolvedPreferences resolved) {
        int length = resolved.dayEnd() - resolved.dayStart();

        if (length < 60) {
            throw new IllegalArgumentException("Leave at least one hour between wake-up and sleep");
        }

        int from = resolved.from();
        int to = resolved.to();

        if (from >= resolved.dayEnd() || to > resolved.dayEnd() || to <= from) {
            throw new IllegalArgumentException("Available hours must lie between your wake-up ("
                    + Clock.format(resolved.dayStart()) + ") and sleep time (" + Clock.format(resolved.dayEnd()) + ")");
        }
    }

    private static Setting[] rest(PreferenceInput asked, PreferenceInput kept) {
        Setting every = null;
        Setting length = null;

        for (PreferenceInput input : new PreferenceInput[]{asked, kept}) {
            Source source = input == asked ? Source.REQUEST : Source.SAVED;
            int[] preset = input.restFrequency() == null ? null : REST_PRESETS.get(input.restFrequency());
            String presetText = preset == null ? null
                    : "\"" + input.restFrequency().toLowerCase(Locale.ROOT) + " breaks\" " + (source == Source.REQUEST ? "for this plan" : "preference");

            if (every == null && input.breakEveryMinutes() != null) {
                every = new Setting(input.breakEveryMinutes(), source, source == Source.REQUEST ? "set for this plan" : "your saved preference");
            } else if (every == null && preset != null) {
                every = new Setting(preset[0], source, presetText);
            }

            if (length == null && input.breakMinutes() != null) {
                length = new Setting(input.breakMinutes(), source, source == Source.REQUEST ? "set for this plan" : "your saved preference");
            } else if (length == null && preset != null) {
                length = new Setting(preset[1], source, presetText);
            }
        }

        return new Setting[]{
                every == null ? new Setting(90, Source.DEFAULT, "default") : every,
                length == null ? new Setting(10, Source.DEFAULT, "default") : length
        };
    }

    private static Setting pick(Integer asked, Integer kept, Setting usual, Integer fallback, String fallbackText) {
        if (asked != null) {
            return new Setting(asked, Source.REQUEST, "set for this plan");
        }
        if (kept != null) {
            return new Setting(kept, Source.SAVED, "your saved preference");
        }
        if (usual != null) {
            return usual;
        }
        return new Setting(fallback, Source.DEFAULT, fallbackText);
    }

    private static Setting usual(HistorySignals history, String key, String name) {
        HistorySignals.TimeSignal signal = history.time(key);

        if (signal == null || signal.observations() < MIN_OBSERVATIONS) {
            return null;
        }

        return new Setting(signal.minute(), Source.HISTORY,
                "you usually start " + name + " around " + Clock.format(signal.minute())
                        + " (" + signal.observations() + " completed runs)");
    }

    private static void within(Integer value, int min, int max, String name) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max + " minutes");
        }
    }
}
