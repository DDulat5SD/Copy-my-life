package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.PostComment;
import com.copymylife.copymylife.model.Report;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineComment;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.ReportRepository;
import com.copymylife.copymylife.repository.RoutineCommentRepository;
import com.copymylife.copymylife.repository.UserRepository;
import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

// Reports for administrators to review. Only an administrator's decision changes the reported
// content; US-24: hiding and removing routines, posts and post comments go through the same
// AdminContentService as the US-25 admin pages. Each action takes the report row first, so two
// decisions on one report never both pass, and a closed report takes no further action.
@RestController
@RequestMapping("/admin-api/reports")
public class AdminReportController {

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final RoutineCommentRepository commentRepository;
    private final AdminContentService adminContentService;

    public AdminReportController(
            ReportRepository reportRepository,
            UserRepository userRepository,
            RoutineCommentRepository commentRepository,
            AdminContentService adminContentService
    ) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
        this.adminContentService = adminContentService;
    }

    public record StatusRequest(
            Report.Status status,
            String note
    ) {}

    // targetAvailable is false once the reported content is deleted; the report itself stays.
    public record AdminReportResponse(
            Long id,
            String type,
            String status,
            String reason,
            String details,
            LocalDateTime createdAt,
            Long reporterId,
            String reporterUsername,
            String reporterEmail,
            Long reportedUserId,
            String reportedUsername,
            Long reportedCommentId,
            String commentText,
            String actionTaken,
            String reviewNote,
            LocalDateTime reviewedAt,
            Long reviewedById,
            Long reportedRoutineId,
            String routineTitle,
            Long reportedPostId,
            String postText,
            Long reportedPostCommentId,
            String postCommentText,
            Long contextRoutineId,
            Long contextPostId,
            boolean targetAvailable,
            boolean targetHidden,
            boolean reportedUserBanned
    ) {}

    private Report findReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Report not found"
                ));
    }

    private Report lockReport(Long reportId) {
        return reportRepository.findForUpdate(reportId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Report not found"
                ));
    }

    private User getAdmin(Authentication authentication) {
        return userRepository.findByEmail(authentication.getName())
                .filter(user -> "ADMIN".equals(user.getRole()))
                .filter(user -> !user.isBanned())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Admin access required"
                ));
    }

    private void ensureOpen(Report report) {
        if (report.getStatus() == Report.Status.RESOLVED ||
                report.getStatus() == Report.Status.REJECTED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This report is already closed"
            );
        }
    }

    private static void ensureType(Report report, Report.Type type, Object target, String what) {
        if (report.getType() != type) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This report is not about " + what
            );
        }

        if (target == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The reported content has already been deleted"
            );
        }
    }

    private AdminReportResponse toResponse(Report report) {
        User reporter = report.getReporter();
        User target = report.getReportedUser();
        RoutineComment comment = report.getReportedComment();
        Routine routine = report.getReportedRoutine();
        Post post = report.getReportedPost();
        PostComment postComment = report.getReportedPostComment();

        boolean available = switch (report.getType()) {
            case USER -> true;
            case COMMENT -> comment != null;
            case ROUTINE -> routine != null && !routine.isDeleted();
            case POST -> post != null;
            case POST_COMMENT -> postComment != null;
        };
        boolean hidden = switch (report.getType()) {
            case USER -> target.isProfileHiddenByAdmin();
            case COMMENT -> comment != null && comment.isDeleted();
            case ROUTINE -> routine != null && routine.isHiddenByAdmin();
            case POST -> post != null && post.isHiddenByAdmin();
            case POST_COMMENT -> false;
        };

        return new AdminReportResponse(
                report.getId(),
                report.getType().name(),
                report.getStatus().name(),
                report.getReason(),
                report.getDetails(),
                report.getCreatedAt(),
                reporter.getId(),
                reporter.getUsername(),
                reporter.getEmail(),
                target.getId(),
                target.getUsername(),
                comment == null ? null : comment.getId(),
                comment == null ? null : comment.getText(),
                report.getActionTaken(),
                report.getReviewNote(),
                report.getReviewedAt(),
                report.getReviewedBy() == null
                        ? null
                        : report.getReviewedBy().getId(),
                routine == null ? null : routine.getId(),
                routine == null ? null : routine.getTitle(),
                post == null ? null : post.getId(),
                post == null ? null : post.getText(),
                postComment == null ? null : postComment.getId(),
                postComment == null ? null : postComment.getText(),
                comment == null ? null : comment.getRoutine().getId(),
                postComment == null ? null : postComment.getPost().getId(),
                available,
                hidden,
                target.isBanned()
        );
    }

    private void finishReport(
            Report report,
            User admin,
            String action,
            String note
    ) {
        report.setStatus(Report.Status.RESOLVED);
        report.setReviewedBy(admin);
        report.setReviewedAt(LocalDateTime.now());
        report.setActionTaken(action);
        report.setReviewNote(note);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminReportResponse> getReports(
            @RequestParam(required = false) Report.Status status,
            @RequestParam(required = false) Report.Type type
    ) {
        return reportRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .filter(report ->
                        status == null || report.getStatus() == status
                )
                .filter(report ->
                        type == null || report.getType() == type
                )
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{reportId}")
    @Transactional(readOnly = true)
    public AdminReportResponse getReport(
            @PathVariable Long reportId
    ) {
        return toResponse(findReport(reportId));
    }

    @PutMapping("/{reportId}/status")
    @Transactional
    public AdminReportResponse changeStatus(
            @PathVariable Long reportId,
            @RequestBody StatusRequest request,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);

        if (request == null ||
                request.status() == null ||
                request.status() == Report.Status.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid report status"
            );
        }

        if (request.note() != null && request.note().length() > 1000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Note is too long"
            );
        }

        User admin = getAdmin(authentication);

        report.setStatus(request.status());
        report.setReviewedBy(admin);
        report.setReviewNote(request.note());

        if (request.status() == Report.Status.RESOLVED ||
                request.status() == Report.Status.REJECTED) {
            report.setReviewedAt(LocalDateTime.now());
        }

        return toResponse(reportRepository.save(report));
    }

    @PostMapping("/{reportId}/ban-user")
    @Transactional
    public AdminReportResponse banReportedUser(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);

        User admin = getAdmin(authentication);
        User target = report.getReportedUser();

        if (!"USER".equals(target.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Administrator accounts cannot be banned"
            );
        }

        target.setBanned(true);
        userRepository.save(target);

        finishReport(
                report,
                admin,
                "BAN_USER",
                "Reported user has been banned"
        );

        return toResponse(reportRepository.save(report));
    }

    @PostMapping("/{reportId}/remove-comment")
    @Transactional
    public AdminReportResponse removeReportedComment(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);

        if (report.getType() != Report.Type.COMMENT ||
                report.getReportedComment() == null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This report is not about a comment"
            );
        }

        User admin = getAdmin(authentication);

        RoutineComment comment = commentRepository
                .findById(report.getReportedComment().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Comment not found"
                ));

        if (comment.isDeleted()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Comment is already removed"
            );
        }

        comment.setDeleted(true);
        comment.setDeletedAt(LocalDateTime.now());
        commentRepository.save(comment);

        finishReport(
                report,
                admin,
                "REMOVE_COMMENT",
                "Reported comment has been removed"
        );

        return toResponse(reportRepository.save(report));
    }

    // US-24: hidden content leaves every public view and stays with its owner (US-25).
    @PostMapping("/{reportId}/hide-routine")
    @Transactional
    public AdminReportResponse hideReportedRoutine(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);
        ensureType(report, Report.Type.ROUTINE, report.getReportedRoutine(), "a routine");

        User admin = getAdmin(authentication);

        if (report.getReportedRoutine().isHiddenByAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Routine is already hidden"
            );
        }

        adminContentService.setRoutineHidden(report.getReportedRoutine().getId(), true);

        finishReport(
                report,
                admin,
                "HIDE_ROUTINE",
                "Reported routine has been hidden"
        );

        return toResponse(reportRepository.save(report));
    }

    @PostMapping("/{reportId}/hide-post")
    @Transactional
    public AdminReportResponse hideReportedPost(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);
        ensureType(report, Report.Type.POST, report.getReportedPost(), "a post");

        User admin = getAdmin(authentication);

        if (report.getReportedPost().isHiddenByAdmin()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Post is already hidden"
            );
        }

        adminContentService.setPostHidden(report.getReportedPost().getId(), true);

        finishReport(
                report,
                admin,
                "HIDE_POST",
                "Reported post has been hidden"
        );

        return toResponse(reportRepository.save(report));
    }

    // The post goes with its photo, likes and comments; reports about it stay, without the link.
    @PostMapping("/{reportId}/remove-post")
    @Transactional
    public AdminReportResponse removeReportedPost(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);
        ensureType(report, Report.Type.POST, report.getReportedPost(), "a post");

        User admin = getAdmin(authentication);
        Long postId = report.getReportedPost().getId();

        report.setReportedPost(null);
        adminContentService.removePost(postId);

        finishReport(
                report,
                admin,
                "REMOVE_POST",
                "Reported post has been removed"
        );

        return toResponse(reportRepository.save(report));
    }

    @PostMapping("/{reportId}/remove-post-comment")
    @Transactional
    public AdminReportResponse removeReportedPostComment(
            @PathVariable Long reportId,
            Authentication authentication
    ) {
        Report report = lockReport(reportId);
        ensureOpen(report);
        ensureType(report, Report.Type.POST_COMMENT, report.getReportedPostComment(), "a post comment");

        User admin = getAdmin(authentication);
        Long commentId = report.getReportedPostComment().getId();

        report.setReportedPostComment(null);
        adminContentService.removePostComment(commentId);

        finishReport(
                report,
                admin,
                "REMOVE_POST_COMMENT",
                "Reported post comment has been removed"
        );

        return toResponse(reportRepository.save(report));
    }
}
