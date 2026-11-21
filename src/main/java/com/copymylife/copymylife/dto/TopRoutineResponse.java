package com.copymylife.copymylife.dto;

public class TopRoutineResponse {

    private Long id;
    private String title;
    private String description;
    private String category;
    private String imageUrl;
    private String authorName;
    private long likeCount;
    private long saveCount;
    private double averageRating;
    private long ratingCount;
    // US-30: "morning", "afternoon" or "evening", and the "HH:mm" its first activity starts at;
    // both null for a routine without a timed activity.
    private String timeOfDay;
    private String startsAt;

    public TopRoutineResponse(
            Long id,
            String title,
            String description,
            String category,
            String imageUrl,
            String authorName,
            long likeCount,
            long saveCount,
            double averageRating,
            long ratingCount
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.category = category;
        this.imageUrl = imageUrl;
        this.authorName = authorName;
        this.likeCount = likeCount;
        this.saveCount = saveCount;
        this.averageRating = averageRating;
        this.ratingCount = ratingCount;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getAuthorName() {
        return authorName;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getSaveCount() {
        return saveCount;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public long getRatingCount() {
        return ratingCount;
    }

    public String getTimeOfDay() {
        return timeOfDay;
    }

    public void setTimeOfDay(String timeOfDay) {
        this.timeOfDay = timeOfDay;
    }

    public String getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(String startsAt) {
        this.startsAt = startsAt;
    }
}