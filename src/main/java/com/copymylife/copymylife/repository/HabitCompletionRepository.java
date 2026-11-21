package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.HabitCompletion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HabitCompletionRepository extends JpaRepository<HabitCompletion, Long> {

    Optional<HabitCompletion>
    findFirstByUserIdAndHabitIdAndCompletedDateAndRoutineCompletionIsNullOrderByIdDesc(
            Long userId,
            Long habitId,
            LocalDate completedDate
    );

    List<HabitCompletion> findByUserIdAndCompletedDateBetween(Long userId, LocalDate from, LocalDate to);

    long deleteByUserIdAndHabitIdAndCompletedDate(Long userId, Long habitId, LocalDate completedDate);

    long deleteByHabitId(Long habitId);

    boolean existsByHabitId(Long habitId);

    Optional<HabitCompletion>
    findByRoutineCompletionIdAndHabitId(
            Long routineCompletionId,
            Long habitId
    );

    List<HabitCompletion>
    findByRoutineCompletionId(Long routineCompletionId);

    boolean existsByRoutineHabitId(Long routineHabitId);

    // US-21: every record of the user up to a day, with its habit, for streaks and the week.
    @Query("""
            SELECT hc FROM HabitCompletion hc
            JOIN FETCH hc.habit
            WHERE hc.user.id = :userId
              AND hc.completedDate <= :until
            """)
    List<HabitCompletion> findHistory(@Param("userId") Long userId, @Param("until") LocalDate until);

    // US-07: the days each habit was completed, a habit counted once a day; rows from before
    // statuses existed count when they have a completion time, as in HabitCompletion.currentStatus().
    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT habit_id, completed_date
                FROM habit_completions
                WHERE user_id = :userId
                  AND (status = 'COMPLETED' OR (status IS NULL AND completed_at IS NOT NULL))
            ) done
            """, nativeQuery = true)
    long countCompletedHabitDays(@Param("userId") Long userId);

    // Routine assistant: the user's own records of run habits from a day on, with habit and plan.
    @Query("""
            SELECT hc FROM HabitCompletion hc
            JOIN FETCH hc.habit
            JOIN FETCH hc.routineHabit
            WHERE hc.user.id = :userId
              AND hc.completedDate >= :from
            """)
    List<HabitCompletion> findRunRecordsSince(@Param("userId") Long userId, @Param("from") LocalDate from);
}