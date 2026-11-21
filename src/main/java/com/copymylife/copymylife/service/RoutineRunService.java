package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.*;
import com.copymylife.copymylife.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static com.copymylife.copymylife.model.HabitCompletionStatus.*;

@Service
public class RoutineRunService {

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final HabitCompletionRepository habitCompletionRepository;

    public RoutineRunService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            RoutineHabitRepository routineHabitRepository,
            RoutineCompletionRepository routineCompletionRepository,
            HabitCompletionRepository habitCompletionRepository
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.habitCompletionRepository = habitCompletionRepository;
    }

    @Transactional
    public Map<String, Object> startRoutine(String email, Long routineId, ZoneId zone) {
        User user = lockUser(email);
        Routine routine = getRoutine(user, routineId);

        Optional<RoutineCompletion> active = activeRun(user.getId());

        if (active.isPresent()) {
            RoutineCompletion run = active.get();

            if (!run.getRoutine().getId().equals(routineId)) {
                throw new IllegalArgumentException(
                        "Another routine is already in progress"
                );
            }

            return response(routine, getLinks(routineId), run);
        }

        // Deleted habits are kept only for past runs; a new run is built from the current ones.
        List<RoutineHabit> links = getLinks(routineId).stream()
                .filter(link -> !link.isRemoved())
                .toList();

        if (links.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add habits before starting this routine"
            );
        }

        int usedMinutes = links.stream()
                .mapToInt(link -> value(link.getHabit().getDurationMinutes()))
                .sum();

        int routineMinutes = value(routine.getDurationMinutes());

        if (usedMinutes != routineMinutes) {
            throw new IllegalArgumentException(
                    "Habit total must be exactly " + routineMinutes
                            + " minutes. Current total: " + usedMinutes
            );
        }

        if (links.stream().allMatch(RoutineHabit::isPaused)) {
            throw new IllegalArgumentException(
                    "Every habit in this routine is paused. Resume one to start it."
            );
        }

        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, zone);

        if (planOf(links, Map.of(), today).isEmpty()) {
            throw new IllegalArgumentException(
                    "Nothing is planned for " + HabitSchedule.fullDayName(today)
                            + " in this routine. Its habits are on other days."
            );
        }

        RoutineCompletion run = new RoutineCompletion();

        run.setUser(user);
        run.setRoutine(routine);
        run.setCompletedDate(today);
        run.setStartedAt(now);
        run.setCompletedAt(null);
        run.setExpiresAt(today.plusDays(1).atStartOfDay(zone).toInstant());

        run = routineCompletionRepository.save(run);

        routine.setCompleted(false);
        routineRepository.save(routine);

        return response(routine, links, run);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getCurrentRun(String email, Long routineId) {
        User user = getUser(email);
        Routine routine = getRoutine(user, routineId);

        return latestRun(user.getId(), routineId)
                .map(run -> response(routine, getLinks(routineId), run))
                .orElse(null);
    }

    // Starts the next habit, or resumes a postponed one.
    @Transactional
    public Map<String, Object> startHabit(
            String email,
            Long routineId,
            Long routineHabitId
    ) {
        Step step = getStep(email, routineId, routineHabitId);
        HabitCompletionStatus status = step.status();

        if (status == IN_PROGRESS) {
            return response(step);
        }

        if (status == COMPLETED || status == SKIPPED) {
            throw alreadyDone(status);
        }

        if (habitRunning(step.records())) {
            throw new IllegalArgumentException(
                    "Complete the current habit first"
            );
        }

        HabitCompletion completion = status == null
                ? newCompletion(step)
                : step.completion();

        completion.setStatus(IN_PROGRESS);
        completion.setStartedAt(Instant.now());
        completion.setCompletedAt(null);
        completion.setPlannedMinutes(step.target().getHabit().getDurationMinutes());

        habitCompletionRepository.save(completion);

        return response(step);
    }

    @Transactional
    public Map<String, Object> completeHabit(
            String email,
            Long routineId,
            Long routineHabitId
    ) {
        Step step = getStep(email, routineId, routineHabitId);
        HabitCompletionStatus status = step.status();

        if (status == COMPLETED) {
            return response(step);
        }

        if (status != IN_PROGRESS) {
            throw new IllegalArgumentException(
                    "Start this habit first"
            );
        }

        HabitCompletion completion = step.completion();
        Instant now = Instant.now();

        Instant canCompleteAt = completion.getStartedAt()
                .plus(Duration.ofMinutes(
                        value(step.target().getHabit().getDurationMinutes())
                ));

        if (now.isBefore(canCompleteAt)) {
            long seconds = Math.max(
                    1,
                    (Duration.between(now, canCompleteAt).toMillis() + 999) / 1000
            );

            throw new IllegalArgumentException(
                    "You can complete this habit in " + countdown(seconds)
            );
        }

        completion.setStatus(COMPLETED);
        completion.setCompletedAt(now);
        habitCompletionRepository.saveAndFlush(completion);

        finishIfDone(step, now);

        return response(step);
    }

    @Transactional
    public Map<String, Object> skipHabit(
            String email,
            Long routineId,
            Long routineHabitId
    ) {
        Step step = getStep(email, routineId, routineHabitId);
        HabitCompletionStatus status = step.status();

        if (status == SKIPPED) {
            return response(step);
        }

        if (status == COMPLETED) {
            throw alreadyDone(status);
        }

        HabitCompletion completion = status == null
                ? newCompletion(step)
                : step.completion();

        completion.setStatus(SKIPPED);
        completion.setCompletedAt(null);
        habitCompletionRepository.saveAndFlush(completion);

        finishIfDone(step, Instant.now());

        return response(step);
    }

    @Transactional
    public Map<String, Object> postponeHabit(
            String email,
            Long routineId,
            Long routineHabitId
    ) {
        Step step = getStep(email, routineId, routineHabitId);
        HabitCompletionStatus status = step.status();

        if (status == POSTPONED) {
            return response(step);
        }

        if (status == COMPLETED || status == SKIPPED) {
            throw alreadyDone(status);
        }

        HabitCompletion completion = status == null
                ? newCompletion(step)
                : step.completion();

        completion.setStatus(POSTPONED);
        completion.setCompletedAt(null);
        habitCompletionRepository.save(completion);

        return response(step);
    }

    // Ends the session. Habit results stay as they are; the routine itself is not touched.
    @Transactional
    public Map<String, Object> stopRoutine(String email, Long routineId) {
        User user = lockUser(email);
        Routine routine = getRoutine(user, routineId);
        List<RoutineHabit> links = getLinks(routineId);

        Optional<RoutineCompletion> active = activeRun(user.getId())
                .filter(run -> run.getRoutine().getId().equals(routineId));

        if (active.isPresent()) {
            RoutineCompletion run = active.get();
            run.setEndedAt(Instant.now());
            routineCompletionRepository.save(run);

            return response(routine, links, run);
        }

        // Pressed again, or the run already ended: show how it ended.
        return latestRun(user.getId(), routineId)
                .map(run -> response(routine, links, run))
                .orElseThrow(() ->
                        new IllegalArgumentException("Start the routine first")
                );
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getActiveRun(String email) {
        User user = getUser(email);
        Optional<RoutineCompletion> active = activeRun(user.getId());

        if (active.isEmpty()) {
            return null;
        }

        RoutineCompletion run = active.get();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("routineId", run.getRoutine().getId());
        result.put("routineTitle", run.getRoutine().getTitle());
        result.put("startedAt", run.getStartedAt());
        result.put("date", run.getCompletedDate());

        return result;
    }

    // Everything one habit action needs, loaded once with the user's row locked.
    private record Step(
            Routine routine,
            RoutineCompletion run,
            List<RoutineHabit> links,
            RoutineHabit target,
            Map<Long, HabitCompletion> records
    ) {
        HabitCompletion completion() {
            return records.get(target.getId());
        }

        HabitCompletionStatus status() {
            HabitCompletion completion = completion();
            return completion == null ? null : completion.currentStatus();
        }
    }

    private Step getStep(String email, Long routineId, Long routineHabitId) {
        User user = lockUser(email);
        Routine routine = getRoutine(user, routineId);
        RoutineCompletion run = getRun(user.getId(), routineId);
        Map<Long, HabitCompletion> records = getRecords(run);
        List<RoutineHabit> links = getLinks(routineId);
        RoutineHabit target = links.get(findIndex(links, routineHabitId));
        List<RoutineHabit> plan = planOf(links, records, run.getCompletedDate());

        if (!plan.contains(target)) {
            throw new IllegalArgumentException(
                    target.isRemoved()
                            ? "This habit was deleted from this routine"
                            : target.isPaused()
                            ? "This habit is paused in this routine"
                            : "This habit is not planned for " + HabitSchedule.fullDayName(run.getCompletedDate())
            );
        }

        return new Step(routine, run, plan, target, records);
    }

    // A run holds the habits planned for its day, in the order of the routine's timeline.
    // Paused and deleted habits sit out new runs; a habit already recorded in a run stays part
    // of that run, even if its schedule changed or it was deleted later, so no result drops out of view.
    private List<RoutineHabit> planOf(
            List<RoutineHabit> links,
            Map<Long, HabitCompletion> records,
            LocalDate day
    ) {
        return links.stream()
                .filter(link -> records.containsKey(link.getId())
                        || (!link.isRemoved() && !link.isPaused()
                        && (day == null || HabitSchedule.plannedOn(link, day))))
                .sorted(HabitSchedule.DAY_ORDER)
                .toList();
    }

    // Habits are taken in order: a new record can only be made for the first habit without one.
    private HabitCompletion newCompletion(Step step) {
        if (habitRunning(step.records())) {
            throw new IllegalArgumentException(
                    "Complete the current habit first"
            );
        }

        if (!step.target().getId().equals(nextLinkId(step.links(), step.records()))) {
            throw new IllegalArgumentException(
                    "Complete the previous habit first"
            );
        }

        HabitCompletion completion = new HabitCompletion();
        completion.setUser(step.run().getUser());
        completion.setHabit(step.target().getHabit());
        completion.setRoutineHabit(step.target());
        completion.setRoutineCompletion(step.run());
        completion.setCompletedDate(step.run().getCompletedDate());

        step.records().put(step.target().getId(), completion);

        return completion;
    }

    // The run closes once every habit is completed or skipped; postponed ones keep it open.
    private void finishIfDone(Step step, Instant now) {
        boolean allResolved = true;
        boolean allCompleted = true;

        for (RoutineHabit link : step.links()) {
            HabitCompletion completion = step.records().get(link.getId());
            HabitCompletionStatus status =
                    completion == null ? null : completion.currentStatus();

            allResolved &= status == COMPLETED || status == SKIPPED;
            allCompleted &= status == COMPLETED;
        }

        if (!allResolved) {
            return;
        }

        if (allCompleted) {
            step.run().setCompletedAt(now);

            step.routine().setCompleted(true);
            routineRepository.save(step.routine());
        } else {
            step.run().setEndedAt(now);
        }

        routineCompletionRepository.save(step.run());
    }

    private Map<String, Object> response(Step step) {
        return response(step.routine(), step.links(), step.run(), step.records());
    }

    private Map<String, Object> response(
            Routine routine,
            List<RoutineHabit> links,
            RoutineCompletion run
    ) {
        return response(routine, links, run, getRecords(run));
    }

    private Map<String, Object> response(
            Routine routine,
            List<RoutineHabit> links,
            RoutineCompletion run,
            Map<Long, HabitCompletion> records
    ) {
        List<RoutineHabit> plan = planOf(links, records, run.getCompletedDate());
        Instant now = Instant.now();

        int completedCount = 0;
        int skippedCount = 0;
        int postponedCount = 0;
        boolean allResolved = true;

        for (RoutineHabit link : plan) {
            HabitCompletion completion = records.get(link.getId());
            HabitCompletionStatus status =
                    completion == null ? null : completion.currentStatus();

            if (status == COMPLETED) {
                completedCount++;
            } else if (status == SKIPPED) {
                skippedCount++;
            } else {
                allResolved = false;

                if (status == POSTPONED) {
                    postponedCount++;
                }
            }
        }

        String runStatus = runStatus(run, now, allResolved);
        boolean open = runStatus.equals("RUNNING");
        boolean running = habitRunning(records);
        Long nextId = nextLinkId(plan, records);

        List<Map<String, Object>> items = new ArrayList<>();

        for (int i = 0; i < plan.size(); i++) {
            RoutineHabit link = plan.get(i);
            Habit habit = link.getHabit();
            HabitCompletion completion = records.get(link.getId());
            HabitCompletionStatus stored =
                    completion == null ? null : completion.currentStatus();

            Instant startedAt =
                    completion == null ? null : completion.getStartedAt();

            Instant completedAt =
                    completion == null ? null : completion.getCompletedAt();

            Instant canCompleteAt = null;
            boolean canStart = false;
            boolean canResume = false;
            boolean canComplete = false;
            boolean canSkip = false;
            boolean canPostpone = false;
            long remainingSeconds = 0;
            String status;

            if (stored == COMPLETED || stored == SKIPPED) {
                status = stored.name();
            } else if (stored == POSTPONED) {
                status = stored.name();
                canResume = open && !running;
                canSkip = open;
            } else if (stored == IN_PROGRESS && !open) {
                status = "NOT_FINISHED";
            } else if (stored == IN_PROGRESS) {
                status = stored.name();
                canSkip = true;
                canPostpone = true;
                canCompleteAt = startedAt.plus(Duration.ofMinutes(
                        value(habit.getDurationMinutes())
                ));

                if (now.isBefore(canCompleteAt)) {
                    remainingSeconds = Math.max(
                            1,
                            (Duration.between(now, canCompleteAt).toMillis() + 999) / 1000
                    );
                } else {
                    canComplete = true;
                }
            } else if (!open) {
                status = "NOT_STARTED";
            } else if (link.getId().equals(nextId) && !running) {
                status = "READY";
                canStart = true;
                canSkip = true;
                canPostpone = true;
            } else {
                status = "LOCKED";
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("habitId", habit.getId());
            item.put("routineHabitId", link.getId());
            item.put("position", i + 1);
            item.put("title", habit.getTitle());
            item.put("description", habit.getDescription());
            item.put("durationMinutes", habit.getDurationMinutes());
            item.put("startTime", link.getStartTime() == null ? null : HabitSchedule.format(link.getStartTime()));
            item.put("repeatDays", HabitSchedule.dayList(link.getRepeatDays()));
            item.put("status", status);
            item.put("canStart", canStart);
            item.put("canResume", canResume);
            item.put("canComplete", canComplete);
            item.put("canSkip", canSkip);
            item.put("canPostpone", canPostpone);
            item.put("remainingSeconds", remainingSeconds);
            item.put("startedAt", startedAt);
            item.put("canCompleteAt", canCompleteAt);
            item.put("completedAt", completedAt);
            // The length the user entered for a completed habit (routine assistant), or null.
            item.put("reportedMinutes", completion == null ? null : completion.getReportedMinutes());

            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("routineId", routine.getId());
        result.put("date", run.getCompletedDate());
        result.put("status", runStatus);
        result.put("startedAt", run.getStartedAt());
        result.put("completedAt", run.getCompletedAt());
        result.put("endedAt", run.getEndedAt());
        result.put("expiresAt", run.getExpiresAt());
        result.put("completedCount", completedCount);
        result.put("skippedCount", skippedCount);
        result.put("postponedCount", postponedCount);
        result.put("totalCount", plan.size());
        result.put(
                "progressPercent",
                plan.isEmpty()
                        ? 0
                        : Math.round(completedCount * 100.0 / plan.size())
        );
        result.put("serverTime", now);
        result.put("habits", items);

        return result;
    }

    private String runStatus(
            RoutineCompletion run,
            Instant now,
            boolean allResolved
    ) {
        if (run.getCompletedAt() != null) {
            return "COMPLETED";
        }

        if (run.getEndedAt() != null) {
            return allResolved ? "FINISHED" : "STOPPED";
        }

        return expired(run, now) ? "EXPIRED" : "RUNNING";
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found")
                );
    }

    private User lockUser(String email) {
        return userRepository.findByEmailForUpdate(email)
                .orElseThrow(() ->
                        new IllegalArgumentException("User not found")
                );
    }

    private Routine getRoutine(User user, Long routineId) {
        return routineRepository
                .findByIdAndUserIdAndDeletedFalse(routineId, user.getId())
                .orElseThrow(() ->
                        new IllegalArgumentException("Routine not found")
                );
    }

    private Optional<RoutineCompletion> activeRun(Long userId) {
        return routineCompletionRepository.findActiveRun(userId);
    }

    private Optional<RoutineCompletion> latestRun(Long userId, Long routineId) {
        return routineCompletionRepository
                .findFirstByUserIdAndRoutineIdAndStartedAtIsNotNullOrderByStartedAtDesc(
                        userId,
                        routineId
                );
    }

    private RoutineCompletion getRun(Long userId, Long routineId) {
        Optional<RoutineCompletion> active = activeRun(userId);

        if (active.isEmpty()) {
            Optional<RoutineCompletion> overdue = latestRun(userId, routineId)
                    .filter(run -> run.getCompletedAt() == null
                            && run.getEndedAt() == null
                            && expired(run, Instant.now()));

            if (overdue.isPresent()) {
                throw new IllegalArgumentException(
                        "This run was for " + overdue.get().getCompletedDate()
                                + " and that day is over. Start the routine again to track today."
                );
            }

            throw new IllegalArgumentException(
                    "Start the routine first"
            );
        }

        RoutineCompletion run = active.get();

        if (!run.getRoutine().getId().equals(routineId)) {
            throw new IllegalArgumentException(
                    "Another routine is already in progress"
            );
        }

        return run;
    }

    private List<RoutineHabit> getLinks(Long routineId) {
        return routineHabitRepository
                .findByRoutineIdOrderByPositionAsc(routineId);
    }

    private int findIndex(List<RoutineHabit> links, Long routineHabitId) {
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i).getId().equals(routineHabitId)) {
                return i;
            }
        }

        throw new IllegalArgumentException(
                "Routine habit not found"
        );
    }

    private Map<Long, HabitCompletion> getRecords(RoutineCompletion run) {
        Map<Long, HabitCompletion> records = new HashMap<>();

        for (HabitCompletion completion
                : habitCompletionRepository.findByRoutineCompletionId(run.getId())) {
            if (completion.getRoutineHabit() != null) {
                records.put(completion.getRoutineHabit().getId(), completion);
            }
        }

        return records;
    }

    private Long nextLinkId(
            List<RoutineHabit> links,
            Map<Long, HabitCompletion> records
    ) {
        return links.stream()
                .map(RoutineHabit::getId)
                .filter(id -> !records.containsKey(id))
                .findFirst()
                .orElse(null);
    }

    private boolean habitRunning(Map<Long, HabitCompletion> records) {
        return records.values().stream()
                .anyMatch(completion -> completion.currentStatus() == IN_PROGRESS);
    }

    private boolean expired(RoutineCompletion run, Instant now) {
        return run.getExpiresAt() != null && !now.isBefore(run.getExpiresAt());
    }

    private IllegalArgumentException alreadyDone(HabitCompletionStatus status) {
        return new IllegalArgumentException(
                "This habit is already " + status.name().toLowerCase()
        );
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private String countdown(long seconds) {
        return String.format(
                "%02d:%02d",
                seconds / 60,
                seconds % 60
        );
    }
}
