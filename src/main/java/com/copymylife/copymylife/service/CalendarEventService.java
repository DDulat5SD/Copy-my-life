package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.CalendarEventRequest;
import com.copymylife.copymylife.dto.CalendarEventResponse;
import com.copymylife.copymylife.model.CalendarEvent;
import com.copymylife.copymylife.model.CalendarEventException;
import com.copymylife.copymylife.model.RepeatType;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.CalendarEventExceptionRepository;
import com.copymylife.copymylife.repository.CalendarEventRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// A calendar event places one of the user's routines on a day, once or repeating.
// A repeating event is changed either for one occurrence or for the entire series,
// never without that choice. One occurrence is taken out of the series with a
// CalendarEventException; a changed occurrence becomes its own single event.
@Service
public class CalendarEventService {

    public static final String SCOPE_OCCURRENCE = "occurrence";
    public static final String SCOPE_SERIES = "series";

    private static final int MAX_RANGE_DAYS = 42;

    private final CalendarEventRepository calendarEventRepository;
    private final CalendarEventExceptionRepository exceptionRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;

    public CalendarEventService(CalendarEventRepository calendarEventRepository, CalendarEventExceptionRepository exceptionRepository, RoutineRepository routineRepository, UserRepository userRepository) {
        this.calendarEventRepository = calendarEventRepository;
        this.exceptionRepository = exceptionRepository;
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public CalendarEventResponse create(String email, CalendarEventRequest request) {

        User user = lockUser(email);

        requireComplete(request, true, true);

        Routine routine = getRoutine(user, request.getRoutineId());

        // Pressing Add twice keeps one event.
        Optional<CalendarEvent> same = calendarEventRepository
                .findFirstByUserIdAndRoutineIdAndStartDateAndStartTimeAndRepeatTypeAndSeriesIsNull(
                        user.getId(), routine.getId(), request.getDay(), request.getTime(), request.getRepeat());

        if (same.isPresent()) {
            return toResponse(same.get(), same.get().getStartDate());
        }

        CalendarEvent event = new CalendarEvent(user, routine, request.getDay(), request.getTime(), request.getRepeat());

        CalendarEvent saved = calendarEventRepository.save(event);

        return toResponse(saved, saved.getStartDate());
    }

    @Transactional(readOnly = true)
    public List<CalendarEventResponse> getWeek(String email, LocalDate from, LocalDate to) {

        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The end of the range must not be before its start");
        }

        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new IllegalArgumentException("Choose a range of at most " + MAX_RANGE_DAYS + " days");
        }

        User user = getUser(email);

        Map<Long, Set<LocalDate>> cancelled = new HashMap<>();

        for (CalendarEventException exception : exceptionRepository.findByEventUserId(user.getId())) {
            cancelled.computeIfAbsent(exception.getEvent().getId(), id -> new HashSet<>())
                    .add(exception.getOccurrenceDate());
        }

        List<CalendarEventResponse> result = new ArrayList<>();

        for (CalendarEvent event : calendarEventRepository.findByUserId(user.getId())) {
            if (event.getRoutine() == null || event.getRoutine().isDeleted()) {
                continue;
            }

            Set<LocalDate> skipped = cancelled.getOrDefault(event.getId(), Set.of());
            LocalDate date = event.getStartDate().isAfter(from) ? event.getStartDate() : from;

            for (; !date.isAfter(to); date = date.plusDays(1)) {
                if (event.occursOn(date) && !skipped.contains(date)) {
                    result.add(toResponse(event, date));
                }
            }
        }

        return result;
    }

    @Transactional
    public CalendarEventResponse update(String email, Long eventId, String scope, LocalDate date, CalendarEventRequest request) {

        User user = lockUser(email);
        CalendarEvent event = getEvent(user, eventId);

        if (!event.isRepeating()) {
            requireComplete(request, true, true);

            event.setRoutine(getRoutine(user, request.getRoutineId()));
            event.setStartDate(request.getDay());
            event.setStartTime(request.getTime());

            // A changed occurrence stays a single day of its series.
            event.setRepeatType(event.getSeries() == null ? request.getRepeat() : RepeatType.NONE);

            return toResponse(calendarEventRepository.save(event), event.getStartDate());
        }

        if (SCOPE_OCCURRENCE.equals(scope)) {
            return updateOccurrence(user, event, requireOccurrence(event, date), request);
        }

        if (SCOPE_SERIES.equals(scope)) {
            requireComplete(request, false, true);

            // Every occurrence changes; the series keeps its first day so earlier days stay.
            event.setRoutine(getRoutine(user, request.getRoutineId()));
            event.setStartTime(request.getTime());
            event.setRepeatType(request.getRepeat());

            return toResponse(calendarEventRepository.save(event), event.getStartDate());
        }

        throw new IllegalArgumentException("Choose whether to change one occurrence or the entire series");
    }

    @Transactional
    public void delete(String email, Long eventId, String scope, LocalDate date) {

        User user = lockUser(email);
        CalendarEvent event = getEvent(user, eventId);

        if (!event.isRepeating()) {
            calendarEventRepository.delete(event);
            return;
        }

        if (SCOPE_OCCURRENCE.equals(scope)) {
            LocalDate day = requireOccurrence(event, date);

            // Deleting the same day again changes nothing.
            if (!exceptionRepository.existsByEventIdAndOccurrenceDate(event.getId(), day)) {
                exceptionRepository.save(new CalendarEventException(event, day));
            }

            return;
        }

        if (SCOPE_SERIES.equals(scope)) {
            calendarEventRepository.deleteAll(calendarEventRepository.findBySeriesId(event.getId()));
            exceptionRepository.deleteByEventId(event.getId());
            calendarEventRepository.delete(event);
            return;
        }

        throw new IllegalArgumentException("Choose whether to delete one occurrence or the entire series");
    }

    // Takes the day out of the series and puts a single event in its place. Changing the
    // same day again updates that single event instead of adding another.
    private CalendarEventResponse updateOccurrence(User user, CalendarEvent series, LocalDate day, CalendarEventRequest request) {

        requireComplete(request, true, false);

        Routine routine = getRoutine(user, request.getRoutineId());

        Optional<CalendarEvent> replaced = calendarEventRepository.findBySeriesIdAndOriginalDate(series.getId(), day);
        CalendarEvent single;

        if (replaced.isPresent()) {
            single = replaced.get();
        } else if (exceptionRepository.existsByEventIdAndOccurrenceDate(series.getId(), day)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That occurrence was deleted");
        } else {
            exceptionRepository.save(new CalendarEventException(series, day));

            single = new CalendarEvent(user, routine, day, request.getTime(), RepeatType.NONE);
            single.setSeries(series);
            single.setOriginalDate(day);
        }

        single.setRoutine(routine);
        single.setStartDate(request.getDay());
        single.setStartTime(request.getTime());

        CalendarEvent saved = calendarEventRepository.save(single);

        return toResponse(saved, saved.getStartDate());
    }

    private LocalDate requireOccurrence(CalendarEvent event, LocalDate date) {

        if (date == null) {
            throw new IllegalArgumentException("Choose which occurrence to change");
        }

        if (!event.occursOn(date)) {
            throw new IllegalArgumentException("That day is not part of this series");
        }

        return date;
    }

    private void requireComplete(CalendarEventRequest request, boolean needsDay, boolean needsRepeat) {

        if (request == null || request.getRoutineId() == null) {
            throw new IllegalArgumentException("Choose a routine");
        }

        if (needsDay && request.getDay() == null) {
            throw new IllegalArgumentException("Choose a day");
        }

        if (request.getTime() == null) {
            throw new IllegalArgumentException("Choose a time");
        }

        if (needsRepeat && request.getRepeat() == null) {
            throw new IllegalArgumentException("Choose how often it repeats");
        }
    }

    private CalendarEventResponse toResponse(CalendarEvent event, LocalDate occurrenceDate) {

        Routine routine = event.getRoutine();

        CalendarEventResponse response = new CalendarEventResponse(event.getId(), routine.getId(), routine.getTitle(), routine.getCategory(), routine.getDurationMinutes(), occurrenceDate, event.getStartTime(), event.getRepeatType());

        response.setRepeating(event.isRepeating());
        response.setSeriesId(event.getSeries() == null ? null : event.getSeries().getId());
        response.setOriginalDay(event.getOriginalDate());

        return response;
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // Holds the user's row, so one user's calendar changes never interleave.
    private User lockUser(String email) {
        return userRepository.findByEmailForUpdate(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // Only the user's own routines; someone else's, private or public, reads as missing.
    private Routine getRoutine(User user, Long routineId) {
        return routineRepository.findByIdAndUserIdAndDeletedFalse(routineId, user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Routine not found"));
    }

    private CalendarEvent getEvent(User user, Long eventId) {
        return calendarEventRepository.findByIdAndUserId(eventId, user.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Calendar event not found"));
    }
}
