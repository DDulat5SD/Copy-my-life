package com.copymylife.copymylife.dto;

import java.time.Instant;
import java.util.List;

// The author part carries only what a public profile already shows.
public record TodoListResponse(
        Long id,
        String title,
        boolean isPublic,
        boolean ownedByMe,
        Long authorId,
        String authorName,
        String authorUsername,
        List<Task> tasks,
        Instant createdAt,
        Instant updatedAt
) {

    public record Task(Long id, String text, boolean done) {
    }
}
