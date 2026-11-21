package com.copymylife.copymylife.service;

import com.copymylife.copymylife.model.PasswordResetToken;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.PasswordResetTokenRepository;
import com.copymylife.copymylife.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.copymylife.copymylife.exception.ExpiredResetTokenException;
import com.copymylife.copymylife.exception.InvalidResetTokenException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository passwordResetTokenRepository, PasswordEncoder passwordEncoder, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;

        
    }

    // Returns null and sends nothing when no account matches the address.
    @Transactional
    public String createResetToken(String email) {

        Optional<User> account = userRepository.findByLoginEmail(email);

        if (account.isEmpty()) {
            return null;
        }

        User user = account.get();

        passwordResetTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();

        PasswordResetToken resetToken = new PasswordResetToken();

        resetToken.setToken(token);
        resetToken.setUser(user);
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));

        passwordResetTokenRepository.save(resetToken);

        emailService.sendPasswordResetEmail(user.getEmail(), token);

        return token;
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {

        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token).orElseThrow(() -> new InvalidResetTokenException("Invalid reset token"));

        if (!PasswordRules.isStrong(newPassword)) {
            throw new IllegalArgumentException(PasswordRules.MESSAGE);
        }

        if (resetToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ExpiredResetTokenException(
                    "Reset token has expired"
            );
        }

        User user = resetToken.getUser();

        user.setPassword(passwordEncoder.encode(newPassword));

        // Every session opened with the old password ends.
        user.setTokenVersion(user.currentTokenVersion() + 1);

        userRepository.save(user);

        passwordResetTokenRepository.delete(resetToken);
    }


    
}