package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.Habit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HabitRepository extends JpaRepository<Habit, Long> {

    List<Habit> findByRoutineId(Long routineId);

    Optional<Habit> findByIdAndRoutineId(Long id, Long routineId);

    List<Habit> findByRoutine_User_Id(Long userId);

    List<Habit> findByUserIdOrderByIdDesc(Long userId);

    Optional<Habit> findByIdAndUserId(Long habitId, Long userId);
    
}
