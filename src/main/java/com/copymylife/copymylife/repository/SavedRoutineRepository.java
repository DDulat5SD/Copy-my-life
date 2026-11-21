package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.SavedRoutine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SavedRoutineRepository extends JpaRepository<SavedRoutine, Long> {

    List<SavedRoutine> findByUserId(Long userId);

    Optional<SavedRoutine> findByUserIdAndRoutineId(
            Long userId,
            Long routineId
    );

    long deleteByUserIdAndRoutineId(
            Long userId,
            Long routineId
    );

    long countByRoutineId(Long routineId);

    @Query("""
            SELECT COUNT(s)
            FROM SavedRoutine s
            WHERE s.routine.id = :routineId
            AND (
                s.createdAt >= :from
                OR s.createdAt IS NULL
            )
            """)
    long countRecentSaves(
            @Param("routineId") Long routineId,
            @Param("from") LocalDateTime from
    );
}