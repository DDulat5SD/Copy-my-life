package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.RoutineRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RoutineRatingRepository extends JpaRepository<RoutineRating, Long> {

    Optional<RoutineRating> findByUserIdAndRoutineId(
            Long userId,
            Long routineId
    );

    long countByRoutineId(Long routineId);

    @Query("""
            SELECT COALESCE(AVG(r.value), 0)
            FROM RoutineRating r
            WHERE r.routine.id = :routineId
            """)
    Double findAverageByRoutineId(
            @Param("routineId") Long routineId
    );

    @Query("""
            SELECT COALESCE(AVG(r.value), 0)
            FROM RoutineRating r
            WHERE r.routine.isPublic = true
              AND r.routine.hiddenAt IS NULL
            """)
    Double findPublicAverageRating();

    @Query("""
            SELECT COUNT(r)
            FROM RoutineRating r
            WHERE r.routine.id = :routineId
            AND (
                r.updatedAt >= :from
                OR r.updatedAt IS NULL
            )
            """)
    long countRecentRatings(
            @Param("routineId") Long routineId,
            @Param("from") LocalDateTime from
    );

    @Query("""
            SELECT AVG(r.value)
            FROM RoutineRating r
            WHERE r.routine.id = :routineId
            AND (
                r.updatedAt >= :from
                OR r.updatedAt IS NULL
            )
            """)
    Double getRecentAverageRating(
            @Param("routineId") Long routineId,
            @Param("from") LocalDateTime from
    );
}