package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.model.UserFollow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserFollowRepository extends JpaRepository<UserFollow, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO user_follows (follower_id, following_id, created_at)
            VALUES (:followerId, :followingId, :createdAt)
            ON CONFLICT (follower_id, following_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("followerId") Long followerId,
            @Param("followingId") Long followingId,
            @Param("createdAt") LocalDateTime createdAt
    );

    @Modifying
    @Query("""
            DELETE FROM UserFollow f
            WHERE f.follower.id = :followerId
              AND f.following.id = :followingId
            """)
    int deletePair(
            @Param("followerId") Long followerId,
            @Param("followingId") Long followingId
    );

    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);

    @Query("""
            SELECT COUNT(f) FROM UserFollow f
            WHERE f.following.id = :userId
              AND f.follower.banned = false
            """)
    long countFollowers(@Param("userId") Long userId);

    @Query("""
            SELECT COUNT(f) FROM UserFollow f
            WHERE f.follower.id = :userId
              AND f.following.banned = false
            """)
    long countFollowing(@Param("userId") Long userId);

    // The same creators countFollowing counts, newest follow first.
    @Query("""
            SELECT f.following FROM UserFollow f
            WHERE f.follower.id = :userId
              AND f.following.banned = false
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    List<User> findFollowing(@Param("userId") Long userId);

    // The same people countFollowers counts, newest follower first.
    @Query("""
            SELECT f.follower FROM UserFollow f
            WHERE f.following.id = :userId
              AND f.follower.banned = false
            ORDER BY f.createdAt DESC, f.id DESC
            """)
    List<User> findFollowers(@Param("userId") Long userId);
}
