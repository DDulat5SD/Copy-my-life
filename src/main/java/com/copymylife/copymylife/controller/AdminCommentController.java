
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.RoutineComment;
import com.copymylife.copymylife.repository.RoutineCommentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin-api/comments")
public class AdminCommentController {

    private final RoutineCommentRepository commentRepository;

    public AdminCommentController(
            RoutineCommentRepository commentRepository
    ) {
        this.commentRepository = commentRepository;
    }

    public record AdminCommentResponse(
            Long id,
            Long routineId,
            String routineTitle,
            Long userId,
            String userName,
            String username,
            String userEmail,
            Long parentId,
            String text,
            LocalDateTime createdAt,
            boolean deleted,
            LocalDateTime deletedAt
    ) {}

    private RoutineComment findComment(Long commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Comment not found"
                ));
    }

    private AdminCommentResponse toResponse(RoutineComment comment) {
        return new AdminCommentResponse(
                comment.getId(),
                comment.getRoutine().getId(),
                comment.getRoutine().getTitle(),
                comment.getUser().getId(),
                comment.getUser().getName(),
                comment.getUser().getUsername(),
                comment.getUser().getEmail(),
                comment.getParentComment() == null
                        ? null
                        : comment.getParentComment().getId(),
                comment.getText(),
                comment.getCreatedAt(),
                comment.isDeleted(),
                comment.getDeletedAt()
        );
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AdminCommentResponse> getAllComments(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long routineId,
            @RequestParam(required = false) Boolean deleted
    ) {
        return commentRepository
                .findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .filter(comment ->
                        userId == null ||
                                comment.getUser().getId().equals(userId)
                )
                .filter(comment ->
                        routineId == null ||
                                comment.getRoutine().getId().equals(routineId)
                )
                .filter(comment ->
                        deleted == null ||
                                comment.isDeleted() == deleted
                )
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{commentId}")
    @Transactional(readOnly = true)
    public AdminCommentResponse getComment(
            @PathVariable Long commentId
    ) {
        return toResponse(findComment(commentId));
    }

    @DeleteMapping("/{commentId}")
    @Transactional
    public AdminCommentResponse deleteComment(
            @PathVariable Long commentId
    ) {
        RoutineComment comment = findComment(commentId);

        if (!comment.isDeleted()) {
            comment.setDeleted(true);
            comment.setDeletedAt(LocalDateTime.now());
            commentRepository.save(comment);
        }

        return toResponse(comment);
    }
}
