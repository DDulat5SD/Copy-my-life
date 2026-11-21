package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.SavedRoutine;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.SavedRoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class SavedRoutineService {

    private final SavedRoutineRepository savedRoutineRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final RoutineAccessService routineAccessService;

    public SavedRoutineService(SavedRoutineRepository savedRoutineRepository, RoutineRepository routineRepository, UserRepository userRepository, RoutineAccessService routineAccessService) {
        this.savedRoutineRepository = savedRoutineRepository;
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.routineAccessService = routineAccessService;
    }

    public RoutineResponse saveRoutine(String email, Long routineId) {

        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        Routine routine = routineAccessService.requireViewableRoutine(routineId, user);

        if (!routine.isPublic()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only public routines can be saved");
        }

        SavedRoutine existing = savedRoutineRepository.findByUserIdAndRoutineId(user.getId(), routineId).orElse(null);

        if (existing == null) {
            SavedRoutine savedRoutine = new SavedRoutine();
            savedRoutine.setUser(user);
            savedRoutine.setRoutine(routine);
            savedRoutineRepository.save(savedRoutine);
        }

        return toResponse(routine);
    }

    @Transactional(readOnly = true)
    public List<RoutineResponse> getSavedRoutines(String email) {

        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        List<SavedRoutine> savedRoutines = savedRoutineRepository.findByUserId(user.getId());

        List<RoutineResponse> responses = new ArrayList<>();

        for (SavedRoutine savedRoutine : savedRoutines) {
            Routine routine = savedRoutine.getRoutine();

            if (routineAccessService.canViewRoutine(routine, user)) {
                responses.add(toResponse(routine));
            }
        }

        return responses;
    }

    @Transactional
    public boolean removeSavedRoutine(String email, Long routineId) {

        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        long deleted = savedRoutineRepository.deleteByUserIdAndRoutineId(user.getId(), routineId);

        return deleted > 0;
    }

    private RoutineResponse toResponse(Routine routine) {

        RoutineResponse response = new RoutineResponse();

        response.setId(routine.getId());
        response.setTitle(routine.getTitle());
        response.setDescription(routine.getDescription());
        response.setDurationMinutes(routine.getDurationMinutes());
        response.setCategory(routine.getCategory());
        response.setPublic(routine.isPublic());
        response.setImageUrl(routine.getImageUrl());
        response.setCompleted(routine.isCompleted());
        response.setAverageRating(routine.getAverageRating());
        response.setRatingCount(routine.getRatingCount());

        return response;
    }
    
}