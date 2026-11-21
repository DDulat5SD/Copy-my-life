package com.copymylife.copymylife.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

public class RoutineResponse {
    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;
    private String category;
    private boolean isPublic;
    private String imageUrl;
    private boolean completed;
    private double averageRating;
    private long ratingCount;
    private String authorName;
    private String authorAvatarUrl;
    private String authorUsername;
    private long likeCount;
    private long saveCount;
    private boolean builtIn;

    // US-25: shown to the owner when an administrator has hidden it.
    private boolean hiddenByAdmin;

    // Set only where the viewer is known; other responses leave it out.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean ownedByMe;

    public Boolean getOwnedByMe() {
        return ownedByMe;
    }

    public void setOwnedByMe(Boolean ownedByMe) {
        this.ownedByMe = ownedByMe;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }


    public String getAuthorName() {
        return authorName;
    }
    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorAvatarUrl() {
        return authorAvatarUrl;
    }
    public void setAuthorAvatarUrl(String authorAvatarUrl) {
        this.authorAvatarUrl = authorAvatarUrl;
    }

    public long getLikeCount() {
        return likeCount;
    }
    public void setLikeCount(long likeCount) {
        this.likeCount = likeCount;
    }

    public long getSaveCount() {
        return saveCount;
    }
    public void setSaveCount(long saveCount) {
        this.saveCount = saveCount;
    }

    public double getAverageRating() {
        return averageRating;
    }
    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public long getRatingCount() {
        return ratingCount;
    }
    public void setRatingCount(long ratingCount) {
        this.ratingCount = ratingCount;
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

    public Integer getDurationMinutes() {
        return durationMinutes;
    }
    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
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

    public String getImageUrl() {
        return imageUrl;
    }
    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public boolean isCompleted() {
        return completed;
    }
    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }
    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }

    public boolean isHiddenByAdmin() {
        return hiddenByAdmin;
    }

    public void setHiddenByAdmin(boolean hiddenByAdmin) {
        this.hiddenByAdmin = hiddenByAdmin;
    }
}
