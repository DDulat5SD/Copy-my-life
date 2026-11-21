package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.LifestyleMatchResponse;
import com.copymylife.copymylife.service.LifestyleMatchingService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// US-26: Find Similar People, for the signed-in user only; worked out afresh on every request.
@RestController
@RequestMapping("/users/me/lifestyle-matches")
public class LifestyleMatchingController {

    private final LifestyleMatchingService lifestyleMatchingService;

    public LifestyleMatchingController(LifestyleMatchingService lifestyleMatchingService) {
        this.lifestyleMatchingService = lifestyleMatchingService;
    }

    @GetMapping
    public ResponseEntity<LifestyleMatchResponse> findMatches(Authentication authentication) {
        return ResponseEntity.ok(lifestyleMatchingService.findMatches(authentication.getName()));
    }
}
