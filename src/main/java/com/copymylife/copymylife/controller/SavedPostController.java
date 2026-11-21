package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.PostResponse;
import com.copymylife.copymylife.service.SavedPostService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// US-16: the signed-in user's bookmarked posts, beside /saved-routines.
@RestController
@RequestMapping("/saved-posts")
public class SavedPostController {

    private final SavedPostService savedPostService;

    public SavedPostController(SavedPostService savedPostService) {
        this.savedPostService = savedPostService;
    }

    @PostMapping("/{postId}")
    public ResponseEntity<PostResponse> save(@PathVariable Long postId, Authentication authentication) {
        return ResponseEntity.ok(savedPostService.save(authentication.getName(), postId));
    }

    @GetMapping
    public ResponseEntity<List<PostResponse>> list(Authentication authentication) {
        return ResponseEntity.ok(savedPostService.list(authentication.getName()));
    }

    // Safe to repeat: removing a bookmark that is not there leaves the same result.
    @DeleteMapping("/{postId}")
    public ResponseEntity<Void> remove(@PathVariable Long postId, Authentication authentication) {
        savedPostService.remove(authentication.getName(), postId);
        return ResponseEntity.noContent().build();
    }
}
