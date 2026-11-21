package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.service.LikedRoutineService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/liked-routines")
public class LikedRoutineController {

    private final LikedRoutineService likedRoutineService;

    public LikedRoutineController(LikedRoutineService likedRoutineService) {
        this.likedRoutineService = likedRoutineService;
    }

    @PostMapping("/{routineId}")
    public ResponseEntity<RoutineResponse> likeRoutine(@PathVariable Long routineId, Authentication authentication) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                likedRoutineService.likeRoutine(email, routineId)
        );
    }

    @GetMapping
    public ResponseEntity<List<RoutineResponse>> getLikedRoutines(Authentication authentication) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                likedRoutineService.getLikedRoutines(email)
        );
    }

    @DeleteMapping("/{routineId}")
    public ResponseEntity<Void> removeLikedRoutine(@PathVariable Long routineId, Authentication authentication) {

        String email = authentication.getName();

        boolean deleted =
                likedRoutineService.removeLikedRoutine(
                        email,
                        routineId
                );

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }


}