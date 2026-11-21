package com.copymylife.copymylife.service;

import com.copymylife.copymylife.config.HalloweenRoutineCatalog;
import com.copymylife.copymylife.dto.AdaptedCopyRequest;
import com.copymylife.copymylife.dto.RoutineResponse;
import com.copymylife.copymylife.exception.AdaptedActivityException;
import com.copymylife.copymylife.model.Habit;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.model.RoutineHabit;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import com.copymylife.copymylife.dto.UserPublicRoutineResponse;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class RoutineService {

    private final RoutineRepository routineRepository;
    private final UserRepository userRepository;
    private final LikedRoutineRepository likedRoutineRepository;
    private final SavedRoutineRepository savedRoutineRepository;
    private final RoutineCompletionRepository routineCompletionRepository;
    private final FileUploadService fileUploadService;
    private final RoutineAccessService routineAccessService;
    private final HabitRepository habitRepository;
    private final RoutineHabitRepository routineHabitRepository;


    public RoutineService(RoutineRepository routineRepository, UserRepository userRepository, LikedRoutineRepository likedRoutineRepository, SavedRoutineRepository savedRoutineRepository, RoutineCompletionRepository routineCompletionRepository, FileUploadService fileUploadService, RoutineAccessService routineAccessService, HabitRepository habitRepository, RoutineHabitRepository routineHabitRepository) {
        this.routineRepository = routineRepository;
        this.userRepository = userRepository;
        this.likedRoutineRepository = likedRoutineRepository;
        this.savedRoutineRepository = savedRoutineRepository;
        this.routineCompletionRepository = routineCompletionRepository;
        this.fileUploadService = fileUploadService;
        this.routineAccessService = routineAccessService;
        this.habitRepository = habitRepository;
        this.routineHabitRepository = routineHabitRepository;
    }

    private void ensureRoutineNotRunning(User user, Long routineId) {
        routineCompletionRepository
                .findActiveRun(
                        user.getId()
                )
                .ifPresent(run -> {
                    if (run.getRoutine().getId().equals(routineId)) {
                        throw new IllegalArgumentException(
                                "Routine is currently running"
                        );
                    }
                });
    }

    private RoutineResponse toResponse(Routine routine) {

        RoutineResponse response = new RoutineResponse();

        response.setId(routine.getId());
        response.setTitle(routine.getTitle());
        response.setDescription(routine.getDescription());
        response.setDurationMinutes(routine.getDurationMinutes());
        response.setCategory(routine.getCategory());
        response.setPublic(routine.isPublic());
        response.setImageUrl(routine.getImageUrl());
        response.setCompleted(routine.isCompleted());
        response.setAverageRating(routine.getAverageRating());
        response.setRatingCount(routine.getRatingCount());
        response.setLikeCount(likedRoutineRepository.countByRoutineId(routine.getId()));
        response.setSaveCount(savedRoutineRepository.countByRoutineId(routine.getId()));
        response.setAuthorName(resolveAuthorName(routine));
        response.setBuiltIn(routine.isBuiltIn());
        response.setHiddenByAdmin(routine.isHiddenByAdmin());

        if (routine.isBuiltIn()) {
            response.setAuthorUsername(null);
            response.setAuthorAvatarUrl(routine.getImageUrl());
        } else if (routine.getUser() != null) {
            response.setAuthorUsername(routine.getUser().getUsername());
            response.setAuthorAvatarUrl(routine.getUser().getAvatarUrl());
        }

        return response;
    }

    public RoutineResponse createRoutine(String email, String title, String description, String category, Integer durationMinutes, boolean isPublic, String imageUrl, MultipartFile image) {

        RoutineRules.validate(title, description, category, durationMinutes);
        ImageUrlPolicy.requireSafe(imageUrl);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new RuntimeException("User not found")
                );

        Routine routine = new Routine();

        routine.setTitle(title.trim());
        routine.setDescription(description);
        routine.setCategory(category.trim());
        routine.setDurationMinutes(durationMinutes);
        routine.setPublic(isPublic);
        routine.setUser(user);

        String finalImageUrl;

        if (image != null && !image.isEmpty()) {
            finalImageUrl = fileUploadService.storeRoutineImage(image);
        } else {
            finalImageUrl = imageUrl;
        }

        routine.setImageUrl(finalImageUrl);

        Routine saved =
                routineRepository.save(routine);

        return toResponse(saved);
    }

    public List<RoutineResponse> getMyRoutines(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        List<Routine> routines = routineRepository.findByUserIdAndDeletedFalse(user.getId());

        List<RoutineResponse> responses = new ArrayList<>();

        for (Routine routine : routines) {
            responses.add(toResponse(routine));
        }
        return responses;
    }

    // email is null for a visitor, who sees exactly what any other non-owner sees.
    public Optional<RoutineResponse> getRoutineById(String email, Long routineId) {
        User user = email == null
                ? null
                : userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        return routineRepository.findById(routineId)
                .filter(routine -> routineAccessService.canViewRoutine(routine, user))
                .map(routine -> {
                    RoutineResponse response = toResponse(routine);
                    response.setOwnedByMe(user != null && routine.getUser().getId().equals(user.getId()));
                    return response;
                });
    }

    /**
     * Makes an independent private routine for the caller. Habits are cloned rather than shared,
     * so later edits on either side stay on that side, and no ratings, likes, comments or
     * progress come along.
     */
    @Transactional
    public RoutineResponse copyRoutine(String email, Long routineId) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        Routine source = routineAccessService.requireViewableRoutine(routineId, user);
        List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);

        // Each activity keeps the plan it has in the source.
        Map<Long, CopyPlan> plans = new HashMap<>();

        for (RoutineHabit link : links) {
            plans.put(link.getId(), new CopyPlan(
                    link.getStartTime(),
                    link.getRepeatDays(),
                    link.getNotes(),
                    link.getHabit().getDurationMinutes()
            ));
        }

        return saveCopy(user, source, links, plans, source.getDurationMinutes());
    }

    /**
     * US-19: a copy whose activities were adapted before saving: start time, duration, repeat days
     * and notes, under the same rules as editing an activity. Every activity of the source is given
     * once; all of them are checked before anything is written, a refusal names the activity, and
     * nothing is kept. The copy is as long as its activities, so it can be started straight away.
     */
    @Transactional
    public RoutineResponse copyAdaptedRoutine(String email, Long routineId, AdaptedCopyRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        Routine source = routineAccessService.requireViewableRoutine(routineId, user);
        List<RoutineHabit> links = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId);
        Map<Long, AdaptedCopyRequest.Activity> asked = askedActivities(links, request);

        Map<Long, CopyPlan> plans = new HashMap<>();
        // A habit listed twice is one habit in the copy too, so both places need one duration.
        Map<Long, Integer> habitMinutes = new HashMap<>();
        // The activities planned so far, for the overlap check; never saved.
        List<RoutineHabit> planned = new ArrayList<>();
        int total = 0;

        for (int index = 0; index < links.size(); index++) {
            RoutineHabit link = links.get(index);
            AdaptedCopyRequest.Activity activity = asked.get(link.getId());

            try {
                HabitRules.validateDuration(activity.getDurationMinutes());
                int minutes = activity.getDurationMinutes();

                requireDistinctDays(activity.getRepeatDays());
                LocalTime startTime = HabitSchedule.parseStartTime(activity.getStartTime());
                String days = HabitSchedule.parseDays(activity.getRepeatDays());
                String notes = HabitSchedule.parseNotes(activity.getNotes());
                HabitSchedule.requireDaysForTime(startTime, days);

                Integer earlier = habitMinutes.putIfAbsent(link.getHabit().getId(), minutes);

                if (earlier != null && earlier != minutes) {
                    throw refused("This is the same habit as an earlier activity, so it needs the same duration ("
                            + earlier + " min)");
                }

                HabitSchedule.checkOverlaps(planned, startTime, minutes, days);
                planned.add(plannedLink(link.getHabit().getTitle(), startTime, days, minutes));

                plans.put(link.getId(), new CopyPlan(startTime, days, notes, minutes));
                total += minutes;
            } catch (ResponseStatusException refusal) {
                throw new AdaptedActivityException(
                        index + 1,
                        "Activity " + (index + 1) + " \"" + link.getHabit().getTitle() + "\": " + refusal.getReason()
                );
            }
        }

        if (total > RoutineRules.MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException(
                    "The activities add up to " + total + " minutes, but a routine can last at most "
                            + RoutineRules.MAX_DURATION_MINUTES + " minutes (24 hours)"
            );
        }

        return saveCopy(user, source, links, plans, links.isEmpty() ? source.getDurationMinutes() : total);
    }

    // The plan of one activity in a copy, and the minutes of its habit.
    private record CopyPlan(LocalTime startTime, String repeatDays, String notes, Integer durationMinutes) {
    }

    // Every activity of the source, each given once and none from elsewhere, by routine habit id.
    private Map<Long, AdaptedCopyRequest.Activity> askedActivities(
            List<RoutineHabit> links,
            AdaptedCopyRequest request
    ) {
        List<AdaptedCopyRequest.Activity> activities =
                request == null || request.getActivities() == null ? List.of() : request.getActivities();

        if (activities.size() != links.size()) {
            throw new IllegalArgumentException(
                    "Give every activity of this routine once: " + links.size() + " expected, "
                            + activities.size() + " given"
            );
        }

        Set<Long> known = new HashSet<>();
        links.forEach(link -> known.add(link.getId()));

        Map<Long, AdaptedCopyRequest.Activity> asked = new HashMap<>();

        for (AdaptedCopyRequest.Activity activity : activities) {
            Long id = activity == null ? null : activity.getRoutineHabitId();

            if (id == null || !known.contains(id)) {
                throw new IllegalArgumentException("An activity that is not part of this routine was given");
            }

            if (asked.put(id, activity) != null) {
                throw new IllegalArgumentException("An activity of this routine was given more than once");
            }
        }

        return asked;
    }

    // Repeat days are a set; a day named twice is a mistake to point out, not to fold away.
    private static void requireDistinctDays(List<String> days) {
        if (days == null) {
            return;
        }

        Set<String> seen = new HashSet<>();

        for (String day : days) {
            String code = day == null ? "" : day.trim().toUpperCase(Locale.ROOT);

            if (!code.isEmpty() && !seen.add(code)) {
                throw refused(code + " is chosen more than once");
            }
        }
    }

    // An unsaved activity with the plan given, for HabitSchedule.checkOverlaps.
    private static RoutineHabit plannedLink(String title, LocalTime startTime, String days, int minutes) {
        Habit habit = new Habit();
        habit.setTitle(title);
        habit.setDurationMinutes(minutes);

        RoutineHabit link = new RoutineHabit();
        link.setHabit(habit);
        link.setStartTime(startTime);
        link.setRepeatDays(days);

        return link;
    }

    private static ResponseStatusException refused(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    // Makes the private copy: the routine, a clone of each habit and a link with each activity's plan.
    private RoutineResponse saveCopy(
            User user,
            Routine source,
            List<RoutineHabit> links,
            Map<Long, CopyPlan> plans,
            Integer durationMinutes
    ) {
        Routine copy = new Routine();
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setCategory(source.getCategory());
        copy.setDurationMinutes(durationMinutes);
        copy.setImageUrl(ImageUrlPolicy.isSafe(source.getImageUrl()) ? source.getImageUrl() : null);
        copy.setPublic(false);
        copy.setUser(user);
        // US-12: counted as a copy of the routine the user chose, in this same transaction.
        copy.setCopiedFrom(source);

        Routine saved = routineRepository.save(copy);

        // A habit listed twice in the source stays one habit listed twice in the copy.
        Map<Long, Habit> clones = new HashMap<>();

        for (RoutineHabit link : links) {
            CopyPlan plan = plans.get(link.getId());

            Habit clone = clones.computeIfAbsent(
                    link.getHabit().getId(),
                    id -> cloneHabit(link.getHabit(), plan.durationMinutes(), user, saved)
            );

            RoutineHabit clonedLink = new RoutineHabit();
            clonedLink.setRoutine(saved);
            clonedLink.setHabit(clone);
            clonedLink.setPosition(link.getPosition());
            clonedLink.setStartTime(plan.startTime());
            clonedLink.setRepeatDays(plan.repeatDays());
            clonedLink.setNotes(plan.notes());

            routineHabitRepository.save(clonedLink);
        }

        RoutineResponse response = toResponse(saved);
        response.setOwnedByMe(true);

        return response;
    }

    private Habit cloneHabit(Habit source, Integer durationMinutes, User owner, Routine routine) {
        Habit clone = new Habit();
        clone.setTitle(source.getTitle());
        clone.setDescription(source.getDescription());
        clone.setDurationMinutes(durationMinutes);
        clone.setRoutine(routine);
        clone.setUser(owner);

        return habitRepository.save(clone);
    }

    public Optional<RoutineResponse> updateRoutine(String email, Long routineId, String title,String description, String category, Integer durationMinutes, boolean isPublic, String imageUrl, MultipartFile image) {

        RoutineRules.validate(title, description, category, durationMinutes);
        ImageUrlPolicy.requireSafe(imageUrl);

        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));

        Optional<Routine> routine = routineRepository.findByIdAndUserIdAndDeletedFalse(routineId, user.getId());

        if (routine.isEmpty()) return Optional.empty();


        Routine existRoutine = routine.get();

        ensureRoutineNotRunning(user, routineId);

        // Paused habits keep their minutes, so they count too.
        int habitMinutes = routineHabitRepository.findByRoutineIdAndRemovedAtIsNullOrderByPositionAsc(routineId).stream()
                .mapToInt(link -> link.getHabit().getDurationMinutes() == null ? 0 : link.getHabit().getDurationMinutes())
                .sum();

        if (durationMinutes < habitMinutes) {
            throw new IllegalArgumentException(
                    "Routine duration can't be shorter than its habits (" + habitMinutes + " min)"
            );
        }

        existRoutine.setTitle(title.trim());
        existRoutine.setDescription(description);
        existRoutine.setCategory(category.trim());
        existRoutine.setDurationMinutes(durationMinutes);
        existRoutine.setPublic(isPublic);

        if (image != null && !image.isEmpty()) {

            String uploadedImageUrl = fileUploadService.storeRoutineImage(image);
            existRoutine.setImageUrl(uploadedImageUrl);

        } else if (imageUrl != null && !imageUrl.isBlank()) {

            existRoutine.setImageUrl(imageUrl);
        }

        Routine updated = routineRepository.save(existRoutine);

        return Optional.of(toResponse(updated));
    }

    @Transactional
    public boolean deleteRoutine(String email, Long routineId) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<Routine> routine = routineRepository
                .findByIdAndUserIdAndDeletedFalse(routineId, user.getId());

        if (routine.isEmpty()) {
            return false;
        }

        ensureRoutineNotRunning(user, routineId);

        Routine existing = routine.get();
        existing.setDeleted(true);
        routineRepository.save(existing);

        return true;
    }

    @Transactional
    public Optional<RoutineResponse> setCompleted(String email, Long routineId, boolean completed) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Optional<Routine> routine =
                routineRepository.findByIdAndUserIdAndDeletedFalse(routineId, user.getId());

        if (routine.isEmpty()) {
            return Optional.empty();
        }

        throw new IllegalArgumentException(
                "Routine completion is managed automatically by its habits"
        );
    }

    public List<RoutineResponse> getPublicRoutines() {

        List<Routine> routines = routineRepository.findPublicUserRoutines();

        List<RoutineResponse> responses = new ArrayList<>();

        for (Routine routine : routines) {
            responses.add(toResponse(routine));
        }

        return responses;
    }

    public List<UserPublicRoutineResponse> getUserPublicRoutines(Long userId) {

        userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Routine> routines =
                routineRepository.findPublicRoutinesByUserId(userId);

        List<UserPublicRoutineResponse> responses =
                new ArrayList<>();

        for (Routine routine : routines) {

            responses.add(
                    new UserPublicRoutineResponse(
                            routine.getId(),
                            routine.getTitle(),
                            routine.getDescription(),
                            routine.getDurationMinutes(),
                            routine.getCategory(),
                            routine.getImageUrl(),
                            routine.getAverageRating(),
                            routine.getRatingCount()
                    )
            );
        }

        return responses;
    }

    // US-05: the ids of the routines Explore lists that match a search, in one query; the same
    // for a visitor and a signed-in user, since everything searched is public.
    @Transactional(readOnly = true)
    public List<Long> searchRoutineIds(String query, String category) {
        String chosen = SearchText.normalize(category);

        return routineRepository.searchVisibleRoutineIds(
                SearchText.containsPattern(SearchText.normalize(query)),
                "all".equals(chosen) ? "" : chosen
        );
    }

    public List<RoutineResponse> getBuiltInRoutines() {

        List<Routine> routines = routineRepository.findByBuiltInTrue();

        List<RoutineResponse> responses = new ArrayList<>();

        for (Routine routine : routines) {
            responses.add(toResponse(routine));
        }

        return responses;
    }

    private String resolveAuthorName(Routine routine) {
        if (!routine.isBuiltIn()) {
            if (routine.getUser() != null) return routine.getUser().getName();
            return "User";
        }

        String title = routine.getTitle();

        Optional<String> halloweenAuthor = HalloweenRoutineCatalog.findAuthorName(title);

        if (halloweenAuthor.isPresent()) return halloweenAuthor.get();

        if ("Elon Musk's Productivity System".equals(title)) return "Elon Musk";

        if (title.endsWith(" Training Routine")) {
            return title.substring(0, title.length() - " Training Routine".length());
        }

        if (title.endsWith(" Morning Routine")) {
            return title.substring(0, title.length() - " Morning Routine".length());
        }

        if (title.endsWith(" Routine")) {
            return title.substring(0, title.length() - " Routine".length());
        }

        return title;
    }

}