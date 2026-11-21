package com.copymylife.copymylife.service;

import com.copymylife.copymylife.assistant.ActivityKind;
import com.copymylife.copymylife.assistant.DailySummaryEngine;
import com.copymylife.copymylife.assistant.HabitDurationAnalyzer;
import com.copymylife.copymylife.assistant.HistorySignals;
import com.copymylife.copymylife.model.AssistantDecision;
import com.copymylife.copymylife.model.HabitCompletion;
import com.copymylife.copymylife.model.HabitCompletionStatus;
import com.copymylife.copymylife.model.RoutineCompletion;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.AssistantDecisionRepository;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.copymylife.copymylife.model.HabitCompletionStatus.*;

// Routine assistant: what the user's own stored runs say, and nothing more. A habit's length is
// known only from the run timer (Start to Complete) or a length the user entered; a habit that
// was only marked done has none. Callers run inside a transaction.
@Service
public class AssistantHistoryService {

    static final int HISTORY_DAYS = 90;
    static final String DURATION = "DURATION";

    private final HabitCompletionRepository habitCompletionRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final AssistantDecisionRepository decisionRepository;

    public AssistantHistoryService(
            HabitCompletionRepository habitCompletionRepository,
            RoutineCompletionRepository routineCompletionRepository,
            RoutineHabitRepository routineHabitRepository,
            AssistantDecisionRepository decisionRepository
    ) {
        this.habitCompletionRepository = habitCompletionRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.decisionRepository = decisionRepository;
    }

    // The user's run records of the last HISTORY_DAYS days up to today.
    List<HabitCompletion> records(User user, LocalDate today) {
        return habitCompletionRepository.findRunRecordsSince(user.getId(), today.minusDays(HISTORY_DAYS));
    }

    // Minutes from Start to Complete of a completed habit, or null when not measured.
    static Integer timerMinutes(HabitCompletion record) {
        if (record.currentStatus() != COMPLETED || record.getStartedAt() == null || record.getCompletedAt() == null) {
            return null;
        }
        long seconds = Duration.between(record.getStartedAt(), record.getCompletedAt()).getSeconds();
        return seconds < 0 ? null : (int) Math.round(seconds / 60.0);
    }

    // Usual meal and exercise start times (meals and exercise recognised from the habit titles)
    // and usual lengths by title, from completed runs only.
    HistorySignals signals(List<HabitCompletion> records, ZoneId zone) {
        Map<String, List<Integer>> times = new HashMap<>();
        Map<String, List<Integer>> lengths = new HashMap<>();

        for (HabitCompletion record : records) {
            if (record.currentStatus() != COMPLETED || record.getStartedAt() == null) {
                continue;
            }
            String title = record.getHabit().getTitle();
            String meal = ActivityKind.mealOf(title);
            String key = meal != null ? meal : ActivityKind.recognise(title) == ActivityKind.EXERCISE ? "exercise" : null;
            if (key != null) {
                LocalTime local = LocalTime.ofInstant(record.getStartedAt(), zone);
                times.computeIfAbsent(key, k -> new ArrayList<>()).add(local.getHour() * 60 + local.getMinute());
            }

            Integer length = length(record);
            if (length != null) {
                lengths.computeIfAbsent(title.strip().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(length);
            }
        }

        Map<String, HistorySignals.TimeSignal> usualTimes = new LinkedHashMap<>();
        times.forEach((key, values) -> usualTimes.put(key, new HistorySignals.TimeSignal(median(values), values.size())));
        Map<String, HistorySignals.LengthSignal> usualLengths = new LinkedHashMap<>();
        lengths.forEach((key, values) -> usualLengths.put(key, new HistorySignals.LengthSignal(median(values), values.size())));
        return new HistorySignals(usualTimes, usualLengths);
    }

    // The duration analysis of each activity, by routine habit id.
    Map<Long, HabitDurationAnalyzer.Insight> insights(User user, List<RoutineHabit> links,
                                                      List<HabitCompletion> records, ZoneId zone) {
        Map<Long, List<HabitDurationAnalyzer.Observation>> byLink = new HashMap<>();

        for (HabitCompletion record : records) {
            if (record.currentStatus() != COMPLETED || record.getRoutineHabit() == null) {
                continue;
            }
            Instant at = record.getCompletedAt() != null ? record.getCompletedAt() : record.getStartedAt();
            byLink.computeIfAbsent(record.getRoutineHabit().getId(), id -> new ArrayList<>())
                    .add(new HabitDurationAnalyzer.Observation(record.getCompletedDate(), timerMinutes(record),
                            record.getReportedMinutes(), at));
        }

        Map<Long, HabitDurationAnalyzer.Decision> decisions = new HashMap<>();
        for (AssistantDecision decision : decisionRepository.findLatestFirst(user.getId(), DURATION)) {
            decisions.putIfAbsent(decision.getRoutineHabit().getId(), new HabitDurationAnalyzer.Decision(
                    "ACCEPTED".equals(decision.getDecision()),
                    decision.getSuggestedMinutes() == null ? 0 : decision.getSuggestedMinutes(),
                    decision.getPlannedMinutes() == null ? 0 : decision.getPlannedMinutes(),
                    decision.getDecidedAt(),
                    LocalDate.ofInstant(decision.getDecidedAt(), zone)));
        }

        Map<Long, HabitDurationAnalyzer.Insight> insights = new LinkedHashMap<>();
        for (RoutineHabit link : links) {
            Integer planned = link.getHabit().getDurationMinutes();
            insights.put(link.getId(), HabitDurationAnalyzer.analyze(link.getHabit().getTitle(),
                    planned == null ? 0 : planned, byLink.getOrDefault(link.getId(), List.of()), decisions.get(link.getId())));
        }
        return insights;
    }

    DailySummaryEngine.Summary summary(User user, LocalDate date, LocalDate today, ZoneId zone) {
        Instant now = Instant.now();
        List<RoutineCompletion> runs = routineCompletionRepository
                .findByUserIdAndCompletedDateBetween(user.getId(), date.minusDays(13), date).stream()
                .filter(run -> run.getStartedAt() != null)
                .sorted(Comparator.comparing(RoutineCompletion::getStartedAt))
                .toList();

        List<DailySummaryEngine.RunResult> dayRuns = new ArrayList<>();
        int lastPlanned = 0, lastDone = 0, previousPlanned = 0, previousDone = 0;
        List<RoutineHabit> dayLinks = new ArrayList<>();

        for (RoutineCompletion run : runs) {
            List<RoutineHabit> links = routineHabitRepository.findByRoutineIdOrderByPositionAsc(run.getRoutine().getId());
            DailySummaryEngine.RunResult result = runResult(run, links, now, zone);
            int planned = result.habits().size();
            int done = (int) result.habits().stream().filter(h -> "COMPLETED".equals(h.status())).count();

            if (!run.getCompletedDate().isBefore(date.minusDays(6))) {
                lastPlanned += planned;
                lastDone += done;
            } else {
                previousPlanned += planned;
                previousDone += done;
            }
            if (run.getCompletedDate().equals(date)) {
                dayRuns.add(result);
                dayLinks.addAll(links);
            }
        }

        List<ProgressHistory.Mark> marks = habitCompletionRepository.findHistory(user.getId(), date).stream()
                .map(record -> new ProgressHistory.Mark(record.getHabit().getId(), record.getHabit().getTitle(),
                        record.getCompletedDate(), record.currentStatus()))
                .toList();
        List<DailySummaryEngine.Streak> streaks = new ProgressHistory(marks, date).habitStreaks().stream()
                .map(streak -> new DailySummaryEngine.Streak(streak.title(), streak.currentDays()))
                .toList();

        List<HabitCompletion> records = records(user, today);
        Map<Long, Integer> skips = new HashMap<>();
        for (HabitCompletion record : records) {
            if (record.currentStatus() == SKIPPED && record.getRoutineHabit() != null
                    && !record.getCompletedDate().isBefore(date.minusDays(13)) && !record.getCompletedDate().isAfter(date)) {
                skips.merge(record.getRoutineHabit().getId(), 1, Integer::sum);
            }
        }

        DailySummaryEngine.Trend trend = new DailySummaryEngine.Trend(
                lastPlanned == 0 ? null : Math.round(lastDone * 100f / lastPlanned), lastPlanned,
                previousPlanned == 0 ? null : Math.round(previousDone * 100f / previousPlanned), previousPlanned);

        return DailySummaryEngine.summarize(new DailySummaryEngine.Input(date, date.equals(today), dayRuns, streaks, trend,
                insights(user, dayLinks, records, zone), skips));
    }

    // One run as the run page shows it: the habits planned for its day, each with its result.
    private DailySummaryEngine.RunResult runResult(RoutineCompletion run, List<RoutineHabit> links, Instant now, ZoneId zone) {
        Map<Long, HabitCompletion> records = new HashMap<>();
        for (HabitCompletion record : habitCompletionRepository.findByRoutineCompletionId(run.getId())) {
            if (record.getRoutineHabit() != null) {
                records.put(record.getRoutineHabit().getId(), record);
            }
        }

        boolean open = run.getCompletedAt() == null && run.getEndedAt() == null
                && (run.getExpiresAt() == null || now.isBefore(run.getExpiresAt()));

        List<RoutineHabit> plan = links.stream()
                .filter(link -> records.containsKey(link.getId())
                        || (!link.isRemoved() && !link.isPaused() && HabitSchedule.plannedOn(link, run.getCompletedDate())))
                .sorted(HabitSchedule.DAY_ORDER)
                .toList();

        List<DailySummaryEngine.HabitResult> habits = new ArrayList<>();
        for (RoutineHabit link : plan) {
            HabitCompletion record = records.get(link.getId());
            HabitCompletionStatus status = record == null ? null : record.currentStatus();
            String label = status == null ? "NOT_STARTED"
                    : status == IN_PROGRESS ? (open ? "IN_PROGRESS" : "NOT_FINISHED")
                    : status.name();
            Integer planned = record != null && record.getPlannedMinutes() != null
                    ? record.getPlannedMinutes() : link.getHabit().getDurationMinutes();
            Integer actualStart = null;
            if (record != null && record.getStartedAt() != null) {
                LocalTime local = LocalTime.ofInstant(record.getStartedAt(), zone);
                actualStart = local.getHour() * 60 + local.getMinute();
            }

            habits.add(new DailySummaryEngine.HabitResult(run.getRoutine().getId(), run.getRoutine().getTitle(),
                    link.getId(), link.getHabit().getTitle(), label, planned == null ? 0 : planned,
                    record == null ? null : timerMinutes(record), record == null ? null : record.getReportedMinutes(),
                    link.getStartTime() == null ? null : link.getStartTime().getHour() * 60 + link.getStartTime().getMinute(),
                    actualStart));
        }

        String runStatus = run.getCompletedAt() != null ? "COMPLETED"
                : run.getEndedAt() != null ? "ENDED" : open ? "RUNNING" : "EXPIRED";
        return new DailySummaryEngine.RunResult(run.getId(), run.getRoutine().getId(), run.getRoutine().getTitle(),
                runStatus, habits);
    }

    // The length a record counts with: what the user entered, else a believable timer reading.
    private static Integer length(HabitCompletion record) {
        if (record.currentStatus() != COMPLETED) {
            return null;
        }
        if (record.getReportedMinutes() != null) {
            return record.getReportedMinutes();
        }
        Integer timer = timerMinutes(record);
        Integer planned = record.getPlannedMinutes() != null ? record.getPlannedMinutes() : record.getHabit().getDurationMinutes();
        if (timer == null || (planned != null && timer > planned * 3 && timer - planned > 60)) {
            return null;
        }
        return timer;
    }

    private static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2f);
    }
}
