package com.copymylife.copymylife.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

// Compares a habit's planned length with how long it actually took in the user's own runs.
// A length is known in two ways only: the run timer (from pressing Start to pressing Complete)
// or a length the user entered after completing it. A habit that was only marked done has no
// length and is never counted. The timer can't show a habit as shorter than planned (Complete
// unlocks once the planned time has passed) and runs on if Complete is pressed late, so a timer
// reading far beyond the plan is left out as a probable forgotten timer.
// One long run proves nothing: a change is suggested only when at least MIN_RUNS of the latest
// WINDOW measured runs exist and most of them (SHARE) differ from the plan by a clear margin.
// Nothing is changed here; a suggestion the user declined is not repeated until they have done
// MIN_RUNS more measured runs.
public final class HabitDurationAnalyzer {

    public static final int MIN_RUNS = 3;
    static final int WINDOW = 10;
    static final double SHARE = 0.6;

    public enum Status {
        NO_DATA,
        NOT_ENOUGH_DATA,
        ON_PLAN,
        OFTEN_LONGER,
        OFTEN_SHORTER,
        KEPT_BY_YOU
    }

    // One completed run of the habit; either length may be missing.
    public record Observation(LocalDate day, Integer timerMinutes, Integer reportedMinutes, Instant at) {
    }

    // The user's latest answer to a length suggestion for this habit.
    public record Decision(boolean accepted, int suggestedMinutes, int plannedMinutes, Instant at, LocalDate day) {
    }

    public record Insight(
            String title,
            int plannedMinutes,
            Status status,
            int measuredRuns,
            int timerRuns,
            int enteredRuns,
            int leftOut,
            Integer usualMinutes,
            int longerRuns,
            int shorterRuns,
            Integer suggestedMinutes,
            String message,
            String evidence
    ) {
    }

    private HabitDurationAnalyzer() {
    }

    public static Insight analyze(String title, int planned, List<Observation> observations, Decision decision) {
        List<Observation> latestFirst = new ArrayList<>(observations);
        latestFirst.sort(Comparator.comparing(HabitDurationAnalyzer::moment).reversed());

        List<Integer> values = new ArrayList<>();
        int timer = 0;
        int entered = 0;
        int leftOut = 0;

        for (Observation observation : latestFirst) {
            if (values.size() == WINDOW) {
                break;
            }
            if (observation.reportedMinutes() != null) {
                values.add(observation.reportedMinutes());
                entered++;
            } else if (observation.timerMinutes() != null) {
                if (forgottenTimer(observation.timerMinutes(), planned)) {
                    leftOut++;
                } else {
                    values.add(observation.timerMinutes());
                    timer++;
                }
            }
        }

        if (values.isEmpty()) {
            String message = leftOut > 0
                    ? "The run timer for " + title + " was left running far beyond the plan each time, so no length is known yet."
                    : "No measured time yet for " + title + ". A length is measured when you press Start and Complete in a run,"
                    + " or when you enter the actual time after completing it.";
            return new Insight(title, planned, Status.NO_DATA, 0, 0, 0, leftOut, null, 0, 0, null, message, null);
        }

        int margin = margin(planned);
        int longer = (int) values.stream().filter(v -> v >= planned + margin).count();
        int shorter = (int) values.stream().filter(v -> v <= planned - margin).count();
        int usual = median(values);
        String evidence = evidence(values, timer, entered, leftOut, longer, shorter, margin);

        if (values.size() < MIN_RUNS) {
            return new Insight(title, planned, Status.NOT_ENOUGH_DATA, values.size(), timer, entered, leftOut, usual,
                    longer, shorter, null, "Measured " + values.size() + (values.size() == 1 ? " time" : " times")
                    + " so far; a change is suggested only after at least " + MIN_RUNS + " measured runs.", evidence);
        }

        Status status = statusOf(longer, shorter, values.size());
        Integer suggested = switch (status) {
            case OFTEN_LONGER -> Math.min(1440, Math.max(planned + 5, Clock.round5(usual)));
            case OFTEN_SHORTER -> Math.max(5, Math.min(planned - 5, Clock.round5(usual)));
            default -> null;
        };

        if (suggested != null && decision != null && !decision.accepted()) {
            long since = latestFirst.stream().filter(o -> moment(o).isAfter(decision.at()))
                    .filter(o -> o.reportedMinutes() != null || (o.timerMinutes() != null && !forgottenTimer(o.timerMinutes(), planned)))
                    .count();
            if (since < MIN_RUNS) {
                return new Insight(title, planned, Status.KEPT_BY_YOU, values.size(), timer, entered, leftOut, usual,
                        longer, shorter, null, "You chose to keep " + decision.plannedMinutes() + " min for " + title
                        + " on " + decision.day() + ". It is checked again after "
                        + MIN_RUNS + " more measured runs (" + since + " so far).", evidence);
            }
        }

        String message = switch (status) {
            case OFTEN_LONGER -> "You often spend about " + usual + " minutes on " + title + ", although " + planned
                    + " minutes is scheduled (" + longer + " of your last " + values.size() + " measured runs took longer)."
                    + " Would you like to increase it to " + suggested + " minutes?";
            case OFTEN_SHORTER -> "You often finish " + title + " in about " + usual + " minutes, although " + planned
                    + " minutes is scheduled (" + shorter + " of your last " + values.size() + " measured runs were shorter)."
                    + " Would you like to shorten it to " + suggested + " minutes?";
            default -> title + " usually takes about as long as planned (" + usual + " min measured, " + planned + " min planned).";
        };

        return new Insight(title, planned, status, values.size(), timer, entered, leftOut, usual, longer, shorter,
                suggested, message, evidence);
    }

    // A clear difference: at least 5 minutes and at least a fifth of the plan.
    static int margin(int planned) {
        return Math.max(5, (planned + 4) / 5);
    }

    static boolean forgottenTimer(int timerMinutes, int planned) {
        return timerMinutes > planned * 3 && timerMinutes - planned > 60;
    }

    static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2f);
    }

    private static Status statusOf(int longer, int shorter, int runs) {
        if (longer >= Math.ceil(runs * SHARE)) {
            return Status.OFTEN_LONGER;
        }
        if (shorter >= Math.ceil(runs * SHARE)) {
            return Status.OFTEN_SHORTER;
        }
        return Status.ON_PLAN;
    }

    private static String evidence(List<Integer> values, int timer, int entered, int leftOut, int longer, int shorter, int margin) {
        StringBuilder text = new StringBuilder();
        text.append(values.size()).append(values.size() == 1 ? " measured run: " : " measured runs: ")
                .append(values.stream().map(String::valueOf).collect(Collectors.joining(", "))).append(" min (");
        List<String> sources = new ArrayList<>();
        if (timer > 0) {
            sources.add(timer + " by the run timer");
        }
        if (entered > 0) {
            sources.add(entered + " entered by you");
        }
        text.append(String.join(", ", sources)).append(")");
        if (longer > 0) {
            text.append("; ").append(longer).append(" at least ").append(margin).append(" min longer than planned");
        }
        if (shorter > 0) {
            text.append("; ").append(shorter).append(" at least ").append(margin).append(" min shorter");
        }
        if (leftOut > 0) {
            text.append("; ").append(leftOut).append(" timer reading").append(leftOut == 1 ? "" : "s")
                    .append(" far beyond the plan left out");
        }
        return text.append(".").toString();
    }

    private static Instant moment(Observation observation) {
        return observation.at() != null ? observation.at() : observation.day().atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
