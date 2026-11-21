package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.Report;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {

    List<Report> findByReporterIdOrderByCreatedAtDesc(Long reporterId);

    // US-24: an administrator's decision waits for any other decision on the same report.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Report r WHERE r.id = :id")
    Optional<Report> findForUpdate(@Param("id") Long id);

    boolean existsByReporterIdAndTypeAndReportedUserIdAndStatusIn(
            Long reporterId,
            Report.Type type,
            Long reportedUserId,
            Collection<Report.Status> statuses
    );

    boolean existsByReporterIdAndReportedCommentIdAndStatusIn(
            Long reporterId,
            Long reportedCommentId,
            Collection<Report.Status> statuses
    );

    boolean existsByReporterIdAndReportedRoutineIdAndStatusIn(
            Long reporterId,
            Long reportedRoutineId,
            Collection<Report.Status> statuses
    );

    boolean existsByReporterIdAndReportedPostIdAndStatusIn(
            Long reporterId,
            Long reportedPostId,
            Collection<Report.Status> statuses
    );

    boolean existsByReporterIdAndReportedPostCommentIdAndStatusIn(
            Long reporterId,
            Long reportedPostCommentId,
            Collection<Report.Status> statuses
    );
}
