package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    // Oldest first, as a conversation reads; banned authors' comments are not shown.
    @Query("""
            SELECT c FROM PostComment c JOIN FETCH c.user u
            WHERE c.post.id = :postId AND u.banned = false
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<PostComment> findShownOf(@Param("postId") Long postId);

    @Query("SELECT COUNT(c) FROM PostComment c WHERE c.post.id = :postId AND c.user.banned = false")
    long countShownOf(@Param("postId") Long postId);

    // Only the author's own comment on this post; a bulk delete, so removing it twice is harmless.
    @Modifying
    @Query("DELETE FROM PostComment c WHERE c.id = :id AND c.post.id = :postId AND c.user.id = :userId")
    int deleteOwn(@Param("id") Long id, @Param("postId") Long postId, @Param("userId") Long userId);
}
