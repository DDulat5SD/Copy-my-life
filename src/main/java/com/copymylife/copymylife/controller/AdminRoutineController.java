
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.RoutineHabitResponse;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/admin-api/routines")
public class AdminRoutineController {

    private final RoutineRepository routineRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final HabitRepository habitRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final AdminContentService adminContentService;

    public AdminRoutineController(
            RoutineRepository routineRepository,
            RoutineHabitRepository routineHabitRepository,
            HabitRepository habitRepository,
            HabitCompletionRepository habitCompletionRepository,
            RoutineCompletionRepository routineCompletionRepository,
            AdminContentService adminContentService
    ) {
        this.routineRepository = routineRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.habitRepository = habitRepository;
        this.habitCompletionRepository = habitCompletionRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.adminContentService = adminContentService;
    }

    public record AdminRoutineResponse(
            Long id,
            String title,
            String description,
            Integer durationMinutes,
            String category,
            boolean publicRoutine,
            boolean builtIn,
            boolean deleted,
            Long ownerId,
            String ownerName,
            String ownerUsername,
            String ownerEmail,
            String ownerAvatarUrl,
            int habitCount,
            int usedMinutes,
            boolean hidden
    ) {}

    // US-25: what an administrator may change about a routine.
    public record AdminRoutineEditRequest(
            String title,
            String description,
            String category
    ) {}

    public record AdminHabitResponse(
            Long id,
            String title,
            String description,
            Integer durationMinutes
    ) {}

    public record AdminHabitCreateRequest(
            String title,
            String description,
            Integer durationMinutes
    ) {}

    private Routine getRoutine(Long routineId) {
        return routineRepository.findById(routineId)
                .filter(routine -> !routine.isDeleted())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Routine not found"
                ));
    }

    private List<RoutineHabit> getLinks(Long routineId) {
        return routineHabitRepository
                .findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
    }

    private int getUsedMinutes(List<RoutineHabit> links) {
        return links.stream()
                .mapToInt(link -> {
                    Integer duration = link.getHabit().getDurationMinutes();
                    return duration == null ? 0 : duration;
                })
                .sum();
    }

    private AdminRoutineResponse toRoutineResponse(Routine routine) {
        User owner = routine.getUser();
        List<RoutineHabit> links = getLinks(routine.getId());

        return new AdminRoutineResponse(
                routine.getId(),
                routine.getTitle(),
                routine.getDescription(),
                routine.getDurationMinutes(),
                routine.getCategory(),
                routine.isPublic(),
                routine.isBuiltIn(),
                routine.isDeleted(),
                owner.getId(),
                owner.getName(),
                owner.getUsername(),
                owner.getEmail(),
                owner.getAvatarUrl(),
                links.size(),
                getUsedMinutes(links),
                routine.isHiddenByAdmin()
        );
    }

    private RoutineHabitResponse toHabitResponse(RoutineHabit link) {
        Habit habit = link.getHabit();

        RoutineHabitResponse response = new RoutineHabitResponse();

        response.setId(link.getId());
        response.setRoutineHabitId(link.getId());
        response.setHabitId(habit.getId());
        response.setTitle(habit.getTitle());
        response.setDescription(habit.getDescription());
        response.setDurationMinutes(habit.getDurationMinutes());
        response.setPosition(link.getPosition());

        return response;
    }

    private List<RoutineHabitResponse> getHabitResponses(Long routineId) {
        return getLinks(routineId)
                .stream()
                .map(this::toHabitResponse)
                .toList();
    }

    private void ensureNotRunning(Routine routine) {
        routineCompletionRepository
                .findActiveRun(
                        routine.getUser().getId()
                )
                .ifPresent(run -> {
                    if (run.getRoutine().getId().equals(routine.getId())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot modify a running routine"
                        );
                    }
                });
    }

    private void validateHabit(Routine routine, Habit habit) {
        Integer duration = habit.getDurationMinutes();

        if (duration == null || duration <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid habit duration"
            );
        }

        int routineMinutes = routine.getDurationMinutes() == null
                ? 0
                : routine.getDurationMinutes();

        int usedMinutes = getUsedMinutes(getLinks(routine.getId()));

        if (usedMinutes + duration > routineMinutes) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + Math.max(0, routineMinutes - usedMinutes)
                            + " minutes remaining"
            );
        }
    }

    private List<RoutineHabitResponse> attachHabit(
            Routine routine,
            Habit habit
    ) {
        validateHabit(routine, habit);

        int nextPosition = routineHabitRepository
                .findMaxPositionByRoutineId(routine.getId()) + 1;

        RoutineHabit link = new RoutineHabit();
        link.setRoutine(routine);
        link.setHabit(habit);
        link.setPosition(nextPosition);

        routineHabitRepository.saveAndFlush(link);

        return getHabitResponses(routine.getId());
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminRoutineResponse> getRoutines() {
        return routineRepository.findByDeletedFalse()
                .stream()
                .map(this::toRoutineResponse)
                .toList();
    }

    @GetMapping("/{routineId}")
    @Transactional(readOnly = true)
    public AdminRoutineResponse getRoutineDetails(
            @PathVariable Long routineId
    ) {
        return toRoutineResponse(getRoutine(routineId));
    }

    @GetMapping("/{routineId}/habits")
    @Transactional(readOnly = true)
    public List<RoutineHabitResponse> getRoutineHabits(
            @PathVariable Long routineId
    ) {
        getRoutine(routineId);
        return getHabitResponses(routineId);
    }

    @GetMapping("/{routineId}/available-habits")
    @Transactional(readOnly = true)
    public List<AdminHabitResponse> getAvailableHabits(
            @PathVariable Long routineId
    ) {
        Routine routine = getRoutine(routineId);

        return habitRepository
                .findByUserIdOrderByIdDesc(routine.getUser().getId())
                .stream()
                .map(habit -> new AdminHabitResponse(
                        habit.getId(),
                        habit.getTitle(),
                        habit.getDescription(),
                        habit.getDurationMinutes()
                ))
                .toList();
    }

    @PostMapping("/{routineId}/habits/{habitId}")
    @Transactional
    public List<RoutineHabitResponse> addExistingHabit(
            @PathVariable Long routineId,
            @PathVariable Long habitId
    ) {
        Routine routine = getRoutine(routineId);
        ensureNotRunning(routine);

        Habit habit = habitRepository
                .findByIdAndUserId(habitId, routine.getUser().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Habit not found in the owner's library"
                ));

        return attachHabit(routine, habit);
    }

    @PostMapping("/{routineId}/habits/create")
    @Transactional
    public List<RoutineHabitResponse> createAndAddHabit(
            @PathVariable Long routineId,
            @RequestBody AdminHabitCreateRequest request
    ) {
        Routine routine = getRoutine(routineId);
        ensureNotRunning(routine);

        if (request == null ||
                request.title() == null ||
                request.title().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Habit title is required"
            );
        }

        if (request.durationMinutes() == null ||
                request.durationMinutes() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid habit duration"
            );
        }

        Habit habit = new Habit();
        habit.setTitle(request.title().trim());
        habit.setDescription(request.description());
        habit.setDurationMinutes(request.durationMinutes());
        habit.setUser(routine.getUser());
        habit.setRoutine(null);

        validateHabit(routine, habit);

        habitRepository.saveAndFlush(habit);

        return attachHabit(routine, habit);
    }

    @DeleteMapping("/{routineId}/habits/{routineHabitId}")
    @Transactional
    public List<RoutineHabitResponse> removeHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId
    ) {
        Routine routine = getRoutine(routineId);
        ensureNotRunning(routine);

        RoutineHabit link = routineHabitRepository
                .findByIdAndRoutineIdAndRemovedAtIsNull(routineHabitId, routineId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Routine habit not found"
                ));

        if (habitCompletionRepository.existsByRoutineHabitId(routineHabitId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This habit occurrence has completion history"
            );
        }

        routineHabitRepository.delete(link);
        routineHabitRepository.flush();

        List<RoutineHabit> remaining = getLinks(routineId);

        if (!remaining.isEmpty()) {
            int offset = routineHabitRepository
                    .findMaxPositionByRoutineId(routineId) + 1;

            for (int i = 0; i < remaining.size(); i++) {
                remaining.get(i).setPosition(offset + i);
            }

            routineHabitRepository.saveAllAndFlush(remaining);

            for (int i = 0; i < remaining.size(); i++) {
                remaining.get(i).setPosition(i);
            }

            routineHabitRepository.saveAllAndFlush(remaining);
        }

        return getHabitResponses(routineId);
    }

    // US-25: the title, description and category, as the public pages show them.
    @PutMapping("/{routineId}")
    @Transactional
    public AdminRoutineResponse editRoutine(
            @PathVariable Long routineId,
            @RequestBody(required = false) AdminRoutineEditRequest request
    ) {
        AdminRoutineEditRequest given = request == null ? new AdminRoutineEditRequest(null, null, null) : request;

        return toRoutineResponse(adminContentService.editRoutine(
                routineId, given.title(), given.description(), given.category()));
    }

    // US-25: hidden routines leave every public view; the owner keeps them.
    @PutMapping("/{routineId}/hide")
    @Transactional
    public AdminRoutineResponse hideRoutine(@PathVariable Long routineId) {
        return toRoutineResponse(adminContentService.setRoutineHidden(routineId, true));
    }

    @PutMapping("/{routineId}/unhide")
    @Transactional
    public AdminRoutineResponse unhideRoutine(@PathVariable Long routineId) {
        return toRoutineResponse(adminContentService.setRoutineHidden(routineId, false));
    }

    @DeleteMapping("/{routineId}")
    @Transactional
    public AdminRoutineResponse deleteRoutine(
            @PathVariable Long routineId
    ) {
        Routine routine = getRoutine(routineId);
        ensureNotRunning(routine);

        routine.setDeleted(true);
        routineRepository.save(routine);

        return toRoutineResponse(routine);
    }
}
