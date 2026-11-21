package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.ReminderResponse;
import com.copymylife.copymylife.dto.ReminderSettingsResponse;
import com.copymylife.copymylife.service.ReminderService;
import com.copymylife.copymylife.service.UserTimeZone;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

// US-22: the signed-in user's own reminders and their reminder setting. Times are read in the
// zone the browser sends (X-Time-Zone).
@RestController
@RequestMapping("/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final UserTimeZone userTimeZone;

    public ReminderController(ReminderService reminderService, UserTimeZone userTimeZone) {
        this.reminderService = reminderService;
        this.userTimeZone = userTimeZone;
    }

    @GetMapping("/settings")
    public ResponseEntity<ReminderSettingsResponse> getSettings(Authentication authentication) {
        return ResponseEntity.ok(reminderService.getSettings(authentication.getName()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ReminderSettingsResponse> updateSettings(Authentication authentication, @RequestBody(required = false) ReminderSettingsResponse.Request request) {
        return ResponseEntity.ok(reminderService.updateSettings(authentication.getName(), request));
    }

    // A POST: each reminder returned here is recorded as shown and is not returned again.
    @PostMapping("/due")
    public ResponseEntity<List<ReminderResponse>> claimDue(@RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone, Authentication authentication) {
        return ResponseEntity.ok(reminderService.claimDue(authentication.getName(), userTimeZone.resolve(timeZone), Instant.now()));
    }

    @GetMapping("/upcoming")
    public ResponseEntity<List<ReminderResponse>> upcoming(@RequestHeader(value = UserTimeZone.HEADER, required = false) String timeZone, Authentication authentication) {
        return ResponseEntity.ok(reminderService.upcoming(authentication.getName(), userTimeZone.resolve(timeZone), Instant.now()));
    }
}
