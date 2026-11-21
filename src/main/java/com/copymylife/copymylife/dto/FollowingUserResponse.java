package com.copymylife.copymylife.dto;

// A creator the user follows: only what their public profile already shows.
public class FollowingUserResponse {

    private final Long userId;
    private final String name;
    private final String username;
    private final String avatarUrl;

    public FollowingUserResponse(Long userId, String name, String username, String avatarUrl) {
        this.userId = userId;
        this.name = name;
        this.username = username;
        this.avatarUrl = avatarUrl;
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
}
