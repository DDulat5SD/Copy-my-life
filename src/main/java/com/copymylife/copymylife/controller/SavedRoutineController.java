package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.service.SavedRoutineService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/saved-routines")
public class SavedRoutineController {

    private final SavedRoutineService savedRoutineService;

    public SavedRoutineController(SavedRoutineService savedRoutineService) {
        this.savedRoutineService = savedRoutineService;
    }

    @PostMapping("/{routineId}")
    public ResponseEntity<RoutineResponse> saveRoutine(@PathVariable Long routineId, Authentication authentication) {

        String email = authentication.getName();

        RoutineResponse routine = savedRoutineService.saveRoutine(email, routineId);

        return ResponseEntity.ok(routine);
    }

    @GetMapping
    public ResponseEntity<List<RoutineResponse>> getSavedRoutines(Authentication authentication) {

        String email = authentication.getName();

        return ResponseEntity.ok(savedRoutineService.getSavedRoutines(email));
    }

    @DeleteMapping("/{routineId}")
    public ResponseEntity<Void> removeSavedRoutine(@PathVariable Long routineId, Authentication authentication) {

        String email = authentication.getName();

        boolean deleted = savedRoutineService.removeSavedRoutine(email, routineId);

        if (deleted) return ResponseEntity.noContent().build();

        return ResponseEntity.notFound().build();
    }

    
}