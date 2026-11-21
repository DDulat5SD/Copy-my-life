package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.AdaptedCopyRequest;
import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.service.RoutineService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/routines")
public class RoutineController {

    private final RoutineService routineService;

    public RoutineController(RoutineService routineService) {
        this.routineService = routineService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RoutineResponse> createRoutine(@RequestParam String title, @RequestParam String description, @RequestParam String category, @RequestParam Integer durationMinutes, @RequestParam(defaultValue = "false") boolean isPublic, @RequestParam(required = false) String imageUrl, @RequestPart(value = "image", required = false) MultipartFile image, Authentication authentication) {

        String email = authentication.getName();

        RoutineResponse routine = routineService.createRoutine(email, title, description, category, durationMinutes, isPublic, imageUrl, image);

        return ResponseEntity.ok(routine);
    }

    @GetMapping
    public ResponseEntity<List<RoutineResponse>> getMyRoutines(Authentication authentication) {

        String email = authentication.getName();

        List<RoutineResponse> routines = routineService.getMyRoutines(email);

        return ResponseEntity.ok(routines);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoutineResponse> getRoutineById(@PathVariable Long id, Authentication authentication) {

        // A visitor has no authentication; only a public routine is returned to them.
        String email = authentication == null ? null : authentication.getName();

        Optional<RoutineResponse> routine = routineService.getRoutineById(email, id);

        if (routine.isPresent()) {
            return ResponseEntity.ok(routine.get());
        }

        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/copy")
    public ResponseEntity<RoutineResponse> copyRoutine(@PathVariable Long id, Authentication authentication) {

        return ResponseEntity.ok(routineService.copyRoutine(authentication.getName(), id));
    }

    // US-19: a copy whose activities were adapted before saving.
    @PostMapping("/{id}/adapted-copy")
    public ResponseEntity<RoutineResponse> copyAdaptedRoutine(@PathVariable Long id, @RequestBody(required = false) AdaptedCopyRequest request, Authentication authentication) {
        return ResponseEntity.ok(routineService.copyAdaptedRoutine(authentication.getName(), id, request));
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<RoutineResponse> updateRoutine(@PathVariable Long id, @RequestParam String title, @RequestParam String description, @RequestParam String category, @RequestParam Integer durationMinutes, @RequestParam(defaultValue = "false") boolean isPublic, @RequestParam(required = false) String imageUrl, @RequestPart(value = "image", required = false) MultipartFile image, Authentication authentication) {

        String email = authentication.getName();

        Optional<RoutineResponse> updatedRoutine = routineService.updateRoutine(email, id, title, description, category, durationMinutes, isPublic, imageUrl, image);

        if (updatedRoutine.isPresent()) {
            return ResponseEntity.ok(updatedRoutine.get());
        }

        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRoutine(@PathVariable Long id, Authentication authentication) {

        String email = authentication.getName();

        boolean deleted = routineService.deleteRoutine(email, id);

        if (deleted) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping("/public")
    public ResponseEntity<List<RoutineResponse>> getPublicRoutines() {List<RoutineResponse> routines = routineService.getPublicRoutines();

        return ResponseEntity.ok(routines);
    }

    @GetMapping("/built-in")
    public ResponseEntity<List<RoutineResponse>> getBuiltInRoutines() {
        return ResponseEntity.ok(routineService.getBuiltInRoutines());
    }

    // US-05: open to visitors; a token changes nothing, as only public content is searched.
    @GetMapping("/search")
    public ResponseEntity<List<Long>> searchRoutines(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String category
    ) {
        return ResponseEntity.ok(routineService.searchRoutineIds(q, category));
    }
    
}