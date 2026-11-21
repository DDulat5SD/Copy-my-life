package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.SiteRating;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SiteRatingRepository extends JpaRepository<SiteRating, Long> {

    Optional<SiteRating> findByUserId(Long userId);

    @Query("SELECT COALESCE(AVG(r.value), 0) FROM SiteRating r")
    Double findAverageRating();
}