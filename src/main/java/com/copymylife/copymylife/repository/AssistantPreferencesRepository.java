package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.AssistantPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AssistantPreferencesRepository extends JpaRepository<AssistantPreferences, Long> {

    Optional<AssistantPreferences> findByUserId(Long userId);
}
