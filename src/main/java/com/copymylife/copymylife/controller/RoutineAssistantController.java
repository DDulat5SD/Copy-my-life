package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.assistant.DailySummaryEngine;
import com.copymylife.copymylife.dto.AssistantChangesRequest;
import com.copymylife.copymylife.dto.AssistantPlanRequest;
import com.copymylife.copymylife.dto.AssistantPreferencesRequest;
import com.copymylife.copymylife.dto.AssistantSaveRequest;
import com.copymylife.copymylife.service.RoutineAssistantService;
import com.copymylife.copymylife.service.UserTimeZone;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

// The personal routine assistant, for the signed-in member only (every path needs a token).
// Generating and reviewing only preview; the routine is created or changed by the save and apply
// calls, which the page makes when the user presses Save or Apply.
@RestController
@RequestMapping("/assistant")
public class RoutineAssistantController {

    private final RoutineAssistantService assistant;
    private final UserTimeZone userTimeZone;

    public RoutineAssistantController(RoutineAssistantService assistant, UserTimeZone userTimeZone) {
        this.assistant = assistant;
        this.userTimeZone = userTimeZone;
    }

    @GetMapping("/preferences")
    public ResponseEntity<Map<String, Object>> getPreferences(
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.getPreferences(authentication.getName(), userTimeZone.resolve(timeZone)));
    }

    @PutMapping("/preferences")
    public ResponseEntity<Map<String, Object>> savePreferences(
            @RequestBody(required = false) AssistantPreferencesRequest request,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.savePreferences(authentication.getName(), request, userTimeZone.resolve(timeZone)));
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parse(@RequestBody(required = false) Map<String, String> body,
                                                     Authentication authentication) {
        return ResponseEntity.ok(assistant.parse(authentication.getName(), body == null ? null : body.get("text")));
    }

    @PostMapping("/plans")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestBody(required = false) AssistantPlanRequest request,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.generate(authentication.getName(), request, userTimeZone.resolve(timeZone)));
    }

    @PostMapping("/plans/save")
    public ResponseEntity<Map<String, Object>> save(@RequestBody(required = false) AssistantSaveRequest request,
                                                    Authentication authentication) {
        return ResponseEntity.ok(assistant.saveGenerated(authentication.getName(), request));
    }

    @GetMapping("/routines/{routineId}/review")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable Long routineId,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.review(authentication.getName(), routineId, userTimeZone.resolve(timeZone)));
    }

    @PostMapping("/routines/{routineId}/changes/preview")
    public ResponseEntity<Map<String, Object>> previewChanges(@PathVariable Long routineId,
                                                              @RequestBody(required = false) AssistantChangesRequest request,
                                                              Authentication authentication) {
        return ResponseEntity.ok(assistant.previewChanges(authentication.getName(), routineId, request));
    }

    @PostMapping("/routines/{routineId}/changes/apply")
    public ResponseEntity<Map<String, Object>> applyChanges(@PathVariable Long routineId,
                                                            @RequestBody(required = false) AssistantChangesRequest request,
                                                            Authentication authentication) {
        return ResponseEntity.ok(assistant.applyChanges(authentication.getName(), routineId, request));
    }

    @GetMapping("/insights/durations")
    public ResponseEntity<Map<String, Object>> durations(
            @RequestParam(required = false) Long routineId,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.durationInsights(authentication.getName(), routineId, userTimeZone.resolve(timeZone)));
    }

    // The user keeps the planned length: the suggestion rests until new measured runs come in.
    @PostMapping("/insights/durations/{routineHabitId}/keep")
    public ResponseEntity<Map<String, Object>> keep(
            @PathVariable Long routineHabitId,
            @RequestBody(required = false) Map<String, Integer> body,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.keepDuration(authentication.getName(), routineHabitId,
                body == null ? null : body.get("suggestedMinutes"), userTimeZone.resolve(timeZone)));
    }

    @PutMapping("/runs/{runId}/habits/{routineHabitId}/actual-minutes")
    public ResponseEntity<Map<String, Object>> actualMinutes(
            @PathVariable Long runId,
            @PathVariable Long routineHabitId,
            @RequestBody(required = false) Map<String, Integer> body,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.reportActualMinutes(authentication.getName(), runId, routineHabitId,
                body == null ? null : body.get("minutes")));
    }

    @GetMapping("/summary")
    public ResponseEntity<DailySummaryEngine.Summary> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone,
            Authentication authentication
    ) {
        return ResponseEntity.ok(assistant.summary(authentication.getName(), date, userTimeZone.resolve(timeZone)));
    }
}
