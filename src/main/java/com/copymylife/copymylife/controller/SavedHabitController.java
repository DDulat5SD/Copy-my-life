package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.SavedHabitResponse;
import com.copymylife.copymylife.service.SavedHabitService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/saved-habits")
public class SavedHabitController {

    private final SavedHabitService savedHabitService;

    public SavedHabitController(SavedHabitService savedHabitService) {
        this.savedHabitService = savedHabitService;
    }

    @PostMapping("/{habitId}")
    public ResponseEntity<SavedHabitResponse> saveHabit(@PathVariable Long habitId, Authentication authentication) {
        return ResponseEntity.ok(savedHabitService.saveHabit(authentication.getName(), habitId));
    }

    @GetMapping
    public ResponseEntity<List<SavedHabitResponse>> getSavedHabits(Authentication authentication) {
        return ResponseEntity.ok(savedHabitService.getSavedHabits(authentication.getName()));
    }

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Void> removeSavedHabit(@PathVariable Long habitId, Authentication authentication) {

        boolean deleted =
                savedHabitService.removeSavedHabit(
                        authentication.getName(),
                        habitId
                );

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }
    
}