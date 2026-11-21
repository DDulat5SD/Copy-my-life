
package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

// A report names exactly one target, the one its type uses; reportedUser is always the person
// behind it (the user, or the author of the comment, routine or post). US-24: the routine, post and
// post-comment links are emptied, not cascaded, when that content is deleted, so the report and its
// review history stay. The database also enforces one target per type and one active report per
// reporter and target (ReportConstraints, db/manual/report_content_targets.sql).
@Entity
@Table(
        name = "reports",
        indexes = {
                @Index(name = "idx_reports_routine", columnList = "reported_routine_id"),
                @Index(name = "idx_reports_post", columnList = "reported_post_id"),
                @Index(name = "idx_reports_post_comment", columnList = "reported_post_comment_id")
        }
)
public class Report {

    // COMMENT is a comment under a routine, as before US-24.
    public enum Type {
        USER, COMMENT, ROUTINE, POST, POST_COMMENT
    }

    public enum Status {
        OPEN, IN_REVIEW, RESOLVED, REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_user_id", nullable = false)
    private User reportedUser;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_comment_id")
    private RoutineComment reportedComment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_routine_id", foreignKey = @ForeignKey(name = "fk_reports_routine"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Routine reportedRoutine;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_post_id", foreignKey = @ForeignKey(name = "fk_reports_post"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Post reportedPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_post_comment_id", foreignKey = @ForeignKey(name = "fk_reports_post_comment"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private PostComment reportedPostComment;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Type type;

    @Column(nullable = false, length = 120)
    private String reason;

    @Column(length = 1000)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    @Column(name = "action_taken", nullable = false, length = 30)
    private String actionTaken = "NONE";

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.OPEN;
        if (actionTaken == null) actionTaken = "NONE";
    }

    public Long getId() { return id; }

    public User getReporter() { return reporter; }
    public void setReporter(User reporter) { this.reporter = reporter; }

    public User getReportedUser() { return reportedUser; }
    public void setReportedUser(User reportedUser) {
        this.reportedUser = reportedUser;
    }

    public RoutineComment getReportedComment() {
        return reportedComment;
    }
    public void setReportedComment(RoutineComment reportedComment) {
        this.reportedComment = reportedComment;
    }

    public Routine getReportedRoutine() { return reportedRoutine; }
    public void setReportedRoutine(Routine reportedRoutine) {
        this.reportedRoutine = reportedRoutine;
    }

    public Post getReportedPost() { return reportedPost; }
    public void setReportedPost(Post reportedPost) {
        this.reportedPost = reportedPost;
    }

    public PostComment getReportedPostComment() {
        return reportedPostComment;
    }
    public void setReportedPostComment(PostComment reportedPostComment) {
        this.reportedPostComment = reportedPostComment;
    }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public User getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) {
        this.reviewNote = reviewNote;
    }

    public String getActionTaken() { return actionTaken; }
    public void setActionTaken(String actionTaken) {
        this.actionTaken = actionTaken;
    }
}
