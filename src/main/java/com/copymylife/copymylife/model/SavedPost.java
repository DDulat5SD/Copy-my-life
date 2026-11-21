package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

// US-16: a post the user bookmarked to come back to. Goes with the post and with the user.
@Entity
@Table(
        name = "saved_posts",
        uniqueConstraints = @UniqueConstraint(name = "uk_saved_posts_user_post", columnNames = {"user_id", "post_id"})
)
public class SavedPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_saved_posts_user"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false, foreignKey = @ForeignKey(name = "fk_saved_posts_post"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedPost() {
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Post getPost() {
        return post;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
