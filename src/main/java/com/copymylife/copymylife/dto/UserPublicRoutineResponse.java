package com.copymylife.copymylife.dto;

public class UserPublicRoutineResponse {

    private Long id;
    private String title;
    private String description;
    private Integer durationMinutes;
    private String category;
    private String imageUrl;
    private double averageRating;
    private long ratingCount;

    public UserPublicRoutineResponse(
            Long id,
            String title,
            String description,
            Integer durationMinutes,
            String category,
            String imageUrl,
            double averageRating,
            long ratingCount
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.durationMinutes = durationMinutes;
        this.category = category;
        this.imageUrl = imageUrl;
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

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public String getCategory() {
        return category;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public long getRatingCount() {
        return ratingCount;
    }
}