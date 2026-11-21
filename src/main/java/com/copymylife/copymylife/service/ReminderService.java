package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ReminderResponse;
import com.copymylife.copymylife.dto.ReminderSettingsResponse;
import com.copymylife.copymylife.model.*;
import com.copymylife.copymylife.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

// US-22: reminders for the user's own scheduled activities, shown in the app. The browser asks
// for due reminders every minute while a page is open; each occurrence is handed out once
// (ReminderDeliveryRepository.claim), and nothing is handed out while reminders are off.
// Activities are the timed habits of the user's routines and the routines on their calendar,
// read fresh on every request: a paused, deleted or rescheduled activity, a cancelled calendar
// day or a deleted routine simply has no occurrence any more.
@Service
public class ReminderService {

    // Deliveries are kept a few days, long enough for any window around midnight in any zone.
    private static final int KEEP_DAYS = 3;

    private final UserRepository userRepository;
    private final ReminderSettingsRepository settingsRepository;
    private final ReminderDeliveryRepository deliveryRepository;
    private final RoutineHabitRepository routineHabitRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final CalendarEventExceptionRepository exceptionRepository;

    public ReminderService(
            UserRepository userRepository,
            ReminderSettingsRepository settingsRepository,
            ReminderDeliveryRepository deliveryRepository,
            RoutineHabitRepository routineHabitRepository,
            CalendarEventRepository calendarEventRepository,
            CalendarEventExceptionRepository exceptionRepository
    ) {
        this.userRepository = userRepository;
        this.settingsRepository = settingsRepository;
        this.deliveryRepository = deliveryRepository;
        this.routineHabitRepository = routineHabitRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.exceptionRepository = exceptionRepository;
    }

    @Transactional(readOnly = true)
    public ReminderSettingsResponse getSettings(String email) {
        return toResponse(settingsRepository.findByUserId(getUser(email).getId()).orElse(null));
    }

    @Transactional
    public ReminderSettingsResponse updateSettings(String email, ReminderSettingsResponse.Request request) {
        if (request == null || request.enabled() == null) {
            throw new IllegalArgumentException("Choose whether reminders are on or off");
        }

        if (request.leadMinutes() != null && !ReminderSchedule.LEAD_OPTIONS.contains(request.leadMinutes())) {
            throw new IllegalArgumentException("Choose to be reminded 5, 10, 15, 30 or 60 minutes before");
        }

        // The user's row is held, so two saves at once never make two settings rows.
        User user = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        ReminderSettings settings = settingsRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    ReminderSettings created = new ReminderSettings(user);
                    created.setLeadMinutes(ReminderSchedule.DEFAULT_LEAD_MINUTES);
                    return created;
                });

        settings.setEnabled(request.enabled());

        if (request.leadMinutes() != null) {
            settings.setLeadMinutes(request.leadMinutes());
        }

        settings.setUpdatedAt(Instant.now());

        return toResponse(settingsRepository.save(settings));
    }

    // The reminders due now that were not shown yet; each is recorded as shown. None while off.
    @Transactional
    public List<ReminderResponse> claimDue(String email, ZoneId zone, Instant now) {
        User user = getUser(email);
        ReminderSettingsResponse settings = toResponse(settingsRepository.findByUserId(user.getId()).orElse(null));

        if (!settings.enabled()) {
            return List.of();
        }

        LocalDate today = LocalDate.ofInstant(now, zone);
        deliveryRepository.deleteOlder(user.getId(), today.minusDays(KEEP_DAYS));

        List<ReminderResponse> result = new ArrayList<>();

        for (ReminderSchedule.Slot slot : slots(user, ReminderSchedule.daysAround(zone, now))) {
            if (ReminderSchedule.due(slot, zone, now, settings.leadMinutes())
                    && deliveryRepository.claim(user.getId(), slot.key(), slot.date(), now) == 1) {
                result.add(toResponse(slot, zone, now, true));
            }
        }

        result.sort(Comparator.comparing(ReminderResponse::startsAt));

        return result;
    }

    // The rest of today's activities with a time, for the user to see what will be reminded.
    @Transactional(readOnly = true)
    public List<ReminderResponse> upcoming(String email, ZoneId zone, Instant now) {
        User user = getUser(email);
        LocalDate today = LocalDate.ofInstant(now, zone);

        List<ReminderSchedule.Slot> todays = slots(user, List.of(today)).stream()
                .filter(slot -> !now.isAfter(ReminderSchedule.startOf(slot, zone).plus(ReminderSchedule.GRACE)))
                .toList();

        if (todays.isEmpty()) {
            return List.of();
        }

        Set<String> shown = new HashSet<>(deliveryRepository.findDelivered(
                user.getId(), todays.stream().map(ReminderSchedule.Slot::key).toList()));

        return todays.stream()
                .map(slot -> toResponse(slot, zone, now, shown.contains(slot.key())))
                .sorted(Comparator.comparing(ReminderResponse::startsAt))
                .toList();
    }

    // Every occurrence on these days of the user's own activities, and nobody else's.
    private List<ReminderSchedule.Slot> slots(User user, List<LocalDate> days) {
        List<ReminderSchedule.Slot> slots = new ArrayList<>();

        for (RoutineHabit link : routineHabitRepository.findTimedForReminders(user.getId())) {
            for (LocalDate day : days) {
                if (HabitSchedule.plannedOn(link, day)) {
                    slots.add(new ReminderSchedule.Slot(
                            ReminderSchedule.habitKey(link.getId(), day, link.getStartTime()),
                            "HABIT",
                            link.getHabit().getTitle(),
                            link.getRoutine().getId(),
                            link.getRoutine().getTitle(),
                            day,
                            link.getStartTime()
                    ));
                }
            }
        }

        Map<Long, Set<LocalDate>> cancelled = new HashMap<>();

        for (CalendarEventException exception : exceptionRepository.findByEventUserId(user.getId())) {
            cancelled.computeIfAbsent(exception.getEvent().getId(), id -> new HashSet<>())
                    .add(exception.getOccurrenceDate());
        }

        for (CalendarEvent event : calendarEventRepository.findByUserId(user.getId())) {
            Routine routine = event.getRoutine();

            if (routine == null || routine.isDeleted() || !routine.getUser().getId().equals(user.getId())) {
                continue;
            }

            for (LocalDate day : days) {
                if (event.occursOn(day) && !cancelled.getOrDefault(event.getId(), Set.of()).contains(day)) {
                    slots.add(new ReminderSchedule.Slot(
                            ReminderSchedule.eventKey(event.getId(), day, event.getStartTime()),
                            "ROUTINE",
                            routine.getTitle(),
                            routine.getId(),
                            routine.getTitle(),
                            day,
                            event.getStartTime()
                    ));
                }
            }
        }

        return slots;
    }

    private static ReminderResponse toResponse(ReminderSchedule.Slot slot, ZoneId zone, Instant now, boolean shown) {
        return new ReminderResponse(slot.key(), slot.kind(), slot.title(), slot.routineId(), slot.routineTitle(),
                slot.date(), slot.time(), ReminderSchedule.startOf(slot, zone),
                ReminderSchedule.minutesUntil(slot, zone, now), shown);
    }

    private static ReminderSettingsResponse toResponse(ReminderSettings settings) {
        return settings == null
                ? new ReminderSettingsResponse(ReminderSchedule.DEFAULT_ENABLED,
                ReminderSchedule.DEFAULT_LEAD_MINUTES, ReminderSchedule.LEAD_OPTIONS)
                : new ReminderSettingsResponse(settings.isEnabled(), settings.getLeadMinutes(),
                ReminderSchedule.LEAD_OPTIONS);
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
