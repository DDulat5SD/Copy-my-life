package com.copymylife.copymylife.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class FollowStatusResponse {

    private final long followersCount;
    private final long followingCount;
    private final boolean following;

    public FollowStatusResponse(long followersCount, long followingCount, boolean following) {
        this.followersCount = followersCount;
        this.followingCount = followingCount;
        this.following = following;
    }

    public long getFollowersCount() {
        return followersCount;
    }

    public long getFollowingCount() {
        return followingCount;
    }

    @JsonProperty("isFollowing")
    public boolean isFollowing() {
        return following;
    }
}
