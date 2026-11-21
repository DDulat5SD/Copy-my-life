
package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.HabitCreateRequest;
import com.copymylife.copymylife.dto.HabitResponse;
import com.copymylife.copymylife.dto.HabitUpdateRequest;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HabitLibraryService {

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final RoutineCompletionRepository routineCompletionRepository;

    public HabitLibraryService(
            HabitRepository habitRepository,
            UserRepository userRepository,
            RoutineHabitRepository routineHabitRepository,
            HabitCompletionRepository habitCompletionRepository,
            RoutineCompletionRepository routineCompletionRepository
    ) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.habitCompletionRepository = habitCompletionRepository;
        this.routineCompletionRepository = routineCompletionRepository;
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "User not found"
                        )
                );
    }

    private Habit getOwnedHabit(User user, Long habitId) {
        return habitRepository.findByIdAndUserId(
                habitId,
                user.getId()
        ).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Habit not found"
                )
        );
    }

    // Habit id -> number of the user's routines where it is paused.
    private Map<Long, Integer> pausedRoutineCounts(User user) {
        Map<Long, Integer> counts = new HashMap<>();

        for (Object[] row : routineHabitRepository.countPausedRoutinesByHabit(user.getId())) {
            counts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        return counts;
    }

    private void ensureNotRunning(User user, List<RoutineHabit> links) {
        routineCompletionRepository
                .findActiveRun(
                        user.getId()
                )
                .ifPresent(run -> {
                    boolean linked = links.stream().anyMatch(
                            link -> link.getRoutine().getId()
                                    .equals(run.getRoutine().getId())
                    );

                    if (linked) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Habit belongs to a running routine"
                        );
                    }
                });
    }

    private HabitResponse toResponse(Habit habit, Map<Long, Integer> pausedCounts) {
        HabitResponse response = new HabitResponse();

        response.setId(habit.getId());
        response.setTitle(habit.getTitle());
        response.setDescription(habit.getDescription());
        response.setDurationMinutes(habit.getDurationMinutes());
        response.setCompleted(false);
        response.setPausedRoutineCount(pausedCounts.getOrDefault(habit.getId(), 0));

        return response;
    }

    @Transactional
    public HabitResponse create(
            String email,
            HabitCreateRequest request
    ) {
        HabitRules.validate(
                request.getTitle(),
                request.getDescription(),
                request.getDurationMinutes()
        );

        User user = getUser(email);

        Habit habit = new Habit();
        habit.setUser(user);
        habit.setRoutine(null);
        habit.setTitle(request.getTitle().trim());
        habit.setDescription(request.getDescription());
        habit.setDurationMinutes(request.getDurationMinutes());

        return toResponse(habitRepository.save(habit), Map.of());
    }

    @Transactional(readOnly = true)
    public List<HabitResponse> getAll(String email) {
        User user = getUser(email);
        Map<Long, Integer> pausedCounts = pausedRoutineCounts(user);

        return habitRepository
                .findByUserIdOrderByIdDesc(user.getId())
                .stream()
                .map(habit -> toResponse(habit, pausedCounts))
                .toList();
    }

    @Transactional(readOnly = true)
    public HabitResponse getById(String email, Long habitId) {
        User user = getUser(email);
        return toResponse(getOwnedHabit(user, habitId), pausedRoutineCounts(user));
    }

    @Transactional
    public HabitResponse update(
            String email,
            Long habitId,
            HabitUpdateRequest request
    ) {
        HabitRules.validate(
                request.getTitle(),
                request.getDescription(),
                request.getDurationMinutes()
        );

        User user = getUser(email);
        Habit habit = getOwnedHabit(user, habitId);

        List<RoutineHabit> links =
                routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habitId);

        ensureNotRunning(user, links);

        boolean durationChanged =
                !request.getDurationMinutes()
                        .equals(habit.getDurationMinutes());

        if (durationChanged && !links.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remove habit from routines before changing duration"
            );
        }

        habit.setTitle(request.getTitle().trim());
        habit.setDescription(request.getDescription());
        habit.setDurationMinutes(request.getDurationMinutes());

        return toResponse(habitRepository.save(habit), pausedRoutineCounts(user));
    }

    @Transactional
    public void delete(String email, Long habitId) {
        User user = getUser(email);
        Habit habit = getOwnedHabit(user, habitId);

        if (routineHabitRepository.existsByHabitIdAndRemovedAtIsNull(habitId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remove habit from routines first"
            );
        }

        if (habitCompletionRepository.existsByHabitId(habitId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot delete a habit with completion history"
            );
        }

        habitRepository.delete(habit);
    }
}
