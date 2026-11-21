package com.copymylife.copymylife.service;

import com.copymylife.copymylife.repository.RevokedTokenRepository;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

// Signing out stores a hash of the token until it would have expired; the JWT filter
// refuses any stored token. Kept in PostgreSQL, so it survives a restart or a redeploy.
// The hash is taken of the token's canonical spelling, so every look-alike of a signed-out
// token maps to the same row. Issued tokens are already canonical, so rows stored before
// this keep matching.
@Service
public class TokenRevocationService {

    private final RevokedTokenRepository revokedTokenRepository;
    private final JwtService jwtService;

    public TokenRevocationService(RevokedTokenRepository revokedTokenRepository, JwtService jwtService) {
        this.revokedTokenRepository = revokedTokenRepository;
        this.jwtService = jwtService;
    }

    // A missing, broken or expired token has nothing to revoke.
    @Transactional
    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }

        // Signing out with a look-alike ends the real token it stands for.
        Optional<String> canonical = JwtService.canonicalForm(token);

        if (canonical.isEmpty()) {
            return;
        }

        Optional<Claims> claims = jwtService.readClaims(canonical.get());

        if (claims.isEmpty() || claims.get().getExpiration() == null) {
            return;
        }

        Long userId;

        try {
            userId = Long.parseLong(claims.get().getSubject());
        } catch (NumberFormatException exception) {
            return;
        }

        revokedTokenRepository.deleteExpired(Instant.now());
        revokedTokenRepository.revoke(
                JwtService.fingerprint(canonical.get()),
                userId,
                claims.get().getExpiration().toInstant()
        );
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String token) {
        return revokedTokenRepository.existsByTokenHash(JwtService.fingerprint(JwtService.canonicalForm(token).orElse(token)));
    }
}
