package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.service.RoutineRunService;
import com.copymylife.copymylife.service.UserTimeZone;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/routine-runs")
public class RoutineRunController {

    private final RoutineRunService routineRunService;
    private final UserTimeZone userTimeZone;

    public RoutineRunController(
            RoutineRunService routineRunService,
            UserTimeZone userTimeZone
    ) {
        this.routineRunService =
                routineRunService;
        this.userTimeZone =
                userTimeZone;
    }

    @PostMapping("/{routineId}/start")
    public ResponseEntity<Map<String, Object>> startRoutine(
            @PathVariable Long routineId,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                routineRunService.startRoutine(
                        authentication.getName(),
                        routineId,
                        userTimeZone.resolve(timeZone)
                )
        );
    }

    @PostMapping("/{routineId}/stop")
    public ResponseEntity<Map<String, Object>> stopRoutine(
            @PathVariable Long routineId,
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                routineRunService.stopRoutine(
                        authentication.getName(),
                        routineId
                )
        );
    }

    @GetMapping("/{routineId}")
    public ResponseEntity<Map<String, Object>> getCurrentRun(
            @PathVariable Long routineId,
            Authentication authentication
    ) {

        Map<String, Object> response =
                routineRunService.getCurrentRun(
                        authentication.getName(),
                        routineId
                );

        if (response == null) {
            return ResponseEntity.noContent()
                    .build();
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{routineId}/routine-habits/{routineHabitId}/start")
    public ResponseEntity<Map<String, Object>> startHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineRunService.startHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId
                )
        );
    }

    @PostMapping("/{routineId}/routine-habits/{routineHabitId}/complete")
    public ResponseEntity<Map<String, Object>> completeHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineRunService.completeHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId
                )
        );
    }

    @PostMapping("/{routineId}/routine-habits/{routineHabitId}/skip")
    public ResponseEntity<Map<String, Object>> skipHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineRunService.skipHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId
                )
        );
    }

    @PostMapping("/{routineId}/routine-habits/{routineHabitId}/postpone")
    public ResponseEntity<Map<String, Object>> postponeHabit(
            @PathVariable Long routineId,
            @PathVariable Long routineHabitId,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                routineRunService.postponeHabit(
                        authentication.getName(),
                        routineId,
                        routineHabitId
                )
        );
    }

    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveRun(
            Authentication authentication
    ) {

        Map<String, Object> response =
                routineRunService.getActiveRun(
                        authentication.getName()
                );

        if (response == null) {
            return ResponseEntity.noContent()
                    .build();
        }

        return ResponseEntity.ok(response);
    }
}