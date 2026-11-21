package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.HabitCreateRequest;
import com.copymylife.copymylife.dto.HabitResponse;
import com.copymylife.copymylife.dto.HabitUpdateRequest;
import com.copymylife.copymylife.service.HabitService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/routines/{routineId}/habits")
public class HabitController {

    private final HabitService habitService;

    public HabitController(HabitService habitService) {
        this.habitService = habitService;
    }

    @PostMapping
    public ResponseEntity<HabitResponse> createHabit(@PathVariable Long routineId, @Valid @RequestBody HabitCreateRequest request, Authentication authentication) {
        String email = authentication.getName();

        Optional<HabitResponse> habit =
                habitService.createHabit(email, routineId, request);

        if (habit.isPresent()) {
            return ResponseEntity.ok(habit.get());
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping
    public ResponseEntity<List<HabitResponse>> getHabits(@PathVariable Long routineId, Authentication authentication) {
        String email = authentication.getName();

        List<HabitResponse> habits =
                habitService.getHabits(email, routineId);

        return ResponseEntity.ok(habits);
    }

    @GetMapping("/{habitId}")
    public ResponseEntity<HabitResponse> getHabitById(@PathVariable Long routineId, @PathVariable Long habitId, Authentication authentication) {
        String email = authentication.getName();

        Optional<HabitResponse> habit =
                habitService.getHabitById(email, routineId, habitId);

        if (habit.isPresent()) {
            return ResponseEntity.ok(habit.get());
        }

        return ResponseEntity.notFound().build();
    }

    @PutMapping("/{habitId}")
    public ResponseEntity<HabitResponse> updateHabit(@PathVariable Long routineId, @PathVariable Long habitId, @RequestBody HabitUpdateRequest request, Authentication authentication) {
        String email = authentication.getName();

        Optional<HabitResponse> updatedHabit =
                habitService.updateHabit(email, routineId, habitId, request);

        if (updatedHabit.isPresent()) {
            return ResponseEntity.ok(updatedHabit.get());
        }

        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Void> deleteHabit(@PathVariable Long routineId, @PathVariable Long habitId, Authentication authentication) {
        String email = authentication.getName();

        boolean deleted =
                habitService.deleteHabit(email, routineId, habitId);

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }
}
