package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.time.LocalTime;

// The routine assistant's saved planning preferences, one row per user. Every field is optional:
// a missing one is filled from the user's own tracking records or a default, and the assistant
// says which. Commitments are kept as JSON text: [{"title","start","end"}].
@Entity
@Table(name = "assistant_preferences")
public class AssistantPreferences {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "wake_time")
    private LocalTime wakeTime;

    @Column(name = "sleep_time")
    private LocalTime sleepTime;

    @Column(name = "available_from")
    private LocalTime availableFrom;

    @Column(name = "available_to")
    private LocalTime availableTo;

    @Column(name = "breakfast_time")
    private LocalTime breakfastTime;

    @Column(name = "lunch_time")
    private LocalTime lunchTime;

    @Column(name = "dinner_time")
    private LocalTime dinnerTime;

    @Column(name = "meal_minutes")
    private Integer mealMinutes;

    @Column(name = "exercise_time")
    private LocalTime exerciseTime;

    @Column(name = "focus_block_minutes")
    private Integer focusBlockMinutes;

    @Column(name = "break_every_minutes")
    private Integer breakEveryMinutes;

    @Column(name = "break_minutes")
    private Integer breakMinutes;

    @Column(name = "rest_frequency", length = 16)
    private String restFrequency;

    @Column(name = "intensity", length = 16)
    private String intensity;

    @Column(name = "commitments", length = 4000)
    private String commitments;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public LocalTime getWakeTime() {
        return wakeTime;
    }

    public void setWakeTime(LocalTime wakeTime) {
        this.wakeTime = wakeTime;
    }

    public LocalTime getSleepTime() {
        return sleepTime;
    }

    public void setSleepTime(LocalTime sleepTime) {
        this.sleepTime = sleepTime;
    }

    public LocalTime getAvailableFrom() {
        return availableFrom;
    }

    public void setAvailableFrom(LocalTime availableFrom) {
        this.availableFrom = availableFrom;
    }

    public LocalTime getAvailableTo() {
        return availableTo;
    }

    public void setAvailableTo(LocalTime availableTo) {
        this.availableTo = availableTo;
    }

    public LocalTime getBreakfastTime() {
        return breakfastTime;
    }

    public void setBreakfastTime(LocalTime breakfastTime) {
        this.breakfastTime = breakfastTime;
    }

    public LocalTime getLunchTime() {
        return lunchTime;
    }

    public void setLunchTime(LocalTime lunchTime) {
        this.lunchTime = lunchTime;
    }

    public LocalTime getDinnerTime() {
        return dinnerTime;
    }

    public void setDinnerTime(LocalTime dinnerTime) {
        this.dinnerTime = dinnerTime;
    }

    public Integer getMealMinutes() {
        return mealMinutes;
    }

    public void setMealMinutes(Integer mealMinutes) {
        this.mealMinutes = mealMinutes;
    }

    public LocalTime getExerciseTime() {
        return exerciseTime;
    }

    public void setExerciseTime(LocalTime exerciseTime) {
        this.exerciseTime = exerciseTime;
    }

    public Integer getFocusBlockMinutes() {
        return focusBlockMinutes;
    }

    public void setFocusBlockMinutes(Integer focusBlockMinutes) {
        this.focusBlockMinutes = focusBlockMinutes;
    }

    public Integer getBreakEveryMinutes() {
        return breakEveryMinutes;
    }

    public void setBreakEveryMinutes(Integer breakEveryMinutes) {
        this.breakEveryMinutes = breakEveryMinutes;
    }

    public Integer getBreakMinutes() {
        return breakMinutes;
    }

    public void setBreakMinutes(Integer breakMinutes) {
        this.breakMinutes = breakMinutes;
    }

    public String getRestFrequency() {
        return restFrequency;
    }

    public void setRestFrequency(String restFrequency) {
        this.restFrequency = restFrequency;
    }

    public String getIntensity() {
        return intensity;
    }

    public void setIntensity(String intensity) {
        this.intensity = intensity;
    }

    public String getCommitments() {
        return commitments;
    }

    public void setCommitments(String commitments) {
        this.commitments = commitments;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
