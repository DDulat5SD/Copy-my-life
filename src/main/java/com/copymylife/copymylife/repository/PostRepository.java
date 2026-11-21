package com.copymylife.copymylife.repository;

import com.copymylife.copymylife.model.Post;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p WHERE p.user.id = :userId ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> findAllOf(@Param("userId") Long userId);

    @Query("SELECT p FROM Post p WHERE p.user.id = :userId AND p.isPublic = true AND p.hiddenAt IS NULL ORDER BY p.createdAt DESC, p.id DESC")
    List<Post> findPublicOf(@Param("userId") Long userId);

    Optional<Post> findByIdAndUserId(Long id, Long userId);
}
