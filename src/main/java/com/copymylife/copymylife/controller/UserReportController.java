
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.Report;
import com.copymylife.copymylife.service.ReportService;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

// US-24: what members report, for administrators to review. Every rule is in ReportService;
// refusals come back as {"message", "code"} (ReportApiExceptionHandler).
@RestController
@RequestMapping("/reports")
public class UserReportController {

    private final ReportService reportService;

    public UserReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    // reason is one of ReportService.Reason; details is an optional explanation.
    public record ReportRequest(
            String reason,
            String details
    ) {}

    // message is set only on the answer to a new report.
    public record UserReportResponse(
            Long id,
            String type,
            Long reportedUserId,
            Long reportedCommentId,
            Long reportedRoutineId,
            Long reportedPostId,
            Long reportedPostCommentId,
            String reason,
            String details,
            String status,
            LocalDateTime createdAt,
            String message
    ) {}

    private static UserReportResponse toResponse(Report report, String message) {
        return new UserReportResponse(
                report.getId(),
                report.getType().name(),
                report.getReportedUser().getId(),
                report.getReportedComment() == null ? null : report.getReportedComment().getId(),
                report.getReportedRoutine() == null ? null : report.getReportedRoutine().getId(),
                report.getReportedPost() == null ? null : report.getReportedPost().getId(),
                report.getReportedPostComment() == null ? null : report.getReportedPostComment().getId(),
                report.getReason(),
                report.getDetails(),
                report.getStatus().name(),
                report.getCreatedAt(),
                message
        );
    }

    private static UserReportResponse submitted(Report report) {
        return toResponse(report, ReportService.SUBMITTED);
    }

    private static String reason(ReportRequest request) {
        return request == null ? null : request.reason();
    }

    private static String details(ReportRequest request) {
        return request == null ? null : request.details();
    }

    @PostMapping("/users/{userId}")
    public UserReportResponse reportUser(
            @PathVariable Long userId,
            @RequestBody(required = false) ReportRequest request,
            Authentication authentication
    ) {
        return submitted(reportService.reportUser(
                authentication.getName(), userId, reason(request), details(request)));
    }

    // A comment under a routine.
    @PostMapping("/comments/{commentId}")
    public UserReportResponse reportComment(
            @PathVariable Long commentId,
            @RequestBody(required = false) ReportRequest request,
            Authentication authentication
    ) {
        return submitted(reportService.reportRoutineComment(
                authentication.getName(), commentId, reason(request), details(request)));
    }

    @PostMapping("/routines/{routineId}")
    public UserReportResponse reportRoutine(
            @PathVariable Long routineId,
            @RequestBody(required = false) ReportRequest request,
            Authentication authentication
    ) {
        return submitted(reportService.reportRoutine(
                authentication.getName(), routineId, reason(request), details(request)));
    }

    @PostMapping("/posts/{postId}")
    public UserReportResponse reportPost(
            @PathVariable Long postId,
            @RequestBody(required = false) ReportRequest request,
            Authentication authentication
    ) {
        return submitted(reportService.reportPost(
                authentication.getName(), postId, reason(request), details(request)));
    }

    @PostMapping("/post-comments/{commentId}")
    public UserReportResponse reportPostComment(
            @PathVariable Long commentId,
            @RequestBody(required = false) ReportRequest request,
            Authentication authentication
    ) {
        return submitted(reportService.reportPostComment(
                authentication.getName(), commentId, reason(request), details(request)));
    }

    @GetMapping("/mine")
    @Transactional(readOnly = true)
    public List<UserReportResponse> getMyReports(
            Authentication authentication
    ) {
        return reportService.getMine(authentication.getName())
                .stream()
                .map(report -> toResponse(report, null))
                .toList();
    }
}
