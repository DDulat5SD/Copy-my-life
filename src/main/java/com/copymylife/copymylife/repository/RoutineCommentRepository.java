package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.RoutineComment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RoutineCommentRepository extends JpaRepository<RoutineComment, Long> {

    List<RoutineComment> findByRoutineIdOrderByCreatedAtDesc(Long routineId);

    List<RoutineComment> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<RoutineComment> findByIdAndUserId(Long id, Long userId);
}                                       