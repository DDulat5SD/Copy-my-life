package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.RoutineCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RoutineCompletionRepository extends JpaRepository<RoutineCompletion, Long> {

    Optional<RoutineCompletion> findByUserIdAndRoutineIdAndCompletedDate(Long userId, Long routineId, LocalDate completedDate);

    List<RoutineCompletion> findByUserIdAndCompletedDateBetween(Long userId, LocalDate from, LocalDate to);

    long deleteByUserIdAndRoutineIdAndCompletedDate(Long userId, Long routineId, LocalDate completedDate);

    @Query("""
        SELECT r FROM RoutineCompletion r
        WHERE r.user.id = :userId
          AND r.startedAt IS NOT NULL
          AND r.completedAt IS NULL
          AND r.endedAt IS NULL
          AND (r.expiresAt IS NULL OR r.expiresAt > :now)
        ORDER BY r.startedAt DESC
        """)
    List<RoutineCompletion> findOpenRuns(@Param("userId") Long userId, @Param("now") Instant now);

    // A run stays active until it is completed, stopped or finished, or its day is over.
    default Optional<RoutineCompletion> findActiveRun(Long userId) {
        return findOpenRuns(userId, Instant.now()).stream().findFirst();
    }

    boolean existsByRoutineId(Long routineId);

    Optional<RoutineCompletion> findFirstByUserIdAndRoutineIdAndStartedAtIsNotNullOrderByStartedAtDesc(
            Long userId,
            Long routineId
    );
}