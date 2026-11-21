package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.LikedRoutine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface LikedRoutineRepository extends JpaRepository<LikedRoutine, Long> {

    List<LikedRoutine> findByUserId(Long userId);

    Optional<LikedRoutine> findByUserIdAndRoutineId(
            Long userId,
            Long routineId
    );

    long deleteByUserIdAndRoutineId(
            Long userId,
            Long routineId
    );

    long countByRoutineId(Long routineId);

    @Query("""
            SELECT COUNT(l)
            FROM LikedRoutine l
            WHERE l.routine.id = :routineId
            AND (
                l.createdAt >= :from
                OR l.createdAt IS NULL
            )
            """)
    long countRecentLikes(
            @Param("routineId") Long routineId,
            @Param("from") LocalDateTime from
    );
}