package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.SavedHabit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedHabitRepository extends JpaRepository<SavedHabit, Long> {

    List<SavedHabit> findByUserIdOrderByIdDesc(Long userId);

    Optional<SavedHabit> findByUserIdAndHabitId(Long userId, Long habitId);

    long deleteByUserIdAndHabitId(Long userId, Long habitId);

    boolean existsByHabitId(Long habitId);
}