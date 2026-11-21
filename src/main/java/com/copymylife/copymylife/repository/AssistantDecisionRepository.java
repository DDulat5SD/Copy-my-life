package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.AssistantDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssistantDecisionRepository extends JpaRepository<AssistantDecision, Long> {

    // Latest first, with the routine habit id, for the duration suggestions.
    @Query("""
            SELECT d FROM AssistantDecision d
            WHERE d.user.id = :userId AND d.kind = :kind
            ORDER BY d.decidedAt DESC, d.id DESC
            """)
    List<AssistantDecision> findLatestFirst(@Param("userId") Long userId, @Param("kind") String kind);
}
