package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.RatingResponse;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineRating;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineRatingRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class RoutineRatingService {

    private final RoutineRatingRepository routineRatingRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final RoutineAccessService routineAccessService;

    public RoutineRatingService(RoutineRatingRepository routineRatingRepository, RoutineRepository routineRepository, UserRepository userRepository, RoutineAccessService routineAccessService) {
        this.routineRatingRepository = routineRatingRepository;
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.routineAccessService = routineAccessService;
    }

    public RatingResponse rateRoutine(String email, Long routineId, int value) {

        if (value < 1 || value > 5) {
            throw new RuntimeException("Rating must be between 1 and 5");
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Routine routine = routineAccessService.requireViewableRoutine(routineId, user);

        RoutineRating rating =
                routineRatingRepository.findByUserIdAndRoutineId(user.getId(), routineId)
                        .orElseGet(RoutineRating::new);

        rating.setUser(user);
        rating.setRoutine(routine);
        rating.setValue(value);

        routineRatingRepository.save(rating);

        updateRoutineRating(routine);

        return createResponse(routine, value);
    }

    public RatingResponse getRating(String email, Long routineId) {

        User user = email == null ? null : userRepository.findByEmail(email).orElse(null);

        Routine routine = routineAccessService.requireViewableRoutine(routineId, user);

        int userRating = 0;

        if (user != null) {
            userRating = routineRatingRepository
                    .findByUserIdAndRoutineId(user.getId(), routineId)
                    .map(RoutineRating::getValue)
                    .orElse(0);
        }

        return createResponse(routine, userRating);
    }

    private void updateRoutineRating(Routine routine) {

        double average =
                routineRatingRepository.findAverageByRoutineId(routine.getId());

        long count =
                routineRatingRepository.countByRoutineId(routine.getId());

        routine.setAverageRating(average);
        routine.setRatingCount(count);

        routineRepository.save(routine);
    }

    private RatingResponse createResponse(Routine routine, int userRating) {

        RatingResponse response = new RatingResponse();

        response.setAverageRating(routine.getAverageRating());
        response.setRatingCount(routine.getRatingCount());
        response.setUserRating(userRating);

        return response;
    }
    
}