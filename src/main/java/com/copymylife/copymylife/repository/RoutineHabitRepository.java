package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.RoutineHabit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoutineHabitRepository
        extends JpaRepository<RoutineHabit, Long> {

    // Every link of the routine, deleted ones included; only finished runs need those.
    List<RoutineHabit> findByRoutineIdOrderByPositionAsc(
            Long routineId
    );

    // The habits the routine has now.
    List<RoutineHabit> findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(
            Long routineId
    );

    boolean existsByRoutineIdAndHabitIdAndRemovedAtIsNull(
            Long routineId,
            Long habitId
    );

    void deleteByRoutineIdAndHabitId(
            Long routineId,
            Long habitId
    );

    void deleteByHabitId(Long habitId);

    @Query("""
            SELECT COALESCE(MAX(rh.position), -1)
            FROM RoutineHabit rh
            WHERE rh.routine.id = :routineId
              AND rh.removedAt IS NULL
            """)
    Integer findMaxPositionByRoutineId(
            @Param("routineId") Long routineId
    );

    // Every link of the habit, deleted ones included, so statistics keep their history.
    List<RoutineHabit> findByHabitId(Long habitId);

    List<RoutineHabit> findByHabitIdAndRemovedAtIsNull(Long habitId);

    boolean existsByHabitIdAndRemovedAtIsNull(Long habitId);

    Optional<RoutineHabit> findByIdAndRoutineIdAndRemovedAtIsNull(
            Long id,
            Long routineId
    );

    // Pairs of [habit id, number of live routines where the owner paused it].
    @Query("""
            SELECT rh.habit.id, COUNT(rh)
            FROM RoutineHabit rh
            WHERE rh.habit.user.id = :userId
              AND rh.pausedAt IS NOT NULL
              AND rh.removedAt IS NULL
              AND rh.routine.deleted = false
            GROUP BY rh.habit.id
            """)
    List<Object[]> countPausedRoutinesByHabit(
            @Param("userId") Long userId
    );

    // US-30: pairs of [routine id, start time] of the timed activities still in these routines
    // and not paused; Top Routines finds each routine's time of day from them.
    @Query("""
            SELECT rh.routine.id, rh.startTime
            FROM RoutineHabit rh
            WHERE rh.routine.id IN :routineIds
              AND rh.removedAt IS NULL
              AND rh.pausedAt IS NULL
              AND rh.startTime IS NOT NULL
            """)
    List<Object[]> findActiveStartTimes(
            @Param("routineIds") Collection<Long> routineIds
    );

    // US-26: the activities these routines run now (not removed, not paused), with their habit.
    @Query("""
            SELECT rh
            FROM RoutineHabit rh
            JOIN FETCH rh.habit
            WHERE rh.routine.id IN :routineIds
              AND rh.removedAt IS NULL
              AND rh.pausedAt IS NULL
            ORDER BY rh.routine.id, rh.position
            """)
    List<RoutineHabit> findRunningWithHabitByRoutineIdIn(
            @Param("routineIds") Collection<Long> routineIds
    );

    // US-22: the user's own timed activities that can still come up: in a routine not deleted,
    // not deleted from it and not paused; with their habit and routine.
    @Query("""
            SELECT rh
            FROM RoutineHabit rh
            JOIN FETCH rh.habit
            JOIN FETCH rh.routine r
            WHERE r.user.id = :userId
              AND r.deleted = false
              AND rh.removedAt IS NULL
              AND rh.pausedAt IS NULL
              AND rh.startTime IS NOT NULL
            """)
    List<RoutineHabit> findTimedForReminders(
            @Param("userId") Long userId
    );
}