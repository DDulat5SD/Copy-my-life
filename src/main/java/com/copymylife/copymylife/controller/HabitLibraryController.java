
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.HabitCreateRequest;
import com.copymylife.copymylife.dto.HabitResponse;
import com.copymylife.copymylife.dto.HabitUpdateRequest;
import com.copymylife.copymylife.service.HabitLibraryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/habit-library")
public class HabitLibraryController {

    private final HabitLibraryService habitLibraryService;

    public HabitLibraryController(
            HabitLibraryService habitLibraryService
    ) {
        this.habitLibraryService = habitLibraryService;
    }

    @PostMapping
    public ResponseEntity<HabitResponse> create(
            @Valid @RequestBody HabitCreateRequest request,
            Authentication authentication
    ) {
        HabitResponse habit = habitLibraryService.create(
                authentication.getName(),
                request
        );

        return ResponseEntity.ok(habit);
    }

    @GetMapping
    public ResponseEntity<List<HabitResponse>> getAll(
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                habitLibraryService.getAll(authentication.getName())
        );
    }

    @GetMapping("/{habitId}")
    public ResponseEntity<HabitResponse> getById(
            @PathVariable Long habitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                habitLibraryService.getById(
                        authentication.getName(),
                        habitId
                )
        );
    }

    @PutMapping("/{habitId}")
    public ResponseEntity<HabitResponse> update(
            @PathVariable Long habitId,
            @RequestBody HabitUpdateRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                habitLibraryService.update(
                        authentication.getName(),
                        habitId,
                        request
                )
        );
    }

    @DeleteMapping("/{habitId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long habitId,
            Authentication authentication
    ) {
        habitLibraryService.delete(
                authentication.getName(),
                habitId
        );

        return ResponseEntity.noContent().build();
    }
}
