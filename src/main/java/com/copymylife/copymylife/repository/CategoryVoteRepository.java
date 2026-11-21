package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.CategoryVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryVoteRepository extends JpaRepository<CategoryVote, Long> {

    Optional<CategoryVote> findByRoutineIdAndUserId(Long routineId, Long userId);

    boolean existsByRoutineIdAndUserId(Long routineId, Long userId);

    long countByCategoryId(Long categoryId);

    // Pairs of [category id, votes] for one routine.
    @Query("""
            SELECT v.category.id, COUNT(v)
            FROM CategoryVote v
            WHERE v.routine.id = :routineId
            GROUP BY v.category.id
            """)
    List<Object[]> countByCategory(@Param("routineId") Long routineId);
}
