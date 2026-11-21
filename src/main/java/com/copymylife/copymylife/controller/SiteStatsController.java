package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.SiteRatingRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/site-stats")
public class SiteStatsController {

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final SiteRatingRepository siteRatingRepository;

    public SiteStatsController(UserRepository userRepository, RoutineRepository routineRepository, SiteRatingRepository siteRatingRepository) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.siteRatingRepository = siteRatingRepository;
    }

    @GetMapping
    public Map<String, Object> getStats() {
        return Map.of(
                "users", userRepository.countByEmailNot("explore@copymylife.local"),
                "routines", routineRepository.countPublicRoutines(),
                "creators", routineRepository.countPublicCreators(),
                "rating", siteRatingRepository.findAverageRating()
        );
    }
}