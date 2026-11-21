package com.copymylife.copymylife.assistant;

import java.util.Map;

// What the user's own tracking records show, worked out by the backend from stored runs only:
// the usual start time of meals and exercise ("breakfast", "lunch", "dinner", "exercise") and
// the usual length of an activity by its title in lower case. Absent means not measured.
public record HistorySignals(
        Map<String, TimeSignal> usualTimes,
        Map<String, LengthSignal> usualLengths
) {

    public static final HistorySignals NONE = new HistorySignals(Map.of(), Map.of());

    // The median start time over a number of completed runs.
    public record TimeSignal(int minute, int observations) {
    }

    // The median length over a number of completed runs that had a measured or entered length.
    public record LengthSignal(int minutes, int observations) {
    }

    public TimeSignal time(String key) {
        return usualTimes.get(key);
    }
}
