package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.CommentResponse;
import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineComment;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineCommentRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoutineCommentService {

    // The routine_comments.text column holds 1000 characters.
    static final int MAX_TEXT_LENGTH = 1000;

    private final RoutineCommentRepository routineCommentRepository;
    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final RoutineAccessService routineAccessService;

    public RoutineCommentService(
            RoutineCommentRepository routineCommentRepository,
            RoutineRepository routineRepository,
            UserRepository userRepository,
            RoutineAccessService routineAccessService
    ) {
        this.routineCommentRepository = routineCommentRepository;
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.routineAccessService = routineAccessService;
    }

    public CommentResponse addComment(
            String email,
            Long routineId,
            String text,
            Long parentId
    ) {

        // US-15: an empty, blank or too long comment is the caller's mistake (400), not a server error.
        if (
                text == null ||
                        text.isBlank()
        ) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Comment cannot be empty"
            );
        }

        if (text.trim().length() > MAX_TEXT_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A comment can be at most " + MAX_TEXT_LENGTH + " characters"
            );
        }

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "User not found"
                                )
                        );

        Routine routine =
                routineAccessService.requireViewableRoutine(routineId, user);

        RoutineComment parentComment =
                null;

        if (parentId != null) {

            // A parent under another routine answers like a missing one, so its id reveals nothing.
            RoutineComment requestedParent =
                    routineCommentRepository
                            .findById(parentId)
                            .filter(parent -> parent.getRoutine().getId().equals(routineId))
                            .orElseThrow(
                                    () -> new ResponseStatusException(
                                            HttpStatus.NOT_FOUND,
                                            "Comment not found"
                                    )
                            );

            if (
                    requestedParent.getParentComment()
                            != null
            ) {
                parentComment =
                        requestedParent.getParentComment();
            } else {
                parentComment =
                        requestedParent;
            }
        }

        RoutineComment comment =
                new RoutineComment();

        comment.setUser(user);
        comment.setRoutine(routine);
        comment.setText(text.trim());
        comment.setParentComment(parentComment);

        RoutineComment saved =
                routineCommentRepository.save(
                        comment
                );

        return toCommentResponse(saved);
    }


    @Transactional(readOnly = true)
    public List<CommentResponse> getRoutineComments(String email, Long routineId) {

        User user = userRepository.findByEmail(email).orElse(null);

        routineAccessService.requireViewableRoutine(routineId, user);

        List<RoutineComment> comments = routineCommentRepository
                .findByRoutineIdOrderByCreatedAtDesc(routineId);

        java.util.Set<Long> rootsWithReplies = comments.stream()
                .filter(comment -> !comment.isDeleted())
                .filter(comment -> comment.getParentComment() != null)
                .map(comment -> comment.getParentComment().getId())
                .collect(java.util.stream.Collectors.toSet());

        List<CommentResponse> responses = new ArrayList<>();

        for (RoutineComment comment : comments) {
            if (comment.isDeleted()) {
                if (comment.getParentComment() != null ||
                        !rootsWithReplies.contains(comment.getId())) {
                    continue;
                }
            }

            responses.add(toCommentResponse(comment));
        }

        return responses;
    }


    @Transactional(readOnly = true)
    public List<RoutineResponse> getCommentedRoutines(
            String email
    ) {

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () -> new RuntimeException(
                                        "User not found"
                                )
                        );

        List<RoutineComment> comments =
                routineCommentRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                user.getId()
                        );

        Map<Long, RoutineResponse> routines =
                new LinkedHashMap<>();

        for (
                RoutineComment comment :
                comments
        ) {

            Routine routine =
                    comment.getRoutine();

            if (comment.isDeleted() || !routineAccessService.canViewRoutine(routine, user)) {
                continue;
            }

            routines.putIfAbsent(
                    routine.getId(),
                    toRoutineResponse(routine)
            );
        }

        return new ArrayList<>(
                routines.values()
        );
    }


    @Transactional
    public boolean deleteComment(
            String email,
            Long commentId
    ) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        RoutineComment comment = routineCommentRepository
                .findByIdAndUserId(commentId, user.getId())
                .filter(c -> !c.isDeleted())
                .orElse(null);

        if (comment == null) {
            return false;
        }

        comment.setDeleted(true);
        comment.setDeletedAt(java.time.LocalDateTime.now());

        routineCommentRepository.save(comment);

        return true;
    }



    private CommentResponse toCommentResponse(
            RoutineComment comment
    ) {
        CommentResponse response = new CommentResponse();

        response.setId(comment.getId());

        if (comment.getParentComment() != null) {
            response.setParentId(
                    comment.getParentComment().getId()
            );
        }

        response.setCreatedAt(comment.getCreatedAt());

        if (comment.isDeleted()) {
            response.setText("This comment has been removed");
            response.setUserName("Removed comment");
            response.setUsername(null);
            response.setAvatarUrl(null);
            response.setUserId(null);
        } else {
            response.setText(comment.getText());
            response.setUserId(comment.getUser().getId());
            response.setUserName(comment.getUser().getName());
            response.setUsername(comment.getUser().getUsername());
            response.setAvatarUrl(comment.getUser().getAvatarUrl());
        }

        return response;
    }


    private RoutineResponse toRoutineResponse(
            Routine routine
    ) {

        RoutineResponse response =
                new RoutineResponse();

        response.setId(
                routine.getId()
        );

        response.setTitle(
                routine.getTitle()
        );

        response.setDescription(
                routine.getDescription()
        );

        response.setDurationMinutes(
                routine.getDurationMinutes()
        );

        response.setCategory(
                routine.getCategory()
        );

        response.setPublic(
                routine.isPublic()
        );

        response.setImageUrl(
                routine.getImageUrl()
        );

        response.setCompleted(
                routine.isCompleted()
        );

        response.setAverageRating(
                routine.getAverageRating()
        );

        response.setRatingCount(
                routine.getRatingCount()
        );

        return response;
    }
}