package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.CalendarEvent;
import com.copymylife.copymylife.model.RepeatType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

public interface CalendarEventRepository
        extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByUserId(Long userId);

    Optional<CalendarEvent> findByIdAndUserId(
            Long id,
            Long userId
    );

    // The single event that replaced one occurrence of a series.
    Optional<CalendarEvent> findBySeriesIdAndOriginalDate(
            Long seriesId,
            LocalDate originalDate
    );

    List<CalendarEvent> findBySeriesId(Long seriesId);

    // An event just like the one being added, so a second press adds nothing.
    Optional<CalendarEvent> findFirstByUserIdAndRoutineIdAndStartDateAndStartTimeAndRepeatTypeAndSeriesIsNull(
            Long userId,
            Long routineId,
            LocalDate startDate,
            LocalTime startTime,
            RepeatType repeatType
    );
}