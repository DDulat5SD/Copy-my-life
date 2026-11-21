package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.PasswordResetToken;
import com.copymylife.copymylife.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByToken(String token);

    void deleteByUser(User user);
}