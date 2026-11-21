package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * One-way link: follower sees following's work. Independent of {@link Friendship}.
 * Rows are written through UserFollowRepository.insertIfAbsent so that repeated
 * or simultaneous requests cannot fail on the unique constraint.
 */
@Entity
@Table(
        name = "user_follows",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_follows_pair",
                columnNames = {"follower_id", "following_id"}
        ),
        indexes = @Index(
                name = "idx_user_follows_following",
                columnList = "following_id"
        ),
        check = @CheckConstraint(
                name = "ck_user_follows_not_self",
                constraint = "follower_id <> following_id"
        )
)
public class UserFollow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "follower_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_follows_follower")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User follower;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "following_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_user_follows_following")
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User following;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getFollower() {
        return follower;
    }

    public User getFollowing() {
        return following;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
