package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.RatingRequest;
import com.copymylife.copymylife.dto.RatingResponse;
import com.copymylife.copymylife.service.RoutineRatingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/routine-ratings")
public class RoutineRatingController {

    private final RoutineRatingService routineRatingService;

    public RoutineRatingController(RoutineRatingService routineRatingService) {
        this.routineRatingService = routineRatingService;
    }

    @PostMapping("/{routineId}")
    public ResponseEntity<RatingResponse> rateRoutine(@PathVariable Long routineId, @RequestBody RatingRequest request, Authentication authentication) {
        return ResponseEntity.ok(
                routineRatingService.rateRoutine(
                        authentication.getName(),
                        routineId,
                        request.getValue()
                )
        );
    }

    @GetMapping("/{routineId}")
    public ResponseEntity<RatingResponse> getRating(@PathVariable Long routineId, Authentication authentication) {
        String email = authentication == null ? null : authentication.getName();
        return ResponseEntity.ok(routineRatingService.getRating(email, routineId));
    }

    
}