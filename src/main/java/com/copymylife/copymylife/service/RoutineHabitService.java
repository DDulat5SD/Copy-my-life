
package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.HabitCopyRequest;
import com.copymylife.copymylife.dto.RoutineHabitResponse;
import com.copymylife.copymylife.dto.RoutineHabitScheduleRequest;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.RoutineCompletionRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RoutineHabitService {

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final HabitRepository habitRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final HabitCompletionRepository habitCompletionRepository;
    private final RoutineAccessService routineAccessService;

    public RoutineHabitService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            HabitRepository habitRepository,
            RoutineHabitRepository routineHabitRepository,
            RoutineCompletionRepository routineCompletionRepository,
            HabitCompletionRepository habitCompletionRepository,
            RoutineAccessService routineAccessService
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.habitRepository = habitRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.habitCompletionRepository = habitCompletionRepository;
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

    private Routine getRoutine(User user, Long routineId) {
        return routineRepository
                .findByIdAndUserIdAndDeletedFalse(routineId, user.getId())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Routine not found"
                        )
                );
    }

    private Habit getHabit(User user, Long habitId) {
        return habitRepository
                .findByIdAndUserId(habitId, user.getId())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Habit not found"
                        )
                );
    }

    private void ensureNotRunning(User user, Long routineId) {
        routineCompletionRepository
                .findActiveRun(
                        user.getId()
                )
                .ifPresent(run -> {
                    if (run.getRoutine().getId().equals(routineId)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot edit a running routine"
                        );
                    }
                });
    }

    private List<RoutineHabit> getLinks(Long routineId) {
        return routineHabitRepository
                .findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
    }

    private RoutineHabit getLink(Long routineId, Long routineHabitId) {
        return routineHabitRepository
                .findByIdAndRoutineIdAndRemovedAtIsNull(routineHabitId, routineId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Routine habit not found"
                        )
                );
    }

    // Pausing is the owner's own plan; other viewers see the routine as published.
    private RoutineHabitResponse toResponse(RoutineHabit link, boolean owner) {
        Habit habit = link.getHabit();

        RoutineHabitResponse response = new RoutineHabitResponse();

        response.setId(link.getId());
        response.setRoutineHabitId(link.getId());
        response.setHabitId(habit.getId());
        response.setTitle(habit.getTitle());
        response.setDescription(habit.getDescription());
        response.setDurationMinutes(habit.getDurationMinutes());
        response.setPosition(link.getPosition());
        response.setPaused(owner && link.isPaused());
        response.setPausedAt(owner ? link.getPausedAt() : null);

        // The plan is part of the routine, so every viewer of the routine sees it.
        HabitSchedule.describe(link, response);

        return response;
    }

    private List<RoutineHabitResponse> getResponses(Long routineId, boolean owner) {
        return getLinks(routineId)
                .stream()
                .map(link -> toResponse(link, owner))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RoutineHabitResponse> getHabits(
            String email,
            Long routineId
    ) {
        // email is null for a visitor, who sees a public routine as published.
        User user = email == null ? null : getUser(email);

        Routine routine =
                routineAccessService.requireViewableRoutine(routineId, user);

        return getResponses(
                routineId,
                user != null && routine.getUser().getId().equals(user.getId())
        );
    }

    @Transactional
    public List<RoutineHabitResponse> addHabit(
            String email,
            Long routineId,
            Long habitId
    ) {
        User user = lockUser(email);
        Routine routine = getRoutine(user, routineId);
        Habit habit = getHabit(user, habitId);

        ensureNotRunning(user, routineId);

        List<RoutineHabit> links = getLinks(routineId);

        int usedMinutes = links.stream()
                .mapToInt(link -> {
                    Integer duration = link.getHabit().getDurationMinutes();
                    return duration == null ? 0 : duration;
                })
                .sum();

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

        if (usedMinutes + duration > routineMinutes) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + Math.max(0, routineMinutes - usedMinutes)
                            + " minutes remaining in this routine"
            );
        }

        int nextPosition = links.stream()
                .mapToInt(RoutineHabit::getPosition)
                .max()
                .orElse(-1) + 1;

        RoutineHabit link = new RoutineHabit();
        link.setRoutine(routine);
        link.setHabit(habit);
        link.setPosition(nextPosition);

        routineHabitRepository.saveAndFlush(link);

        return getResponses(routineId, true);
    }

    /**
     * US-18: copies one activity of a routine the user may see into one of their own routines.
     * Only that activity comes along, as a new habit of the user's with the same title,
     * description, duration, start time, repeat days and notes, so later edits on either side
     * stay on that side. The target must have room for it and no timed activity sharing its
     * minutes, as when an activity is added there by hand. Everything is checked before
     * anything is written, and nothing is kept when the copy is refused.
     */
    @Transactional
    public List<RoutineHabitResponse> copyHabit(
            String email,
            Long sourceRoutineId,
            Long routineHabitId,
            HabitCopyRequest request
    ) {
        User user = lockUser(email);

        routineAccessService.requireViewableRoutine(sourceRoutineId, user);

        RoutineHabit source = routineHabitRepository
                .findByIdAndRoutineIdAndRemovedAtIsNull(routineHabitId, sourceRoutineId)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Activity not found"
                        )
                );

        Long targetId = request == null ? null : request.getTargetRoutineId();

        if (targetId == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Choose one of your routines to copy this habit into"
            );
        }

        Routine target = routineRepository
                .findByIdAndUserIdAndDeletedFalse(targetId, user.getId())
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Target routine not found"
                        )
                );

        ensureNotRunning(user, targetId);

        Habit habit = source.getHabit();
        LocalTime startTime = source.getStartTime();
        String days;
        String notes;

        // The source was checked when it was saved; data from before those checks is refused here.
        try {
            HabitRules.validate(habit.getTitle(), habit.getDescription(), habit.getDurationMinutes());
            days = HabitSchedule.parseDays(HabitSchedule.dayList(source.getRepeatDays()));
            notes = HabitSchedule.parseNotes(source.getNotes());
            HabitSchedule.requireDaysForTime(startTime, days);
        } catch (ResponseStatusException refusal) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This habit can't be copied: " + refusal.getReason()
            );
        }

        int minutes = habit.getDurationMinutes();
        List<RoutineHabit> links = getLinks(targetId);
        int used = usedMinutes(links, null);
        int routineMinutes = target.getDurationMinutes() == null
                ? 0
                : target.getDurationMinutes();

        if (used + minutes > routineMinutes) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only " + Math.max(0, routineMinutes - used) + " minutes remaining in \""
                            + target.getTitle() + "\", and this habit takes " + minutes + " min"
            );
        }

        HabitSchedule.checkOverlaps(links, startTime, minutes, days);

        Habit clone = new Habit();
        clone.setTitle(habit.getTitle().trim());
        clone.setDescription(habit.getDescription());
        clone.setDurationMinutes(minutes);
        clone.setRoutine(target);
        clone.setUser(user);
        habitRepository.save(clone);

        // Positions are unique per routine, removed rows included, so the copy goes after all of them.
        int lastPosition = routineHabitRepository.findByRoutineIdOrderByPositionAsc(targetId).stream()
                .mapToInt(RoutineHabit::getPosition)
                .max()
                .orElse(-1);

        RoutineHabit link = new RoutineHabit();
        link.setRoutine(target);
        link.setHabit(clone);
        link.setPosition(Math.max(0, lastPosition + 1));
        link.setStartTime(startTime);
        link.setRepeatDays(days);
        link.setNotes(notes);
        routineHabitRepository.saveAndFlush(link);

        return getResponses(targetId, true);
    }

    @Transactional
    public List<RoutineHabitResponse> removeHabit(
            String email,
            Long routineId,
            Long routineHabitId
    ) {
        User user = lockUser(email);
        getRoutine(user, routineId);

        ensureNotRunning(user, routineId);

        RoutineHabit link = getLink(routineId, routineHabitId);

        // A tracked habit leaves the routine but keeps its row, so finished runs and
        // statistics still find what was done. One never tracked is removed outright.
        if (habitCompletionRepository.existsByRoutineHabitId(routineHabitId)) {
            link.setRemovedAt(Instant.now());
            // Positions are unique per routine; a negative one, unique by id, keeps the
            // removed row clear of the 0..n-1 the routine's own habits use.
            link.setPosition(-Math.toIntExact(link.getId()));
            routineHabitRepository.saveAndFlush(link);
        } else {
            routineHabitRepository.delete(link);
            routineHabitRepository.flush();
        }

        List<RoutineHabit> remaining = getLinks(routineId);

        reorderPositions(routineId, remaining);

        return getResponses(routineId, true);
    }

    @Transactional
    public List<RoutineHabitResponse> reorderHabits(
            String email,
            Long routineId,
            List<Long> routineHabitIds
    ) {
        User user = lockUser(email);
        getRoutine(user, routineId);

        ensureNotRunning(user, routineId);

        List<RoutineHabit> links = getLinks(routineId);

        if (routineHabitIds == null ||
                routineHabitIds.contains(null) ||
                routineHabitIds.size() != links.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid routine habit order"
            );
        }

        Set<Long> uniqueIds = new HashSet<>(routineHabitIds);

        if (uniqueIds.size() != routineHabitIds.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Duplicate routine habit IDs"
            );
        }

        Map<Long, RoutineHabit> linkMap = new HashMap<>();

        for (RoutineHabit link : links) {
            linkMap.put(link.getId(), link);
        }

        if (!linkMap.keySet().equals(uniqueIds)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Habit list does not match this routine"
            );
        }

        List<RoutineHabit> ordered = new ArrayList<>();

        for (Long id : routineHabitIds) {
            ordered.add(linkMap.get(id));
        }

        reorderPositions(routineId, ordered);

        return getResponses(routineId, true);
    }

    // Pause and resume only change whether the habit joins new runs; its place and history stay.
    @Transactional
    public List<RoutineHabitResponse> setPaused(
            String email,
            Long routineId,
            Long routineHabitId,
            boolean paused
    ) {
        User user = lockUser(email);
        getRoutine(user, routineId);

        // A run that has started keeps the plan it started with.
        ensureNotRunning(user, routineId);

        RoutineHabit link = getLink(routineId, routineHabitId);

        // Pressing again keeps the first pause time.
        if (link.isPaused() != paused) {
            link.setPausedAt(paused ? Instant.now() : null);
            routineHabitRepository.saveAndFlush(link);
        }

        return getResponses(routineId, true);
    }

    // Sets the habit's start time, repeat days and notes in this routine, and optionally its duration.
    @Transactional
    public List<RoutineHabitResponse> updateSchedule(
            String email,
            Long routineId,
            Long routineHabitId,
            RoutineHabitScheduleRequest request
    ) {
        User user = lockUser(email);
        Routine routine = getRoutine(user, routineId);

        ensureNotRunning(user, routineId);

        RoutineHabit link = getLink(routineId, routineHabitId);
        Habit habit = link.getHabit();

        LocalTime startTime = HabitSchedule.parseStartTime(request.getStartTime());
        String days = HabitSchedule.parseDays(request.getRepeatDays());
        String notes = HabitSchedule.parseNotes(request.getNotes());
        HabitSchedule.requireDaysForTime(startTime, days);

        List<RoutineHabit> links = getLinks(routineId);
        int duration = habit.getDurationMinutes() == null ? 0 : habit.getDurationMinutes();
        Integer requested = request.getDurationMinutes();

        if (requested != null && requested != duration) {
            HabitRules.validateDuration(requested);

            // The duration belongs to the habit, so it can only change where the habit is used once.
            if (routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habit.getId()).size() > 1) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Habit is used in multiple routines"
                );
            }

            int others = usedMinutes(links, link.getId());
            int routineMinutes = routine.getDurationMinutes() == null
                    ? 0
                    : routine.getDurationMinutes();

            if (others + requested > routineMinutes) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Only " + Math.max(0, routineMinutes - others)
                                + " minutes remaining in this routine"
                );
            }

            duration = requested;
        }

        HabitSchedule.checkOverlaps(
                links.stream()
                        .filter(other -> !other.getId().equals(link.getId()))
                        .toList(),
                startTime,
                duration,
                days
        );

        if (!Integer.valueOf(duration).equals(habit.getDurationMinutes())) {
            habit.setDurationMinutes(duration);
            habitRepository.save(habit);
        }

        link.setStartTime(startTime);
        link.setRepeatDays(days);
        link.setNotes(notes);
        routineHabitRepository.saveAndFlush(link);

        return getResponses(routineId, true);
    }

    private int usedMinutes(List<RoutineHabit> links, Long excludedLinkId) {
        return links.stream()
                .filter(link -> !link.getId().equals(excludedLinkId))
                .mapToInt(link -> {
                    Integer minutes = link.getHabit().getDurationMinutes();
                    return minutes == null ? 0 : minutes;
                })
                .sum();
    }

    private void reorderPositions(
            Long routineId,
            List<RoutineHabit> links
    ) {
        if (links.isEmpty()) {
            return;
        }

        int offset = routineHabitRepository
                .findMaxPositionByRoutineId(routineId) + 1;

        for (int i = 0; i < links.size(); i++) {
            links.get(i).setPosition(offset + i);
        }

        routineHabitRepository.saveAllAndFlush(links);

        for (int i = 0; i < links.size(); i++) {
            links.get(i).setPosition(i);
        }

        routineHabitRepository.saveAllAndFlush(links);
    }
}
