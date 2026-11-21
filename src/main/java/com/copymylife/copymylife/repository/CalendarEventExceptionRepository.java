package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.CalendarEventException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CalendarEventExceptionRepository
        extends JpaRepository<CalendarEventException, Long> {

    boolean existsByEventIdAndOccurrenceDate(Long eventId, LocalDate occurrenceDate);

    List<CalendarEventException> findByEventUserId(Long userId);

    long deleteByEventId(Long eventId);
}
