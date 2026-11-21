package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.FriendRequestResponse;
import com.copymylife.copymylife.dto.FriendResponse;
import com.copymylife.copymylife.model.FriendStatus;
import com.copymylife.copymylife.model.Friendship;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.FriendshipRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.copymylife.copymylife.dto.UserProfileResponse;
import java.util.Optional;

// US-29: one row per pair of users holds their relationship: a pending request, an accepted
// friendship or a declined request. A friendship is mutual by being that one row, so accepting or
// removing it changes both friend lists at once. Changes to one pair wait for each other, and the
// pair index (FriendshipPairIndex) keeps a second row out; refusals answer with a status and a reason.
@Service
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    public FriendshipService(
            FriendshipRepository friendshipRepository,
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate
    ) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public FriendRequestResponse sendFriendRequest(
            String email,
            Long receiverId
    ) {

        User sender = requireUser(email);

        if (sender.getId().equals(receiverId)) {
            throw refused(HttpStatus.BAD_REQUEST, "You cannot send a friend request to yourself");
        }

        // As with follows, only members whose public profile may be seen can be asked.
        User receiver = userRepository.findById(receiverId)
                .filter(u -> "USER".equals(u.getRole()) && !u.isBanned() && !u.isProfileHiddenByAdmin())
                .orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "User not found"));

        if (!"USER".equals(sender.getRole())) {
            throw refused(HttpStatus.BAD_REQUEST, "Administrator accounts cannot send friend requests");
        }

        lockPair(sender.getId(), receiver.getId());

        Optional<Friendship> existingFriendship = findBetween(sender.getId(), receiver.getId());

        if (existingFriendship.isPresent()) {

            Friendship existing = existingFriendship.get();

            if (existing.getStatus() == FriendStatus.ACCEPTED) {
                throw refused(HttpStatus.CONFLICT, "You are already friends");
            }

            if (existing.getStatus() == FriendStatus.PENDING) {

                if (existing.getSender().getId().equals(sender.getId())) {
                    throw refused(HttpStatus.CONFLICT, "Friend request already sent");
                }

                throw refused(HttpStatus.CONFLICT, "This user has already sent you a friend request");
            }

            // A declined request may be sent again, by either of the two.
            existing.setSender(sender);
            existing.setReceiver(receiver);
            existing.setStatus(FriendStatus.PENDING);

            return toRequestResponse(save(existing), receiver);
        }

        Friendship friendship = new Friendship();

        friendship.setSender(sender);
        friendship.setReceiver(receiver);
        friendship.setStatus(FriendStatus.PENDING);

        return toRequestResponse(save(friendship), receiver);
    }

    @Transactional
    public FriendResponse acceptFriendRequest(
            String email,
            Long requestId
    ) {

        User currentUser = requireUser(email);
        Friendship friendship = pendingRequestTo(currentUser, requestId);

        friendship.setStatus(FriendStatus.ACCEPTED);

        friendshipRepository.saveAndFlush(friendship);

        return toFriendResponse(
                friendship,
                friendship.getSender()
        );
    }

    @Transactional
    public FriendRequestResponse rejectFriendRequest(
            String email,
            Long requestId
    ) {

        User currentUser = requireUser(email);
        Friendship friendship = pendingRequestTo(currentUser, requestId);

        // The request leaves both lists; the row only remembers it was declined.
        friendship.setStatus(FriendStatus.REJECTED);

        return toRequestResponse(
                friendshipRepository.saveAndFlush(friendship),
                friendship.getSender()
        );
    }

    @Transactional
    public void cancelFriendRequest(
            String email,
            Long requestId
    ) {

        User currentUser = requireUser(email);

        Friendship friendship =
                friendshipRepository
                        .findByIdAndSenderIdForUpdate(requestId, currentUser.getId())
                        .orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "Friend request not found"));

        if (friendship.getStatus() != FriendStatus.PENDING) {
            throw refused(HttpStatus.CONFLICT, "Friend request is not pending");
        }

        friendshipRepository.delete(friendship);
    }

    // Only the receiver answers, and only once: the request is held while it is answered.
    private Friendship pendingRequestTo(User receiver, Long requestId) {
        Friendship friendship =
                friendshipRepository
                        .findByIdAndReceiverIdForUpdate(requestId, receiver.getId())
                        .orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "Friend request not found"));

        if (friendship.getStatus() != FriendStatus.PENDING) {
            throw refused(HttpStatus.CONFLICT, "Friend request is not pending");
        }

        return friendship;
    }

    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getIncomingRequests(
            String email
    ) {

        User currentUser = requireUser(email);

        List<Friendship> friendships =
                friendshipRepository
                        .findByReceiverIdAndStatusOrderByCreatedAtDesc(
                                currentUser.getId(),
                                FriendStatus.PENDING
                        );

        List<FriendRequestResponse> responses =
                new ArrayList<>();

        for (Friendship friendship : friendships) {

            responses.add(
                    toRequestResponse(
                            friendship,
                            friendship.getSender()
                    )
            );
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<FriendRequestResponse> getSentRequests(
            String email
    ) {

        User currentUser = requireUser(email);

        List<Friendship> friendships =
                friendshipRepository
                        .findBySenderIdAndStatusOrderByCreatedAtDesc(
                                currentUser.getId(),
                                FriendStatus.PENDING
                        );

        List<FriendRequestResponse> responses =
                new ArrayList<>();

        for (Friendship friendship : friendships) {

            responses.add(
                    toRequestResponse(
                            friendship,
                            friendship.getReceiver()
                    )
            );
        }

        return responses;
    }

    @Transactional(readOnly = true)
    public List<FriendResponse> getFriends(
            String email
    ) {

        User currentUser = requireUser(email);

        List<Friendship> friendships =
                friendshipRepository.findByUserIdAndStatus(
                        currentUser.getId(),
                        FriendStatus.ACCEPTED
                );

        List<FriendResponse> responses =
                new ArrayList<>();

        for (Friendship friendship : friendships) {

            User friend;

            if (friendship.getSender()
                    .getId()
                    .equals(currentUser.getId())) {

                friend = friendship.getReceiver();

            } else {

                friend = friendship.getSender();
            }

            responses.add(
                    toFriendResponse(
                            friendship,
                            friend
                    )
            );
        }

        return responses;
    }

    // Either friend ends the friendship for both; nobody else can.
    @Transactional
    public void removeFriend(
            String email,
            Long friendUserId
    ) {

        User currentUser = requireUser(email);

        lockPair(currentUser.getId(), friendUserId);

        Friendship friendship =
                findBetween(currentUser.getId(), friendUserId)
                        .filter(found -> found.getStatus() == FriendStatus.ACCEPTED)
                        .orElseThrow(() -> refused(HttpStatus.NOT_FOUND, "This user is not your friend"));

        friendshipRepository.delete(friendship);
    }

    // The pair's relationship; a friendship counts over a request if rows from before the pair index disagree.
    private Optional<Friendship> findBetween(Long userId, Long otherId) {
        return friendshipRepository.findAllBetweenUsers(userId, otherId)
                .stream()
                .min(Comparator.comparing((Friendship friendship) -> switch (friendship.getStatus()) {
                    case ACCEPTED -> 0;
                    case PENDING -> 1;
                    case REJECTED -> 2;
                }));
    }

    // Requests, answers and removals of one pair of users wait for each other until commit.
    private void lockPair(Long userId, Long otherId) {
        long low = Math.min(userId, otherId);
        long high = Math.max(userId, otherId);

        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))", resultSet -> null, "friends:" + low + ":" + high);
    }

    // The database has the last word: a second row for the pair is refused as a duplicate.
    private Friendship save(Friendship friendship) {
        try {
            return friendshipRepository.saveAndFlush(friendship);
        } catch (DataIntegrityViolationException exception) {
            throw refused(HttpStatus.CONFLICT, "Friend request already sent");
        }
    }

    private User requireUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> refused(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private static ResponseStatusException refused(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private FriendResponse toFriendResponse(
            Friendship friendship,
            User user
    ) {

        FriendResponse response =
                new FriendResponse();

        response.setFriendshipId(friendship.getId());
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setUsername(user.getUsername());
        response.setAvatarUrl(user.getAvatarUrl());

        return response;
    }

    private FriendRequestResponse toRequestResponse(
            Friendship friendship,
            User user
    ) {

        FriendRequestResponse response =
                new FriendRequestResponse();

        response.setRequestId(friendship.getId());
        response.setUserId(user.getId());
        response.setName(user.getName());
        response.setUsername(user.getUsername());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setStatus(friendship.getStatus());
        response.setCreatedAt(friendship.getCreatedAt());

        return response;
    }

    // US-05: what a visitor sees, the same public fields as anyone signed in sees.
    public UserProfileResponse getGuestProfile(Long userId) {

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserProfileResponse response = publicProfile(targetUser);
        response.setRelationshipStatus("GUEST");

        return response;
    }

    // Everything on a public profile; never the e-mail address or anything about the account.
    private UserProfileResponse publicProfile(User user) {

        UserProfileResponse response = new UserProfileResponse();

        response.setId(user.getId());
        response.setName(user.getName());
        response.setUsername(user.getUsername());
        response.setProfession(user.getProfession());
        response.setBio(user.getBio());
        response.setInterests(user.getInterests());
        response.setGoals(user.getGoals());
        response.setAvatarUrl(user.getAvatarUrl());

        return response;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile(Long userId, String currentEmail) {

        User currentUser = userRepository.findByEmail(currentEmail)
                .orElseThrow(() -> new RuntimeException("Current user not found"));

        User targetUser = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        UserProfileResponse response = publicProfile(targetUser);

        if (currentUser.getId().equals(targetUser.getId())) {
            response.setRelationshipStatus("SELF");
            response.setRequestId(null);
            return response;
        }

        Optional<Friendship> friendshipOptional =
                findBetween(
                        currentUser.getId(),
                        targetUser.getId()
                );

        if (friendshipOptional.isEmpty()) {
            response.setRelationshipStatus("NONE");
            response.setRequestId(null);
            return response;
        }

        Friendship friendship = friendshipOptional.get();

        if (friendship.getStatus() == FriendStatus.ACCEPTED) {
            response.setRelationshipStatus("FRIENDS");
            response.setRequestId(null);
            return response;
        }

        if (friendship.getStatus() == FriendStatus.PENDING) {

            response.setRequestId(friendship.getId());

            if (friendship.getSender().getId().equals(currentUser.getId())) {
                response.setRelationshipStatus("PENDING");
            } else {
                response.setRelationshipStatus("RECEIVED");
            }

            return response;
        }

        response.setRelationshipStatus("NONE");
        response.setRequestId(null);

        return response;
    }
}
