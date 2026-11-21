package com.copymylife.copymylife.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HalloweenRoutineCatalogTest {

    private static final List<HalloweenRoutineCatalog.RoutineSeed> ROUTINES = HalloweenRoutineCatalog.ROUTINES;

    @Test
    void hasTenUniqueRoutinesWithExpectedSchedule() {
        assertEquals(10, ROUTINES.size());
        assertEquals(10, new HashSet<>(ROUTINES.stream().map(HalloweenRoutineCatalog.RoutineSeed::title).toList()).size());

        for (HalloweenRoutineCatalog.RoutineSeed routine : ROUTINES) {
            assertEquals(220, routine.durationMinutes(), routine.title());
            assertEquals(
                    List.of(35, 30, 30, 30, 35, 30, 30),
                    routine.habits().stream().map(HalloweenRoutineCatalog.HabitSeed::durationMinutes).toList(),
                    routine.title()
            );
        }
    }

    @Test
    void descriptionsArePresentAndDifferFromTitles() {
        for (HalloweenRoutineCatalog.RoutineSeed routine : ROUTINES) {
            for (HalloweenRoutineCatalog.HabitSeed habit : routine.habits()) {
                assertFalse(habit.description().isBlank(), habit.title());
                assertFalse(habit.description().equalsIgnoreCase(habit.title() + "."), habit.title());
                assertTrue(habit.description().length() <= 500, habit.title());
            }
        }
    }

    @Test
    void matchesFrontendCharacterData() throws Exception {
        String data = new ClassPathResource("static/halloween-data.js").getContentAsString(StandardCharsets.UTF_8);

        for (HalloweenRoutineCatalog.RoutineSeed routine : ROUTINES) {
            assertTrue(data.contains("\"name\":\"" + routine.authorName() + "\""), routine.authorName());
            assertTrue(data.contains("\"title\":\"" + routine.title() + "\""), routine.title());
            assertTrue(data.contains("\"image\":\"" + routine.imageUrl() + "\""), routine.imageUrl());

            for (HalloweenRoutineCatalog.HabitSeed habit : routine.habits()) {
                assertTrue(data.contains("\"activity\":\"" + habit.title() + "\""), habit.title());
            }
        }
    }

    @Test
    void findsAuthorOnlyForHalloweenTitles() {
        assertEquals("Michael Myers", HalloweenRoutineCatalog.findAuthorName("The Silent Routine").orElseThrow());
        assertTrue(HalloweenRoutineCatalog.findAuthorName("Bill Gates Routine").isEmpty());
    }
}
