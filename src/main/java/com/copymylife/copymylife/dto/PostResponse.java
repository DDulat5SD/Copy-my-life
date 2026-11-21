package com.copymylife.copymylife.dto;

import java.time.Instant;

// The author part carries only what a public profile already shows.
public record PostResponse(
        Long id,
        String text,
        String imageUrl,
        boolean isPublic,
        boolean ownedByMe,
        Long authorId,
        String authorName,
        String authorUsername,
        String authorAvatarUrl,
        Instant createdAt,
        Instant updatedAt,
        long likeCount,
        boolean likedByMe,
        long commentCount,
        // US-25: only ever true for the author, the one person who still sees a hidden post.
        boolean hiddenByAdmin,
        // US-16: whether the viewer bookmarked it.
        boolean savedByMe
) {
}
