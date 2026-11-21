package com.copymylife.copymylife.dto;

// Someone who follows the user: only what their public profile already shows, and whether
// the user follows them back.
public class FollowerUserResponse {

    private final Long userId;
    private final String name;
    private final String username;
    private final String avatarUrl;
    private final boolean followedBack;

    public FollowerUserResponse(Long userId, String name, String username, String avatarUrl, boolean followedBack) {
        this.userId = userId;
        this.name = name;
        this.username = username;
        this.avatarUrl = avatarUrl;
        this.followedBack = followedBack;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public boolean isFollowedBack() {
        return followedBack;
    }
}
