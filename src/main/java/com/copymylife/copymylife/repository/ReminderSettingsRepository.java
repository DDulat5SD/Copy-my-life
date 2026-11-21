package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.ReminderSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReminderSettingsRepository extends JpaRepository<ReminderSettings, Long> {

    Optional<ReminderSettings> findByUserId(Long userId);
}
