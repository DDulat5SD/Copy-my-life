package com.copymylife.copymylife.service;

import com.copymylife.copymylife.config.HalloweenRoutineCatalog;
import com.copymylife.copymylife.dto.TopRoutineResponse;
import com.copymylife.copymylife.model.Routine;
import com.copymylife.copymylife.repository.LikedRoutineRepository;
import com.copymylife.copymylife.repository.RoutineHabitRepository;
import com.copymylife.copymylife.repository.RoutineRatingRepository;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.SavedRoutineRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TopRoutineService {

    private final RoutineRepository routineRepository;
    private final LikedRoutineRepository likedRoutineRepository;
    private final SavedRoutineRepository savedRoutineRepository;
    private final RoutineRatingRepository routineRatingRepository;
    private final RoutineHabitRepository routineHabitRepository;

    public TopRoutineService(
            RoutineRepository routineRepository,
            LikedRoutineRepository likedRoutineRepository,
            SavedRoutineRepository savedRoutineRepository,
            RoutineRatingRepository routineRatingRepository,
            RoutineHabitRepository routineHabitRepository
    ) {
        this.routineRepository = routineRepository;
        this.likedRoutineRepository = likedRoutineRepository;
        this.savedRoutineRepository = savedRoutineRepository;
        this.routineRatingRepository = routineRatingRepository;
        this.routineHabitRepository = routineHabitRepository;
    }

    // The five best public and built-in routines of the period, counted from the stored likes,
    // saves and ratings on every call. US-30: time is "morning", "afternoon" or "evening" to keep
    // only routines whose first timed activity of the day starts then; anything else is every time.
    public List<TopRoutineResponse> getTopRoutines(
            String period,
            String sort,
            String category,
            String time
    ) {

        LocalDateTime from = getStartDate(period);

        String chosenTime = TimeOfDay.chosen(time);

        List<Routine> routines =
                routineRepository.findRankingRoutines();

        Map<Long, LocalTime> starts =
                firstStartTimes(routines);

        List<TopRoutineResponse> result =
                new ArrayList<>();

        for (Routine routine : routines) {

            if (!matchesCategory(routine, category)) {
                continue;
            }

            LocalTime startsAt =
                    starts.get(routine.getId());

            String timeOfDay =
                    startsAt == null
                            ? null
                            : TimeOfDay.of(startsAt);

            if (
                    chosenTime != null &&
                            !chosenTime.equals(timeOfDay)
            ) {
                continue;
            }

            long likeCount =
                    likedRoutineRepository.countRecentLikes(
                            routine.getId(),
                            from
                    );

            long saveCount =
                    savedRoutineRepository.countRecentSaves(
                            routine.getId(),
                            from
                    );

            long ratingCount =
                    routineRatingRepository.countRecentRatings(
                            routine.getId(),
                            from
                    );

            Double averageRating =
                    routineRatingRepository.getRecentAverageRating(
                            routine.getId(),
                            from
                    );

            double rating =
                    averageRating == null
                            ? 0.0
                            : averageRating;

            if (
                    likeCount == 0 &&
                            saveCount == 0 &&
                            ratingCount == 0
            ) {
                continue;
            }

            String authorName = "User";

            if (routine.getUser() != null) {
                authorName =
                        routine.getUser().getName();
            }

            if (routine.isBuiltIn()) {
                authorName =
                        HalloweenRoutineCatalog
                                .findAuthorName(routine.getTitle())
                                .orElse(authorName);
            }

            TopRoutineResponse response =
                    new TopRoutineResponse(
                            routine.getId(),
                            routine.getTitle(),
                            routine.getDescription(),
                            routine.getCategory(),
                            routine.getImageUrl(),
                            authorName,
                            likeCount,
                            saveCount,
                            rating,
                            ratingCount
                    );

            response.setTimeOfDay(timeOfDay);
            response.setStartsAt(
                    startsAt == null
                            ? null
                            : startsAt.format(DateTimeFormatter.ofPattern("HH:mm"))
            );

            result.add(response);
        }

        sortRoutines(
                result,
                sort
        );

        return result.stream()
                .limit(5)
                .toList();
    }

    // US-30: each routine's first timed activity of the day, counted from 04:00; routines
    // without a timed activity are left out, as they have no time of day.
    private Map<Long, LocalTime> firstStartTimes(
            List<Routine> routines
    ) {

        Map<Long, LocalTime> first =
                new HashMap<>();

        if (routines.isEmpty()) {
            return first;
        }

        List<Object[]> rows =
                routineHabitRepository.findActiveStartTimes(
                        routines.stream()
                                .map(Routine::getId)
                                .toList()
                );

        for (Object[] row : rows) {

            first.merge(
                    (Long) row[0],
                    (LocalTime) row[1],
                    (kept, next) ->
                            TimeOfDay.sinceDayStarts(next) < TimeOfDay.sinceDayStarts(kept)
                                    ? next
                                    : kept
            );
        }

        return first;
    }

    private LocalDateTime getStartDate(
            String period
    ) {

        if ("day".equalsIgnoreCase(period)) {
            return LocalDateTime.now().minusDays(1);
        }

        if ("month".equalsIgnoreCase(period)) {
            return LocalDateTime.now().minusDays(30);
        }

        return LocalDateTime.now().minusDays(7);
    }

    private boolean matchesCategory(
            Routine routine,
            String category
    ) {

        if (
                category == null ||
                        category.isBlank() ||
                        "all".equalsIgnoreCase(category)
        ) {
            return true;
        }

        if (routine.getCategory() == null) {
            return false;
        }

        return routine
                .getCategory()
                .equalsIgnoreCase(category);
    }

    // US-30: each sort is followed by the others, then by the older routine, so that routines
    // with the same numbers always keep one order. A routine without ratings has an average of
    // 0, below every rated one.
    private void sortRoutines(
            List<TopRoutineResponse> routines,
            String sort
    ) {

        Comparator<TopRoutineResponse> likes =
                Comparator
                        .comparingLong(
                                TopRoutineResponse::getLikeCount
                        )
                        .reversed();

        Comparator<TopRoutineResponse> saves =
                Comparator
                        .comparingLong(
                                TopRoutineResponse::getSaveCount
                        )
                        .reversed();

        Comparator<TopRoutineResponse> rating =
                Comparator
                        .comparingDouble(
                                TopRoutineResponse::getAverageRating
                        )
                        .reversed()
                        .thenComparing(
                                Comparator
                                        .comparingLong(
                                                TopRoutineResponse::getRatingCount
                                        )
                                        .reversed()
                        );

        Comparator<TopRoutineResponse> olderFirst =
                Comparator.comparing(
                        TopRoutineResponse::getId
                );

        if ("liked".equalsIgnoreCase(sort)) {

            routines.sort(
                    likes
                            .thenComparing(saves)
                            .thenComparing(rating)
                            .thenComparing(olderFirst)
            );

            return;
        }

        if ("saved".equalsIgnoreCase(sort)) {

            routines.sort(
                    saves
                            .thenComparing(likes)
                            .thenComparing(rating)
                            .thenComparing(olderFirst)
            );

            return;
        }

        if ("rated".equalsIgnoreCase(sort)) {

            routines.sort(
                    rating
                            .thenComparing(likes)
                            .thenComparing(saves)
                            .thenComparing(olderFirst)
            );

            return;
        }

        routines.sort(
                Comparator
                        .comparingDouble(
                                this::calculateScore
                        )
                        .reversed()
                        .thenComparing(rating)
                        .thenComparing(likes)
                        .thenComparing(saves)
                        .thenComparing(olderFirst)
        );
    }

    // Overall: one point for each like, save and rating, and ten for each star of the average.
    private double calculateScore(
            TopRoutineResponse routine
    ) {

        return routine.getLikeCount()
                + routine.getSaveCount()
                + routine.getRatingCount()
                + routine.getAverageRating() * 10;
    }
}