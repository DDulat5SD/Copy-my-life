package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.service.CategoryVoteService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

// US-28: a signed-in user's view of a routine's category vote, and their one vote.
// The voter is always the signed-in user (SecurityConfig needs a token for both).
@RestController
@RequestMapping("/routines/{routineId}/category-vote")
public class CategoryVoteController {

    private final CategoryVoteService categoryVoteService;

    public CategoryVoteController(CategoryVoteService categoryVoteService) {
        this.categoryVoteService = categoryVoteService;
    }

    public record CategoryVoteRequest(Long categoryId) {
    }

    @GetMapping
    public ResponseEntity<CategoryVoteService.Summary> getVote(@PathVariable Long routineId, Authentication authentication) {
        return ResponseEntity.ok(categoryVoteService.getSummary(authentication.getName(), routineId));
    }

    @PostMapping
    public ResponseEntity<CategoryVoteService.Summary> vote(
            @PathVariable Long routineId,
            @RequestBody(required = false) CategoryVoteRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(categoryVoteService.vote(
                authentication.getName(), routineId, request == null ? null : request.categoryId()));
    }
}
