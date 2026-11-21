package com.copymylife.copymylife.service;

import com.copymylife.copymylife.dto.ProgressResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-07: the status rises exactly when the next level's number of completed activities is
 * reached and stays the same until then, with the way to the next level shown; the last
 * level has no next one.
 */
class ProgressStatusServiceTest {

    @Test
    void eachLevelStartsAtItsThreshold() {
        long[][] cases = {{0, 1}, {9, 1}, {10, 2}, {24, 2}, {25, 3}, {49, 3}, {50, 4}, {99, 4}, {100, 5}, {1000, 5}};
        String[] names = {"Starter", "Builder", "Consistent", "Achiever", "Master"};
        for (long[] c : cases) {
            ProgressResponse.Status status = ProgressStatusService.statusFor(c[0]);
            assertEquals(c[1], status.level(), c[0] + " " + status);
            assertEquals(names[(int) c[1] - 1], status.name(), c[0] + " " + status);
            assertEquals(c[0], status.completedActivities());
        }
    }

    @Test
    void withNothingDoneTheFirstLevelShowsTheWayToTheSecond() {
        ProgressResponse.Status status = ProgressStatusService.statusFor(0);
        assertEquals(0, status.levelStartsAt());
        assertEquals(2, status.nextLevel());
        assertEquals("Builder", status.nextName());
        assertEquals(10, status.nextLevelAt());
        assertEquals(10L, status.activitiesToNextLevel());
        assertEquals(0, status.progressPercent());
        assertFalse(status.maxLevel());
    }

    @Test
    void theWayToTheNextLevelIsCountedWithinTheLevel() {
        ProgressResponse.Status oneShort = ProgressStatusService.statusFor(24);
        assertEquals(1L, oneShort.activitiesToNextLevel());
        assertEquals(25, oneShort.nextLevelAt());
        assertEquals(93, oneShort.progressPercent(), "14 of 15, rounded down");

        ProgressResponse.Status reached = ProgressStatusService.statusFor(25);
        assertEquals(25, reached.levelStartsAt());
        assertEquals(25L, reached.activitiesToNextLevel());
        assertEquals(0, reached.progressPercent());
        assertEquals(98, ProgressStatusService.statusFor(99).progressPercent(), "49 of 50, never 100 before the level");
    }

    @Test
    void theLastLevelHasNoNextOne() {
        ProgressResponse.Status status = ProgressStatusService.statusFor(140);
        assertTrue(status.maxLevel());
        assertEquals(100, status.levelStartsAt());
        assertNull(status.nextLevel());
        assertNull(status.nextName());
        assertNull(status.nextLevelAt());
        assertNull(status.activitiesToNextLevel());
        assertEquals(100, status.progressPercent());
    }
}
