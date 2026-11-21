package com.copymylife.copymylife.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String name;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8)
    private String password;

    private String username;

    private String profession;

    @Column(length = 500)
    private String bio;

    private String avatarUrl;

    @Column(name = "role", nullable = false)
    private String role = "USER";

    @Column(name = "banned", nullable = false)
    private boolean banned = false;

    // US-25: set while an administrator has hidden the public profile. The account still works;
    // the profile and what it lists are no longer shown to others.
    @Column(name = "profile_hidden_at")
    private Instant profileHiddenAt;

    @Column(length = 500)
    private String interests;

    @Column(length = 500)
    private String goals;

    // Raised to sign out every existing session, e.g. after a password reset.
    // Null on accounts created before this column existed, which counts as 0.
    @Column(name = "token_version")
    private Integer tokenVersion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getProfession() {
        return profession;
    }

    public void setProfession(String profession) {
        this.profession = profession;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isBanned() {
        return banned;
    }

    public void setBanned(boolean banned) {
        this.banned = banned;
    }

    public String getInterests() {
        return interests;
    }

    public void setInterests(String interests) {
        this.interests = interests;
    }

    public String getGoals() {
        return goals;
    }

    public void setGoals(String goals) {
        this.goals = goals;
    }

    public int currentTokenVersion() {
        return tokenVersion == null ? 0 : tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    public Instant getProfileHiddenAt() {
        return profileHiddenAt;
    }

    public void setProfileHiddenAt(Instant profileHiddenAt) {
        this.profileHiddenAt = profileHiddenAt;
    }

    public boolean isProfileHiddenByAdmin() {
        return profileHiddenAt != null;
    }
}
