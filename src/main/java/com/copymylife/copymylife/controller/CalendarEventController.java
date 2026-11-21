package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.CalendarEventRequest;
import com.copymylife.copymylife.dto.CalendarEventResponse;
import com.copymylife.copymylife.service.CalendarEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/calendar-events")
public class CalendarEventController {

    private final CalendarEventService calendarEventService;

    public CalendarEventController(CalendarEventService calendarEventService) {
        this.calendarEventService = calendarEventService;
    }

    @PostMapping
    public ResponseEntity<CalendarEventResponse> create(Authentication authentication, @RequestBody CalendarEventRequest request) {

        return ResponseEntity.ok(calendarEventService.create(authentication.getName(), request));
    }

    @GetMapping
    public ResponseEntity<List<CalendarEventResponse>> getWeek(Authentication authentication, @RequestParam LocalDate from, @RequestParam LocalDate to) {

        return ResponseEntity.ok(calendarEventService.getWeek(authentication.getName(), from, to));
    }

    // For a repeating event, scope is "occurrence" (with the occurrence's date) or "series".
    @PutMapping("/{id}")
    public ResponseEntity<CalendarEventResponse> update(Authentication authentication, @PathVariable Long id, @RequestParam(required = false) String scope, @RequestParam(required = false) LocalDate date, @RequestBody CalendarEventRequest request) {

        return ResponseEntity.ok(calendarEventService.update(authentication.getName(), id, scope, date, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable Long id, @RequestParam(required = false) String scope, @RequestParam(required = false) LocalDate date) {

        calendarEventService.delete(authentication.getName(), id, scope, date);

        return ResponseEntity.noContent().build();
    }
}