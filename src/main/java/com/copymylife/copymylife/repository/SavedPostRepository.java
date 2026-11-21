package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.SavedPost;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SavedPostRepository extends JpaRepository<SavedPost, Long> {

    // Saving twice, or from two tabs at once, keeps one bookmark.
    @Modifying
    @Query(value = """
            INSERT INTO saved_posts (user_id, post_id, created_at)
            VALUES (:userId, :postId, :createdAt)
            ON CONFLICT (user_id, post_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("postId") Long postId, @Param("createdAt") Instant createdAt);

    // Newest bookmark first, with the post and its author.
    @Query("""
            SELECT s FROM SavedPost s
            JOIN FETCH s.post p
            JOIN FETCH p.user
            WHERE s.user.id = :userId
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<SavedPost> findAllOf(@Param("userId") Long userId);

    boolean existsByUserIdAndPostId(Long userId, Long postId);

    @Modifying
    @Query("DELETE FROM SavedPost s WHERE s.user.id = :userId AND s.post.id = :postId")
    int deletePair(@Param("userId") Long userId, @Param("postId") Long postId);
}
