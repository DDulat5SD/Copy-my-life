package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ProgressResponse;
import com.copymylife.copymylife.repository.HabitCompletionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// US-07: the user's status from the habits they completed. Each habit counts once per day it was
// completed, in any routine; skipped, postponed and unfinished habits never count. What was done
// stays done: deleting a habit from a routine, or the routine itself, takes nothing away.
@Service
public class ProgressStatusService {

    // Product defaults, not from the SRS: the first number of completions of each level.
    static final List<Level> LEVELS = List.of(
            new Level(1, "Starter", 0),
            new Level(2, "Builder", 10),
            new Level(3, "Consistent", 25),
            new Level(4, "Achiever", 50),
            new Level(5, "Master", 100)
    );

    record Level(int number, String name, int from) {
    }

    private final HabitCompletionRepository habitCompletionRepository;

    public ProgressStatusService(HabitCompletionRepository habitCompletionRepository) {
        this.habitCompletionRepository = habitCompletionRepository;
    }

    @Transactional(readOnly = true)
    public ProgressResponse.Status statusOf(Long userId) {
        return statusFor(habitCompletionRepository.countCompletedHabitDays(userId));
    }

    // The highest level reached, and how far it is to the next one; at the last level next is null.
    static ProgressResponse.Status statusFor(long completed) {
        long done = Math.max(0, completed);
        Level level = LEVELS.get(0);

        for (Level candidate : LEVELS) {
            if (done >= candidate.from()) {
                level = candidate;
            }
        }

        int index = LEVELS.indexOf(level);

        if (index == LEVELS.size() - 1) {
            return new ProgressResponse.Status(level.number(), level.name(), done, level.from(),
                    null, null, null, null, 100, true);
        }

        Level next = LEVELS.get(index + 1);
        long span = next.from() - level.from();
        // Rounded down, so 100% only shows once the next level is reached.
        int percent = (int) ((done - level.from()) * 100 / span);

        return new ProgressResponse.Status(level.number(), level.name(), done, level.from(),
                next.number(), next.name(), next.from(), next.from() - done, percent, false);
    }
}
