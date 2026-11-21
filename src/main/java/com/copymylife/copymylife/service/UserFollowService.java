package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.FollowStatusResponse;
import com.copymylife.copymylife.dto.FollowerUserResponse;
import com.copymylife.copymylife.dto.FollowingUserResponse;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.UserFollowRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserFollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserRepository userRepository;
    private final UserService userService;

    public UserFollowService(UserFollowRepository userFollowRepository, UserRepository userRepository, UserService userService) {
        this.userFollowRepository = userFollowRepository;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Transactional
    public FollowStatusResponse follow(String email, Long targetId) {
        User follower = requireUser(email);

        if (follower.getId().equals(targetId)) {
            throw new IllegalArgumentException("You cannot follow yourself");
        }

        if (!"USER".equals(follower.getRole())) {
            throw new IllegalArgumentException("Administrator accounts cannot follow users");
        }

        if (!userService.isVisiblePublicUser(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        userFollowRepository.insertIfAbsent(follower.getId(), targetId, LocalDateTime.now());

        return getStatus(targetId, follower.getId());
    }

    /**
     * Unlike follow, this works for a user who has since been banned: nobody should be
     * stuck with a link they want to remove.
     */
    @Transactional
    public FollowStatusResponse unfollow(String email, Long targetId) {
        User follower = requireUser(email);

        if (!userRepository.existsById(targetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }

        userFollowRepository.deletePair(follower.getId(), targetId);

        return getStatus(targetId, follower.getId());
    }

    // The creators the user follows, read from the stored links. It matches followingCount:
    // banned creators are left out of both. An avatar that fails the image policy is dropped.
    @Transactional(readOnly = true)
    public List<FollowingUserResponse> getFollowing(String email) {
        return userFollowRepository.findFollowing(requireUser(email).getId()).stream()
                .map(creator -> new FollowingUserResponse(
                        creator.getId(),
                        creator.getName(),
                        creator.getUsername(),
                        ImageUrlPolicy.isSafe(creator.getAvatarUrl()) ? creator.getAvatarUrl() : null
                ))
                .toList();
    }

    // The people who follow the user, matching followersCount, each marked when the user
    // already follows them back.
    @Transactional(readOnly = true)
    public List<FollowerUserResponse> getFollowers(String email) {
        Long userId = requireUser(email).getId();
        Set<Long> followedBack = userFollowRepository.findFollowing(userId).stream()
                .map(User::getId)
                .collect(Collectors.toSet());

        return userFollowRepository.findFollowers(userId).stream()
                .map(follower -> new FollowerUserResponse(
                        follower.getId(),
                        follower.getName(),
                        follower.getUsername(),
                        ImageUrlPolicy.isSafe(follower.getAvatarUrl()) ? follower.getAvatarUrl() : null,
                        followedBack.contains(follower.getId())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public FollowStatusResponse getStatus(Long targetId, String viewerEmail) {
        return getStatus(targetId, requireUser(viewerEmail).getId());
    }

    // A visitor follows nobody; they only see how many follow and are followed.
    @Transactional(readOnly = true)
    public FollowStatusResponse getCounts(Long targetId) {
        return new FollowStatusResponse(
                userFollowRepository.countFollowers(targetId),
                userFollowRepository.countFollowing(targetId),
                false
        );
    }

    private FollowStatusResponse getStatus(Long targetId, Long viewerId) {
        return new FollowStatusResponse(
                userFollowRepository.countFollowers(targetId),
                userFollowRepository.countFollowing(targetId),
                userFollowRepository.existsByFollowerIdAndFollowingId(viewerId, targetId)
        );
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
