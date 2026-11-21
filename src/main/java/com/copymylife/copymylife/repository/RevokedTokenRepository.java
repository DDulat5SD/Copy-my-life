package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, Long> {

    boolean existsByTokenHash(String tokenHash);

    // Signing out twice, or from two requests at once, keeps a single row.
    @Modifying
    @Query(value = """
            INSERT INTO revoked_tokens (token_hash, user_id, expires_at, revoked_at)
            VALUES (:tokenHash, :userId, :expiresAt, now())
            ON CONFLICT (token_hash) DO NOTHING
            """, nativeQuery = true)
    int revoke(@Param("tokenHash") String tokenHash, @Param("userId") Long userId, @Param("expiresAt") Instant expiresAt);

    @Modifying
    @Query("DELETE FROM RevokedToken t WHERE t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
