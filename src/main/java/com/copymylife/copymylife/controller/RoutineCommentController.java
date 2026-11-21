package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.CommentRequest;
import com.copymylife.copymylife.dto.CommentResponse;
import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.service.RoutineCommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/routine-comments")
public class RoutineCommentController {

    private final RoutineCommentService routineCommentService;

    public RoutineCommentController(
            RoutineCommentService routineCommentService
    ) {
        this.routineCommentService =
                routineCommentService;
    }

    @PostMapping("/{routineId}")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable Long routineId,
            @RequestBody CommentRequest request,
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                routineCommentService.addComment(
                        authentication.getName(),
                        routineId,
                        request.getText(),
                        request.getParentId()
                )
        );
    }

    @GetMapping("/routine/{routineId}")
    public ResponseEntity<List<CommentResponse>>
    getRoutineComments(
            @PathVariable Long routineId,
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                routineCommentService
                        .getRoutineComments(
                                authentication.getName(),
                                routineId
                        )
        );
    }

    @GetMapping("/commented-routines")
    public ResponseEntity<List<RoutineResponse>>
    getCommentedRoutines(
            Authentication authentication
    ) {

        return ResponseEntity.ok(
                routineCommentService
                        .getCommentedRoutines(
                                authentication.getName()
                        )
        );
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> deleteComment(
            @PathVariable Long commentId,
            Authentication authentication
    ) {

        boolean deleted =
                routineCommentService
                        .deleteComment(
                                authentication.getName(),
                                commentId
                        );

        if (deleted) {
            return ResponseEntity
                    .noContent()
                    .build();
        }

        return ResponseEntity
                .notFound()
                .build();
    }
}