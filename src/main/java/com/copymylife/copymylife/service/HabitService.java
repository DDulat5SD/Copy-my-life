
package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.HabitCreateRequest;
import com.copymylife.copymylife.dto.HabitResponse;
import com.copymylife.copymylife.dto.HabitUpdateRequest;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class HabitService {

    private final HabitRepository habitRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final RoutineAccessService routineAccessService;

    public HabitService(
            HabitRepository habitRepository,
            RoutineRepository routineRepository,
            UserRepository userRepository,
            RoutineCompletionRepository routineCompletionRepository,
            RoutineHabitRepository routineHabitRepository,
            RoutineAccessService routineAccessService
    ) {
        this.habitRepository = habitRepository;
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.routineAccessService = routineAccessService;
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

    // Holds the user's row, so habit edits and routine runs of one user never interleave.
    private User lockUser(String email) {
        return userRepository.findByEmailForUpdate(email)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "User not found"
                        )
                );
    }

    private Optional<Routine> getOwnedRoutine(
            User user,
            Long routineId
    ) {
        return routineRepository.findByIdAndUserIdAndDeletedFalse(
                routineId,
                user.getId()
        );
    }

    private Optional<Habit> getLinkedHabit(
            Long routineId,
            Long habitId
    ) {
        return routineHabitRepository
                .findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId)
                .stream()
                .filter(link ->
                        link.getHabit().getId().equals(habitId)
                )
                .map(RoutineHabit::getHabit)
                .findFirst();
    }

    private List<Habit> getRoutineHabits(Long routineId) {
        return routineHabitRepository
                .findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId)
                .stream()
                .map(RoutineHabit::getHabit)
                .toList();
    }

    private void ensureRoutineNotRunning(
            User user,
            Long routineId
    ) {
        routineCompletionRepository
                .findActiveRun(
                        user.getId()
                )
                .ifPresent(run -> {
                    if (run.getRoutine().getId().equals(routineId)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot modify a running routine"
                        );
                    }
                });
    }

    private void validateHabitDuration(
            Routine routine,
            Long excludedHabitId,
            Integer newDuration
    ) {
        if (newDuration == null || newDuration <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Habit duration must be greater than 0"
            );
        }

        int routineDuration =
                routine.getDurationMinutes() == null
                        ? 0
                        : routine.getDurationMinutes();

        int usedMinutes = getRoutineHabits(routine.getId())
                .stream()
                .filter(habit ->
                        excludedHabitId == null ||
                                !habit.getId().equals(excludedHabitId)
                )
                .mapToInt(habit ->
                        habit.getDurationMinutes() == null
                                ? 0
                                : habit.getDurationMinutes()
                )
                .sum();

        if (usedMinutes + newDuration > routineDuration) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + Math.max(
                            0,
                            routineDuration - usedMinutes
                    ) + " minutes remaining in this routine"
            );
        }
    }

    private HabitResponse toResponse(Habit habit) {
        HabitResponse response = new HabitResponse();

        response.setId(habit.getId());
        response.setTitle(habit.getTitle());
        response.setDescription(habit.getDescription());
        response.setDurationMinutes(habit.getDurationMinutes());
        response.setCompleted(false);

        return response;
    }

    @Transactional
    public Optional<HabitResponse> createHabit(
            String email,
            Long routineId,
            HabitCreateRequest request
    ) {
        User user = lockUser(email);

        Optional<Routine> optionalRoutine =
                getOwnedRoutine(user, routineId);

        if (optionalRoutine.isEmpty()) {
            return Optional.empty();
        }

        Routine routine = optionalRoutine.get();

        ensureRoutineNotRunning(user, routineId);

        HabitRules.validate(
                request.getTitle(),
                request.getDescription(),
                request.getDurationMinutes()
        );

        validateHabitDuration(
                routine,
                null,
                request.getDurationMinutes()
        );

        LocalTime startTime = HabitSchedule.parseStartTime(request.getStartTime());
        String days = HabitSchedule.parseDays(request.getRepeatDays());
        String notes = HabitSchedule.parseNotes(request.getNotes());
        HabitSchedule.requireDaysForTime(startTime, days);
        HabitSchedule.checkOverlaps(
                routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId),
                startTime,
                request.getDurationMinutes(),
                days
        );

        Habit habit = new Habit();
        habit.setUser(user);
        habit.setRoutine(routine);
        habit.setTitle(request.getTitle().trim());
        habit.setDescription(request.getDescription());
        habit.setDurationMinutes(request.getDurationMinutes());

        Habit savedHabit = habitRepository.save(habit);

        RoutineHabit link = new RoutineHabit();
        link.setRoutine(routine);
        link.setHabit(savedHabit);
        link.setPosition(
                routineHabitRepository
                        .findMaxPositionByRoutineId(routineId) + 1
        );
        link.setStartTime(startTime);
        link.setRepeatDays(days);
        link.setNotes(notes);

        routineHabitRepository.save(link);

        return Optional.of(toResponse(savedHabit));
    }

    @Transactional(readOnly = true)
    public List<HabitResponse> getHabits(
            String email,
            Long routineId
    ) {
        User user = getUser(email);

        boolean canView = routineRepository.findById(routineId)
                .filter(routine -> routineAccessService.canViewRoutine(routine, user))
                .isPresent();

        if (!canView) {
            return new ArrayList<>();
        }

        return getRoutineHabits(routineId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<HabitResponse> getHabitById(
            String email,
            Long routineId,
            Long habitId
    ) {
        User user = getUser(email);

        if (getOwnedRoutine(user, routineId).isEmpty()) {
            return Optional.empty();
        }

        return getLinkedHabit(routineId, habitId)
                .map(this::toResponse);
    }

    @Transactional
    public Optional<HabitResponse> updateHabit(
            String email,
            Long routineId,
            Long habitId,
            HabitUpdateRequest request
    ) {
        User user = lockUser(email);

        Optional<Routine> optionalRoutine =
                getOwnedRoutine(user, routineId);

        if (optionalRoutine.isEmpty()) {
            return Optional.empty();
        }

        Optional<Habit> optionalHabit =
                getLinkedHabit(routineId, habitId);

        if (optionalHabit.isEmpty()) {
            return Optional.empty();
        }

        ensureRoutineNotRunning(user, routineId);

        Habit habit = optionalHabit.get();

        if (!habit.getUser().getId().equals(user.getId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You do not own this habit"
            );
        }

        HabitRules.validate(
                request.getTitle(),
                request.getDescription(),
                request.getDurationMinutes()
        );

        List<RoutineHabit> links =
                routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habitId);

        boolean durationChanged =
                !request.getDurationMinutes()
                        .equals(habit.getDurationMinutes());

        if (durationChanged && links.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Habit is used in multiple routines"
            );
        }

        validateHabitDuration(
                optionalRoutine.get(),
                habitId,
                request.getDurationMinutes()
        );

        // A longer habit must still fit between the routine's other timed habits.
        List<RoutineHabit> routineLinks =
                routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);

        for (RoutineHabit own : routineLinks) {
            if (own.getHabit().getId().equals(habitId)) {
                HabitSchedule.checkOverlaps(
                        routineLinks.stream()
                                .filter(other -> !other.getId().equals(own.getId()))
                                .toList(),
                        own.getStartTime(),
                        request.getDurationMinutes(),
                        own.getRepeatDays()
                );
            }
        }

        habit.setTitle(request.getTitle().trim());
        habit.setDescription(request.getDescription());
        habit.setDurationMinutes(request.getDurationMinutes());

        return Optional.of(
                toResponse(habitRepository.save(habit))
        );
    }

    @Transactional
    public Optional<HabitResponse> setCompleted(
            String email,
            Long routineId,
            Long habitId,
            boolean completed
    ) {
        User user = getUser(email);

        if (getOwnedRoutine(user, routineId).isEmpty()) {
            return Optional.empty();
        }

        Optional<Habit> habit =
                getLinkedHabit(routineId, habitId);

        if (habit.isEmpty()) {
            return Optional.empty();
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Use Routine Run to complete habits"
        );
    }

    @Transactional
    public boolean deleteHabit(
            String email,
            Long routineId,
            Long habitId
    ) {
        User user = getUser(email);

        if (getOwnedRoutine(user, routineId).isEmpty()) {
            return false;
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Use RoutineHabit API to remove a specific habit occurrence"
        );
    }
}
