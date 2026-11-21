package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The one place that decides who may see a routine. Refusals answer exactly like a routine
 * that does not exist, so a private routine's id reveals nothing.
 */
@Service
public class RoutineAccessService {

    private final RoutineRepository routineRepository;
    private final RoutineHabitRepository routineHabitRepository;

    public RoutineAccessService(
            RoutineRepository routineRepository,
            RoutineHabitRepository routineHabitRepository
    ) {
        this.routineRepository = routineRepository;
        this.routineHabitRepository = routineHabitRepository;
    }

    public boolean canViewRoutine(Routine routine, User viewer) {
        if (routine == null || routine.isDeleted()) {
            return false;
        }

        // US-25: a routine an administrator hid is only its owner's to see. A banned member's
        // routines are closed to everyone else too, as Explore, search and the leaderboard leave
        // them out; nothing is deleted, so unbanning opens them again.
        return (routine.isPublic() && !routine.isHiddenByAdmin() && !hasBannedAuthor(routine))
                || isOwner(routine, viewer);
    }

    private boolean hasBannedAuthor(Routine routine) {
        return !routine.isBuiltIn() && routine.getUser().isBanned();
    }

    public Routine requireViewableRoutine(Long routineId, User viewer) {
        return routineRepository.findById(routineId)
                .filter(routine -> canViewRoutine(routine, viewer))
                .orElseThrow(() -> notFound("Routine not found"));
    }

    public boolean canViewHabit(Habit habit, User viewer) {
        if (viewer != null && habit.getUser().getId().equals(viewer.getId())) {
            return true;
        }

        if (canViewRoutine(habit.getRoutine(), viewer)) {
            return true;
        }

        return routineHabitRepository.findByHabitIdAndRemovedAtIsNull(habit.getId())
                .stream()
                .map(RoutineHabit::getRoutine)
                .anyMatch(routine -> canViewRoutine(routine, viewer));
    }

    private boolean isOwner(Routine routine, User viewer) {
        return viewer != null && routine.getUser().getId().equals(viewer.getId());
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
