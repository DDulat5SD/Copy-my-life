package com.copymylife.copymylife.dto;

import java.util.List;

// US-12: the signed-in creator's own numbers (GET /users/me/creator-stats). A count with nothing
// to count is 0, never missing. totalCopies adds up the copies of the routines listed.
public record CreatorStatsResponse(
        long followersCount,
        long totalCopies,
        int totalPublicRoutines,
        List<RoutineStats> routines
) {

    public record RoutineStats(
            long routineId,
            String title,
            long copiesCount,
            long likesCount,
            long savesCount,
            double averageRating,
            long ratingCount
    ) {
    }
}
