package com.copymylife.copymylife.dto;

public class RatingResponse {

    private double averageRating;
    private long ratingCount;
    private int userRating;

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

    public int getUserRating() {
        return userRating;
    }
    public void setUserRating(int userRating) {
        this.userRating = userRating;
    }
}