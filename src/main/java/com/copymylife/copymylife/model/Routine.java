package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "routines",
        indexes = @Index(name = "idx_routines_copied_from", columnList = "copied_from_id")
)
public class Routine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String title;

    @Column(length = 500)
    private String description;

    private Integer durationMinutes;

    private String category;

    private boolean isPublic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "completed")
    private Boolean completed = false;

    @Column(name = "built_in")
    private Boolean builtIn = false;

    @Column(name = "average_rating", nullable = false)
    private Double averageRating = 0.0;

    @Column(name = "rating_count", nullable = false)
    private Long ratingCount = 0L;

    @Column(name = "deleted", nullable = false)
    private boolean deleted = false;

    // US-25: set while an administrator has hidden the routine. Hidden routines are left out of
    // every public view; the owner keeps it and cannot publish it again until it is shown.
    @Column(name = "hidden_at")
    private Instant hiddenAt;

    // US-12: the routine this one is a copy of: the one the user chose to copy, even when that was
    // itself a copy. Set once when the copy is saved and never changed. Empty for routines made from
    // scratch and for copies made before US-12, whose source was never recorded.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "copied_from_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_routines_copied_from"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private Routine copiedFrom;

    public double getAverageRating() {
        return averageRating == null ? 0.0 : averageRating;
    }
    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public long getRatingCount() {
        return ratingCount == null ? 0L : ratingCount;
    }
    public void setRatingCount(long ratingCount) {
        this.ratingCount = ratingCount;
    }

    public boolean isBuiltIn() {
        return Boolean.TRUE.equals(builtIn);
    }
    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public boolean isCompleted() {
        return Boolean.TRUE.equals(completed);
    }
    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    public String getCategory() {
        return category;
    }
    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isPublic() {
        return isPublic;
    }
    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public User getUser() {
        return user;
    }
    public void setUser(User user) {
        this.user = user;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }
    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getImageUrl() {
        return imageUrl;
    }
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getHiddenAt() {
        return hiddenAt;
    }

    public void setHiddenAt(Instant hiddenAt) {
        this.hiddenAt = hiddenAt;
    }

    public boolean isHiddenByAdmin() {
        return hiddenAt != null;
    }

    public Routine getCopiedFrom() {
        return copiedFrom;
    }

    public void setCopiedFrom(Routine copiedFrom) {
        this.copiedFrom = copiedFrom;
    }
}
