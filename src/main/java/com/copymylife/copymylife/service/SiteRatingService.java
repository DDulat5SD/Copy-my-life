package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.SiteRatingRequest;
import com.copymylife.copymylife.model.SiteRating;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.SiteRatingRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SiteRatingService {

    private final SiteRatingRepository siteRatingRepository;
    private final UserRepository userRepository;

    public SiteRatingService(SiteRatingRepository siteRatingRepository, UserRepository userRepository) {
        this.siteRatingRepository = siteRatingRepository;
        this.userRepository = userRepository;
    }

    public Map<String, Object> rate(String email, SiteRatingRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        SiteRating rating = siteRatingRepository.findByUserId(user.getId()).orElse(new SiteRating());

        rating.setUser(user);
        rating.setValue(request.getValue());

        siteRatingRepository.save(rating);

        return getRating(email);
    }

    public Map<String, Object> getRating(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        int userRating = siteRatingRepository.findByUserId(user.getId()).map(SiteRating::getValue).orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("averageRating", siteRatingRepository.findAverageRating());
        result.put("ratingCount", siteRatingRepository.count());
        result.put("userRating", userRating);

        return result;
    }
}