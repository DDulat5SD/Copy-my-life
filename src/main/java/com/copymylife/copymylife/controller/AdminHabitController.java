
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.SavedHabitRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/admin-api/habits")
public class AdminHabitController {

    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final SavedHabitRepository savedHabitRepository;

    public AdminHabitController(
            HabitRepository habitRepository,
            UserRepository userRepository,
            RoutineHabitRepository routineHabitRepository,
            HabitCompletionRepository habitCompletionRepository,
            RoutineCompletionRepository routineCompletionRepository,
            SavedHabitRepository savedHabitRepository
    ) {
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.habitCompletionRepository = habitCompletionRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.savedHabitRepository = savedHabitRepository;
    }

    public record AdminHabitResponse(
            Long id,
            String title,
            String description,
            Integer durationMinutes,
            Long ownerId,
            String ownerName,
            String ownerUsername,
            String ownerEmail,
            List<Long> routineIds,
            int linkedOccurrences,
            boolean hasHistory,
            boolean savedByUsers
    ) {}

    public record CreateHabitRequest(
            Long userId,
            String title,
            String description,
            Integer durationMinutes
    ) {}

    public record UpdateHabitRequest(
            String title,
            String description,
            Integer durationMinutes
    ) {}

    private Habit getHabit(Long habitId) {
        return habitRepository.findById(habitId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Habit not found"
                ));
    }

    private void validate(
            String title,
            String description,
            Integer durationMinutes
    ) {
        if (title == null || title.isBlank() || title.length() > 255) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid habit title"
            );
        }

        if (description != null && description.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Description is too long"
            );
        }

        if (durationMinutes == null || durationMinutes <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid habit duration"
            );
        }
    }

    private AdminHabitResponse toResponse(Habit habit) {
        User owner = habit.getUser();

        List<RoutineHabit> links =
                routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habit.getId());

        List<Long> routineIds = links.stream()
                .map(link -> link.getRoutine().getId())
                .distinct()
                .toList();

        return new AdminHabitResponse(
                habit.getId(),
                habit.getTitle(),
                habit.getDescription(),
                habit.getDurationMinutes(),
                owner.getId(),
                owner.getName(),
                owner.getUsername(),
                owner.getEmail(),
                routineIds,
                links.size(),
                habitCompletionRepository.existsByHabitId(habit.getId()),
                savedHabitRepository.existsByHabitId(habit.getId())
        );
    }

    private void ensureNotRunning(
            User owner,
            List<RoutineHabit> links
    ) {
        routineCompletionRepository
                .findActiveRun(
                        owner.getId()
                )
                .ifPresent(run -> {
                    boolean linked = links.stream()
                            .anyMatch(link ->
                                    link.getRoutine().getId()
                                            .equals(run.getRoutine().getId())
                            );

                    if (linked) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot modify a habit in a running routine"
                        );
                    }
                });
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminHabitResponse> getAllHabits() {
        return habitRepository
                .findAll(Sort.by(Sort.Direction.DESC, "id"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{habitId}")
    @Transactional(readOnly = true)
    public AdminHabitResponse getHabitDetails(
            @PathVariable Long habitId
    ) {
        return toResponse(getHabit(habitId));
    }

    @PostMapping
    @Transactional
    public AdminHabitResponse createHabit(
            @RequestBody CreateHabitRequest request
    ) {
        if (request == null || request.userId() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User ID is required"
            );
        }

        validate(
                request.title(),
                request.description(),
                request.durationMinutes()
        );

        User owner = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (!"USER".equals(owner.getRole()) || owner.isBanned()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot create habits for this account"
            );
        }

        Habit habit = new Habit();
        habit.setUser(owner);
        habit.setRoutine(null);
        habit.setTitle(request.title().trim());
        habit.setDescription(request.description());
        habit.setDurationMinutes(request.durationMinutes());

        return toResponse(habitRepository.saveAndFlush(habit));
    }

    @PutMapping("/{habitId}")
    @Transactional
    public AdminHabitResponse updateHabit(
            @PathVariable Long habitId,
            @RequestBody UpdateHabitRequest request
    ) {
        Habit habit = getHabit(habitId);

        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Request body is required"
            );
        }

        validate(
                request.title(),
                request.description(),
                request.durationMinutes()
        );

        List<RoutineHabit> links =
                routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habitId);

        ensureNotRunning(habit.getUser(), links);

        if (habitCompletionRepository.existsByHabitId(habitId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot edit a habit with completion history"
            );
        }

        boolean durationChanged = !Objects.equals(
                habit.getDurationMinutes(),
                request.durationMinutes()
        );

        if (durationChanged && !links.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Remove habit from routines before changing duration"
            );
        }

        habit.setTitle(request.title().trim());
        habit.setDescription(request.description());
        habit.setDurationMinutes(request.durationMinutes());

        return toResponse(habitRepository.saveAndFlush(habit));
    }

    @DeleteMapping("/{habitId}")
    @Transactional
    public void deleteHabit(
            @PathVariable Long habitId
    ) {
        Habit habit = getHabit(habitId);

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

        if (savedHabitRepository.existsByHabitId(habitId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Habit is saved by users"
            );
        }

        habitRepository.delete(habit);
        habitRepository.flush();
    }
}
