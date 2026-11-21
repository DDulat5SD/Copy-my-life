package com.copymylife.copymylife.assistant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Routine assistant: quick fill recognises a few phrases and lists exactly what it took. */
class RequestParserTest {

    @Test
    void theExampleSentenceFillsTheForm() {
        RequestParser.Parsed parsed = RequestParser.parse(
                "I wake up at 07:00, study Java, go to the gym, need lunch at 13:00 and prefer frequent breaks.");
        assertEquals("07:00", parsed.preferences().get("wake"));
        assertEquals("13:00", parsed.preferences().get("lunch"));
        assertEquals("FREQUENT", parsed.preferences().get("restFrequency"));
        assertEquals(List.of("Java Study", "Gym"), parsed.habits().stream().map(RequestParser.Habit::title).toList());
        assertEquals(ActivityKind.STUDY, parsed.habits().get(0).kind());
        assertEquals(ActivityKind.EXERCISE, parsed.habits().get(1).kind());
        assertTrue(parsed.recognised().containsAll(List.of("Wake-up 07:00", "Lunch at 13:00", "Frequent breaks")), parsed.recognised().toString());
    }

    @Test
    void lengthsTimesAndCommitmentsAreRead() {
        RequestParser.Parsed parsed = RequestParser.parse(
                "University from 9:00 to 14:00, gym at 18:00 for 45 min, study Python for 2 hours, sleep at 23:30, long focus sessions");
        assertEquals(1, parsed.commitments().size());
        assertEquals(9 * 60, parsed.commitments().get(0).start());
        assertEquals(14 * 60, parsed.commitments().get(0).end());
        RequestParser.Habit gym = parsed.habits().stream().filter(h -> h.title().equals("Gym")).findFirst().orElseThrow();
        assertEquals(18 * 60, gym.preferredTime());
        assertEquals(45, gym.minutes());
        assertEquals(120, parsed.habits().stream().filter(h -> h.title().equals("Python Study")).findFirst().orElseThrow().minutes());
        assertEquals("23:30", parsed.preferences().get("sleep"));
        assertEquals("RARE", parsed.preferences().get("restFrequency"));
    }

    @Test
    void unrecognisedTextGivesNothingRatherThanGuesses() {
        RequestParser.Parsed parsed = RequestParser.parse("I like the colour blue.");
        assertTrue(parsed.preferences().isEmpty() && parsed.habits().isEmpty() && parsed.recognised().isEmpty());
    }

    @Test
    void kindsAreRecognisedInThreeLanguages() {
        assertEquals(ActivityKind.MEAL, ActivityKind.recognise("Lunch break"));
        assertEquals(ActivityKind.BREAK, ActivityKind.recognise("Study break"));
        assertEquals(ActivityKind.EXERCISE, ActivityKind.recognise("Тренировка в зале"));
        assertEquals(ActivityKind.STUDY, ActivityKind.recognise("Java оқу"));
        assertEquals(ActivityKind.MEAL, ActivityKind.recognise("Түскі ас"));
        assertEquals(ActivityKind.MEAL, ActivityKind.recognise("Brunch with friends"), "\"run\" inside a word is no run");
        assertEquals(ActivityKind.OTHER, ActivityKind.recognise("Restaurant review"), "\"rest\" inside a word is no rest");
        assertEquals("lunch", ActivityKind.mealOf("Обед"));
    }
}
