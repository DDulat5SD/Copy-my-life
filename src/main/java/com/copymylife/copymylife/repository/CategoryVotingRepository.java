package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.CategoryVoting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CategoryVotingRepository extends JpaRepository<CategoryVoting, Long> {

    Optional<CategoryVoting> findByRoutineId(Long routineId);

    // The routines whose voting period has ended but whose votes are not counted yet.
    @Query("""
            SELECT v.routine.id
            FROM CategoryVoting v
            WHERE v.closedAt IS NULL
              AND v.closesAt <= :now
            ORDER BY v.closesAt
            """)
    List<Long> findDueRoutineIds(@Param("now") Instant now);
}
