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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutineScheduleRegressionTest {

    private static final String BOUNDARY = "RoutineScheduleBoundary";
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final List<String> EVERY_DAY = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String token) {
    }

    private record Plan(Account owner, long routine, List<Long> steps, List<Long> habits) {
    }

    // ---- create and edit routine ----

    @Test
    void aRoutineIsCreatedWithEveryFieldAndSurvivesARefresh() throws Exception {
        Account owner = register();

        Reply created = saveRoutine("POST", "/routines", owner, routineFields("Morning plan", "Wake up well", "Morning", "90", true));

        assertEquals(200, created.status(), created.body());
        long id = json.readTree(created.body()).path("id").asLong();
        JsonNode stored = json.readTree(get("/routines/" + id, owner).body());
        assertEquals("Morning plan", stored.path("title").asString());
        assertEquals("Wake up well", stored.path("description").asString());
        assertEquals("Morning", stored.path("category").asString());
        assertEquals(90, stored.path("durationMinutes").asInt());
        assertTrue(stored.path("public").asBoolean() || stored.path("isPublic").asBoolean(), stored.toString());
    }

    @Test
    void invalidRoutinesAreRefusedWithAReason() throws Exception {
        Account owner = register();
        int before = routinesOf(owner);

        for (Map<String, String> fields : List.of(
                routineFields("   ", "d", "Morning", "30", false),
                routineFields("x".repeat(101), "d", "Morning", "30", false),
                routineFields("Fine", "y".repeat(501), "Morning", "30", false),
                routineFields("Fine", "d", "  ", "30", false),
                routineFields("Fine", "d", "Morning", "0", false),
                routineFields("Fine", "d", "Morning", "-5", false),
                routineFields("Fine", "d", "Morning", "1441", false)
        )) {
            Reply reply = saveRoutine("POST", "/routines", owner, fields);
            assertEquals(400, reply.status(), fields + " " + reply.body());
            assertFalse(message(reply).isBlank(), reply.body());
        }
        assertEquals(before, routinesOf(owner));
    }

    @Test
    void editingARoutineIsSavedAndValidated() throws Exception {
        Plan plan = plan(2, 2);

        Reply ok = saveRoutine("PUT", "/routines/" + plan.routine(), plan.owner(), routineFields("Renamed", "New", "Study", "10", false));
        Reply blank = saveRoutine("PUT", "/routines/" + plan.routine(), plan.owner(), routineFields("", "New", "Study", "10", false));
        Reply shorter = saveRoutine("PUT", "/routines/" + plan.routine(), plan.owner(), routineFields("Renamed", "New", "Study", "1", false));

        assertEquals(200, ok.status(), ok.body());
        assertEquals(400, blank.status(), blank.body());
        assertEquals(400, shorter.status(), shorter.body());
        assertEquals("Routine duration can't be shorter than its habits (2 min)", message(shorter));
        JsonNode stored = json.readTree(get("/routines/" + plan.routine(), plan.owner()).body());
        assertEquals("Renamed", stored.path("title").asString());
        assertEquals(10, stored.path("durationMinutes").asInt());
    }

    // ---- schedule: start time, duration, repeat days, notes ----

    @Test
    void aScheduleIsSavedAndSurvivesARefresh() throws Exception {
        Plan plan = plan(2, 60);

        Reply reply = schedule(plan, 0, "07:30", null, List.of("FRI", "MON", "WED"), "Chapter 3, quietly");

        assertEquals(200, reply.status(), reply.body());
        JsonNode step = step(steps(plan), plan.steps().get(0));
        assertEquals("07:30", step.path("startTime").asString());
        assertEquals("07:31", step.path("endTime").asString());
        assertEquals(List.of("MON", "WED", "FRI"), texts(step.path("repeatDays")), "days come back in weekday order");
        assertEquals("Chapter 3, quietly", step.path("notes").asString());
        assertEquals("07:30:00", jdbc.queryForObject("select start_time::text from routine_habits where id = ?", String.class, plan.steps().get(0)));
        assertEquals("MON,WED,FRI", jdbc.queryForObject("select repeat_days from routine_habits where id = ?", String.class, plan.steps().get(0)));
    }

    @Test
    void theDurationCanBeChangedFromTheSchedule() throws Exception {
        Plan plan = plan(2, 30);

        Reply fits = schedule(plan, 0, "06:00", 20, WEEKDAYS, null);
        Reply tooLong = schedule(plan, 1, "07:00", 15, WEEKDAYS, null);

        assertEquals(200, fits.status(), fits.body());
        assertEquals(20, step(steps(plan), plan.steps().get(0)).path("durationMinutes").asInt());
        assertEquals("06:20", step(steps(plan), plan.steps().get(0)).path("endTime").asString());
        assertEquals(400, tooLong.status(), tooLong.body());
        assertEquals("Only 10 minutes remaining in this routine", message(tooLong));
    }

    @Test
    void aSharedHabitKeepsItsDurationButEachRoutineKeepsItsOwnTime() throws Exception {
        Account owner = register();
        long shared = libraryHabit(owner, "Shared", 10);
        long morning = routine(owner, 30, false, "Morning");
        long evening = routine(owner, 30, false, "Wellness");
        post("/routines/" + morning + "/routine-habits/" + shared, owner, null);
        post("/routines/" + evening + "/routine-habits/" + shared, owner, null);
        long morningStep = stepIds(owner, morning).get(0);
        long eveningStep = stepIds(owner, evening).get(0);

        Reply time = put("/routines/" + morning + "/routine-habits/" + morningStep, owner,
                scheduleBody("06:15", null, WEEKDAYS, "before work"));
        Reply duration = put("/routines/" + morning + "/routine-habits/" + morningStep, owner,
                scheduleBody("06:15", 12, WEEKDAYS, null));

        assertEquals(200, time.status(), time.body());
        assertEquals(409, duration.status(), duration.body());
        assertEquals("Habit is used in multiple routines", message(duration));
        JsonNode evening0 = json.readTree(get("/routines/" + evening + "/routine-habits", owner).body()).get(0);
        assertTrue(evening0.path("startTime").isNull(), evening0.toString());
        assertEquals(0, evening0.path("repeatDays").size());
        assertEquals(eveningStep, evening0.path("id").asLong());
        assertEquals(10, jdbc.queryForObject("select duration_minutes from habits where id = ?", Integer.class, shared));
    }

    @Test
    void badTimesDaysAndNotesAreRefused() throws Exception {
        Plan plan = plan(1, 10);

        for (Object[] bad : List.of(
                new Object[]{"25:00", List.of("MON"), null},
                new Object[]{"7:5", List.of("MON"), null},
                new Object[]{"noon", List.of("MON"), null},
                new Object[]{"07:00", List.of(), null},
                new Object[]{"07:00", List.of("FUNDAY"), null},
                new Object[]{"07:00", List.of("MON"), "n".repeat(501)}
        )) {
            @SuppressWarnings("unchecked")
            Reply reply = schedule(plan, 0, (String) bad[0], null, (List<String>) bad[1], (String) bad[2]);
            assertEquals(400, reply.status(), Arrays.toString(bad) + " " + reply.body());
            assertFalse(message(reply).isBlank(), reply.body());
        }
        assertEquals(400, schedule(plan, 0, "07:00", 0, List.of("MON"), null).status());
        assertEquals(400, schedule(plan, 0, "07:00", 1441, List.of("MON"), null).status());
        assertTrue(step(steps(plan), plan.steps().get(0)).path("startTime").isNull(), "nothing was saved");
    }

    @Test
    void repeatDaysWithoutATimeAndClearingTheScheduleAreAllowed() throws Exception {
        Plan plan = plan(1, 10);

        assertEquals(200, schedule(plan, 0, null, null, List.of("SAT", "SUN", "SAT"), "weekends").status());
        JsonNode weekend = step(steps(plan), plan.steps().get(0));
        assertTrue(weekend.path("startTime").isNull());
        assertEquals(List.of("SAT", "SUN"), texts(weekend.path("repeatDays")));

        assertEquals(200, schedule(plan, 0, null, null, null, null).status());
        JsonNode cleared = step(steps(plan), plan.steps().get(0));
        assertTrue(cleared.path("startTime").isNull());
        assertEquals(0, cleared.path("repeatDays").size());
        assertTrue(cleared.path("notes").isNull());
        assertNull(jdbc.queryForObject("select repeat_days from routine_habits where id = ?", String.class, plan.steps().get(0)));
    }

    // ---- overlapping habits ----

    @Test
    void timedHabitsMayNotOverlapOnTheSameDay() throws Exception {
        Plan plan = plan(3, 90);
        schedule(plan, 0, "07:00", 30, List.of("MON", "WED"), null);

        Reply clash = schedule(plan, 1, "07:15", 30, List.of("WED"), null);

        assertEquals(400, clash.status(), clash.body());
        assertEquals("Overlaps with \"Habit 1\" (07:00 – 07:30) on Wed. Two timed habits can't share the same minutes.", message(clash));
        assertEquals(200, schedule(plan, 1, "07:30", 30, List.of("WED"), null).status(), "back to back is fine");
        assertEquals(200, schedule(plan, 2, "07:10", 30, List.of("TUE"), null).status(), "other days are fine");
        assertEquals(200, schedule(plan, 2, null, 30, List.of("MON"), null).status(), "untimed habits never clash");
    }

    @Test
    void anOvernightHabitCountsAgainstTheNextMorning() throws Exception {
        Plan plan = plan(2, 120);
        assertEquals(200, schedule(plan, 0, "23:30", 60, List.of("SUN"), null).status());

        JsonNode late = step(steps(plan), plan.steps().get(0));
        assertEquals("00:30", late.path("endTime").asString());
        assertTrue(late.path("endsNextDay").asBoolean());

        Reply clash = schedule(plan, 1, "00:15", 30, List.of("MON"), null);
        assertEquals(400, clash.status(), clash.body());
        assertTrue(message(clash).contains("on Mon"), clash.body());
        assertEquals(200, schedule(plan, 1, "00:15", 30, List.of("TUE"), null).status());
    }

    @Test
    void aPausedHabitKeepsItsTimeSlot() throws Exception {
        Plan plan = plan(2, 60);
        schedule(plan, 0, "08:00", 30, EVERY_DAY, "keep");
        assertEquals(200, post(stepPath(plan, 0) + "/pause", plan.owner(), null).status());

        Reply clash = schedule(plan, 1, "08:10", 30, List.of("MON"), null);
        assertEquals(400, clash.status(), "a paused habit comes back to the same slot");

        assertEquals(200, post(stepPath(plan, 0) + "/resume", plan.owner(), null).status());
        JsonNode resumed = step(steps(plan), plan.steps().get(0));
        assertEquals("08:00", resumed.path("startTime").asString());
        assertEquals(EVERY_DAY, texts(resumed.path("repeatDays")));
        assertEquals("keep", resumed.path("notes").asString());
    }

    @Test
    void simultaneousOverlappingSchedulesCannotBothWin() throws Exception {
        Plan plan = plan(2, 60);

        List<Integer> statuses = together(12, index ->
                schedule(plan, index % 2, index % 2 == 0 ? "07:00" : "07:10", 30, List.of("MON"), null).status());

        assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 400), statuses.toString());
        List<String> times = jdbc.queryForList(
                "select coalesce(start_time::text, '-') from routine_habits where routine_id = ? order by position",
                String.class, plan.routine());
        assertTrue(times.contains("-"), "only one of the overlapping times may be saved: " + times);
    }

    // ---- creating an activity with its schedule ----

    @Test
    void anActivityCanBeCreatedWithItsSchedule() throws Exception {
        Account owner = register();
        long routine = routine(owner, 30, false, "Morning");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Journal");
        body.put("description", "Three lines");
        body.put("durationMinutes", 15);
        body.put("startTime", "21:45");
        body.put("repeatDays", List.of("SUN", "MON"));
        body.put("notes", "Use the blue notebook");
        Reply reply = post("/routines/" + routine + "/habits", owner, body);

        assertEquals(200, reply.status(), reply.body());
        JsonNode step = json.readTree(get("/routines/" + routine + "/routine-habits", owner).body()).get(0);
        assertEquals("Journal", step.path("title").asString());
        assertEquals("21:45", step.path("startTime").asString());
        assertEquals("22:00", step.path("endTime").asString());
        assertEquals(List.of("MON", "SUN"), texts(step.path("repeatDays")));
        assertEquals("Use the blue notebook", step.path("notes").asString());
    }

    @Test
    void anActivityWithABadScheduleIsNotCreatedAtAll() throws Exception {
        Plan plan = plan(1, 60);
        schedule(plan, 0, "07:00", null, List.of("MON"), null);
        int habits = habitsOf(plan.owner());

        Map<String, Object> clash = new LinkedHashMap<>();
        clash.put("title", "Clash");
        clash.put("durationMinutes", 10);
        clash.put("startTime", "07:00");
        clash.put("repeatDays", List.of("MON"));
        Reply reply = post("/routines/" + plan.routine() + "/habits", plan.owner(), clash);

        assertEquals(400, reply.status(), reply.body());
        assertTrue(message(reply).startsWith("Overlaps with"), reply.body());
        assertEquals(habits, habitsOf(plan.owner()));
        assertEquals(1, stepIds(plan.owner(), plan.routine()).size());
    }

    // ---- timeline data, empty routines and privacy ----

    @Test
    void routinesWithoutAScheduleKeepEveryHabitAndOrder() throws Exception {
        Plan plan = plan(3, 3);

        JsonNode list = steps(plan);

        assertEquals(plan.steps(), ids(list));
        for (JsonNode item : list) {
            assertTrue(item.path("startTime").isNull(), item.toString());
            assertTrue(item.path("endTime").isNull(), item.toString());
            assertEquals(0, item.path("repeatDays").size());
        }
    }

    @Test
    void anEmptyRoutineHasNoHabits() throws Exception {
        Account owner = register();
        long routine = routine(owner, 30, false, "Morning");

        Reply reply = get("/routines/" + routine + "/routine-habits", owner);

        assertEquals(200, reply.status());
        assertEquals("[]", reply.body());
        assertEquals(400, post("/routine-runs/" + routine + "/start", owner, null).status());
    }

    @Test
    void viewersSeeAPublicScheduleButNeverAPrivateOne() throws Exception {
        Plan open = plan(1, 10, true);
        Plan hidden = plan(1, 10, false);
        schedule(open, 0, "09:00", null, List.of("TUE"), "public note");
        schedule(hidden, 0, "09:00", null, List.of("TUE"), "private note");
        Account viewer = register();

        JsonNode seen = json.readTree(get("/routines/" + open.routine() + "/routine-habits", viewer).body()).get(0);
        Reply secret = get("/routines/" + hidden.routine() + "/routine-habits", viewer);

        assertEquals("09:00", seen.path("startTime").asString());
        assertEquals("public note", seen.path("notes").asString());
        assertEquals(404, secret.status());
        assertFalse(secret.body().contains("private note"));
    }

    @Test
    void onlyTheOwnerCanChangeASchedule() throws Exception {
        Plan plan = plan(1, 10, true);
        Account stranger = register();
        Plan strangers = plan(1, 10);
        Map<String, Object> body = scheduleBody("06:00", null, List.of("MON"), "x");

        assertEquals(404, put(stepPath(plan, 0), stranger, body).status());
        assertEquals(403, put(stepPath(plan, 0), null, body).status());
        assertEquals(404, put("/routines/" + strangers.routine() + "/routine-habits/" + plan.steps().get(0), strangers.owner(), body).status());
        assertTrue(step(steps(plan), plan.steps().get(0)).path("startTime").isNull());

        Reply update = saveRoutine("PUT", "/routines/" + plan.routine(), stranger, routineFields("Taken", "d", "Morning", "10", true));
        assertEquals(404, update.status());
    }

    @Test
    void aRunningRoutineKeepsItsSchedule() throws Exception {
        Plan plan = plan(2, 2);
        start(plan);

        Reply reply = schedule(plan, 0, "07:00", null, List.of("MON"), null);

        assertEquals(409, reply.status(), reply.body());
        assertEquals("Cannot edit a running routine", message(reply));
    }

    // ---- compatibility ----

    @Test
    void copyingARoutineCopiesTheSchedule() throws Exception {
        Plan plan = plan(2, 60, true);
        schedule(plan, 0, "06:00", null, WEEKDAYS, "first");
        schedule(plan, 1, "18:30", null, List.of("SAT"), "second");
        post(stepPath(plan, 1) + "/pause", plan.owner(), null);
        Account copier = register();

        Reply copy = post("/routines/" + plan.routine() + "/copy", copier, null);

        assertEquals(200, copy.status(), copy.body());
        long copyId = json.readTree(copy.body()).path("id").asLong();
        JsonNode copied = json.readTree(get("/routines/" + copyId + "/routine-habits", copier).body());
        assertEquals("06:00", copied.get(0).path("startTime").asString());
        assertEquals(WEEKDAYS, texts(copied.get(0).path("repeatDays")));
        assertEquals("first", copied.get(0).path("notes").asString());
        assertEquals("18:30", copied.get(1).path("startTime").asString());
        assertEquals(List.of("SAT"), texts(copied.get(1).path("repeatDays")));
        assertFalse(copied.get(1).path("paused").asBoolean(), "the pause stays with its owner");
        assertNotEquals(plan.steps().get(0), copied.get(0).path("id").asLong());
    }

    @Test
    void dailyTrackingStillWorksWithTimedHabits() throws Exception {
        Plan plan = plan(4, 4);
        schedule(plan, 0, "09:00", null, EVERY_DAY, null);
        schedule(plan, 1, "07:00", null, EVERY_DAY, null);
        start(plan);

        // The run follows the timeline: 07:00, then 09:00, then the untimed habits.
        finish(plan, 1);
        assertEquals(200, act(plan, 0, "postpone").status());
        assertEquals(200, act(plan, 2, "skip").status());
        JsonNode stopped = json.readTree(post("/routine-runs/" + plan.routine() + "/stop", plan.owner(), null).body());

        assertEquals("STOPPED", stopped.path("status").asString(), stopped.toString());
        assertEquals(plan.steps().get(1), stopped.path("habits").get(0).path("routineHabitId").asLong());
        assertEquals("COMPLETED", stopped.path("habits").get(0).path("status").asString());
        assertEquals("POSTPONED", stopped.path("habits").get(1).path("status").asString());
        assertEquals("SKIPPED", stopped.path("habits").get(2).path("status").asString());
        assertEquals(1, stopped.path("completedCount").asInt());
    }

    @Test
    void theCalendarStillWorks() throws Exception {
        Plan plan = plan(1, 1);
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        Map<String, Object> event = new HashMap<>();
        event.put("routineId", plan.routine());
        event.put("day", monday.toString());
        event.put("time", "07:00");
        event.put("repeat", "DAILY");

        Reply created = post("/calendar-events", plan.owner(), event);
        Reply week = get("/calendar-events?from=" + monday + "&to=" + monday.plusDays(6), plan.owner());

        assertEquals(200, created.status(), created.body());
        assertEquals(200, week.status(), week.body());
        assertEquals(7, json.readTree(week.body()).size(), week.body());
    }

    @Test
    void progressStillWorks() throws Exception {
        Plan plan = plan(2, 2);
        schedule(plan, 0, "07:00", null, EVERY_DAY, null);
        start(plan);
        JsonNode run = finish(plan, 0);

        Reply reply = get("/progress?period=day&date=" + run.path("date").asString(), plan.owner());

        assertEquals(200, reply.status());
        for (JsonNode item : json.readTree(reply.body()).path("habits")) {
            if (item.path("label").asString().equals("Completed habits")) {
                assertEquals(1, item.path("completed").asInt());
                assertEquals(2, item.path("total").asInt());
                return;
            }
        }
        throw new AssertionError(reply.body());
    }

    @Test
    void schemaKeepsTheScheduleOnTheRoutineHabit() {
        Map<String, String> columns = new HashMap<>();
        jdbc.queryForList("""
                select column_name, data_type, is_nullable from information_schema.columns
                where table_name = 'routine_habits' and column_name in ('start_time', 'repeat_days', 'notes')
                """).forEach(row -> columns.put((String) row.get("column_name"), row.get("data_type") + "/" + row.get("is_nullable")));

        assertEquals("time without time zone/YES", columns.get("start_time"), columns.toString());
        assertEquals("character varying/YES", columns.get("repeat_days"), columns.toString());
        assertEquals("character varying/YES", columns.get("notes"), columns.toString());
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'habits' and column_name in ('start_time', 'repeat_days', 'notes')
                """, Integer.class), "the same habit can have a different time in each routine");
    }

    // ---- real browser ----

    @BeforeAll
    void planRoutinesInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account owner = register();
        long routine = routine(owner, 80, true, "Morning");
        for (String title : List.of("Read", "Run", "Stretch")) {
            post("/routines/" + routine + "/habits", owner, Map.of("title", title, "description", title + " notes", "durationMinutes", 20));
        }
        List<Long> steps = stepIds(owner, routine);
        put("/routines/" + routine + "/routine-habits/" + steps.get(1), owner, scheduleBody("06:30", null, WEEKDAYS, "Easy pace"));
        put("/routines/" + routine + "/routine-habits/" + steps.get(2), owner, scheduleBody("21:00", null, EVERY_DAY, null));

        Account emptyOwner = register();
        long empty = routine(emptyOwner, 30, false, "Morning");

        Account halloweenOwner = register();
        long halloween = routine(halloweenOwner, 40, false, "Halloween");
        post("/routines/" + halloween + "/habits", halloweenOwner, Map.of("title", "Carve", "durationMinutes", 20));
        post("/routines/" + halloween + "/habits", halloweenOwner, Map.of("title", "Costume", "durationMinutes", 20));
        List<Long> halloweenSteps = stepIds(halloweenOwner, halloween);
        put("/routines/" + halloween + "/routine-habits/" + halloweenSteps.get(0), halloweenOwner, scheduleBody("19:00", null, EVERY_DAY, "Pumpkin"));

        Long builtIn = jdbc.queryForList(
                "select id from routines where built_in = true and category = 'Halloween' and deleted = false order by id limit 1",
                Long.class).stream().findFirst().orElse(null);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerToken", owner.token());
        fixtures.put("routine", routine);
        fixtures.put("emptyToken", emptyOwner.token());
        fixtures.put("emptyRoutine", empty);
        fixtures.put("halloweenToken", halloweenOwner.token());
        fixtures.put("halloweenRoutine", halloween);
        fixtures.put("builtInRoutine", builtIn);
        fixtures.put("viewerToken", register().token());
        fixtures.put("today", LocalDate.now().getDayOfWeek().name().substring(0, 3));

        Path dir = Files.createTempDirectory("schedule-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/routine-schedule-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
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

    @Test
    void browserListsHabitsInTimeOrderWithTheirSchedule() {
        JsonNode timeline = page("timeline");

        assertEquals(List.of("Run", "Stretch", "Read"), texts(timeline.path("titles")), browser.toString());
        assertEquals(List.of("06:30 – 06:50", "21:00 – 21:20", "Any time"), texts(timeline.path("whens")));
        assertEquals(List.of("Mon – Fri", "Every day", ""), texts(timeline.path("days")));
        assertTrue(timeline.path("notes").toString().contains("Easy pace"), timeline.toString());
        assertTrue(timeline.path("tabsVisible").asBoolean());
        assertEquals("06:30 – 21:20", timeline.path("timeRange").asString());
    }

    @Test
    void browserShowsTheWeekAndToday() {
        JsonNode week = page("week");
        assertEquals(List.of("Run", "Stretch"), texts(week.path("days").path("MON")), browser.toString());
        assertEquals(List.of("Stretch"), texts(week.path("days").path("SAT")));
        assertTrue(week.path("anyDay").toString().contains("Read"), week.toString());

        JsonNode today = page("today");
        List<String> expected = List.of("SAT", "SUN").contains(browser.path("todayCode").asString())
                ? List.of("Stretch", "Read") : List.of("Run", "Stretch", "Read");
        assertEquals(expected, texts(today.path("titles")), today.toString());
    }

    @Test
    void browserSavesAScheduleAndKeepsItAfterAReload() {
        JsonNode saved = page("afterSchedule");
        assertEquals(List.of("Run", "Read", "Stretch"), texts(saved.path("titles")), browser.toString());
        assertEquals("07:00 – 07:20", saved.path("whens").get(1).asString());
        assertEquals("Mon, Tue", saved.path("days").get(1).asString());

        JsonNode reloaded = page("scheduleAfterReload");
        assertEquals(List.of("Run", "Read", "Stretch"), texts(reloaded.path("titles")));
        assertTrue(reloaded.path("notes").toString().contains("Chapter 3"), reloaded.toString());
    }

    @Test
    void browserExplainsAnOverlapWithoutChangingAnything() {
        JsonNode clash = page("overlap");

        assertTrue(clash.path("message").asString().startsWith("Overlaps with \"Run\""), browser.toString());
        assertTrue(clash.path("formOpen").asBoolean());
        assertEquals("07:00 – 07:20", page("overlapAfterReload").path("whens").get(1).asString());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    @Test
    void browserAddsANewActivityWithItsTime() {
        JsonNode added = page("afterNewActivity");

        assertEquals(List.of("Run", "Read", "Stretch", "Journal"), texts(added.path("titles")), browser.toString());
        assertEquals("22:00 – 22:20", added.path("whens").get(3).asString());
    }

    @Test
    void browserValidatesTheRoutineForms() {
        assertTrue(page("createInvalid").path("message").asString().contains("1440"), browser.toString());
        assertTrue(page("createBlank").path("message").asString().toLowerCase().contains("title"), browser.toString());
        assertTrue(page("editTooShort").path("message").asString().contains("shorter than its habits"), browser.toString());
    }

    @Test
    void browserShowsAnEmptyRoutine() {
        JsonNode empty = page("empty");

        assertTrue(empty.path("emptyText").asString().contains("doesn't have any habits"), empty.toString());
        assertFalse(empty.path("tabsVisible").asBoolean());
    }

    @Test
    void browserLeavesBuiltInHalloweenRoutinesAsTheyWere() {
        JsonNode builtIn = page("builtInHalloween");
        Assumptions.assumeTrue(builtIn.path("loaded").asBoolean(), "no built-in Halloween routine seeded");

        assertTrue(builtIn.path("halloweenTheme").asBoolean(), builtIn.toString());
        assertFalse(builtIn.path("tabsVisible").asBoolean());
        assertEquals(0, builtIn.path("whens").asInt());
        assertEquals("Flexible time", builtIn.path("timeRange").asString());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "halloweenPhone", "weekPhone", "editorPhone", "autumnDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private interface Attempt {
        int run(int index) throws Exception;
    }

    private List<Integer> together(int count, Attempt attempt) throws Exception {
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            attempts.add(() -> attempt.run(index));
        }
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Plan plan(int habits, int minutes) throws Exception {
        return plan(habits, minutes, false);
    }

    // Habits of one minute each; the routine leaves the rest of its minutes free.
    private Plan plan(int habits, int minutes, boolean isPublic) throws Exception {
        Account owner = register();
        long routine = routine(owner, minutes, isPublic, "Morning");
        for (int i = 1; i <= habits; i++) {
            Reply reply = post("/routines/" + routine + "/habits", owner,
                    Map.of("title", "Habit " + i, "description", "Habit " + i + " description", "durationMinutes", 1));
            assertEquals(200, reply.status(), reply.body());
        }
        List<Long> steps = jdbc.queryForList(
                "select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        List<Long> habitIds = jdbc.queryForList(
                "select habit_id from routine_habits where routine_id = ? order by position", Long.class, routine);
        return new Plan(owner, routine, steps, habitIds);
    }

    private Map<String, Object> scheduleBody(String startTime, Integer minutes, List<String> days, String notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("startTime", startTime);
        body.put("durationMinutes", minutes);
        body.put("repeatDays", days);
        body.put("notes", notes);
        return body;
    }

    private Reply schedule(Plan plan, int index, String startTime, Integer minutes, List<String> days, String notes) throws Exception {
        return put(stepPath(plan, index), plan.owner(), scheduleBody(startTime, minutes, days, notes));
    }

    private String stepPath(Plan plan, int index) {
        return "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(index);
    }

    private JsonNode steps(Plan plan) throws Exception {
        Reply reply = get("/routines/" + plan.routine() + "/routine-habits", plan.owner());
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private List<Long> stepIds(Account owner, long routine) throws Exception {
        return ids(json.readTree(get("/routines/" + routine + "/routine-habits", owner).body()));
    }

    private JsonNode step(JsonNode list, long routineHabitId) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == routineHabitId) {
                return item;
            }
        }
        throw new AssertionError("No routine habit " + routineHabitId + " in " + list);
    }

    private List<Long> ids(JsonNode list) {
        List<Long> values = new ArrayList<>();
        list.forEach(item -> values.add(item.path("id").asLong()));
        return values;
    }

    private long libraryHabit(Account owner, String title, int minutes) throws Exception {
        Reply reply = post("/habit-library", owner, Map.of("title", title, "description", title, "durationMinutes", minutes));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private int habitsOf(Account owner) {
        return jdbc.queryForObject("select count(*) from habits where user_id = ?", Integer.class, owner.id());
    }

    private int routinesOf(Account owner) {
        return jdbc.queryForObject("select count(*) from routines where user_id = ?", Integer.class, owner.id());
    }

    private JsonNode start(Plan plan) throws Exception {
        Reply reply = post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply act(Plan plan, int index, String action) throws Exception {
        return post("/routine-runs/" + plan.routine() + "/routine-habits/" + plan.steps().get(index) + "/" + action,
                plan.owner(), null);
    }

    private JsonNode finish(Plan plan, int index) throws Exception {
        Reply started = act(plan, index, "start");
        assertEquals(200, started.status(), started.body());
        jdbc.update("""
                update habit_completions set started_at = started_at - interval '2 hours'
                where routine_habit_id = ? and started_at is not null and completed_at is null
                """, plan.steps().get(index));
        Reply reply = act(plan, index, "complete");
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Schedule QA",
                "email", "plan_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private Map<String, String> routineFields(String title, String description, String category, String minutes, boolean isPublic) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("category", category);
        fields.put("durationMinutes", minutes);
        fields.put("isPublic", String.valueOf(isPublic));
        return fields;
    }

    private long routine(Account owner, int minutes, boolean isPublic, String category) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", owner, routineFields(category + " schedule", "Schedule check", category, String.valueOf(minutes), isPublic));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply saveRoutine(String method, String path, Account account, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + account.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account, null);
    }

    private Reply post(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("POST", path, account, payload);
    }

    private Reply put(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("PUT", path, account, payload);
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
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
