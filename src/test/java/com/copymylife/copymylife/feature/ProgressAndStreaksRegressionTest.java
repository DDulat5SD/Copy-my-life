package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-21: Progress shows the completion percentage, the current and best streaks and the days
 * of the week, all from the stored tracking records. A streak counts calendar days in a row
 * with the habit completed (today counts while it is not over); skipped, postponed and
 * unfinished habits do not continue it, and a habit done twice in a day counts once. The week
 * shows, for each day, the habits completed out of the habits tracked that day; days to come
 * are not counted as missed. A user only ever sees their own progress.
 * US-07: the status comes from every habit completed so far, each habit once a day; it rises at
 * the next level's number and keeps what was done when habits or routines are deleted.
 * Needs TEST_DB_URL (a local *_test database); the browser part also needs node and Chrome.
 */
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/\\w+_test"
)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL}",
                "spring.datasource.username=${TEST_DB_USERNAME:test}",
                "spring.datasource.password=${TEST_DB_PASSWORD:test}",
                "jwt.secret=dGVzdC1vbmx5LWtleS10ZXN0LW9ubHkta2V5LXRlc3Qtb25seS1rZXktdGVzdC1vbmx5",
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test",
                "app.upload-dir=target/test-uploads"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProgressAndStreaksRegressionTest {

    private static final String BOUNDARY = "ProgressBoundary";
    private static final String ZONE = "Asia/Almaty";
    private static final String EAST = "Pacific/Kiritimati";
    private static final String WEST = "Pacific/Pago_Pago";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token) {
    }

    private record Reply(int status, String body) {
    }

    private record Link(long routine, long routineHabit, long habit) {
    }

    private JsonNode browser;
    private Account owner;
    private Account stranger;
    private Account newcomer;
    private Account master;
    private LocalDate today;
    private final Map<String, Link> h = new LinkedHashMap<>();
    // What was stored, day by day: habit id -> its statuses that day, the oracle for the week.
    private final Map<LocalDate, Map<Long, Set<String>>> stored = new TreeMap<>();
    private LocalDate wednesday;

    // ---- AC1: streaks from stored records ----

    @Test
    void eachHabitsStreakCountsItsCompletedDaysInARow() throws Exception {
        JsonNode progress = progress(owner, "day", today);
        assertStreak(progress, "h1", 3, 5);   // done the last three days, not yet today; five in a row before
        assertStreak(progress, "h2", 2, 2);   // today and yesterday, yesterday in two routines
        assertStreak(progress, "h3", 1, 1);   // skipped yesterday, so only today
        assertStreak(progress, "h5", 2, 2);   // paused today, but its last two days stand
        assertStreak(progress, "h6", 1, 1);   // a record from before statuses existed
        assertStreak(progress, "h7", 1, 1);   // completed today through a real run
        assertEquals(1, streak(progress, "h4").path("bestDays").asInt(), "Monday and Wednesday are not days in a row");
        assertEquals(wednesday.equals(today.minusDays(1)) ? 1 : 0, streak(progress, "h4").path("currentDays").asInt());
    }

    @Test
    void theUsersStreakCountsDaysWithAnyHabitCompleted() throws Exception {
        Set<LocalDate> days = new TreeSet<>();
        stored.forEach((day, habits) -> habits.values().forEach(statuses -> {
            if (statuses.contains("COMPLETED")) {
                days.add(day);
            }
        }));
        JsonNode streak = progress(owner, "day", today).path("streak");
        assertEquals(currentRun(days), streak.path("currentDays").asInt(), streak.toString());
        assertEquals(bestRun(days), streak.path("bestDays").asInt(), streak.toString());
        assertEquals(today.toString(), streak.path("lastCompletedDate").asString());
        assertTrue(streak.path("currentDays").asInt() >= 4, streak.toString());
    }

    @Test
    void theStreaksAreTheSameWhateverDayOrPeriodIsChosen() throws Exception {
        JsonNode current = progress(owner, "day", today).path("habitStreaks");
        // Every habit of the owner was completed at least once, so none is missing.
        assertEquals(7, current.size(), current.toString());
        String streaks = current.toString();
        for (String period : List.of("week", "month", "year")) {
            assertEquals(streaks, progress(owner, period, today.minusDays(40)).path("habitStreaks").toString(), period);
        }
    }

    // ---- weekly progress ----

    @Test
    void theWeekShowsEachDaysRecordsFromMondayToSunday() throws Exception {
        for (LocalDate date : List.of(today, today.minusDays(7))) {
            JsonNode week = progress(owner, "week", date).path("week");
            LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            assertEquals(monday.toString(), week.path("from").asString());
            assertEquals(monday.plusDays(6).toString(), week.path("to").asString());
            assertEquals(7, week.path("days").size(), week.toString());

            int completed = 0;
            int tracked = 0;
            for (int i = 0; i < 7; i++) {
                LocalDate day = monday.plusDays(i);
                JsonNode shown = week.path("days").get(i);
                int[] expected = expectedDay(day);
                assertEquals(day.toString(), shown.path("date").asString());
                assertEquals(List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").get(i), shown.path("day").asString());
                assertEquals(expected[0], shown.path("completed").asInt(), day + " " + shown);
                assertEquals(expected[1], shown.path("skipped").asInt(), day + " " + shown);
                assertEquals(expected[2], shown.path("postponed").asInt(), day + " " + shown);
                assertEquals(expected[3], shown.path("tracked").asInt(), day + " " + shown);
                assertEquals(day.isAfter(today), shown.path("future").asBoolean(), day + " " + shown);
                assertEquals(day.equals(today), shown.path("today").asBoolean(), day + " " + shown);
                if (expected[3] == 0 || day.isAfter(today)) {
                    assertTrue(shown.path("percentage").isNull(), "no records is not 0%: " + shown);
                } else {
                    assertEquals(Math.round(expected[0] * 100.0 / expected[3]), shown.path("percentage").asLong(), shown.toString());
                }
                completed += expected[0];
                tracked += expected[3];
            }
            assertEquals(completed, week.path("completed").asInt(), week.toString());
            assertEquals(tracked, week.path("tracked").asInt(), week.toString());
        }
    }

    @Test
    void aHabitDoneInTwoRoutinesOnOneDayCountsOnce() throws Exception {
        JsonNode yesterday = dayOf(progress(owner, "week", today.minusDays(1)), today.minusDays(1));
        assertEquals(2, count("select count(*) from habit_completions where habit_id = ? and completed_date = ? and status = 'COMPLETED'",
                h.get("h2").habit(), today.minusDays(1)));
        assertEquals(expectedDay(today.minusDays(1))[0], yesterday.path("completed").asInt(), yesterday.toString());
    }

    @Test
    void daysToComeAreNotMissedDays() throws Exception {
        JsonNode week = progress(owner, "week", today.plusDays(7)).path("week");
        week.path("days").forEach(day -> {
            assertTrue(day.path("future").asBoolean(), day.toString());
            assertEquals(0, day.path("tracked").asInt());
            assertTrue(day.path("percentage").isNull());
        });
        assertTrue(week.path("percentage").isNull(), week.toString());
    }

    // ---- completion percentage ----

    @Test
    void theCompletionPercentageComesFromTheRecords() throws Exception {
        JsonNode day = item(progress(owner, "day", today), "habits", "Completed habits");
        // Done today: h2, h3 and h7. Expected: every habit still active somewhere, and h5, h6 only if done.
        assertEquals(3, day.path("completed").asInt(), day.toString());
        assertEquals(5, day.path("total").asInt(), day.toString());
        assertEquals(60, day.path("percentage").asInt(), day.toString());

        JsonNode yesterday = item(progress(owner, "day", today.minusDays(1)), "habits", "Completed habits");
        // h1, h2, h5 and h6 were done; h3 was skipped and does not count.
        assertEquals(4, yesterday.path("completed").asInt(), yesterday.toString());
    }

    @Test
    void aHabitDeletedFromItsRoutineIsNoLongerExpected() throws Exception {
        JsonNode before = item(progress(owner, "day", today.minusDays(20)), "habits", "Completed habits");
        // h6 was deleted from its only routine and h5 is paused in its only one; nothing was done that day.
        assertEquals(0, before.path("completed").asInt(), before.toString());
        assertEquals(5, before.path("total").asInt(), before.toString());
    }

    @Test
    void deletingAHabitChangesNoPastNumbers() throws Exception {
        Account solo = register("Solo");
        long routine = routine(solo, "Solo plan", 2);
        habit(solo, routine, "Solo one", 1, null);
        habit(solo, routine, "Solo two", 1, null);
        List<Link> links = links(routine);
        LocalDate day = today.minusDays(1);
        record(solo, routine, day, Map.of(links.get(0), "COMPLETED", links.get(1), "COMPLETED"), false);
        String before = progress(solo, "week", day).toString();

        Reply removed = send("DELETE", "/routines/" + routine + "/routine-habits/" + links.get(0).routineHabit(), solo, null);
        assertEquals(200, removed.status(), removed.body());

        assertEquals(before, progress(solo, "week", day).toString());
        assertEquals(1, progress(solo, "day", today).path("habitStreaks").get(0).path("currentDays").asInt());
    }

    @Test
    void periodsKeepTheirRanges() throws Exception {
        assertRange(progress(owner, "day", LocalDate.of(2026, 11, 15)), "2026-11-15", "2026-11-15");
        assertRange(progress(owner, "week", LocalDate.of(2026, 11, 15)), "2026-11-09", "2026-11-15");
        assertRange(progress(owner, "week", LocalDate.of(2026, 11, 16)), "2026-11-16", "2026-11-22");
        assertRange(progress(owner, "week", LocalDate.of(2026, 12, 31)), "2026-12-28", "2027-01-03");
        assertRange(progress(owner, "month", LocalDate.of(2028, 2, 10)), "2028-02-01", "2028-02-29");
        assertRange(progress(owner, "year", LocalDate.of(2028, 7, 1)), "2028-01-01", "2028-12-31");
        assertEquals("2026-12-28", progress(owner, "day", LocalDate.of(2026, 12, 31)).path("week").path("from").asString());
    }

    @Test
    void theOldFieldsAreStillThere() throws Exception {
        JsonNode progress = progress(owner, "week", today);
        for (String field : List.of("period", "from", "to", "routines", "habits")) {
            assertTrue(progress.has(field), field + " in " + progress);
        }
        assertEquals("week", progress.path("period").asString());
        assertEquals("Completed routines", progress.path("routines").get(0).path("label").asString());
        assertEquals("Completed habits", progress.path("habits").get(0).path("label").asString());
        for (JsonNode item : progress.path("habits")) {
            assertTrue(item.has("completed") && item.has("total") && item.has("percentage"), item.toString());
        }
    }

    // ---- AC2: nothing tracked ----

    @Test
    void withNoRecordsEverythingIsZeroOrEmpty() throws Exception {
        JsonNode progress = progress(newcomer, "week", today);
        assertFalse(progress.path("hasTracking").asBoolean(), progress.toString());
        assertEquals(0, progress.path("streak").path("currentDays").asInt());
        assertEquals(0, progress.path("streak").path("bestDays").asInt());
        assertTrue(progress.path("streak").path("lastCompletedDate").isNull(), progress.toString());
        assertEquals(0, progress.path("habitStreaks").size());
        assertEquals(0, progress.path("week").path("completed").asInt());
        assertEquals(0, progress.path("week").path("tracked").asInt());
        assertTrue(progress.path("week").path("percentage").isNull());
        progress.path("week").path("days").forEach(day -> assertTrue(day.path("percentage").isNull(), day.toString()));
        for (JsonNode item : progress.path("habits")) {
            assertEquals(0, item.path("completed").asInt(), item.toString());
        }
        assertTrue(progress(owner, "week", today).path("hasTracking").asBoolean());
    }

    // ---- time zone ----

    @Test
    void todayIsTheUsersDayInTheirTimeZone() throws Exception {
        for (String zone : List.of(EAST, WEST, ZONE)) {
            JsonNode progress = json.readTree(getIn("/progress?period=day", owner, zone).body());
            Set<LocalDate> expected = todayIn(zone);
            assertTrue(expected.contains(LocalDate.parse(progress.path("today").asString())), zone + " " + progress.path("today"));
            assertEquals(progress.path("today").asString(), progress.path("from").asString(), zone);
        }
        JsonNode unknown = json.readTree(getIn("/progress?period=day", owner, "Mars/Olympus").body());
        assertTrue(todayIn(ZONE).contains(LocalDate.parse(unknown.path("today").asString())), unknown.path("today").toString());
        assertNotEquals(json.readTree(getIn("/progress?period=day", owner, EAST).body()).path("today"),
                json.readTree(getIn("/progress?period=day", owner, WEST).body()).path("today"),
                "these zones are a day apart");
    }

    // ---- privacy ----

    @Test
    void eachUserSeesOnlyTheirOwnProgress() throws Exception {
        String theirs = progress(stranger, "year", today).toString();
        assertFalse(theirs.contains("Streak one"), theirs);
        // Their only record is a skip: tracked, but no streak and nothing of the owner's.
        assertEquals(0, progress(stranger, "year", today).path("habitStreaks").size());
        assertTrue(progress(stranger, "year", today).path("hasTracking").asBoolean());
        for (String path : List.of("/progress", "/progress?period=week&date=" + today)) {
            Reply anonymous = send("GET", path, null, null);
            assertTrue(anonymous.status() == 401 || anonymous.status() == 403, path + " " + anonymous.status());
        }
    }

    @Test
    void habitsOfADeletedRoutineAreNoLongerExpected() throws Exception {
        Account leaver = register("Leaver");
        long kept = routine(leaver, "Kept plan", 1);
        habit(leaver, kept, "Kept habit", 1, null);
        long gone = routine(leaver, "Gone plan", 1);
        habit(leaver, gone, "Gone habit", 1, null);
        LocalDate day = today.minusDays(1);
        record(leaver, kept, day, Map.of(links(kept).get(0), "COMPLETED"), false);
        assertEquals(2, item(progress(leaver, "day", day), "habits", "Completed habits").path("total").asInt());

        assertEquals(204, send("DELETE", "/routines/" + gone, leaver, null).status());

        JsonNode after = item(progress(leaver, "day", day), "habits", "Completed habits");
        assertEquals(1, after.path("completed").asInt(), after.toString());
        assertEquals(1, after.path("total").asInt(), "the deleted routine's habit was still expected: " + after);
    }

    // ---- US-07: status ----

    @Test
    void theStatusCountsEachHabitCompletedOnADayOnce() throws Exception {
        long expected = stored.values().stream()
                .mapToLong(habits -> habits.values().stream().filter(statuses -> statuses.contains("COMPLETED")).count())
                .sum();
        JsonNode status = progress(owner, "day", today).path("status");
        // h2 was done in two routines yesterday, h6 was deleted from its routine: each counts once.
        assertEquals(expected, status.path("completedActivities").asLong(), status.toString());
        assertEquals(status, progress(owner, "year", today.minusDays(400)).path("status"), "the same whatever is shown");
        assertEquals(status, status(owner));
    }

    @Test
    void newUsersAreStartersAndOnlyCompletedHabitsCount() throws Exception {
        // The newcomer tracked nothing; the stranger only skipped a habit.
        for (Account account : List.of(newcomer, stranger)) {
            JsonNode status = status(account);
            assertEquals(1, status.path("level").asInt(), status.toString());
            assertEquals("Starter", status.path("name").asString(), status.toString());
            assertEquals(0, status.path("completedActivities").asLong(), status.toString());
            assertEquals("Builder", status.path("nextName").asString(), status.toString());
            assertEquals(10, status.path("activitiesToNextLevel").asLong(), status.toString());
            assertEquals(0, status.path("progressPercent").asInt(), status.toString());
            assertFalse(status.path("maxLevel").asBoolean(), status.toString());
        }
        Reply anonymous = send("GET", "/progress/status", null, null);
        assertTrue(anonymous.status() == 401 || anonymous.status() == 403, String.valueOf(anonymous.status()));
    }

    @Test
    void theStatusRisesAtTheNextLevelAndKeepsWhatWasDone() throws Exception {
        Account climber = register("Climber");
        long routine = routine(climber, "Climb plan", 2);
        habit(climber, routine, "Climb one", 1, null);
        habit(climber, routine, "Climb two", 1, null);
        List<Link> links = links(routine);
        for (int back = 1; back <= 9; back++) {
            record(climber, routine, today.minusDays(back), Map.of(links.get(0), "COMPLETED", links.get(1), "SKIPPED"), false);
        }
        // Done again on a day it was already done, and postponed: nothing new.
        record(climber, routine, today.minusDays(1), Map.of(links.get(0), "COMPLETED", links.get(1), "POSTPONED"), false);

        JsonNode before = status(climber);
        assertEquals(9, before.path("completedActivities").asLong(), before.toString());
        assertEquals("Starter", before.path("name").asString(), before.toString());
        assertEquals(1, before.path("activitiesToNextLevel").asLong(), before.toString());
        assertEquals(90, before.path("progressPercent").asInt(), before.toString());

        String others = status(owner).toString();
        record(climber, routine, today, Map.of(links.get(0), "COMPLETED"), false);

        JsonNode after = status(climber);
        assertEquals(10, after.path("completedActivities").asLong(), after.toString());
        assertEquals(2, after.path("level").asInt(), after.toString());
        assertEquals("Builder", after.path("name").asString(), after.toString());
        assertEquals("Consistent", after.path("nextName").asString(), after.toString());
        assertEquals(15, after.path("activitiesToNextLevel").asLong(), after.toString());

        // Deleting the routine takes nothing away; nobody else's status moved.
        assertEquals(204, send("DELETE", "/routines/" + routine, climber, null).status());
        assertEquals(after, status(climber));
        assertEquals(others, status(owner).toString());
    }

    // ---- browser ----

    @Test
    void browserShowsTheStatus() {
        JsonNode view = page("owner");
        JsonNode status = view.path("api").path("status");
        assertTrue(view.path("statusVisible").asBoolean(), view.toString());
        assertEquals("Level " + status.path("level").asInt(), view.path("statusLevel").asString(), view.toString());
        assertEquals(status.path("name").asString(), view.path("statusName").asString(), view.toString());
        assertEquals(String.valueOf(status.path("completedActivities").asLong()), view.path("statusCount").asString());
        assertEquals(status.path("progressPercent").asInt() + "%", view.path("statusFill").asString(), view.toString());
        assertTrue(view.path("statusNext").asString().startsWith(status.path("activitiesToNextLevel").asLong() + " more completed habit"),
                view.toString());
        assertTrue(view.path("statusNext").asString().contains("Level " + status.path("nextLevel").asInt() + " · " + status.path("nextName").asString()),
                view.toString());

        JsonNode empty = page("empty");
        assertTrue(empty.path("statusVisible").asBoolean(), empty.toString());
        assertEquals("Level 1", empty.path("statusLevel").asString(), empty.toString());
        assertEquals("Starter", empty.path("statusName").asString(), empty.toString());
        assertEquals("0", empty.path("statusCount").asString(), empty.toString());
        assertEquals("0%", empty.path("statusFill").asString(), empty.toString());
        assertTrue(empty.path("statusNext").asString().startsWith("10 more completed habits to reach Level 2 · Builder"), empty.toString());

        JsonNode top = page("master");
        assertEquals("Level 5", top.path("statusLevel").asString(), top.toString());
        assertEquals("Master", top.path("statusName").asString(), top.toString());
        assertEquals("100", top.path("statusCount").asString(), top.toString());
        assertEquals("100%", top.path("statusFill").asString(), top.toString());
        assertTrue(top.path("statusNext").asString().contains("highest status"), top.toString());
    }

    @Test
    void browserShowsTheBackendsNumbers() {
        JsonNode view = page("owner");
        JsonNode api = view.path("api");
        assertEquals(item(api, "habits", "Completed habits").path("percentage").asInt() + "%", view.path("completion").asString(), view.toString());
        assertEquals(api.path("streak").path("currentDays").asInt() + " days", view.path("streak").asString(), view.toString());
        assertTrue(view.path("streakDetail").asString().contains("Best: " + api.path("streak").path("bestDays").asInt()), view.toString());
        assertEquals(api.path("week").path("percentage").isNull() ? "—" : api.path("week").path("percentage").asInt() + "%",
                view.path("weekValue").asString(), view.toString());
        assertFalse(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), texts(view.path("dayNames")), view.toString());
        for (int i = 0; i < 7; i++) {
            JsonNode day = api.path("week").path("days").get(i);
            String expected = day.path("future").asBoolean() ? "—"
                    : day.path("tracked").asInt() == 0 ? "—"
                    : day.path("completed").asInt() + "/" + day.path("tracked").asInt();
            assertEquals(expected, view.path("dayCounts").get(i).asString(), "day " + i + " " + view);
        }
        assertTrue(view.path("streakNames").toString().contains("Streak one"), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());
        // Percentages in the lists are the backend's.
        List<String> percents = new ArrayList<>();
        api.path("habits").forEach(item -> percents.add(item.path("percentage").asInt() + "%"));
        assertEquals(percents, texts(view.path("habitPercents")), view.toString());
        assertTrue(view.path("request").asString().contains("X-Time-Zone"), view.toString());
    }

    @Test
    void browserFollowsDayWeekMonthAndYear() {
        JsonNode view = page("periods");
        for (String period : List.of("day", "week", "month", "year")) {
            JsonNode shown = view.path(period);
            assertEquals(period, shown.path("api").path("period").asString(), shown.toString());
            assertEquals(item(shown.path("api"), "habits", "Completed habits").path("percentage").asInt() + "%",
                    shown.path("completion").asString(), period + " " + shown);
            assertTrue(shown.path("label").asString().toLowerCase().contains(period.equals("day") ? "day" : period), shown.toString());
        }
    }

    @Test
    void browserShowsAnEmptyStateWithoutNumbersThatAreNotThere() {
        JsonNode view = page("empty");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertTrue(view.path("emptyText").asString().contains("No tracking yet"), view.toString());
        assertEquals("0 days", view.path("streak").asString(), view.toString());
        assertEquals("—", view.path("weekValue").asString(), view.toString());
        texts(view.path("dayCounts")).forEach(count -> assertEquals("—", count, view.toString()));
    }

    @Test
    void browserKeepsTheOtherProfileSectionsWorking() {
        JsonNode view = page("sections");
        assertTrue(view.path("calendarShown").asBoolean(), view.toString());
        assertFalse(view.path("progressShownWithCalendar").asBoolean(), view.toString());
        assertTrue(view.path("progressBack").asBoolean(), view.toString());
        assertTrue(view.path("habitsShown").asBoolean(), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(10, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            String name = entry.getKey();
            if (view.path("overflow").asInt() > 0) {
                problems.add(name + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(name + " has progress content outside the screen: " + view.path("outside").asString());
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(name + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
            if (view.path("days").asInt() != 7) {
                problems.add(name + " shows " + view.path("days").asInt() + " days");
            }
            if (!view.path("daysOneRow").asBoolean()) {
                problems.add(name + " breaks the week over rows");
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
        assertEquals("autumn", layouts.path("autumn390").path("theme").asString());
    }

    @Test
    void browserNeverFailsOrInterrupts() {
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    // ---- data ----

    @BeforeAll
    void createContent() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");
        today = LocalDate.now(ZoneId.of(ZONE));
        owner = register("Streak keeper");
        stranger = register("Stranger");
        newcomer = register("Newcomer");
        long newcomerRoutine = routine(newcomer, "Not yet started", 1);
        habit(newcomer, newcomerRoutine, "Never tracked", 1, null);

        long r1 = routine(owner, "Streak plan", 3);
        habit(owner, r1, "Streak one " + TRICKY, 1, null);
        habit(owner, r1, "Streak two", 1, null);
        habit(owner, r1, "Streak three", 1, null);
        List<Link> one = links(r1);
        h.put("h1", one.get(0));
        h.put("h2", one.get(1));
        h.put("h3", one.get(2));

        // h2 is in a second routine too.
        long r2 = routine(owner, "Second plan", 1);
        Reply added = send("POST", "/routines/" + r2 + "/routine-habits/" + h.get("h2").habit(), owner, null);
        assertEquals(200, added.status(), added.body());
        Link h2again = links(r2).get(0);

        long r3 = routine(owner, "Some days", 2);
        habit(owner, r3, "Mondays and Wednesdays", 1, List.of("MON", "WED"));
        habit(owner, r3, "Paused later", 1, null);
        h.put("h4", links(r3).get(0));
        h.put("h5", links(r3).get(1));

        long r4 = routine(owner, "Deleted later", 1);
        habit(owner, r4, "Removed later", 1, null);
        h.put("h6", links(r4).get(0));

        long r5 = routine(owner, "Run today", 1);
        habit(owner, r5, "Run step", 1, null);
        h.put("h7", links(r5).get(0));

        // h1: five days in a row long ago, then the last three days but not today.
        for (int back = 10; back >= 6; back--) {
            record(owner, r1, today.minusDays(back), Map.of(h.get("h1"), "COMPLETED"), false);
        }
        record(owner, r1, today.minusDays(3), Map.of(h.get("h1"), "COMPLETED", h.get("h3"), "POSTPONED"), false);
        record(owner, r1, today.minusDays(2), Map.of(h.get("h1"), "COMPLETED", h.get("h3"), "COMPLETED"), false);
        record(owner, r1, today.minusDays(1), Map.of(h.get("h1"), "COMPLETED", h.get("h2"), "COMPLETED", h.get("h3"), "SKIPPED"), false);
        record(owner, r2, today.minusDays(1), Map.of(h2again, "COMPLETED"), false);
        record(owner, r1, today, Map.of(h.get("h2"), "COMPLETED", h.get("h3"), "COMPLETED"), false);

        // h4 on its last Monday and Wednesday before today.
        wednesday = today.minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY));
        record(owner, r3, wednesday.minusDays(2), Map.of(h.get("h4"), "COMPLETED"), false);
        record(owner, r3, wednesday, Map.of(h.get("h4"), "COMPLETED"), false);

        // h5: the last two days, then paused.
        record(owner, r3, today.minusDays(2), Map.of(h.get("h5"), "COMPLETED"), false);
        record(owner, r3, today.minusDays(1), Map.of(h.get("h5"), "COMPLETED"), false);
        assertEquals(200, send("POST", "/routines/" + r3 + "/routine-habits/" + h.get("h5").routineHabit() + "/pause", owner, null).status());

        // h6: a record from before statuses existed, then deleted from its routine.
        record(owner, r4, today.minusDays(1), Map.of(h.get("h6"), "COMPLETED"), true);
        assertEquals(200, send("DELETE", "/routines/" + r4 + "/routine-habits/" + h.get("h6").routineHabit(), owner, null).status());

        // h7: a real run today.
        assertEquals(200, sendIn("POST", "/routine-runs/" + r5 + "/start", owner, ZONE).status());
        assertEquals(200, sendIn("POST", "/routine-runs/" + r5 + "/routine-habits/" + h.get("h7").routineHabit() + "/start", owner, ZONE).status());
        jdbc.update("update habit_completions set started_at = started_at - interval '2 hours' where routine_habit_id = ?",
                h.get("h7").routineHabit());
        Reply done = sendIn("POST", "/routine-runs/" + r5 + "/routine-habits/" + h.get("h7").routineHabit() + "/complete", owner, ZONE);
        assertEquals(200, done.status(), done.body());
        mark(today, h.get("h7"), "COMPLETED");

        // The stranger tracks something of their own.
        long theirs = routine(stranger, "Their plan", 1);
        habit(stranger, theirs, "Their habit", 1, null);
        record(stranger, theirs, today, Map.of(links(theirs).get(0), "SKIPPED"), false);

        // US-07: a user at the last level, one habit completed on each of 100 days.
        master = register("Master");
        long everyDay = routine(master, "Every day", 1);
        habit(master, everyDay, "Daily habit", 1, null);
        Link daily = links(everyDay).get(0);
        for (int back = 0; back < 100; back++) {
            record(master, everyDay, today.minusDays(back), Map.of(daily, "COMPLETED"), false);
        }

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerToken", owner.token());
        fixtures.put("newcomerToken", newcomer.token());
        fixtures.put("masterToken", master.token());
        fixtures.put("today", today.toString());
        fixtures.put("zone", ZONE);

        Path dir = Files.createTempDirectory("progress-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/progress-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    // A finished run of the routine on the day, with a record for each habit; legacy rows have no status.
    private void record(Account account, long routine, LocalDate day, Map<Link, String> statuses, boolean legacy) {
        boolean allDone = statuses.values().stream().allMatch("COMPLETED"::equals);
        long run = jdbc.queryForObject("""
                insert into routine_completions (user_id, routine_id, completed_date, started_at, completed_at, ended_at)
                values (?, ?, ?, now() - interval '3 hours', case when ? then now() - interval '2 hours' end,
                        case when ? then null else now() - interval '2 hours' end)
                returning id
                """, Long.class, account.id(), routine, day, allDone, allDone);
        statuses.forEach((link, status) -> {
            boolean completed = status.equals("COMPLETED");
            jdbc.update("""
                    insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date,
                                                   started_at, completed_at, status)
                    values (?, ?, ?, ?, ?, now() - interval '3 hours', case when ? then now() - interval '2 hours' end, ?)
                    """, account.id(), link.habit(), link.routineHabit(), run, day, completed, legacy ? null : status);
            if (account.equals(owner)) {
                mark(day, link, status);
            }
        });
    }

    private void mark(LocalDate day, Link link, String status) {
        stored.computeIfAbsent(day, key -> new HashMap<>())
                .computeIfAbsent(link.habit(), key -> new TreeSet<>())
                .add(status);
    }

    // [completed, skipped, postponed, tracked] for one day; a habit counts once, by its best status.
    private int[] expectedDay(LocalDate day) {
        int[] counts = new int[4];
        stored.getOrDefault(day, Map.of()).values().forEach(statuses -> {
            if (statuses.contains("COMPLETED")) {
                counts[0]++;
            } else if (statuses.contains("POSTPONED")) {
                counts[2]++;
            } else if (statuses.contains("SKIPPED")) {
                counts[1]++;
            }
            counts[3]++;
        });
        return counts;
    }

    private int currentRun(Set<LocalDate> days) {
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        int run = 0;
        while (days.contains(day)) {
            run++;
            day = day.minusDays(1);
        }
        return run;
    }

    private static int bestRun(Set<LocalDate> days) {
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : days) {
            run = previous != null && previous.plusDays(1).equals(day) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = day;
        }
        return best;
    }

    private void assertStreak(JsonNode progress, String name, int current, int best) {
        JsonNode streak = streak(progress, name);
        assertEquals(current, streak.path("currentDays").asInt(), name + " " + streak);
        assertEquals(best, streak.path("bestDays").asInt(), name + " " + streak);
    }

    private JsonNode streak(JsonNode progress, String name) {
        long habit = h.get(name).habit();
        for (JsonNode streak : progress.path("habitStreaks")) {
            if (streak.path("habitId").asLong() == habit) {
                return streak;
            }
        }
        throw new AssertionError(name + " has no streak in " + progress.path("habitStreaks"));
    }

    private static JsonNode item(JsonNode progress, String group, String label) {
        for (JsonNode item : progress.path(group)) {
            if (label.equals(item.path("label").asString())) {
                return item;
            }
        }
        throw new AssertionError(label + " missing in " + progress);
    }

    private static JsonNode dayOf(JsonNode progress, LocalDate date) {
        for (JsonNode day : progress.path("week").path("days")) {
            if (date.toString().equals(day.path("date").asString())) {
                return day;
            }
        }
        throw new AssertionError(date + " missing in " + progress.path("week"));
    }

    private static void assertRange(JsonNode progress, String from, String to) {
        assertEquals(from, progress.path("from").asString(), progress.path("period").asString());
        assertEquals(to, progress.path("to").asString(), progress.path("period").asString());
    }

    private Set<LocalDate> todayIn(String zone) {
        LocalDate now = LocalDate.now(ZoneId.of(zone));
        return Set.of(now, now.plusDays(1));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private JsonNode progress(Account account, String period, LocalDate date) throws Exception {
        Reply reply = getIn("/progress?period=" + period + "&date=" + date, account, ZONE);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode status(Account account) throws Exception {
        Reply reply = getIn("/progress/status", account, ZONE);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private List<Link> links(long routine) {
        return jdbc.query("select routine_id, id, habit_id from routine_habits where routine_id = ? order by position",
                (row, index) -> new Link(row.getLong(1), row.getLong(2), row.getLong(3)), routine);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "progress_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Progress check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", "false");

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routines"))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private void habit(Account owner, long routine, String title, int minutes, List<String> days) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", title + " description");
        body.put("durationMinutes", minutes);
        if (days != null) {
            body.put("startTime", "07:00");
            body.put("repeatDays", days);
        }
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply getIn(String path, Account account, String zone) throws Exception {
        return sendIn("GET", path, account, zone);
    }

    private Reply sendIn(String method, String path, Account account, String zone) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("X-Time-Zone", zone)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            return "(no output)";
        }
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String findChrome() {
        List<String> candidates = new ArrayList<>();
        if (System.getenv("CHROME_BIN") != null) {
            candidates.add(System.getenv("CHROME_BIN"));
        }
        candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        candidates.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        candidates.add("/usr/bin/google-chrome");
        candidates.add("/usr/bin/chromium");
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        return candidates.stream().filter(path -> new File(path).isFile()).findFirst().orElse(null);
    }
}
