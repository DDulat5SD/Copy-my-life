package com.copymylife.copymylife.dto;

// The post's like count as stored, and whether the caller's like is among them.
public record PostLikeResponse(
        Long postId,
        long likeCount,
        boolean likedByMe
) {
}
