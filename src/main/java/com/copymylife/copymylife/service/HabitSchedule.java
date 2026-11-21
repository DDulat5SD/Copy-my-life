package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.RoutineHabitResponse;
import com.copymylife.copymylife.model.RoutineHabit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

// Rules for a habit's plan inside one routine: start time, repeat days and notes.
// A habit with a start time needs repeat days, and two timed habits of one routine
// may not share any minute on the same day. Untimed habits never clash.
final class HabitSchedule {

    static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    static final int MAX_NOTES_LENGTH = 500;

    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");
    private static final int MINUTES_PER_DAY = 24 * 60;

    private HabitSchedule() {
    }

    static LocalTime parseStartTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        if (!TIME.matcher(value.trim()).matches()) {
            throw invalid("Start time must be a time of day such as 07:30");
        }

        return LocalTime.parse(value.trim());
    }

    // Returns the days in weekday order, e.g. "MON,WED"; null when no day is given.
    static String parseDays(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        Set<String> chosen = new LinkedHashSet<>();

        for (String value : values) {
            String day = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);

            if (!DAYS.contains(day)) {
                throw invalid("Unknown repeat day \"" + value + "\". Use MON, TUE, WED, THU, FRI, SAT or SUN.");
            }

            chosen.add(day);
        }

        return String.join(",", DAYS.stream().filter(chosen::contains).toList());
    }

    static String parseNotes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        if (value.trim().length() > MAX_NOTES_LENGTH) {
            throw invalid("Notes must be at most " + MAX_NOTES_LENGTH + " characters");
        }

        return value.trim();
    }

    static void requireDaysForTime(LocalTime startTime, String days) {
        if (startTime != null && days == null) {
            throw invalid("Choose at least one repeat day for a habit with a start time");
        }
    }

    static List<String> dayList(String days) {
        return days == null || days.isBlank() ? List.of() : Arrays.asList(days.split(","));
    }

    // A habit is planned on the days it repeats. One without repeat days is not tied to a day,
    // so it is planned every day, as habits were before schedules existed.
    static boolean plannedOn(RoutineHabit link, LocalDate date) {
        List<String> days = dayList(link.getRepeatDays());
        return days.isEmpty() || days.contains(dayCode(date));
    }

    // The order of a day, the same as the routine's timeline: timed habits by start time,
    // then the untimed ones; habits that tie keep their place in the routine.
    static final Comparator<RoutineHabit> DAY_ORDER = Comparator
            .comparing(RoutineHabit::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RoutineHabit::getPosition, Comparator.nullsLast(Comparator.naturalOrder()));

    static String dayCode(LocalDate date) {
        return DAYS.get(date.getDayOfWeek().getValue() - 1);
    }

    static String fullDayName(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    // Refuses a plan that shares minutes with another timed habit of the same routine on any day.
    // A habit that runs past midnight continues on the next day.
    static void checkOverlaps(
            List<RoutineHabit> others,
            LocalTime startTime,
            int durationMinutes,
            String days
    ) {
        if (startTime == null) {
            return;
        }

        List<int[]> mine = segments(startTime, durationMinutes, days);

        for (int day = 0; day < DAYS.size(); day++) {
            for (RoutineHabit other : others) {
                if (other.getStartTime() == null) {
                    continue;
                }

                int otherMinutes = minutes(other);

                for (int[] a : mine) {
                    for (int[] b : segments(other.getStartTime(), otherMinutes, other.getRepeatDays())) {
                        if (a[0] == day && b[0] == day && a[1] < b[2] && b[1] < a[2]) {
                            throw invalid("Overlaps with \"" + other.getHabit().getTitle() + "\" ("
                                    + range(other.getStartTime(), otherMinutes) + ") on "
                                    + dayName(DAYS.get(day))
                                    + ". Two timed habits can't share the same minutes.");
                        }
                    }
                }
            }
        }
    }

    static void describe(RoutineHabit link, RoutineHabitResponse response) {
        LocalTime start = link.getStartTime();

        response.setStartTime(start == null ? null : format(start));
        response.setRepeatDays(dayList(link.getRepeatDays()));
        response.setNotes(link.getNotes());

        if (start != null) {
            int end = toMinutes(start) + minutes(link);
            response.setEndTime(format(LocalTime.MIN.plusMinutes(end % MINUTES_PER_DAY)));
            response.setEndsNextDay(end > MINUTES_PER_DAY);
        }
    }

    private static List<int[]> segments(LocalTime startTime, int durationMinutes, String days) {
        List<int[]> result = new ArrayList<>();
        int start = toMinutes(startTime);
        int end = start + durationMinutes;

        for (String day : dayList(days)) {
            int index = DAYS.indexOf(day);

            result.add(new int[]{index, start, Math.min(end, MINUTES_PER_DAY)});

            if (end > MINUTES_PER_DAY) {
                result.add(new int[]{(index + 1) % DAYS.size(), 0, end - MINUTES_PER_DAY});
            }
        }

        return result;
    }

    private static int minutes(RoutineHabit link) {
        Integer value = link.getHabit().getDurationMinutes();
        return value == null ? 0 : value;
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static String range(LocalTime start, int durationMinutes) {
        return format(start) + " – " + format(start.plusMinutes(durationMinutes));
    }

    static String format(LocalTime time) {
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private static String dayName(String code) {
        return code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
