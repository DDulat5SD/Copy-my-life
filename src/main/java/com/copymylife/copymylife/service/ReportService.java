package com.copymylife.copymylife.service;

import com.copymylife.copymylife.config.ReportConstraints;
import com.copymylife.copymylife.model.Post;
import com.copymylife.copymylife.model.PostComment;
import com.copymylife.copymylife.model.Report;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineComment;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PostCommentRepository;
import com.copymylife.copymylife.repository.ReportRepository;
import com.copymylife.copymylife.repository.RoutineCommentRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * US-24: members report a user, a routine, a post, or a comment under a routine or a post, for
 * administrators to review (AdminReportController). Nothing is hidden, removed or banned here.
 * Content the reporter may not see answers exactly like missing content, through the same checks
 * the pages use (RoutineAccessService, PostService.viewable).
 * <p>
 * One active (OPEN or IN_REVIEW) report per reporter and target. Reports by one reporter go in one at
 * a time (an advisory lock), and the database refuses a second active one anyway
 * (ReportConstraints); a closed report (RESOLVED or REJECTED) does not stop a new one. While any of
 * those database safeguards is missing no report is written at all (503).
 */
@Service
public class ReportService {

    public enum Reason {
        SPAM, HARASSMENT, INAPPROPRIATE_CONTENT, MISINFORMATION, OTHER
    }

    public static final int MAX_DETAILS_LENGTH = 1000;
    public static final String SUBMITTED = "Your report has been submitted.";
    public static final String ALREADY_REPORTED = "You have already reported this content.";
    public static final String INVALID_REASON = "Please select a valid report reason.";

    static final List<Report.Status> ACTIVE_STATUSES = List.of(Report.Status.OPEN, Report.Status.IN_REVIEW);

    // Why a report was refused, for the page to tell "already reported" from the rest.
    public static class ReportRefusedException extends ResponseStatusException {

        private final String code;

        public ReportRefusedException(HttpStatus status, String code, String message) {
            super(status, message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final RoutineCommentRepository routineCommentRepository;
    private final PostCommentRepository postCommentRepository;
    private final RoutineAccessService routineAccessService;
    private final PostService postService;
    private final JdbcTemplate jdbcTemplate;
    private final ReportConstraints reportConstraints;

    public ReportService(
            ReportRepository reportRepository,
            UserRepository userRepository,
            RoutineCommentRepository routineCommentRepository,
            PostCommentRepository postCommentRepository,
            RoutineAccessService routineAccessService,
            PostService postService,
            JdbcTemplate jdbcTemplate,
            ReportConstraints reportConstraints
    ) {
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.routineCommentRepository = routineCommentRepository;
        this.postCommentRepository = postCommentRepository;
        this.routineAccessService = routineAccessService;
        this.postService = postService;
        this.jdbcTemplate = jdbcTemplate;
        this.reportConstraints = reportConstraints;
    }

    @Transactional
    public Report reportUser(String email, Long userId, String reason, String details) {
        Report report = draft(reason, details);
        User reporter = requireReporter(email);

        User target = userRepository.findById(userId)
                .filter(user -> "USER".equals(user.getRole()))
                .orElseThrow(() -> notFound("User not found"));

        refuseSelf(reporter, target, "You cannot report yourself");

        report.setType(Report.Type.USER);
        report.setReportedUser(target);
        return submit(reporter, report);
    }

    @Transactional
    public Report reportRoutineComment(String email, Long commentId, String reason, String details) {
        Report report = draft(reason, details);
        User reporter = requireReporter(email);

        RoutineComment comment = routineCommentRepository.findById(commentId)
                .filter(c -> !c.isDeleted())
                .filter(c -> "USER".equals(c.getUser().getRole()))
                .filter(c -> routineAccessService.canViewRoutine(c.getRoutine(), reporter))
                .orElseThrow(() -> notFound("Comment not found"));

        refuseSelf(reporter, comment.getUser(), "You cannot report your own comment");

        report.setType(Report.Type.COMMENT);
        report.setReportedUser(comment.getUser());
        report.setReportedComment(comment);
        return submit(reporter, report);
    }

    @Transactional
    public Report reportRoutine(String email, Long routineId, String reason, String details) {
        Report report = draft(reason, details);
        User reporter = requireReporter(email);
        Routine routine = routineAccessService.requireViewableRoutine(routineId, reporter);

        refuseSelf(reporter, routine.getUser(), "You cannot report your own routine");

        report.setType(Report.Type.ROUTINE);
        report.setReportedUser(routine.getUser());
        report.setReportedRoutine(routine);
        return submit(reporter, report);
    }

    @Transactional
    public Report reportPost(String email, Long postId, String reason, String details) {
        Report report = draft(reason, details);
        User reporter = requireReporter(email);
        Post post = postService.viewable(reporter, postId);

        refuseSelf(reporter, post.getUser(), "You cannot report your own post");

        report.setType(Report.Type.POST);
        report.setReportedUser(post.getUser());
        report.setReportedPost(post);
        return submit(reporter, report);
    }

    // As under the post itself, a banned author's comment is not there.
    @Transactional
    public Report reportPostComment(String email, Long commentId, String reason, String details) {
        Report report = draft(reason, details);
        User reporter = requireReporter(email);

        PostComment comment = postCommentRepository.findById(commentId)
                .filter(c -> !c.getUser().isBanned())
                .orElseThrow(() -> notFound("Comment not found"));

        try {
            postService.viewable(reporter, comment.getPost().getId());
        } catch (ResponseStatusException hiddenPost) {
            throw notFound("Comment not found");
        }

        refuseSelf(reporter, comment.getUser(), "You cannot report your own comment");

        report.setType(Report.Type.POST_COMMENT);
        report.setReportedUser(comment.getUser());
        report.setReportedPostComment(comment);
        return submit(reporter, report);
    }

    @Transactional(readOnly = true)
    public List<Report> getMine(String email) {
        return reportRepository.findByReporterIdOrderByCreatedAtDesc(requireReporter(email).getId());
    }

    // Reason and details are checked before anything is looked up.
    private static Report draft(String reason, String details) {
        Report report = new Report();
        report.setReason(reason(reason).name());
        report.setDetails(details(details));
        return report;
    }

    static Reason reason(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);

        for (Reason reason : Reason.values()) {
            if (reason.name().equals(code)) {
                return reason;
            }
        }

        throw new ReportRefusedException(HttpStatus.BAD_REQUEST, "INVALID_REASON", INVALID_REASON);
    }

    // Null when nothing worth keeping was written.
    static String details(String value) {
        String text = value == null ? "" : value.strip();

        if (text.length() > MAX_DETAILS_LENGTH) {
            throw new ReportRefusedException(HttpStatus.BAD_REQUEST, "DETAILS_TOO_LONG",
                    "Your explanation can be at most " + MAX_DETAILS_LENGTH + " characters.");
        }

        return text.isEmpty() ? null : text;
    }

    private User requireReporter(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ReportRefusedException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "User not found"));

        if (!"USER".equals(user.getRole()) || user.isBanned()) {
            throw new ReportRefusedException(HttpStatus.FORBIDDEN, "FORBIDDEN", "User account required");
        }

        return user;
    }

    private static void refuseSelf(User reporter, User target, String message) {
        if (reporter.getId().equals(target.getId())) {
            throw new ReportRefusedException(HttpStatus.BAD_REQUEST, "SELF_REPORT", message);
        }
    }

    private Report submit(User reporter, Report report) {
        report.setReporter(reporter);

        // Without every unique index a second active report could get in, so none is written.
        if (!reportConstraints.missingSafeguards().isEmpty()) {
            throw unavailable();
        }

        // Held until this transaction ends, so a second request from the same reporter sees this one.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> null,
                "report:" + reporter.getId());

        if (hasActive(reporter, report)) {
            throw alreadyReported();
        }

        try {
            return reportRepository.saveAndFlush(report);
        } catch (DataIntegrityViolationException exception) {
            // The unique indexes settle whatever the lock did not (an older release writing at the same time).
            if (String.valueOf(exception.getMostSpecificCause().getMessage()).contains("uk_reports_active_")) {
                throw alreadyReported();
            }

            throw unavailable();
        }
    }

    private boolean hasActive(User reporter, Report report) {
        Long reporterId = reporter.getId();

        return switch (report.getType()) {
            case USER -> reportRepository.existsByReporterIdAndTypeAndReportedUserIdAndStatusIn(
                    reporterId, Report.Type.USER, report.getReportedUser().getId(), ACTIVE_STATUSES);
            case COMMENT -> reportRepository.existsByReporterIdAndReportedCommentIdAndStatusIn(
                    reporterId, report.getReportedComment().getId(), ACTIVE_STATUSES);
            case ROUTINE -> reportRepository.existsByReporterIdAndReportedRoutineIdAndStatusIn(
                    reporterId, report.getReportedRoutine().getId(), ACTIVE_STATUSES);
            case POST -> reportRepository.existsByReporterIdAndReportedPostIdAndStatusIn(
                    reporterId, report.getReportedPost().getId(), ACTIVE_STATUSES);
            case POST_COMMENT -> reportRepository.existsByReporterIdAndReportedPostCommentIdAndStatusIn(
                    reporterId, report.getReportedPostComment().getId(), ACTIVE_STATUSES);
        };
    }

    private static ReportRefusedException alreadyReported() {
        return new ReportRefusedException(HttpStatus.CONFLICT, "ALREADY_REPORTED", ALREADY_REPORTED);
    }

    private static ReportRefusedException unavailable() {
        return new ReportRefusedException(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE",
                "Reports can't be sent right now. Please try again later.");
    }

    private static ReportRefusedException notFound(String message) {
        return new ReportRefusedException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
