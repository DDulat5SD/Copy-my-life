package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostLikeRepository extends JpaRepository<PostLike, Long> {

    // Liking twice, even at the same moment, leaves one like: the unique key absorbs the second.
    @Modifying
    @Query(value = """
            INSERT INTO post_likes (post_id, user_id, created_at)
            VALUES (:postId, :userId, now())
            ON CONFLICT ON CONSTRAINT uk_post_likes_post_user DO NOTHING
            """, nativeQuery = true)
    int likeOnce(@Param("postId") Long postId, @Param("userId") Long userId);

    // A bulk delete, so a like already gone (another tab) is simply nothing to remove.
    @Modifying
    @Query("DELETE FROM PostLike l WHERE l.post.id = :postId AND l.user.id = :userId")
    int unlike(@Param("postId") Long postId, @Param("userId") Long userId);

    long countByPostId(Long postId);

    boolean existsByPostIdAndUserId(Long postId, Long userId);
}
