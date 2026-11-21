package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.CreatorStatsResponse;
import com.copymylife.copymylife.dto.FollowStatusResponse;
import com.copymylife.copymylife.dto.FollowerUserResponse;
import com.copymylife.copymylife.dto.FollowingUserResponse;
import com.copymylife.copymylife.dto.UserResponse;
import com.copymylife.copymylife.dto.UserUpdateRequest;
import com.copymylife.copymylife.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.copymylife.copymylife.dto.ProfileResponse;
import com.copymylife.copymylife.dto.ProfileUpdateRequest;
import com.copymylife.copymylife.dto.UserSearchResponse;
import com.copymylife.copymylife.dto.UserProfileResponse;
import com.copymylife.copymylife.service.CreatorStatsService;
import com.copymylife.copymylife.service.FileUploadService;
import com.copymylife.copymylife.service.FriendshipService;
import com.copymylife.copymylife.dto.UserPublicRoutineResponse;
import com.copymylife.copymylife.service.RoutineService;
import com.copymylife.copymylife.service.UserFollowService;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final FriendshipService friendshipService;
    private final RoutineService routineService;
    private final UserFollowService userFollowService;
    private final FileUploadService fileUploadService;
    private final CreatorStatsService creatorStatsService;


    public UserController(UserService userService, FriendshipService friendshipService, RoutineService routineService, UserFollowService userFollowService, FileUploadService fileUploadService, CreatorStatsService creatorStatsService) {
        this.userService = userService;
        this.friendshipService = friendshipService;
        this.routineService = routineService;
        this.userFollowService = userFollowService;
        this.fileUploadService = fileUploadService;
        this.creatorStatsService = creatorStatsService;
    }

    @GetMapping
    public List<UserResponse> getUsers(Authentication authentication) {
        if (authentication == null ||
                authentication.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Admin access required"
            );
        }

        return userService.getUsers();
    }

    // US-05: open to visitors, who find the same public profiles; a signed-in user is left out
    // of their own results.
    @GetMapping("/search")
    public ResponseEntity<List<UserSearchResponse>> searchUsers(
            @RequestParam String query,
            Authentication authentication
    ) {

        String currentEmail = isVisitor(authentication) ? null : authentication.getName();

        return ResponseEntity.ok(
                userService.searchUsers(
                        query,
                        currentEmail
                )
        );
    }

    @GetMapping("/{id}/profile")
    public ResponseEntity<UserProfileResponse> getUserProfile(
            @PathVariable Long id,
            Authentication authentication
    ) {
        if (!userService.isVisiblePublicUser(id)) {
            return ResponseEntity.notFound().build();
        }

        // US-05: a visitor reads the public profile, with no friendship or follow of their own.
        if (isVisitor(authentication)) {
            UserProfileResponse profile = friendshipService.getGuestProfile(id);
            FollowStatusResponse counts = userFollowService.getCounts(id);

            profile.setFollowersCount(counts.getFollowersCount());
            profile.setFollowingCount(counts.getFollowingCount());

            return ResponseEntity.ok(profile);
        }

        UserProfileResponse profile =
                friendshipService.getUserProfile(
                        id,
                        authentication.getName()
                );

        FollowStatusResponse followStatus =
                userFollowService.getStatus(
                        id,
                        authentication.getName()
                );

        profile.setFollowersCount(followStatus.getFollowersCount());
        profile.setFollowingCount(followStatus.getFollowingCount());
        profile.setFollowing(followStatus.isFollowing());

        return ResponseEntity.ok(profile);
    }

    @PostMapping("/{id}/follow")
    public ResponseEntity<FollowStatusResponse> followUser(@PathVariable Long id, Authentication authentication) {

        return ResponseEntity.ok(userFollowService.follow(authentication.getName(), id));
    }

    @DeleteMapping("/{id}/follow")
    public ResponseEntity<FollowStatusResponse> unfollowUser(@PathVariable Long id, Authentication authentication) {

        return ResponseEntity.ok(userFollowService.unfollow(authentication.getName(), id));
    }

    // Only ever the signed-in user's own list.
    @GetMapping("/me/following")
    public ResponseEntity<List<FollowingUserResponse>> getMyFollowing(Authentication authentication) {

        return ResponseEntity.ok(userFollowService.getFollowing(authentication.getName()));
    }

    // Only ever the signed-in user's own followers.
    @GetMapping("/me/followers")
    public ResponseEntity<List<FollowerUserResponse>> getMyFollowers(Authentication authentication) {

        return ResponseEntity.ok(userFollowService.getFollowers(authentication.getName()));
    }

    @GetMapping("/{id}/routines/public")
    public ResponseEntity<List<UserPublicRoutineResponse>> getUserPublicRoutines(
            @PathVariable Long id
    ) {
        if (!userService.isVisiblePublicUser(id)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(
                routineService.getUserPublicRoutines(id)
        );
    }
    

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser_ID(@PathVariable long id, Authentication authentication){
        String currentEmail = authentication.getName();

        Optional<UserResponse> targetUser = userService.getUserById(id);

        if (!targetUser.isPresent()) {
            return ResponseEntity.notFound().build();
        }

        if (!targetUser.get().getEmail().equals(currentEmail)) {
            return ResponseEntity.status(403).build();
        }

        return ResponseEntity.ok(targetUser.get());
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request, Authentication authentication){
        String currentEmail = authentication.getName();
        Optional<UserResponse> targetUser  =  userService.getUserById(id);

        if (!targetUser.isPresent()){
            return ResponseEntity.status(404).build();
        } else if (!targetUser.get().getEmail().equals(currentEmail)) {
            return ResponseEntity.status(403).build();
        }
        Optional<UserResponse> updatedUser = userService.updateUser(id, request);

        if (updatedUser.isPresent()) {
            return ResponseEntity.ok(updatedUser.get());
        }

        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable long id, Authentication authentication) {

        String currentEmail = authentication.getName();

        Optional<UserResponse> targetUser = userService.getUserById(id);

        if (!targetUser.isPresent()) {
            return ResponseEntity.notFound().build();
        }
        else if (!targetUser.get().getEmail().equals(currentEmail)) {
            return ResponseEntity.status(403).build();
        }



        userService.deleteById(id);

        return ResponseEntity.noContent().build();
    }

    // US-12: the signed-in member's own followers and routine copies; nobody else's can be asked for.
    @GetMapping("/me/creator-stats")
    public ResponseEntity<CreatorStatsResponse> getMyCreatorStats(Authentication authentication) {
        return ResponseEntity.ok(creatorStatsService.getMine(authentication.getName()));
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileResponse> getMyProfile(Authentication authentication) {

        String email = authentication.getName();

        Optional<ProfileResponse> profile =
                userService.getProfileByEmail(email);

        if (profile.isPresent()) {
            FollowStatusResponse followStatus =
                    userFollowService.getStatus(profile.get().getId(), email);

            profile.get().setFollowersCount(followStatus.getFollowersCount());
            profile.get().setFollowingCount(followStatus.getFollowingCount());

            return ResponseEntity.ok(profile.get());
        }

        return ResponseEntity.notFound().build();
    }

    // Only for the signed-in user; the image goes through the routine image checks.
    @PostMapping(value = "/me/avatar", consumes = "multipart/form-data")
    public ResponseEntity<ProfileResponse> uploadAvatar(@RequestPart("image") MultipartFile image, Authentication authentication) {

        String avatarUrl = fileUploadService.storeAvatar(image);

        return userService.updateAvatar(authentication.getName(), avatarUrl)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/me")
    public ResponseEntity<ProfileResponse> updateMyProfile(@RequestBody ProfileUpdateRequest request, Authentication authentication) {

        String email = authentication.getName();

        Optional<ProfileResponse> updatedProfile =
                userService.updateProfile(email, request);

        if (updatedProfile.isPresent()) {
            return ResponseEntity.ok(updatedProfile.get());
        }

        return ResponseEntity.notFound().build();
    }

    private static boolean isVisitor(Authentication authentication) {
        return authentication == null || authentication instanceof AnonymousAuthenticationToken;
    }
}
