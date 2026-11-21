package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.CreatorStatsResponse;
import com.copymylife.copymylife.dto.CreatorStatsResponse.RoutineStats;
import com.copymylife.copymylife.model.User;
import com.copymylife.copymylife.repository.RoutineRepository;
import com.copymylife.copymylife.repository.UserFollowRepository;
import com.copymylife.copymylife.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

/**
 * US-12 Creator Statistics: how many people follow the signed-in member and how often each of
 * their published routines has been copied. Only ever the caller's own numbers; there is no way to
 * ask for someone else's. Followers are counted exactly as the profile counts them (banned
 * followers left out). A copy is a routine another member, not banned, saved through Copy or
 * Adapt &amp; copy, whose source is the routine they chose (RoutineService.saveCopy); a copy of a
 * copy counts for the copy, not for the original. The creator's own copies do not count, and
 * copies made before US-12 recorded no source and are not counted. Published means what other members can open and copy: public, not
 * deleted, not hidden by an administrator.
 */
@Service
public class CreatorStatsService {

    // Most copied first; among equals, the newest routine first.
    private static final Comparator<RoutineStats> ORDER = Comparator
            .comparingLong(RoutineStats::copiesCount).reversed()
            .thenComparing(Comparator.comparingLong(RoutineStats::routineId).reversed());

    private final UserRepository userRepository;
    private final RoutineRepository routineRepository;
    private final UserFollowRepository userFollowRepository;

    public CreatorStatsService(
            UserRepository userRepository,
            RoutineRepository routineRepository,
            UserFollowRepository userFollowRepository
    ) {
        this.userRepository = userRepository;
        this.routineRepository = routineRepository;
        this.userFollowRepository = userFollowRepository;
    }

    @Transactional(readOnly = true)
    public CreatorStatsResponse getMine(String email) {
        User creator = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        List<RoutineStats> routines = routineRepository.findCreatorRoutineStats(creator.getId()).stream()
                .map(row -> new RoutineStats(
                        row.getRoutineId(),
                        row.getTitle(),
                        orZero(row.getCopiesCount()),
                        orZero(row.getLikesCount()),
                        orZero(row.getSavesCount()),
                        row.getAverageRating() == null ? 0.0 : row.getAverageRating(),
                        orZero(row.getRatingCount())
                ))
                .sorted(ORDER)
                .toList();

        return new CreatorStatsResponse(
                userFollowRepository.countFollowers(creator.getId()),
                routines.stream().mapToLong(RoutineStats::copiesCount).sum(),
                routines.size(),
                routines
        );
    }

    private static long orZero(Long value) {
        return value == null ? 0 : value;
    }
}
