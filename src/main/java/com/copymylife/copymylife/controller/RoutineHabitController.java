
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.HabitCopyRequest;
import com.copymylife.copymylife.dto.RoutineHabitOrderRequest;
import com.copymylife.copymylife.dto.RoutineHabitResponse;
import com.copymylife.copymylife.dto.RoutineHabitScheduleRequest;
import com.copymylife.copymylife.service.RoutineHabitService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/routines/{routineId}/routine-habits")
public class RoutineHabitController {

    private final RoutineHabitService routineHabitService;

    public RoutineHabitController(
            RoutineHabitService routineHabitService
    ) {
        this.routineHabitService = routineHabitService;
    }

    @GetMapping
    public ResponseEntity<List<RoutineHabitResponse>> getHabits(
            @PathVariable Long routineId,
            Authentication authentication
    ) {
        // A visitor has no authentication; only a public routine's habits are returned to them.
        return ResponseEntity.ok(
                routineHabitService.getHabits(
                        authentication == null ? null : authentication.getName(),
                        routineId
                )
        );
    }

    @PostMapping("/{habitId}")
    public ResponseEntity<List<RoutineHabitResponse>> addHabit(
            @PathVariable Long routineId,
            @PathVariable Long habitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.addHabit(
                        authentication.getName(),
                        routineId,
                        habitId
                )
        );
    }

    // US-18: copies this one activity into one of the caller's own routines.
    @PostMapping("/{routineHabitId}/copy")
    public ResponseEntity<List<RoutineHabitResponse>> copyHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            @RequestBody(required = false) HabitCopyRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.copyHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId,
                        request
                )
        );
    }

    @DeleteMapping("/{routineHabitId}")
    public ResponseEntity<List<RoutineHabitResponse>> removeHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.removeHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId
                )
        );
    }

    @PutMapping("/{routineHabitId}")
    public ResponseEntity<List<RoutineHabitResponse>> updateSchedule(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            @RequestBody RoutineHabitScheduleRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.updateSchedule(
                        authentication.getName(),
                        routineId,
                        routineHabitId,
                        request
                )
        );
    }

    @PostMapping("/{routineHabitId}/pause")
    public ResponseEntity<List<RoutineHabitResponse>> pauseHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.setPaused(
                        authentication.getName(),
                        routineId,
                        routineHabitId,
                        true
                )
        );
    }

    @PostMapping("/{routineHabitId}/resume")
    public ResponseEntity<List<RoutineHabitResponse>> resumeHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.setPaused(
                        authentication.getName(),
                        routineId,
                        routineHabitId,
                        false
                )
        );
    }

    @PutMapping("/order")
    public ResponseEntity<List<RoutineHabitResponse>> reorderHabits(
            @PathVariable Long routineId,
            @RequestBody RoutineHabitOrderRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineHabitService.reorderHabits(
                        authentication.getName(),
                        routineId,
                        request.getHabitIds()
                )
        );
    }
}
