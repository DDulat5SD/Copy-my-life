package com.copymylife.copymylife.dto;

import java.util.List;
import java.util.Map;

// US-26: what Find Similar People shows. status is OK (matches found), NO_MATCHES, NEEDS_DATA
// (the user has written nothing to compare yet) or NO_CANDIDATES; message explains the last three.
// Scores and weights are whole percentages; a part without data on either side is null.
public record LifestyleMatchResponse(
        String status,
        String message,
        Map<String, Integer> weights,
        List<Card> matches
) {

    public record Card(
            Long userId,
            String name,
            String username,
            String avatarUrl,
            String profession,
            int score,
            Map<String, Integer> components,
            List<Pair> sharedInterests,
            List<Pair> similarActivities,
            List<String> sharedTimesOfDay,
            List<String> sharedDays,
            List<RoutineLink> publicRoutines
    ) {
    }

    // Something of the user's own ("mine") next to the close one the other member shares.
    public record Pair(String mine, String theirs) {
    }

    public record RoutineLink(Long id, String title, String category) {
    }
}
