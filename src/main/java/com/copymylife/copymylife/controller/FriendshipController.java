package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.FriendRequestResponse;
import com.copymylife.copymylife.dto.FriendResponse;
import com.copymylife.copymylife.service.FriendshipService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/friends")
public class FriendshipController {

    private final FriendshipService friendshipService;

    public FriendshipController(FriendshipService friendshipService) {
        this.friendshipService = friendshipService;
    }

    @PostMapping("/request/{receiverId}")
    public ResponseEntity<FriendRequestResponse> sendFriendRequest(
            @PathVariable Long receiverId,
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.sendFriendRequest(
                        email,
                        receiverId
                )
        );
    }

    @GetMapping("/requests")
    public ResponseEntity<List<FriendRequestResponse>> getIncomingRequests(
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.getIncomingRequests(email)
        );
    }

    @GetMapping("/requests/sent")
    public ResponseEntity<List<FriendRequestResponse>> getSentRequests(
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.getSentRequests(email)
        );
    }

    @PutMapping("/requests/{requestId}/accept")
    public ResponseEntity<FriendResponse> acceptFriendRequest(
            @PathVariable Long requestId,
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.acceptFriendRequest(
                        email,
                        requestId
                )
        );
    }

    @PutMapping("/requests/{requestId}/reject")
    public ResponseEntity<FriendRequestResponse> rejectFriendRequest(
            @PathVariable Long requestId,
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.rejectFriendRequest(
                        email,
                        requestId
                )
        );
    }

    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<Void> cancelFriendRequest(
            @PathVariable Long requestId,
            Authentication authentication
    ) {

        String email = authentication.getName();

        friendshipService.cancelFriendRequest(
                email,
                requestId
        );

        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<FriendResponse>> getFriends(
            Authentication authentication
    ) {

        String email = authentication.getName();

        return ResponseEntity.ok(
                friendshipService.getFriends(email)
        );
    }

    @DeleteMapping("/{friendUserId}")
    public ResponseEntity<Void> removeFriend(
            @PathVariable Long friendUserId,
            Authentication authentication
    ) {

        String email = authentication.getName();

        friendshipService.removeFriend(
                email,
                friendUserId
        );

        return ResponseEntity.noContent().build();
    }
}