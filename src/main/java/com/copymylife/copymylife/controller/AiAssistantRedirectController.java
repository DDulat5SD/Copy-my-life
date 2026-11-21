package com.copymylife.copymylife.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

// The AI pages that used to stand alone now live in the profile's AI Assistant section. Old links
// and bookmarks are sent to the matching tab there, so none of them ends in a 404.
@RestController
public class AiAssistantRedirectController {

    @GetMapping("/assistant.html")
    public ResponseEntity<Void> assistant(@RequestParam(required = false) String review) {
        // assistant.html?review=<routine id> opened the review of that routine.
        if (review != null && review.matches("\\d{1,18}")) {
            return to("/profile.html?review=" + review + "#ai-review");
        }
        return to("/profile.html#ai-generate");
    }

    @GetMapping("/lifestyle-matches.html")
    public ResponseEntity<Void> lifestyleMatches() {
        return to("/profile.html#ai-matches");
    }

    private static ResponseEntity<Void> to(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
