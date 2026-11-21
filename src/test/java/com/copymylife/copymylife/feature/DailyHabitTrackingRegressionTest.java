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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
class DailyHabitTrackingRegressionTest {

    private static final String BOUNDARY = "TrackingRegressionBoundary";
    // Always on different calendar days: 26 hours apart.
    private static final String EAST = "Pacific/Kiritimati";
    private static final String WEST = "Etc/GMT+12";

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

    private record Plan(Account owner, long routine, List<Long> steps) {
    }

    // ---- completed ----

    @Test
    void completingAHabitIsStoredAndCounted() throws Exception {
        Plan plan = plan(2);
        JsonNode started = start(plan);
        begin(plan, 0);
        ripen(plan, 0);

        Reply reply = act(plan, 0, "complete");

        assertEquals(200, reply.status(), reply.body());
        JsonNode run = json.readTree(reply.body());
        assertEquals("COMPLETED", stepStatus(run, plan.steps().get(0)));
        assertEquals("READY", stepStatus(run, plan.steps().get(1)));
        assertEquals("RUNNING", run.path("status").asString());
        assertEquals(1, run.path("completedCount").asInt());
        assertEquals(50, run.path("progressPercent").asInt());
        assertEquals("COMPLETED", storedStatus(plan, 0));
        assertNotNull(storedCompletedAt(plan, 0));
        assertEquals(1, completedHabits(plan.owner(), started.path("date").asString()));
    }

    @Test
    void completingBeforeTheTimerEndsIsRefused() throws Exception {
        Plan plan = plan(2);
        start(plan);
        begin(plan, 0);

        Reply early = act(plan, 0, "complete");

        assertEquals(400, early.status(), early.body());
        assertTrue(message(early).startsWith("You can complete this habit in"), early.body());
        assertEquals("IN_PROGRESS", storedStatus(plan, 0));
    }

    @Test
    void completingEveryHabitCompletesTheRoutine() throws Exception {
        Plan plan = plan(2);
        JsonNode started = start(plan);
        finish(plan, 0);

        JsonNode run = finish(plan, 1);

        assertEquals("COMPLETED", run.path("status").asString());
        assertEquals(100, run.path("progressPercent").asInt());
        assertTrue(json.readTree(get("/routines/" + plan.routine(), plan.owner()).body()).path("completed").asBoolean());
        assertEquals(1, completedRoutines(plan.owner(), started.path("date").asString()));
        assertEquals(204, get("/routine-runs/active", plan.owner()).status());
    }

    // ---- skipped ----

    @Test
    void skippingIsStoredAndNeverCountsAsCompleted() throws Exception {
        Plan plan = plan(2);
        JsonNode started = start(plan);

        Reply reply = act(plan, 0, "skip");

        assertEquals(200, reply.status(), reply.body());
        JsonNode run = json.readTree(reply.body());
        assertEquals("SKIPPED", stepStatus(run, plan.steps().get(0)));
        assertEquals("READY", stepStatus(run, plan.steps().get(1)));
        assertEquals(0, run.path("completedCount").asInt());
        assertEquals(1, run.path("skippedCount").asInt());
        assertEquals(0, run.path("progressPercent").asInt());
        assertEquals("SKIPPED", storedStatus(plan, 0));
        assertNull(storedCompletedAt(plan, 0));
        assertEquals(0, completedHabits(plan.owner(), started.path("date").asString()));
    }

    @Test
    void aStartedHabitCanBeSkipped() throws Exception {
        Plan plan = plan(2);
        start(plan);
        begin(plan, 0);

        JsonNode run = json.readTree(act(plan, 0, "skip").body());

        assertEquals("SKIPPED", stepStatus(run, plan.steps().get(0)));
        assertEquals("READY", stepStatus(run, plan.steps().get(1)));
        assertNull(storedCompletedAt(plan, 0));
    }

    @Test
    void aRunWithSkippedHabitsFinishesButIsNotACompletedRoutine() throws Exception {
        Plan plan = plan(2);
        JsonNode started = start(plan);
        act(plan, 0, "skip");

        JsonNode run = finish(plan, 1);

        assertEquals("FINISHED", run.path("status").asString(), run.toString());
        assertTrue(run.path("completedAt").isNull());
        assertFalse(run.path("endedAt").isNull());
        assertEquals(1, run.path("completedCount").asInt());
        assertEquals(1, run.path("skippedCount").asInt());
        assertFalse(json.readTree(get("/routines/" + plan.routine(), plan.owner()).body()).path("completed").asBoolean());
        assertEquals(0, completedRoutines(plan.owner(), started.path("date").asString()));
        assertEquals(1, completedHabits(plan.owner(), started.path("date").asString()));
        assertEquals(204, get("/routine-runs/active", plan.owner()).status());
    }

    // ---- postponed ----

    @Test
    void postponingKeepsTheHabitForLater() throws Exception {
        Plan plan = plan(3);
        start(plan);

        Reply reply = act(plan, 0, "postpone");

        assertEquals(200, reply.status(), reply.body());
        JsonNode run = json.readTree(reply.body());
        assertEquals("POSTPONED", stepStatus(run, plan.steps().get(0)));
        assertTrue(step(run, plan.steps().get(0)).path("canResume").asBoolean());
        assertEquals("READY", stepStatus(run, plan.steps().get(1)));
        assertEquals(1, run.path("postponedCount").asInt());
        assertEquals("POSTPONED", storedStatus(plan, 0));
        assertNull(storedCompletedAt(plan, 0));
    }

    @Test
    void aPostponedHabitCanBeResumedAndCompletedLater() throws Exception {
        Plan plan = plan(2);
        start(plan);
        act(plan, 0, "postpone");
        JsonNode afterSecond = finish(plan, 1);
        assertEquals("RUNNING", afterSecond.path("status").asString(), "a postponed habit keeps the run open");
        assertTrue(step(afterSecond, plan.steps().get(0)).path("canResume").asBoolean());

        Reply resumed = act(plan, 0, "start");

        assertEquals(200, resumed.status(), resumed.body());
        assertEquals("IN_PROGRESS", stepStatus(json.readTree(resumed.body()), plan.steps().get(0)));
        ripen(plan, 0);
        JsonNode done = json.readTree(act(plan, 0, "complete").body());
        assertEquals("COMPLETED", done.path("status").asString());
        assertEquals(2, done.path("completedCount").asInt());
        assertEquals(0, done.path("postponedCount").asInt());
        assertEquals(1, recordsFor(plan, 0), "resuming reuses the same record");
    }

    @Test
    void aStartedHabitCanBePostponedAndItsTimerRestartsOnResume() throws Exception {
        Plan plan = plan(2);
        start(plan);
        begin(plan, 0);
        ripen(plan, 0);

        JsonNode postponed = json.readTree(act(plan, 0, "postpone").body());
        assertEquals("POSTPONED", stepStatus(postponed, plan.steps().get(0)));
        assertEquals("READY", stepStatus(postponed, plan.steps().get(1)));

        act(plan, 0, "start");
        Reply early = act(plan, 0, "complete");
        assertEquals(400, early.status(), "the timer starts again when the habit is resumed");
    }

    @Test
    void aPostponedHabitCanStillBeSkipped() throws Exception {
        Plan plan = plan(2);
        start(plan);
        act(plan, 0, "postpone");

        JsonNode run = json.readTree(act(plan, 0, "skip").body());

        assertEquals("SKIPPED", stepStatus(run, plan.steps().get(0)));
        assertEquals(1, recordsFor(plan, 0));
    }

    @Test
    void onlyOneHabitRunsAtATime() throws Exception {
        Plan plan = plan(3);
        start(plan);
        act(plan, 0, "postpone");
        begin(plan, 1);

        Reply resume = act(plan, 0, "start");

        assertEquals(400, resume.status(), resume.body());
        assertEquals("Complete the current habit first", message(resume));
        assertEquals("POSTPONED", storedStatus(plan, 0));
    }

    @Test
    void habitsStillGoInOrder() throws Exception {
        Plan plan = plan(3);
        start(plan);

        for (String action : List.of("start", "skip", "postpone", "complete")) {
            Reply reply = act(plan, 2, action);
            assertEquals(400, reply.status(), action + " " + reply.body());
        }
        assertEquals(0, recordsFor(plan, 2));
    }

    @Test
    void finishedHabitsCannotChangeState() throws Exception {
        Plan plan = plan(3);
        start(plan);
        finish(plan, 0);
        act(plan, 1, "skip");

        assertEquals(400, act(plan, 0, "skip").status());
        assertEquals(400, act(plan, 0, "postpone").status());
        assertEquals(400, act(plan, 0, "start").status());
        assertEquals(400, act(plan, 1, "postpone").status());
        assertEquals(400, act(plan, 1, "complete").status());
        assertEquals(400, act(plan, 1, "start").status());
        assertEquals("COMPLETED", storedStatus(plan, 0));
        assertEquals("SKIPPED", storedStatus(plan, 1));
    }

    // ---- stop routine ----

    @Test
    void stoppingEndsTheSessionAndKeepsWhatWasDone() throws Exception {
        Plan plan = plan(3);
        JsonNode started = start(plan);
        finish(plan, 0);
        act(plan, 1, "postpone");
        begin(plan, 2);
        String routineBefore = routineSnapshot(plan.routine());

        Reply reply = stop(plan);

        assertEquals(200, reply.status(), reply.body());
        JsonNode run = json.readTree(reply.body());
        assertEquals("STOPPED", run.path("status").asString());
        assertFalse(run.path("endedAt").isNull());
        assertTrue(run.path("completedAt").isNull());
        assertEquals("COMPLETED", stepStatus(run, plan.steps().get(0)));
        assertEquals("POSTPONED", stepStatus(run, plan.steps().get(1)));
        assertEquals("NOT_FINISHED", stepStatus(run, plan.steps().get(2)));
        for (JsonNode habit : run.path("habits")) {
            for (String flag : List.of("canStart", "canResume", "canComplete", "canSkip", "canPostpone")) {
                assertFalse(habit.path(flag).asBoolean(), flag);
            }
        }
        assertEquals("COMPLETED", storedStatus(plan, 0));
        assertEquals("POSTPONED", storedStatus(plan, 1));
        assertEquals(routineBefore, routineSnapshot(plan.routine()), "stop must not change or delete the routine");
        assertEquals(200, get("/routines/" + plan.routine(), plan.owner()).status());
        assertEquals(204, get("/routine-runs/active", plan.owner()).status());
        assertEquals(1, completedHabits(plan.owner(), started.path("date").asString()));
        assertEquals(0, completedRoutines(plan.owner(), started.path("date").asString()));
    }

    @Test
    void aStoppedSessionAcceptsNoMoreChanges() throws Exception {
        Plan plan = plan(2);
        start(plan);
        stop(plan);

        for (String action : List.of("start", "skip", "postpone", "complete")) {
            Reply reply = act(plan, 0, action);
            assertEquals(400, reply.status(), action);
            assertEquals("Start the routine first", message(reply));
        }
        assertEquals(0, recordsFor(plan, 0));
    }

    @Test
    void stoppingTwiceIsHarmless() throws Exception {
        Plan plan = plan(2);
        start(plan);
        stop(plan);
        Object endedAt = jdbc.queryForObject(
                "select ended_at from routine_completions where routine_id = ?", Object.class, plan.routine());

        Reply again = stop(plan);

        assertEquals(200, again.status(), again.body());
        assertEquals("STOPPED", json.readTree(again.body()).path("status").asString());
        assertEquals(endedAt, jdbc.queryForObject(
                "select ended_at from routine_completions where routine_id = ?", Object.class, plan.routine()));
    }

    @Test
    void stoppingWithoutARunIsRefused() throws Exception {
        Plan plan = plan(2);

        Reply reply = stop(plan);

        assertEquals(400, reply.status(), reply.body());
        assertEquals("Start the routine first", message(reply));
    }

    @Test
    void afterStoppingTheRoutineCanBeEditedAndStartedAgain() throws Exception {
        Plan plan = plan(2);
        JsonNode first = start(plan);
        finish(plan, 0);
        assertEquals(409, post("/routines/" + plan.routine() + "/habits", plan.owner(),
                Map.of("title", "Blocked", "durationMinutes", 1)).status());
        stop(plan);

        JsonNode second = start(plan);

        assertNotEquals(first.path("runId").asLong(), second.path("runId").asLong());
        assertEquals("READY", stepStatus(second, plan.steps().get(0)));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from habit_completions
                where routine_completion_id = ? and status = 'COMPLETED'
                """, Integer.class, first.path("runId").asLong()), "the first session keeps its history");
        stop(plan);
        Reply edit = post("/routines/" + plan.routine() + "/habits", plan.owner(), Map.of("title", "Allowed", "durationMinutes", 1));
        assertEquals(400, edit.status(), "no longer 409 running; only the full duration refuses it: " + edit.body());
    }

    @Test
    void stoppingFreesTheUserToStartAnotherRoutine() throws Exception {
        Plan plan = plan(1);
        long other = routine(plan.owner(), 1);
        habit(plan.owner(), other, "Other", 1);
        start(plan);
        assertEquals(400, post("/routine-runs/" + other + "/start", plan.owner(), null).status());

        stop(plan);

        assertEquals(200, post("/routine-runs/" + other + "/start", plan.owner(), null).status());
    }

    // ---- refresh ----

    @Test
    void everyStatusSurvivesARefresh() throws Exception {
        Plan plan = plan(4);
        start(plan);
        finish(plan, 0);
        act(plan, 1, "skip");
        act(plan, 2, "postpone");
        begin(plan, 3);

        JsonNode reloaded = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals("COMPLETED", stepStatus(reloaded, plan.steps().get(0)));
        assertEquals("SKIPPED", stepStatus(reloaded, plan.steps().get(1)));
        assertEquals("POSTPONED", stepStatus(reloaded, plan.steps().get(2)));
        assertEquals("IN_PROGRESS", stepStatus(reloaded, plan.steps().get(3)));
        assertEquals(1, reloaded.path("completedCount").asInt());
        assertEquals(1, reloaded.path("skippedCount").asInt());
        assertEquals(1, reloaded.path("postponedCount").asInt());
        assertEquals(25, reloaded.path("progressPercent").asInt());
        assertEquals(plan.routine(), json.readTree(get("/routine-runs/active", plan.owner()).body()).path("routineId").asLong());
    }

    // ---- days and time zones ----

    @Test
    void theDayComesFromTheUsersTimeZoneNotTheServerClock() throws Exception {
        for (String zone : List.of(EAST, WEST)) {
            Plan plan = plan(1);
            Set<LocalDate> expected = todayIn(zone);

            JsonNode run = json.readTree(startIn(plan, zone).body());

            LocalDate day = LocalDate.parse(run.path("date").asString());
            assertTrue(expected.contains(day), zone + " gave " + day);
            Instant expiresAt = Instant.parse(run.path("expiresAt").asString());
            assertEquals(day.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant(), expiresAt, zone);
            assertEquals(day, jdbc.queryForObject(
                    "select completed_date from routine_completions where routine_id = ?", LocalDate.class, plan.routine()));
        }
    }

    @Test
    void anUnknownTimeZoneFallsBackToTheConfiguredZone() throws Exception {
        Plan plan = plan(1);
        Set<LocalDate> expected = todayIn("Asia/Almaty");

        Reply reply = startIn(plan, "Mars/Olympus_Mons");

        assertEquals(200, reply.status(), reply.body());
        assertTrue(expected.contains(LocalDate.parse(json.readTree(reply.body()).path("date").asString())));
    }

    @Test
    void eachDayKeepsItsOwnResults() throws Exception {
        Plan plan = plan(2);
        JsonNode east = json.readTree(startIn(plan, EAST).body());
        finish(plan, 0);
        stop(plan);
        String eastHistory = runSnapshot(east.path("runId").asLong());

        JsonNode west = json.readTree(startIn(plan, WEST).body());
        act(plan, 0, "skip");
        finish(plan, 1);

        String eastDay = east.path("date").asString();
        String westDay = west.path("date").asString();
        assertNotEquals(eastDay, westDay);
        assertEquals(eastHistory, runSnapshot(east.path("runId").asLong()), "the other day was not touched");
        assertEquals(1, completedHabits(plan.owner(), eastDay));
        assertEquals(1, completedHabits(plan.owner(), westDay));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from habit_completions
                where routine_completion_id = ? and completed_date = ?::date and status = 'SKIPPED'
                """, Integer.class, west.path("runId").asLong(), westDay));
    }

    @Test
    void aRunFromAnEarlierDayCannotBeChangedToday() throws Exception {
        Plan plan = plan(2);
        JsonNode yesterday = start(plan);
        finish(plan, 0);
        begin(plan, 1);
        jdbc.update("update routine_completions set expires_at = now() - interval '1 minute' where id = ?",
                yesterday.path("runId").asLong());
        String history = runSnapshot(yesterday.path("runId").asLong());

        JsonNode shown = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());
        assertEquals("EXPIRED", shown.path("status").asString());
        assertEquals(204, get("/routine-runs/active", plan.owner()).status());
        for (String action : List.of("start", "skip", "postpone", "complete")) {
            Reply reply = act(plan, 1, action);
            assertEquals(400, reply.status(), action);
        }
        assertEquals(history, runSnapshot(yesterday.path("runId").asLong()));

        JsonNode today = start(plan);

        assertNotEquals(yesterday.path("runId").asLong(), today.path("runId").asLong());
        assertEquals("RUNNING", today.path("status").asString());
        assertEquals("READY", stepStatus(today, plan.steps().get(0)));
        assertEquals(history, runSnapshot(yesterday.path("runId").asLong()));
    }

    @Test
    void theProgressDefaultDayFollowsTheTimeZoneHeader() throws Exception {
        Account user = register();
        for (String zone : List.of(EAST, WEST)) {
            Set<LocalDate> expected = todayIn(zone);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/progress?period=day"))
                    .header("Authorization", "Bearer " + user.token())
                    .header("X-Time-Zone", zone)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(expected.contains(LocalDate.parse(json.readTree(response.body()).path("from").asString())), zone);
        }
    }

    // ---- who may track ----

    @Test
    void everyTrackingCallNeedsAToken() throws Exception {
        Plan plan = plan(2);
        start(plan);
        long step = plan.steps().get(0);
        String base = "/routine-runs/" + plan.routine();

        for (String path : List.of(base + "/start", base + "/stop",
                base + "/routine-habits/" + step + "/start", base + "/routine-habits/" + step + "/complete",
                base + "/routine-habits/" + step + "/skip", base + "/routine-habits/" + step + "/postpone")) {
            assertEquals(403, post(path, null, null).status(), path);
            assertEquals(403, http.send(HttpRequest.newBuilder(uri(path))
                    .header("Authorization", "Bearer not.a.token")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), path);
        }
        assertEquals(403, get(base, null).status());
        assertEquals(0, recordsFor(plan, 0));
        assertEquals("RUNNING", json.readTree(get(base, plan.owner()).body()).path("status").asString());
    }

    @Test
    void nobodyCanTrackSomeoneElsesRoutine() throws Exception {
        for (boolean isPublic : List.of(true, false)) {
            Plan plan = plan(2, isPublic);
            Account stranger = register();
            start(plan);
            begin(plan, 0);
            String before = runSnapshot(runIdOf(plan));
            String base = "/routine-runs/" + plan.routine();

            assertEquals(400, post(base + "/start", stranger, null).status());
            assertEquals(400, post(base + "/stop", stranger, null).status());
            assertEquals(400, get(base, stranger).status());
            for (String action : List.of("start", "skip", "postpone", "complete")) {
                Reply reply = post(base + "/routine-habits/" + plan.steps().get(0) + "/" + action, stranger, null);
                assertEquals(400, reply.status(), action);
                assertEquals("Routine not found", message(reply));
            }
            assertEquals(before, runSnapshot(runIdOf(plan)));
            assertEquals(0, jdbc.queryForObject(
                    "select count(*) from routine_completions where user_id = ?", Integer.class, stranger.id()));
        }
    }

    @Test
    void aHabitFromAnotherRoutineCannotBeTrackedThroughYourOwn() throws Exception {
        Plan victim = plan(2);
        Plan attacker = plan(1);
        start(victim);
        start(attacker);

        for (String action : List.of("start", "skip", "postpone", "complete")) {
            Reply reply = post("/routine-runs/" + attacker.routine() + "/routine-habits/"
                    + victim.steps().get(0) + "/" + action, attacker.owner(), null);
            assertEquals(400, reply.status(), action);
            assertEquals("Routine habit not found", message(reply));
        }
        assertEquals(0, recordsFor(victim, 0));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from habit_completions where routine_habit_id = ?", Integer.class, victim.steps().get(0)));
    }

    // ---- duplicates ----

    @Test
    void pressingAButtonAgainCreatesNoDuplicates() throws Exception {
        Plan plan = plan(3);
        JsonNode first = start(plan);

        assertEquals(first.path("runId").asLong(), start(plan).path("runId").asLong());
        assertEquals(200, act(plan, 0, "postpone").status());
        assertEquals(200, act(plan, 0, "postpone").status());
        assertEquals(200, act(plan, 0, "skip").status());
        assertEquals(200, act(plan, 0, "skip").status());
        assertEquals(200, act(plan, 1, "start").status());
        assertEquals(200, act(plan, 1, "start").status());
        ripen(plan, 1);
        assertEquals(200, act(plan, 1, "complete").status());
        assertEquals(200, act(plan, 1, "complete").status());

        assertEquals(1, runsOf(plan));
        assertEquals(1, recordsFor(plan, 0));
        assertEquals(1, recordsFor(plan, 1));
        assertEquals("SKIPPED", storedStatus(plan, 0));
        assertEquals("COMPLETED", storedStatus(plan, 1));
    }

    @Test
    void simultaneousPressesCreateOneRecord() throws Exception {
        Plan plan = plan(3);
        List<Integer> starts = together(12, () -> post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null).status());
        assertTrue(starts.stream().allMatch(status -> status == 200), starts.toString());
        assertEquals(1, runsOf(plan));

        for (int index = 0; index < 3; index++) {
            String action = List.of("postpone", "skip", "start").get(index);
            int target = index;
            List<Integer> statuses = together(12, () -> act(plan, target, action).status());
            assertTrue(statuses.stream().allMatch(status -> status == 200), action + " " + statuses);
            assertEquals(1, recordsFor(plan, index), action);
        }
    }

    @Test
    void theDatabaseRejectsDuplicateAndUnknownStatuses() throws Exception {
        Plan plan = plan(2);
        long runId = start(plan).path("runId").asLong();
        act(plan, 0, "skip");
        String insert = """
                insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, status)
                select user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, ?
                from habit_completions where routine_habit_id = ?
                """;

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(insert, "COMPLETED", plan.steps().get(0)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update habit_completions set status = 'BOGUS' where routine_completion_id = ?", runId));
        assertEquals(1, recordsFor(plan, 0));
    }

    @Test
    void schemaHasTheTrackingColumnsAndConstraints() {
        List<String> runColumns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'routine_completions'", String.class);
        assertTrue(runColumns.containsAll(List.of("ended_at", "expires_at")), runColumns.toString());
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'habit_completions' and column_name = 'status'
                """, String.class), "older rows keep a null status");

        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'habit_completions'::regclass", String.class);
        assertTrue(constraints.contains("uk_habit_completions_run_step"), constraints.toString());
    }

    // ---- transactions ----

    @Test
    void aFailureWhileFinishingTheRunRollsEverythingBack() throws Exception {
        Plan plan = plan(1);
        long runId = start(plan).path("runId").asLong();
        begin(plan, 0);
        ripen(plan, 0);
        String before = runSnapshot(runId);
        jdbc.execute("""
                create or replace function tracking_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'tracking test failure'; end $$
                """);
        jdbc.execute("create trigger tracking_test_fail before update on routine_completions for each row when (new.id = "
                + runId + ") execute function tracking_test_fail()");
        try {
            Reply reply = act(plan, 0, "complete");
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
            assertEquals(before, runSnapshot(runId), "the habit row must roll back with the run row");
            assertEquals("IN_PROGRESS", storedStatus(plan, 0));

            assertTrue(stop(plan).status() >= 500);
            assertEquals(before, runSnapshot(runId));
        } finally {
            jdbc.execute("drop trigger if exists tracking_test_fail on routine_completions");
            jdbc.execute("drop function if exists tracking_test_fail()");
        }

        JsonNode run = json.readTree(act(plan, 0, "complete").body());
        assertEquals("COMPLETED", run.path("status").asString());
    }

    // ---- earlier history ----

    @Test
    void historyWrittenBeforeStatusesExistedStillCounts() throws Exception {
        Plan plan = plan(2);
        LocalDate day = LocalDate.of(2026, 1, 15);
        long runId = jdbc.queryForObject("""
                insert into routine_completions (user_id, routine_id, completed_date, started_at, completed_at)
                values (?, ?, ?, now() - interval '2 hours', now() - interval '1 hour') returning id
                """, Long.class, plan.owner().id(), plan.routine(), day);
        for (long step : plan.steps()) {
            jdbc.update("""
                    insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, started_at, completed_at)
                    select ?, habit_id, id, ?, ?, now() - interval '2 hours', now() - interval '1 hour' from routine_habits where id = ?
                    """, plan.owner().id(), runId, day, step);
        }

        JsonNode run = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals("COMPLETED", run.path("status").asString(), run.toString());
        assertEquals(2, run.path("completedCount").asInt());
        assertEquals("COMPLETED", stepStatus(run, plan.steps().get(0)));
        assertEquals(2, completedHabits(plan.owner(), day.toString()));
        assertEquals(1, completedRoutines(plan.owner(), day.toString()));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from habit_completions where routine_completion_id = ? and status is null", Integer.class, runId));
    }

    @Test
    void aRunStartedBeforeTheUpgradeCanBeFinishedOrStopped() throws Exception {
        Plan plan = plan(2);
        long runId = jdbc.queryForObject("""
                insert into routine_completions (user_id, routine_id, completed_date, started_at)
                values (?, ?, current_date, now() - interval '3 hours') returning id
                """, Long.class, plan.owner().id(), plan.routine());
        jdbc.update("""
                insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, started_at, completed_at)
                select ?, habit_id, id, ?, current_date, now() - interval '3 hours', now() - interval '2 hours' from routine_habits where id = ?
                """, plan.owner().id(), runId, plan.steps().get(0));
        jdbc.update("""
                insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, started_at)
                select ?, habit_id, id, ?, current_date, now() - interval '2 hours' from routine_habits where id = ?
                """, plan.owner().id(), runId, plan.steps().get(1));

        JsonNode legacy = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());
        assertEquals("RUNNING", legacy.path("status").asString(), legacy.toString());
        assertEquals("COMPLETED", stepStatus(legacy, plan.steps().get(0)));
        assertEquals("IN_PROGRESS", stepStatus(legacy, plan.steps().get(1)));
        assertEquals(plan.routine(), json.readTree(get("/routine-runs/active", plan.owner()).body()).path("routineId").asLong());

        JsonNode done = json.readTree(act(plan, 1, "complete").body());

        assertEquals("COMPLETED", done.path("status").asString(), done.toString());
        assertEquals(2, done.path("completedCount").asInt());
        assertEquals(1, runsOf(plan));
    }

    // ---- real browser ----

    @BeforeAll
    void trackARoutineInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Plan main = plan(4);
        start(main);
        begin(main, 0);
        ripen(main, 0);

        Plan failing = plan(2);
        start(failing);

        Plan finished = plan(1);
        start(finished);
        finish(finished, 0);

        Plan halloween = plan(3, true, "Halloween");
        start(halloween);
        finish(halloween, 0);
        act(halloween, 1, "postpone");

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("mainToken", main.owner().token());
        fixtures.put("mainRoutine", main.routine());
        fixtures.put("mainSteps", main.steps());
        fixtures.put("failingToken", failing.owner().token());
        fixtures.put("failingRoutine", failing.routine());
        fixtures.put("finishedToken", finished.owner().token());
        fixtures.put("finishedRoutine", finished.routine());
        fixtures.put("halloweenToken", halloween.owner().token());
        fixtures.put("halloweenRoutine", halloween.routine());

        Path dir = Files.createTempDirectory("tracking-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/habit-tracking-check.mjs", chrome,
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
    void browserShowsTheRightButtonsForEachHabit() {
        JsonNode start = page("initial");

        assertEquals("Routine in progress", start.path("title").asString(), browser.toString());
        assertTrue(start.path("stopVisible").asBoolean());
        assertEquals(List.of("Complete", "Postpone", "Skip"), texts(start.path("rows").get(0).path("buttons")));
        assertEquals(List.of("Start"), texts(start.path("rows").get(1).path("buttons")));
        assertFalse(start.path("rows").get(1).path("enabled").asBoolean());
    }

    @Test
    void browserCompleteSkipAndPostponeUpdateStatusAndProgress() {
        JsonNode completed = page("afterComplete");
        assertEquals("Completed", completed.path("rows").get(0).path("badge").asString(), browser.toString());
        assertEquals("1 / 4 completed", completed.path("count").asString());
        assertEquals("25%", completed.path("bar").asString());
        assertEquals(List.of("Start", "Postpone", "Skip"), texts(completed.path("rows").get(1).path("buttons")));

        JsonNode postponed = page("afterPostpone");
        assertEquals("Postponed", postponed.path("rows").get(1).path("badge").asString());
        assertEquals(List.of("Resume", "Skip"), texts(postponed.path("rows").get(1).path("buttons")));

        JsonNode skipped = page("afterSkip");
        assertEquals("Skipped", skipped.path("rows").get(2).path("badge").asString());
        assertEquals("1 / 4 completed", skipped.path("count").asString());
        assertTrue(skipped.path("summary").asString().contains("1 skipped"), skipped.toString());
        assertTrue(skipped.path("summary").asString().contains("1 postponed"), skipped.toString());
    }

    @Test
    void browserStatusesSurviveAReload() {
        JsonNode before = page("afterSkip");
        JsonNode reloaded = page("afterReload");

        assertEquals(before.path("count").asString(), reloaded.path("count").asString(), browser.toString());
        for (int i = 0; i < 4; i++) {
            assertEquals(before.path("rows").get(i).path("badge").asString(), reloaded.path("rows").get(i).path("badge").asString());
        }
    }

    @Test
    void browserResumesAPostponedHabit() {
        JsonNode resumed = page("afterResume");

        assertEquals("In progress", resumed.path("rows").get(1).path("badge").asString(), browser.toString());
        assertTrue(texts(resumed.path("rows").get(1).path("buttons")).get(0).startsWith("Complete in"));
    }

    @Test
    void browserStopAsksFirstThenEndsTheSession() {
        JsonNode asking = page("stopConfirm");
        assertTrue(asking.path("confirmVisible").asBoolean(), browser.toString());
        assertEquals("Routine in progress", asking.path("title").asString());

        JsonNode kept = page("stopCancelled");
        assertFalse(kept.path("confirmVisible").asBoolean());
        assertEquals("Routine in progress", kept.path("title").asString());

        JsonNode stopped = page("afterStop");
        assertEquals("Routine stopped", stopped.path("title").asString());
        assertFalse(stopped.path("stopVisible").asBoolean());
        assertEquals("Completed", stopped.path("rows").get(0).path("badge").asString());
        assertEquals(0, stopped.path("enabledButtons").asInt());

        JsonNode reloaded = page("stoppedReload");
        assertEquals("Routine stopped", reloaded.path("title").asString());
        assertEquals("Completed", reloaded.path("rows").get(0).path("badge").asString());
    }

    @Test
    void browserExplainsFailuresAndKeepsTheState() {
        JsonNode failed = page("failedAction");

        assertTrue(failed.path("message").asString().contains("couldn't"), failed.toString());
        assertEquals("READY", failed.path("rowStatus").asString());
        assertTrue(failed.path("rows").get(0).path("enabled").asBoolean());

        JsonNode refused = page("refusedAction");
        assertEquals("Start the routine first", refused.path("message").asString(), refused.toString());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    @Test
    void browserShowsAFinishedRoutine() {
        JsonNode done = page("finishedRoutine");

        assertEquals("Routine completed", done.path("title").asString(), browser.toString());
        assertFalse(done.path("stopVisible").asBoolean());
        assertEquals("1 / 1 completed", done.path("count").asString());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "halloweenPhone", "autumnDesktop", "halloweenDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            // Negative on desktop: innerWidth also counts the vertical scrollbar.
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmostButton").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private Plan plan(int habits) throws Exception {
        return plan(habits, false);
    }

    private Plan plan(int habits, boolean isPublic) throws Exception {
        return plan(habits, isPublic, "Morning");
    }

    private Plan plan(int habits, boolean isPublic, String category) throws Exception {
        Account owner = register();
        long routine = routine(owner, habits, isPublic, category);
        for (int i = 1; i <= habits; i++) {
            habit(owner, routine, "Habit " + i, 1);
        }
        List<Long> steps = jdbc.queryForList(
                "select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        return new Plan(owner, routine, steps);
    }

    private JsonNode start(Plan plan) throws Exception {
        Reply reply = post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply startIn(Plan plan, String zone) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routine-runs/" + plan.routine() + "/start"))
                .header("Authorization", "Bearer " + plan.owner().token())
                .header("X-Time-Zone", zone)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply stop(Plan plan) throws Exception {
        return post("/routine-runs/" + plan.routine() + "/stop", plan.owner(), null);
    }

    private Reply act(Plan plan, int index, String action) throws Exception {
        return post("/routine-runs/" + plan.routine() + "/routine-habits/" + plan.steps().get(index) + "/" + action,
                plan.owner(), null);
    }

    private void begin(Plan plan, int index) throws Exception {
        Reply reply = act(plan, index, "start");
        assertEquals(200, reply.status(), reply.body());
    }

    // Moves the habit's start back so its timer has already run out.
    private void ripen(Plan plan, int index) {
        jdbc.update("""
                update habit_completions set started_at = started_at - interval '2 hours'
                where routine_habit_id = ? and started_at is not null and completed_at is null
                """, plan.steps().get(index));
    }

    private JsonNode finish(Plan plan, int index) throws Exception {
        begin(plan, index);
        ripen(plan, index);
        Reply reply = act(plan, index, "complete");
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode step(JsonNode run, long routineHabitId) {
        for (JsonNode habit : run.path("habits")) {
            if (habit.path("routineHabitId").asLong() == routineHabitId) {
                return habit;
            }
        }
        throw new AssertionError("No habit " + routineHabitId + " in " + run);
    }

    private String stepStatus(JsonNode run, long routineHabitId) {
        return step(run, routineHabitId).path("status").asString();
    }

    private String storedStatus(Plan plan, int index) {
        return jdbc.queryForObject("""
                select status from habit_completions hc join routine_completions rc on rc.id = hc.routine_completion_id
                where hc.routine_habit_id = ? order by rc.id desc limit 1
                """, String.class, plan.steps().get(index));
    }

    private Object storedCompletedAt(Plan plan, int index) {
        return jdbc.queryForObject("""
                select hc.completed_at from habit_completions hc join routine_completions rc on rc.id = hc.routine_completion_id
                where hc.routine_habit_id = ? order by rc.id desc limit 1
                """, Object.class, plan.steps().get(index));
    }

    private int recordsFor(Plan plan, int index) {
        return jdbc.queryForObject("""
                select count(*) from habit_completions
                where routine_habit_id = ? and routine_completion_id = (select max(id) from routine_completions where routine_id = ?)
                """, Integer.class, plan.steps().get(index), plan.routine());
    }

    private int runsOf(Plan plan) {
        return jdbc.queryForObject("select count(*) from routine_completions where routine_id = ?", Integer.class, plan.routine());
    }

    private long runIdOf(Plan plan) {
        return jdbc.queryForObject("select max(id) from routine_completions where routine_id = ?", Long.class, plan.routine());
    }

    private String runSnapshot(long runId) {
        return jdbc.queryForList("""
                select id, completed_date, started_at, completed_at, ended_at, expires_at from routine_completions where id = ?
                """, runId) + "|" + jdbc.queryForList("""
                select id, routine_habit_id, status, completed_date, started_at, completed_at
                from habit_completions where routine_completion_id = ? order by id
                """, runId);
    }

    private String routineSnapshot(long routine) {
        return jdbc.queryForList("select title, description, duration_minutes, deleted from routines where id = ?", routine)
                + "|" + jdbc.queryForList("""
                select rh.id, rh.position, h.id, h.title, h.duration_minutes
                from routine_habits rh join habits h on h.id = rh.habit_id where rh.routine_id = ? order by rh.position
                """, routine);
    }

    private int completedHabits(Account account, String day) throws Exception {
        return progressItem(account, day, "habits", "Completed habits");
    }

    private int completedRoutines(Account account, String day) throws Exception {
        return progressItem(account, day, "routines", "Completed routines");
    }

    private int progressItem(Account account, String day, String group, String label) throws Exception {
        Reply reply = get("/progress?period=day&date=" + day, account);
        assertEquals(200, reply.status(), reply.body());
        for (JsonNode item : json.readTree(reply.body()).path(group)) {
            if (label.equals(item.path("label").asString())) {
                return item.path("completed").asInt();
            }
        }
        throw new AssertionError(label + " missing in " + reply.body());
    }

    // The calendar day can turn over between reading the clock and the request.
    private Set<LocalDate> todayIn(String zone) {
        LocalDate now = LocalDate.now(ZoneId.of(zone));
        return Set.of(now, now.plusDays(1));
    }

    private List<Integer> together(int count, Callable<Integer> attempt) throws Exception {
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attempts.add(attempt);
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

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Tracking QA",
                "email", "track_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, int minutes) throws Exception {
        return routine(owner, minutes, false, "Morning");
    }

    private long routine(Account owner, int minutes, boolean isPublic, String category) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", category + " tracking");
        fields.put("description", "Daily tracking check");
        fields.put("category", category);
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));

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

    private void habit(Account owner, long routine, String title, int minutes) throws Exception {
        Reply reply = post("/routines/" + routine + "/habits", owner,
                Map.of("title", title, "description", title + " description", "durationMinutes", minutes));
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account, null);
    }

    private Reply post(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("POST", path, account, payload);
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
