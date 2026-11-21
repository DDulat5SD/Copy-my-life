package com.copymylife.copymylife.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpMethod;

@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin-api/**").hasRole("ADMIN")
                        .requestMatchers("/reports/**").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/users").hasRole("ADMIN")
                        // Post photos may be private: never as files, only through GET /posts/{id}/image.
                        .requestMatchers("/uploads/posts/**").denyAll()
                        // Visitors may read a routine and its timeline; the service still hides private ones.
                        .requestMatchers(HttpMethod.GET, "/routines/{id:\\d+}", "/routines/{id:\\d+}/routine-habits")
                        .permitAll()
                        // US-05: visitors may search Explore; only public and built-in routines are found.
                        .requestMatchers(HttpMethod.GET, "/routines/search")
                        .permitAll()
                        // US-25: the categories Explore and the routine forms offer.
                        .requestMatchers(HttpMethod.GET, "/categories")
                        .permitAll()
                        // US-05: visitors may find people and read a public profile and its public
                        // routines; to-do lists, posts and every change still need signing in.
                        .requestMatchers(HttpMethod.GET, "/users/search", "/users/{id:\\d+}/profile",
                                "/users/{id:\\d+}/routines/public")
                        .permitAll()
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/admin-login.html",
                                "/admin-dashboard.html",
                                "/admin-users.html",
                                "/admin-routines.html",
                                "/admin-habits.html",
                                "/admin-comments.html",
                                "/admin-reports.html",
                                "/admin-posts.html",
                                "/admin-categories.html",
                                "/admin-profile.html",
                                "/admin-habit-view.html",
                                "/admin-routine-view.html",
                                "/admin-detail.css",
                                "/admin-simple-detail.css",
                                "/admin.css",
                                "/admin.js",
                               "/admin-theme.js",
                               "/admin-theme.css",
                                "/auth/**",
                                "/error",
                                "/login.html",
                                "/signin.html",
                                "/login.css",
                                "/signin.css",
                                "/main.html",
                                "/main.css",
                                "/profile.html",
                                "/profile.css",
                                "/routine.html",
                                "/routine.css",
                                "/Explore.html",
                                "/Explore_Style.css",
                                "/forgot-password.html",
                                "/forgot-password.css",
                                "/reset-password.html",
                                "/uploads/**",
                                "/site-stats",
                                "/site-rating.js",
                                "/site-rating.css",
                                "/user-profile.html",
                                "/user-profile.css",
                                "/todo-lists.html",
                                "/todo-lists.js",
                                "/posts.html",
                                "/posts.js",
                                "/site-header.js",
                                "/my-content.css",
                                "/report-dialog.js",
                                "/report-dialog.css",
                                "/creator-stats.js",
                                "/creator-stats.css",
                                "/reminders.js",
                                "/reminders.css",
                                "/top-routines.html",
                                "/top-routines.css",
                                "/lifestyle-matches.html",
                                "/lifestyle-matches.css",
                                "/lifestyle-matches.js",
                                "/assistant.html",
                                "/assistant.css",
                                "/assistant.js",
                                "/top-routines",
                                "/routines/public",
                                "/routine-run.html",
                                "/routine-run.css",
                                "/routine-run.js",
                                "/mobile.css",
                                "/mobile.js",
                                "/safe-dom.js",
                                "/session.js",
                                "/upload-check.js",
                                "/theme.css",
                                "/theme.js",
                                "/img/themes/**",
                                "/img/halloween/**",
                                "/halloween-routine.html",
                                "/halloween-data.js",
                                "/halloween-theme.css",
                                "/routines/built-in"
                        )
                        .permitAll()
                        .anyRequest()
                        .authenticated()
                );

        http.addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
        );

        return http.build();
    }
}