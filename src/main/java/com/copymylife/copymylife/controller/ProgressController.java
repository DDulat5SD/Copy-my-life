package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.ProgressResponse;
import com.copymylife.copymylife.service.ProgressService;
import com.copymylife.copymylife.service.UserTimeZone;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/progress")
public class ProgressController {

    private final ProgressService progressService;
    private final UserTimeZone userTimeZone;

    public ProgressController(ProgressService progressService, UserTimeZone userTimeZone) {
        this.progressService = progressService;
        this.userTimeZone = userTimeZone;
    }

    @GetMapping
    public ResponseEntity<ProgressResponse> getProgress(@RequestParam(defaultValue = "day") String period, @RequestParam(required = false) LocalDate date, @RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone, Authentication authentication) {

        // US-21: today in the user's own zone, for streaks and the days still to come.
        LocalDate today = userTimeZone.today(timeZone, Instant.now());

        if (date == null) {
            date = today;
        }

        return ResponseEntity.ok(
                progressService.getProgress(
                        authentication.getName(),
                        period,
                        date,
                        today
                )
        );
    }

    // US-07: the user's status; /progress carries the same object as "status".
    @GetMapping("/status")
    public ResponseEntity<ProgressResponse.Status> getStatus(Authentication authentication) {
        return ResponseEntity.ok(progressService.getStatus(authentication.getName()));
    }
}