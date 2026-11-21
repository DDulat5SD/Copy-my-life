
package com.copymylife.copymylife.controller;

import com.copymylife.copymylife.model.*;
import com.copymylife.copymylife.repository.*;
import com.copymylife.copymylife.service.AdminContentService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/admin-api/profiles")
public class AdminProfileController {

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final HabitRepository habitRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final FriendshipRepository friendshipRepository;
    private final RoutineCompletionRepository completionRepository;
    private final AdminContentService adminContentService;

    public AdminProfileController(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            HabitRepository habitRepository,
            CalendarEventRepository calendarEventRepository,
            FriendshipRepository friendshipRepository,
            RoutineCompletionRepository completionRepository,
            AdminContentService adminContentService
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.habitRepository = habitRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.friendshipRepository = friendshipRepository;
        this.completionRepository = completionRepository;
        this.adminContentService = adminContentService;
    }

    // US-25: edit, hide or clear a member's public profile; each answers with the profile as it is now.
    @PutMapping("/{userId}")
    @Transactional
    public AdminProfileResponse editProfile(
            @PathVariable Long userId,
            @RequestBody(required = false) AdminContentService.ProfileEdit request
    ) {
        adminContentService.editProfile(userId, request);
        return getProfile(userId);
    }

    @PutMapping("/{userId}/hide")
    @Transactional
    public AdminProfileResponse hideProfile(@PathVariable Long userId) {
        adminContentService.setProfileHidden(userId, true);
        return getProfile(userId);
    }

    @PutMapping("/{userId}/unhide")
    @Transactional
    public AdminProfileResponse unhideProfile(@PathVariable Long userId) {
        adminContentService.setProfileHidden(userId, false);
        return getProfile(userId);
    }

    @DeleteMapping("/{userId}/details")
    @Transactional
    public AdminProfileResponse removeProfileDetails(@PathVariable Long userId) {
        adminContentService.removeProfileDetails(userId);
        return getProfile(userId);
    }

    public record RoutineInfo(
            Long id,
            String title,
            String description,
            Integer durationMinutes,
            String category,
            boolean publicRoutine,
            boolean deleted
    ) {}

    public record HabitInfo(
            Long id,
            String title,
            String description,
            Integer durationMinutes
    ) {}

    public record CalendarInfo(
            Long id,
            Long routineId,
            String routineTitle,
            LocalDate startDate,
            LocalTime startTime,
            String repeat
    ) {}

    public record FriendInfo(
            Long id,
            String name,
            String username,
            String avatarUrl
    ) {}

    public record CompletionInfo(
            Long id,
            Long routineId,
            String routineTitle,
            LocalDate date,
            boolean completed
    ) {}

    public record AdminProfileResponse(
            Long id,
            String name,
            String username,
            String email,
            String profession,
            String bio,
            String interests,
            String goals,
            String avatarUrl,
            String role,
            boolean banned,
            boolean profileHidden,
            List<RoutineInfo> routines,
            List<HabitInfo> habits,
            List<CalendarInfo> calendar,
            List<FriendInfo> friends,
            List<CompletionInfo> history
    ) {}

    @GetMapping("/{userId}")
    @Transactional(readOnly = true)
    public AdminProfileResponse getProfile(@PathVariable Long userId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        List<RoutineInfo> routines = routineRepository.findAll()
                .stream()
                .filter(r -> r.getUser() != null)
                .filter(r -> r.getUser().getId().equals(userId))
                .map(r -> new RoutineInfo(
                        r.getId(),
                        r.getTitle(),
                        r.getDescription(),
                        r.getDurationMinutes(),
                        r.getCategory(),
                        r.isPublic(),
                        r.isDeleted()
                ))
                .toList();

        List<HabitInfo> habits = habitRepository
                .findByUserIdOrderByIdDesc(userId)
                .stream()
                .map(h -> new HabitInfo(
                        h.getId(),
                        h.getTitle(),
                        h.getDescription(),
                        h.getDurationMinutes()
                ))
                .toList();

        List<CalendarInfo> calendar = calendarEventRepository
                .findByUserId(userId)
                .stream()
                .map(event -> new CalendarInfo(
                        event.getId(),
                        event.getRoutine().getId(),
                        event.getRoutine().getTitle(),
                        event.getStartDate(),
                        event.getStartTime(),
                        event.getRepeatType().name()
                ))
                .toList();

        List<FriendInfo> friends = friendshipRepository
                .findByUserIdAndStatus(userId, FriendStatus.ACCEPTED)
                .stream()
                .map(friendship -> {
                    User friend = friendship.getSender().getId().equals(userId)
                            ? friendship.getReceiver()
                            : friendship.getSender();

                    return new FriendInfo(
                            friend.getId(),
                            friend.getName(),
                            friend.getUsername(),
                            friend.getAvatarUrl()
                    );
                })
                .toList();

        List<CompletionInfo> history = completionRepository.findAll()
                .stream()
                .filter(run -> run.getUser().getId().equals(userId))
                .map(run -> new CompletionInfo(
                        run.getId(),
                        run.getRoutine().getId(),
                        run.getRoutine().getTitle(),
                        run.getCompletedDate(),
                        run.getCompletedAt() != null
                ))
                .toList();

        return new AdminProfileResponse(
                user.getId(),
                user.getName(),
                user.getUsername(),
                user.getEmail(),
                user.getProfession(),
                user.getBio(),
                user.getInterests(),
                user.getGoals(),
                user.getAvatarUrl(),
                user.getRole(),
                user.isBanned(),
                user.isProfileHiddenByAdmin(),
                routines,
                habits,
                calendar,
                friends,
                history
        );
    }
}
