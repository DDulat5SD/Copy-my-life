
package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ProgressItemResponse;
import com.copymylife.copymylife.dto.ProgressResponse;
import com.copymylife.copymylife.model.*;
import com.copymylife.copymylife.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class ProgressService {

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final HabitRepository habitRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final ProgressStatusService progressStatusService;

    public ProgressService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            HabitRepository habitRepository,
            RoutineHabitRepository routineHabitRepository,
            RoutineCompletionRepository routineCompletionRepository,
            HabitCompletionRepository habitCompletionRepository,
            ProgressStatusService progressStatusService
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.habitRepository = habitRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.habitCompletionRepository = habitCompletionRepository;
        this.progressStatusService = progressStatusService;
    }

    // The period's completion percentages, and (US-21) the user's streaks as of today and the
    // week of the chosen day. today is the user's day in their own time zone.
    @Transactional(readOnly = true)
    public ProgressResponse getProgress(
            String email,
            String period,
            LocalDate date,
            LocalDate today
    ) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        LocalDate selectedDate = date == null
                ? today
                : date;

        LocalDate[] range = getRange(period, selectedDate);

        LocalDate from = range[0];
        LocalDate to = range[1];

        List<Routine> routines =
                routineRepository.findByUserIdAndDeletedFalse(user.getId());

        List<Habit> habits =
                habitRepository.findByUserIdOrderByIdDesc(user.getId());

        List<RoutineCompletion> routineCompletions =
                routineCompletionRepository.findByUserIdAndCompletedDateBetween(
                        user.getId(),
                        from,
                        to
                );

        List<HabitCompletion> habitCompletions =
                habitCompletionRepository.findByUserIdAndCompletedDateBetween(
                        user.getId(),
                        from,
                        to
                );

        Set<Long> completedRoutineIds = new HashSet<>();

        for (RoutineCompletion completion : routineCompletions) {
            if (completion.getCompletedAt() != null) {
                completedRoutineIds.add(
                        completion.getRoutine().getId()
                );
            }
        }

        Set<Long> completedHabitIds = new HashSet<>();

        for (HabitCompletion completion : habitCompletions) {
            if (completion.getCompletedAt() != null) {
                completedHabitIds.add(
                        completion.getHabit().getId()
                );
            }
        }

        List<ProgressItemResponse> routineProgress =
                buildRoutineProgress(
                        routines,
                        completedRoutineIds
                );

        List<ProgressItemResponse> habitProgress =
                buildHabitProgress(
                        habits,
                        completedHabitIds
                );

        ProgressResponse response = new ProgressResponse(
                period,
                from,
                to,
                routineProgress,
                habitProgress
        );

        ProgressHistory history = history(user, today, selectedDate);

        response.setToday(today);
        response.setHasTracking(history.hasTracking());
        response.setStreak(history.streak());
        response.setHabitStreaks(history.habitStreaks());
        response.setWeek(history.week(selectedDate));
        response.setStatus(progressStatusService.statusOf(user.getId()));

        return response;
    }

    // US-07: the status alone, the same as in getProgress.
    @Transactional(readOnly = true)
    public ProgressResponse.Status getStatus(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return progressStatusService.statusOf(user.getId());
    }

    // US-21: the user's own records up to the later of today and the chosen week's end.
    private ProgressHistory history(User user, LocalDate today, LocalDate selectedDate) {
        LocalDate weekEnd = selectedDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        LocalDate until = weekEnd.isAfter(today) ? weekEnd : today;

        List<ProgressHistory.Mark> marks = new ArrayList<>();

        for (HabitCompletion completion
                : habitCompletionRepository.findHistory(user.getId(), until)) {
            marks.add(new ProgressHistory.Mark(
                    completion.getHabit().getId(),
                    completion.getHabit().getTitle(),
                    completion.getCompletedDate(),
                    completion.currentStatus()
            ));
        }

        return new ProgressHistory(marks, today);
    }

    private List<ProgressItemResponse> buildRoutineProgress(
            List<Routine> routines,
            Set<Long> completedIds
    ) {
        List<ProgressItemResponse> result = new ArrayList<>();

        int completed = 0;

        for (Routine routine : routines) {
            if (completedIds.contains(routine.getId())) {
                completed++;
            }
        }

        result.add(
                new ProgressItemResponse(
                        "Completed routines",
                        completed,
                        routines.size()
                )
        );

        Map<String, List<Routine>> categories =
                new LinkedHashMap<>();

        for (Routine routine : routines) {
            String category = normalizeCategory(
                    routine.getCategory()
            );

            categories.computeIfAbsent(
                    category,
                    key -> new ArrayList<>()
            ).add(routine);
        }

        for (Map.Entry<String, List<Routine>> entry
                : categories.entrySet()) {

            int categoryCompleted = 0;

            for (Routine routine : entry.getValue()) {
                if (completedIds.contains(routine.getId())) {
                    categoryCompleted++;
                }
            }

            result.add(
                    new ProgressItemResponse(
                            entry.getKey(),
                            categoryCompleted,
                            entry.getValue().size()
                    )
            );
        }

        return result;
    }

    private List<ProgressItemResponse> buildHabitProgress(
            List<Habit> habits,
            Set<Long> completedIds
    ) {
        List<ProgressItemResponse> result = new ArrayList<>();

        Map<Long, List<RoutineHabit>> linksByHabit = new HashMap<>();
        List<Habit> counted = new ArrayList<>();

        // A habit paused or deleted in every routine it belongs to, or whose routines were all
        // deleted, is not expected, unless it was done anyway (US-21: deleted ones used to be
        // expected for ever).
        for (Habit habit : habits) {
            List<RoutineHabit> links =
                    routineHabitRepository.findByHabitId(
                            habit.getId()
                    );

            linksByHabit.put(habit.getId(), links);

            if (completedIds.contains(habit.getId()) || !pausedEverywhere(links)) {
                counted.add(habit);
            }
        }

        int completed = 0;

        for (Habit habit : counted) {
            if (completedIds.contains(habit.getId())) {
                completed++;
            }
        }

        result.add(
                new ProgressItemResponse(
                        "Completed habits",
                        completed,
                        counted.size()
                )
        );

        Map<String, Set<Long>> categories =
                new LinkedHashMap<>();

        for (Habit habit : counted) {
            List<RoutineHabit> links = linksByHabit.get(habit.getId());
            List<RoutineHabit> active = links.stream()
                    .filter(link -> !link.isPaused())
                    .toList();

            Set<String> habitCategories = new HashSet<>();

            for (RoutineHabit link : active.isEmpty() ? links : active) {
                Routine routine = link.getRoutine();

                if (routine == null) {
                    continue;
                }

                String category = normalizeCategory(
                        routine.getCategory()
                );

                habitCategories.add(category);
            }

            if (habitCategories.isEmpty()) {
                if (habit.getRoutine() != null) {
                    habitCategories.add(
                            normalizeCategory(
                                    habit.getRoutine().getCategory()
                            )
                    );
                } else {
                    habitCategories.add("General");
                }
            }

            for (String category : habitCategories) {
                categories.computeIfAbsent(
                        category,
                        key -> new LinkedHashSet<>()
                ).add(habit.getId());
            }
        }

        for (Map.Entry<String, Set<Long>> entry
                : categories.entrySet()) {

            int categoryCompleted = 0;

            for (Long habitId : entry.getValue()) {
                if (completedIds.contains(habitId)) {
                    categoryCompleted++;
                }
            }

            result.add(
                    new ProgressItemResponse(
                            entry.getKey(),
                            categoryCompleted,
                            entry.getValue().size()
                    )
            );
        }

        return result;
    }

    private boolean pausedEverywhere(List<RoutineHabit> links) {
        List<RoutineHabit> live = links.stream()
                .filter(link -> link.getRoutine() != null
                        && !link.getRoutine().isDeleted())
                .toList();

        // Only in deleted routines: as gone as a habit deleted from them (habits never linked stay).
        if (live.isEmpty()) {
            return !links.isEmpty();
        }

        return live.stream().allMatch(link -> link.isPaused() || link.isRemoved());
    }

    private String normalizeCategory(String category) {
        return category == null || category.isBlank()
                ? "General"
                : category;
    }

    private LocalDate[] getRange(
            String period,
            LocalDate date
    ) {
        String value = period == null
                ? "day"
                : period.toLowerCase();

        if (value.equals("week")) {
            LocalDate from = date.with(
                    TemporalAdjusters.previousOrSame(
                            DayOfWeek.MONDAY
                    )
            );

            return new LocalDate[]{
                    from,
                    from.plusDays(6)
            };
        }

        if (value.equals("month")) {
            LocalDate from = date.withDayOfMonth(1);

            return new LocalDate[]{
                    from,
                    date.withDayOfMonth(date.lengthOfMonth())
            };
        }

        if (value.equals("year")) {
            return new LocalDate[]{
                    date.withDayOfYear(1),
                    date.withDayOfYear(date.lengthOfYear())
            };
        }

        return new LocalDate[]{
                date,
                date
        };
    }
}
