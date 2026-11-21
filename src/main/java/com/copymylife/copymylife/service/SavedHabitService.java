package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.SavedHabitResponse;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.SavedHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.HabitRepository;
import com.copymylife.copymylife.repository.SavedHabitRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class SavedHabitService {

    private final SavedHabitRepository savedHabitRepository;
    private final HabitRepository habitRepository;
    private final UserRepository userRepository;
    private final RoutineAccessService routineAccessService;

    public SavedHabitService(SavedHabitRepository savedHabitRepository, HabitRepository habitRepository, UserRepository userRepository, RoutineAccessService routineAccessService) {
        this.savedHabitRepository = savedHabitRepository;
        this.habitRepository = habitRepository;
        this.userRepository = userRepository;
        this.routineAccessService = routineAccessService;
    }

    public SavedHabitResponse saveHabit(String email, Long habitId) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Habit habit = habitRepository.findById(habitId)
                .filter(found -> routineAccessService.canViewHabit(found, user))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Habit not found"));

        SavedHabit existing = savedHabitRepository
                .findByUserIdAndHabitId(user.getId(), habitId)
                .orElse(null);

        if (existing == null) {

            SavedHabit savedHabit = new SavedHabit();

            savedHabit.setUser(user);
            savedHabit.setHabit(habit);

            savedHabitRepository.save(savedHabit);
        }

        return toResponse(habit, user);
    }

    public List<SavedHabitResponse> getSavedHabits(String email) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<SavedHabit> savedHabits =
                savedHabitRepository.findByUserIdOrderByIdDesc(user.getId());

        List<SavedHabitResponse> responses = new ArrayList<>();

        for (SavedHabit savedHabit : savedHabits) {
            if (routineAccessService.canViewHabit(savedHabit.getHabit(), user)) {
                responses.add(toResponse(savedHabit.getHabit(), user));
            }
        }

        return responses;
    }

    @Transactional
    public boolean removeSavedHabit(String email, Long habitId) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        long deleted =
                savedHabitRepository.deleteByUserIdAndHabitId(
                        user.getId(),
                        habitId
                );

        return deleted > 0;
    }

    private SavedHabitResponse toResponse(Habit habit, User viewer) {

        Routine routine = habit.getRoutine();

        SavedHabitResponse response = new SavedHabitResponse();

        response.setId(habit.getId());
        response.setTitle(habit.getTitle());
        response.setDescription(habit.getDescription());
        response.setDurationMinutes(habit.getDurationMinutes());

        // Library habits have no routine, and a habit's own routine may be private to its owner.
        if (routineAccessService.canViewRoutine(routine, viewer)) {
            response.setRoutineId(routine.getId());
            response.setRoutineTitle(routine.getTitle());
            response.setCategory(routine.getCategory());
        }

        return response;
    }
    
}