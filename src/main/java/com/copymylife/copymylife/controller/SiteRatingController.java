package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.dto.SiteRatingRequest;
import com.copymylife.copymylife.service.SiteRatingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/site-ratings")
public class SiteRatingController {

    private final SiteRatingService siteRatingService;

    public SiteRatingController(SiteRatingService siteRatingService) {
        this.siteRatingService = siteRatingService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getRating(Authentication authentication) {
        return ResponseEntity.ok(siteRatingService.getRating(authentication.getName()));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> rate(@Valid @RequestBody SiteRatingRequest request, Authentication authentication) {
        return ResponseEntity.ok(siteRatingService.rate(authentication.getName(), request));
    }
}