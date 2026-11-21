package com.copymylife.copymylife.dto;

import java.time.Instant;

// The author part carries only what a public profile already shows.
public record PostCommentResponse(
        Long id,
        Long postId,
        String text,
        boolean ownedByMe,
        Long authorId,
        String authorName,
        String authorUsername,
        String authorAvatarUrl,
        Instant createdAt
) {
}
