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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class HabitManagementRegressionTest {

    private static final String BOUNDARY = "HabitManagementBoundary";
    private static final String PASSWORD = "Audit#12345";
    private static final long MISSING = 987_654_321L;

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

    private record Account(long id, String token, String email) {
    }

    private record Plan(Account owner, long routine, List<Long> steps, List<Long> habits) {
    }

    // ---- create ----

    @Test
    void habitsCanBeCreatedInTheLibraryAndInARoutine() throws Exception {
        Account owner = register();
        long routine = routine(owner, 10, false);

        Reply library = post("/habit-library", owner, habitBody("Read", "Ten pages", 5));
        assertEquals(200, library.status(), library.body());
        long libraryHabit = json.readTree(library.body()).path("id").asLong();
        Reply added = post("/routines/" + routine + "/routine-habits/" + libraryHabit, owner, null);
        assertEquals(200, added.status(), added.body());

        Reply direct = post("/routines/" + routine + "/habits", owner, habitBody("Stretch", "Neck", 5));
        assertEquals(200, direct.status(), direct.body());

        JsonNode list = steps(owner, routine);
        assertEquals(2, list.size());
        assertEquals("Read", list.get(0).path("title").asString());
        assertEquals("Stretch", list.get(1).path("title").asString());
        assertEquals(0, list.get(0).path("position").asInt());
        assertEquals(1, list.get(1).path("position").asInt());
        assertFalse(list.get(0).path("paused").asBoolean());
    }

    @Test
    void invalidHabitsAreRefusedWithAReason() throws Exception {
        Account owner = register();
        long routine = routine(owner, 1440, false);
        int before = habitsOf(owner);

        List<Map<String, Object>> bad = List.of(
                habitBody("   ", "d", 5),
                habitBody("Zero", "d", 0),
                habitBody("Negative", "d", -3),
                habitBody("Too long", "d", 1441),
                habitBody("x".repeat(256), "d", 5),
                habitBody("Wordy", "y".repeat(501), 5)
        );
        for (Map<String, Object> body : bad) {
            Reply library = post("/habit-library", owner, body);
            Reply inRoutine = post("/routines/" + routine + "/habits", owner, body);
            assertEquals(400, library.status(), body + " " + library.body());
            assertEquals(400, inRoutine.status(), body + " " + inRoutine.body());
        }
        Reply reason = post("/habit-library", owner, habitBody("Wordy", "y".repeat(501), 5));
        assertFalse(message(reason).isBlank(), reason.body());
        assertEquals(before, habitsOf(owner));
    }

    @Test
    void aHabitMustFitTheRoutineDuration() throws Exception {
        Plan plan = plan(2);
        long extra = libraryHabit(plan.owner(), "Extra", 1);

        Reply reply = post("/routines/" + plan.routine() + "/routine-habits/" + extra, plan.owner(), null);

        assertEquals(400, reply.status(), reply.body());
        assertEquals("Only 0 minutes remaining in this routine", message(reply));
        assertEquals(2, steps(plan.owner(), plan.routine()).size());
    }

    // ---- edit ----

    @Test
    void editingAHabitIsSaved() throws Exception {
        Account owner = register();
        long routine = routine(owner, 10, false);
        post("/routines/" + routine + "/habits", owner, habitBody("Old", "old", 4));
        long habit = jdbc.queryForObject("select habit_id from routine_habits where routine_id = ?", Long.class, routine);

        Reply reply = send("PUT", "/routines/" + routine + "/habits/" + habit, owner, habitBody("New title", "new text", 6));

        assertEquals(200, reply.status(), reply.body());
        JsonNode step = steps(owner, routine).get(0);
        assertEquals("New title", step.path("title").asString());
        assertEquals("new text", step.path("description").asString());
        assertEquals(6, step.path("durationMinutes").asInt());

        long library = libraryHabit(owner, "Lib", 3);
        assertEquals(200, send("PUT", "/habit-library/" + library, owner, habitBody("Lib 2", "x", 7)).status());
        assertEquals("Lib 2", jdbc.queryForObject("select title from habits where id = ?", String.class, library));
    }

    @Test
    void editingKeepsTheRoutineDurationRule() throws Exception {
        Plan plan = plan(2);

        Reply tooLong = send("PUT", "/routines/" + plan.routine() + "/habits/" + plan.habits().get(0), plan.owner(),
                habitBody("Habit 1", "d", 5));
        Reply invalid = send("PUT", "/routines/" + plan.routine() + "/habits/" + plan.habits().get(0), plan.owner(),
                habitBody("Habit 1", "z".repeat(501), 1));

        assertEquals(400, tooLong.status(), tooLong.body());
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals(1, jdbc.queryForObject("select duration_minutes from habits where id = ?", Integer.class, plan.habits().get(0)));
    }

    // ---- delete ----

    @Test
    void removingAHabitClosesTheGapInTheOrder() throws Exception {
        Plan plan = plan(3);

        Reply reply = send("DELETE", "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(1), plan.owner(), null);

        assertEquals(200, reply.status(), reply.body());
        JsonNode list = steps(plan.owner(), plan.routine());
        assertEquals(2, list.size());
        assertEquals(List.of(plan.steps().get(0), plan.steps().get(2)), ids(list));
        assertEquals(List.of(0, 1), positions(plan.routine()));
    }

    @Test
    void aHabitWithHistoryIsDeletedAndItsHistoryKept() throws Exception {
        Plan plan = plan(2);
        start(plan);
        finish(plan, 0);
        finish(plan, 1);
        String history = completions(plan.owner());

        Reply reply = send("DELETE", "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(0), plan.owner(), null);

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(plan.steps().get(1)), ids(steps(plan.owner(), plan.routine())));
        assertEquals(history, completions(plan.owner()));
    }

    @Test
    void aLibraryHabitIsOnlyDeletedOnceItIsInNoRoutine() throws Exception {
        Plan plan = plan(1);
        long library = libraryHabit(plan.owner(), "Spare", 1);

        Reply linked = send("DELETE", "/habit-library/" + plan.habits().get(0), plan.owner(), null);
        Reply free = send("DELETE", "/habit-library/" + library, plan.owner(), null);

        assertEquals(409, linked.status(), linked.body());
        assertEquals("Remove habit from routines first", message(linked));
        assertEquals(204, free.status(), free.body());
        assertEquals(0, jdbc.queryForObject("select count(*) from habits where id = ?", Integer.class, library));
    }

    // ---- reorder ----

    @Test
    void reorderingIsSavedAndSurvivesARefresh() throws Exception {
        Plan plan = plan(3);
        List<Long> reversed = new ArrayList<>(plan.steps());
        Collections.reverse(reversed);

        Reply reply = reorder(plan, reversed);

        assertEquals(200, reply.status(), reply.body());
        assertEquals(reversed, ids(steps(plan.owner(), plan.routine())));
        assertEquals(List.of(0, 1, 2), positions(plan.routine()));
    }

    @Test
    void anInvalidOrderIsRefused() throws Exception {
        Plan plan = plan(3);
        Plan other = plan(1);
        List<Long> s = plan.steps();

        for (List<Long> order : List.of(
                List.of(s.get(0), s.get(1)),
                List.of(s.get(0), s.get(0), s.get(1)),
                List.of(s.get(0), s.get(1), other.steps().get(0))
        )) {
            assertEquals(400, reorder(plan, order).status(), order.toString());
        }
        assertEquals(s, ids(steps(plan.owner(), plan.routine())));
    }

    @Test
    void simultaneousReordersLeaveOneValidOrder() throws Exception {
        Plan plan = plan(4);
        List<List<Long>> orders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            List<Long> order = new ArrayList<>(plan.steps());
            Collections.rotate(order, i);
            orders.add(order);
        }

        List<Integer> statuses = together(orders.size(), index -> reorder(plan, orders.get(index)).status());

        assertTrue(statuses.stream().allMatch(status -> status == 200), statuses.toString());
        assertEquals(List.of(0, 1, 2, 3), positions(plan.routine()));
        assertTrue(orders.contains(ids(steps(plan.owner(), plan.routine()))));
    }

    // ---- pause and resume ----

    @Test
    void pausingIsStoredAndSurvivesARefreshAndANewLogin() throws Exception {
        Plan plan = plan(3);

        Reply reply = pause(plan, 1);

        assertEquals(200, reply.status(), reply.body());
        assertTrue(step(json.readTree(reply.body()), plan.steps().get(1)).path("paused").asBoolean());
        assertNotNull(pausedAt(plan.steps().get(1)));
        assertTrue(step(steps(plan.owner(), plan.routine()), plan.steps().get(1)).path("paused").asBoolean());

        Account again = login(plan.owner());
        JsonNode afterLogin = json.readTree(get("/routines/" + plan.routine() + "/routine-habits", again).body());
        assertTrue(step(afterLogin, plan.steps().get(1)).path("paused").asBoolean());
        assertFalse(step(afterLogin, plan.steps().get(0)).path("paused").asBoolean());
        assertEquals(3, afterLogin.size(), "a paused habit is not deleted");
    }

    @Test
    void resumingPutsTheHabitBackInItsPlace() throws Exception {
        Plan plan = plan(3);
        pause(plan, 1);

        Reply reply = resume(plan, 1);

        assertEquals(200, reply.status(), reply.body());
        JsonNode list = steps(plan.owner(), plan.routine());
        assertEquals(plan.steps(), ids(list));
        assertEquals(1, step(list, plan.steps().get(1)).path("position").asInt());
        assertFalse(step(list, plan.steps().get(1)).path("paused").asBoolean());
        assertNull(pausedAt(plan.steps().get(1)));
    }

    @Test
    void aPausedHabitKeepsItsPlaceWhenOthersAreReordered() throws Exception {
        Plan plan = plan(3);
        pause(plan, 0);
        List<Long> order = List.of(plan.steps().get(2), plan.steps().get(0), plan.steps().get(1));

        assertEquals(200, reorder(plan, order).status());
        resume(plan, 0);

        assertEquals(order, ids(steps(plan.owner(), plan.routine())));
    }

    @Test
    void pausingInOneRoutineLeavesOtherRoutinesAlone() throws Exception {
        Account owner = register();
        long shared = libraryHabit(owner, "Shared", 2);
        long morning = routine(owner, 2, false);
        long evening = routine(owner, 2, false);
        post("/routines/" + morning + "/routine-habits/" + shared, owner, null);
        post("/routines/" + evening + "/routine-habits/" + shared, owner, null);
        long morningStep = steps(owner, morning).get(0).path("id").asLong();

        assertEquals(200, post("/routines/" + morning + "/routine-habits/" + morningStep + "/pause", owner, null).status());

        assertTrue(steps(owner, morning).get(0).path("paused").asBoolean());
        assertFalse(steps(owner, evening).get(0).path("paused").asBoolean());
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from routine_habits where habit_id = ? and paused_at is not null", Integer.class, shared));
        assertEquals(1, jdbc.queryForObject("select count(*) from habits where id = ?", Integer.class, shared));
        assertEquals(1, json.readTree(get("/habit-library/" + shared, owner).body()).path("pausedRoutineCount").asInt());
    }

    @Test
    void pressingPauseOrResumeAgainChangesNothing() throws Exception {
        Plan plan = plan(2);
        String before = linksOf(plan.routine());

        assertEquals(200, pause(plan, 0).status());
        Object pausedAt = pausedAt(plan.steps().get(0));
        assertEquals(200, pause(plan, 0).status());
        assertEquals(pausedAt, pausedAt(plan.steps().get(0)), "a second pause keeps the first time");
        assertEquals(200, resume(plan, 0).status());
        assertEquals(200, resume(plan, 0).status());

        assertEquals(before, linksOf(plan.routine()));
    }

    @Test
    void simultaneousPauseAndResumeEndInAValidState() throws Exception {
        Plan plan = plan(3);
        String before = linksOf(plan.routine());

        List<Integer> pauses = together(12, index -> pause(plan, 1).status());
        assertTrue(pauses.stream().allMatch(status -> status == 200), pauses.toString());
        assertNotNull(pausedAt(plan.steps().get(1)));

        List<Integer> mixed = together(12, index -> (index % 2 == 0 ? pause(plan, 1) : resume(plan, 1)).status());
        assertTrue(mixed.stream().allMatch(status -> status == 200), mixed.toString());
        resume(plan, 1);

        assertEquals(before, linksOf(plan.routine()));
    }

    @Test
    void onlyTheOwnerCanPauseOrResume() throws Exception {
        Plan plan = plan(2, true);
        Account stranger = register();
        Plan strangers = plan(1);
        String base = "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(0);

        for (String action : List.of("pause", "resume")) {
            assertEquals(404, post(base + "/" + action, stranger, null).status(), action);
            assertEquals(403, post(base + "/" + action, null, null).status(), action);
            assertEquals(404, post("/routines/" + strangers.routine() + "/routine-habits/" + plan.steps().get(0) + "/" + action,
                    strangers.owner(), null).status(), action);
            assertEquals(404, post("/routines/" + plan.routine() + "/routine-habits/" + MISSING + "/" + action,
                    plan.owner(), null).status(), action);
        }
        assertNull(pausedAt(plan.steps().get(0)));
    }

    @Test
    void otherPeopleDoNotSeeThePausedState() throws Exception {
        Plan plan = plan(2, true);
        pause(plan, 0);
        Account viewer = register();

        JsonNode list = json.readTree(get("/routines/" + plan.routine() + "/routine-habits", viewer).body());

        assertEquals(2, list.size());
        assertFalse(step(list, plan.steps().get(0)).path("paused").asBoolean());
        assertTrue(step(list, plan.steps().get(0)).path("pausedAt").isNull());
    }

    @Test
    void aCopiedRoutineStartsWithEveryHabitActive() throws Exception {
        Plan plan = plan(2, true);
        pause(plan, 0);
        Account copier = register();

        Reply copy = post("/routines/" + plan.routine() + "/copy", copier, null);

        assertEquals(200, copy.status(), copy.body());
        long copyId = json.readTree(copy.body()).path("id").asLong();
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from routine_habits where routine_id = ? and paused_at is not null", Integer.class, copyId));
        assertNotNull(pausedAt(plan.steps().get(0)));
    }

    // ---- pause and the daily run ----

    @Test
    void pausedHabitsAreLeftOutOfANewRun() throws Exception {
        Plan plan = plan(3);
        pause(plan, 1);

        JsonNode run = start(plan);

        assertEquals(2, run.path("totalCount").asInt(), run.toString());
        assertEquals(List.of(plan.steps().get(0), plan.steps().get(2)), runSteps(run));
        Reply paused = act(plan, 1, "start");
        assertEquals(400, paused.status(), paused.body());
        assertEquals("This habit is paused in this routine", message(paused));

        finish(plan, 0);
        JsonNode done = finish(plan, 2);
        assertEquals("COMPLETED", done.path("status").asString(), done.toString());
        assertEquals(100, done.path("progressPercent").asInt());
    }

    @Test
    void theDurationRuleStillCountsPausedHabits() throws Exception {
        Plan plan = plan(3);
        pause(plan, 2);
        long extra = libraryHabit(plan.owner(), "Extra", 1);

        assertEquals(200, post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null).status());
        stop(plan);
        Reply add = post("/routines/" + plan.routine() + "/routine-habits/" + extra, plan.owner(), null);
        assertEquals(400, add.status(), "a paused habit keeps its minutes for when it is resumed");
    }

    @Test
    void aRoutineWithEveryHabitPausedCannotStart() throws Exception {
        Plan plan = plan(2);
        pause(plan, 0);
        pause(plan, 1);

        Reply reply = post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null);

        assertEquals(400, reply.status(), reply.body());
        assertEquals("Every habit in this routine is paused. Resume one to start it.", message(reply));
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_completions where routine_id = ?", Integer.class, plan.routine()));
    }

    @Test
    void pauseAndResumeWaitUntilTheRunningSessionEnds() throws Exception {
        Plan plan = plan(3);
        pause(plan, 2);
        start(plan);
        finish(plan, 0);
        String run = runSnapshot(plan);

        Reply pausing = pause(plan, 1);
        Reply resuming = resume(plan, 2);

        assertEquals(409, pausing.status(), pausing.body());
        assertEquals(409, resuming.status(), resuming.body());
        assertEquals("Cannot edit a running routine", message(pausing));
        assertEquals(run, runSnapshot(plan));
        assertNull(pausedAt(plan.steps().get(1)));
        assertNotNull(pausedAt(plan.steps().get(2)));

        stop(plan);
        assertEquals(200, pause(plan, 1).status());
        assertEquals(200, resume(plan, 2).status());
    }

    @Test
    void dailyTrackingStillWorksAroundAPausedHabit() throws Exception {
        Plan plan = plan(4);
        pause(plan, 1);
        start(plan);

        finish(plan, 0);
        assertEquals(200, act(plan, 2, "postpone").status());
        JsonNode skipped = json.readTree(act(plan, 3, "skip").body());
        assertEquals("POSTPONED", stepStatus(skipped, plan.steps().get(2)));
        assertEquals("SKIPPED", stepStatus(skipped, plan.steps().get(3)));
        assertEquals("RUNNING", skipped.path("status").asString());
        assertEquals(3, skipped.path("totalCount").asInt());

        JsonNode stopped = json.readTree(stop(plan).body());
        assertEquals("STOPPED", stopped.path("status").asString());
        assertEquals(3, stopped.path("habits").size());
        assertEquals("COMPLETED", storedStatus(plan, 0));
        assertEquals("POSTPONED", storedStatus(plan, 2));
        assertEquals("SKIPPED", storedStatus(plan, 3));
    }

    @Test
    void pausingLaterDoesNotChangeEarlierHistory() throws Exception {
        Plan plan = plan(2);
        start(plan);
        finish(plan, 0);
        JsonNode done = finish(plan, 1);
        String history = completions(plan.owner());
        String day = done.path("date").asString();

        pause(plan, 0);

        assertEquals(history, completions(plan.owner()));
        JsonNode shown = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());
        assertEquals("COMPLETED", shown.path("status").asString());
        assertEquals(2, shown.path("totalCount").asInt(), "the finished run still shows what was done");
        assertEquals("COMPLETED", stepStatus(shown, plan.steps().get(0)));
        assertEquals(2, progress(plan.owner(), day, "Completed habits")[0]);
    }

    // ---- progress ----

    @Test
    void progressLeavesOutHabitsThatArePausedEverywhere() throws Exception {
        Account owner = register();
        long morning = routine(owner, 3, false);
        long evening = routine(owner, 1, false);
        long a = libraryHabit(owner, "A", 1);
        long b = libraryHabit(owner, "B", 1);
        long shared = libraryHabit(owner, "Shared", 1);
        for (long habit : List.of(a, b, shared)) {
            post("/routines/" + morning + "/routine-habits/" + habit, owner, null);
        }
        post("/routines/" + evening + "/routine-habits/" + shared, owner, null);
        JsonNode morningSteps = steps(owner, morning);
        String today = LocalDate.now(ZoneId.of("Asia/Almaty")).toString();

        assertEquals(3, progress(owner, today, "Completed habits")[1]);

        post("/routines/" + morning + "/routine-habits/" + morningSteps.get(1).path("id").asLong() + "/pause", owner, null);
        post("/routines/" + morning + "/routine-habits/" + morningSteps.get(2).path("id").asLong() + "/pause", owner, null);

        int[] counts = progress(owner, today, "Completed habits");
        assertEquals(0, counts[0]);
        assertEquals(2, counts[1], "B is paused everywhere; Shared is still active in the evening routine");
    }

    @Test
    void aHabitDoneTodayStillCountsAfterItIsPaused() throws Exception {
        Plan plan = plan(2);
        start(plan);
        JsonNode run = finish(plan, 0);
        stop(plan);

        pause(plan, 0);
        pause(plan, 1);

        int[] counts = progress(plan.owner(), run.path("date").asString(), "Completed habits");
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
    }

    // ---- errors and schema ----

    @Test
    void refusalsExplainThemselves() throws Exception {
        Plan plan = plan(1);
        start(plan);

        Reply running = pause(plan, 0);
        Reply missing = send("DELETE", "/routines/" + plan.routine() + "/routine-habits/" + MISSING, plan.owner(), null);

        assertEquals("Cannot edit a running routine", message(running));
        assertEquals("Cannot edit a running routine", message(missing));
    }

    @Test
    void schemaStoresPauseOnTheRoutineHabit() {
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'routine_habits' and column_name = 'paused_at'
                """, String.class), "existing links stay active with a null pause time");
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'habits' and column_name like 'paused%'
                """, Integer.class), "pause belongs to one routine, not to the habit everywhere");
    }

    // ---- real browser ----

    @BeforeAll
    void manageHabitsInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Plan main = plan(3);
        Plan history = plan(1);
        start(history);
        finish(history, 0);
        Plan halloween = plan(2, true, "Halloween");
        pause(halloween, 1);
        Long builtIn = jdbc.queryForList(
                "select id from routines where built_in = true and category = 'Halloween' and deleted = false order by id limit 1",
                Long.class).stream().findFirst().orElse(null);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("mainToken", main.owner().token());
        fixtures.put("mainRoutine", main.routine());
        fixtures.put("historyToken", history.owner().token());
        fixtures.put("historyRoutine", history.routine());
        fixtures.put("halloweenToken", halloween.owner().token());
        fixtures.put("halloweenRoutine", halloween.routine());
        fixtures.put("builtInRoutine", builtIn);
        fixtures.put("viewerToken", register().token());

        Path dir = Files.createTempDirectory("habit-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/habit-management-check.mjs", chrome,
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
    void browserPausesAndResumesFromTheHabitEditor() {
        JsonNode open = page("editorOpen");
        assertEquals(List.of("Habit 1", "Habit 2", "Habit 3"), texts(open.path("titles")), browser.toString());
        assertEquals(List.of("Pause", "Pause", "Pause"), texts(open.path("pauseLabels")));

        JsonNode paused = page("afterPause");
        assertEquals(List.of("Pause", "Resume", "Pause"), texts(paused.path("pauseLabels")), browser.toString());
        assertEquals(List.of("", "Paused", ""), texts(paused.path("badges")));
        assertEquals(List.of("", "Paused", ""), texts(paused.path("detailBadges")));
        assertTrue(paused.path("habitsCount").asString().contains("1 paused"), paused.toString());

        JsonNode reloaded = page("pausedAfterReload");
        assertEquals(List.of("", "Paused", ""), texts(reloaded.path("detailBadges")), browser.toString());

        JsonNode resumed = page("afterResume");
        assertEquals(List.of("Pause", "Pause", "Pause"), texts(resumed.path("pauseLabels")), browser.toString());
        assertEquals(List.of("Habit 1", "Habit 2", "Habit 3"), texts(resumed.path("titles")));
        assertEquals(List.of("", "", ""), texts(resumed.path("detailBadges")));
    }

    @Test
    void browserReordersAndKeepsTheOrderAfterAReload() {
        assertEquals(List.of("Habit 2", "Habit 1", "Habit 3"), texts(page("afterMoveDown").path("titles")), browser.toString());
        assertEquals(List.of("Habit 2", "Habit 1", "Habit 3"), texts(page("orderAfterReload").path("detailTitles")));
    }

    @Test
    void browserAsksBeforeRemovingAHabit() {
        JsonNode asking = page("removeAsked");
        assertTrue(asking.path("confirmVisible").asBoolean(), browser.toString());
        assertEquals("Delete from this routine? Past results stay saved.", asking.path("confirmText").asString());
        assertEquals(3, asking.path("titles").size());

        assertEquals(3, page("removeCancelled").path("titles").size());
        assertFalse(page("removeCancelled").path("confirmVisible").asBoolean());

        JsonNode removed = page("afterRemove");
        assertEquals(List.of("Habit 2", "Habit 3"), texts(removed.path("titles")), browser.toString());
        assertEquals(List.of("Habit 2", "Habit 3"), texts(removed.path("detailTitles")));
    }

    @Test
    void browserDeletesAHabitWithHistory() {
        JsonNode removed = page("removeWithHistory");

        assertEquals("", removed.path("message").asString(), browser.toString());
        assertEquals(0, removed.path("titles").size(), browser.toString());
        assertEquals(0, page("removeWithHistoryReload").path("detailTitles").size(), browser.toString());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear in the editor, not in alert boxes");
    }

    @Test
    void browserShowsPausedRoutinesOnMyHabits() {
        JsonNode cards = page("myHabits");

        assertTrue(cards.path("badges").toString().contains("Paused in 1 routine"), browser.toString());
    }

    @Test
    void browserLeavesBuiltInHalloweenRoutinesAsTheyWere() {
        JsonNode builtIn = page("builtInHalloween");
        Assumptions.assumeTrue(builtIn.path("loaded").asBoolean(), "no built-in Halloween routine seeded");

        assertTrue(builtIn.path("halloweenTheme").asBoolean(), builtIn.toString());
        assertFalse(builtIn.path("editHabitsVisible").asBoolean());
        assertEquals(0, builtIn.path("pausedBadges").asInt());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "halloweenPhone", "autumnDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmostControl").asInt() <= view.path("width").asInt(), layout + " " + view);
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
            Reply reply = post("/routines/" + routine + "/habits", owner, habitBody("Habit " + i, "Habit " + i + " description", 1));
            assertEquals(200, reply.status(), reply.body());
        }
        List<Long> steps = jdbc.queryForList(
                "select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        List<Long> habitIds = jdbc.queryForList(
                "select habit_id from routine_habits where routine_id = ? order by position", Long.class, routine);
        return new Plan(owner, routine, steps, habitIds);
    }

    private Map<String, Object> habitBody(String title, String description, int minutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("durationMinutes", minutes);
        return body;
    }

    private long libraryHabit(Account owner, String title, int minutes) throws Exception {
        Reply reply = post("/habit-library", owner, habitBody(title, title + " description", minutes));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private JsonNode steps(Account owner, long routine) throws Exception {
        Reply reply = get("/routines/" + routine + "/routine-habits", owner);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
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

    private List<Integer> positions(long routine) {
        return jdbc.queryForList("select position from routine_habits where routine_id = ? order by position", Integer.class, routine);
    }

    private String linksOf(long routine) {
        return jdbc.queryForList("select id, habit_id, position from routine_habits where routine_id = ? order by id", routine).toString();
    }

    private Object pausedAt(long routineHabitId) {
        return jdbc.queryForObject("select paused_at from routine_habits where id = ?", Object.class, routineHabitId);
    }

    private int habitsOf(Account owner) {
        return jdbc.queryForObject("select count(*) from habits where user_id = ?", Integer.class, owner.id());
    }

    private String completions(Account owner) {
        return jdbc.queryForList("""
                select id, habit_id, routine_habit_id, routine_completion_id, status, completed_date, started_at, completed_at
                from habit_completions where user_id = ? order by id
                """, owner.id()).toString();
    }

    private String runSnapshot(Plan plan) {
        return jdbc.queryForList("""
                select rc.id, rc.completed_at, rc.ended_at, hc.id, hc.status, hc.started_at, hc.completed_at
                from routine_completions rc left join habit_completions hc on hc.routine_completion_id = rc.id
                where rc.routine_id = ? order by rc.id, hc.id
                """, plan.routine()).toString();
    }

    private String storedStatus(Plan plan, int index) {
        return jdbc.queryForObject("""
                select status from habit_completions hc join routine_completions rc on rc.id = hc.routine_completion_id
                where hc.routine_habit_id = ? order by rc.id desc limit 1
                """, String.class, plan.steps().get(index));
    }

    private Reply pause(Plan plan, int index) throws Exception {
        return post("/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(index) + "/pause", plan.owner(), null);
    }

    private Reply resume(Plan plan, int index) throws Exception {
        return post("/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(index) + "/resume", plan.owner(), null);
    }

    private Reply reorder(Plan plan, List<Long> order) throws Exception {
        return send("PUT", "/routines/" + plan.routine() + "/routine-habits/order", plan.owner(), Map.of("habitIds", order));
    }

    private JsonNode start(Plan plan) throws Exception {
        Reply reply = post("/routine-runs/" + plan.routine() + "/start", plan.owner(), null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply stop(Plan plan) throws Exception {
        return post("/routine-runs/" + plan.routine() + "/stop", plan.owner(), null);
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

    private List<Long> runSteps(JsonNode run) {
        List<Long> values = new ArrayList<>();
        run.path("habits").forEach(item -> values.add(item.path("routineHabitId").asLong()));
        return values;
    }

    private String stepStatus(JsonNode run, long routineHabitId) {
        for (JsonNode habit : run.path("habits")) {
            if (habit.path("routineHabitId").asLong() == routineHabitId) {
                return habit.path("status").asString();
            }
        }
        throw new AssertionError("No habit " + routineHabitId + " in " + run);
    }

    // Returns {completed, total} for one progress line.
    private int[] progress(Account account, String day, String label) throws Exception {
        Reply reply = get("/progress?period=day&date=" + day, account);
        assertEquals(200, reply.status(), reply.body());
        for (JsonNode item : json.readTree(reply.body()).path("habits")) {
            if (label.equals(item.path("label").asString())) {
                return new int[]{item.path("completed").asInt(), item.path("total").asInt()};
            }
        }
        throw new AssertionError(label + " missing in " + reply.body());
    }

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        String email = "habit_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = post("/auth/register", null, Map.of("name", "Habit QA", "email", email, "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString(), email);
    }

    private Account login(Account account) throws Exception {
        Reply reply = post("/auth/login", null, Map.of("email", account.email(), "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        String token = json.readTree(reply.body()).path("token").asString();
        assertFalse(token.isBlank(), reply.body());
        return new Account(account.id(), token, account.email());
    }

    private long routine(Account owner, int minutes, boolean isPublic) throws Exception {
        return routine(owner, minutes, isPublic, "Morning");
    }

    private long routine(Account owner, int minutes, boolean isPublic, String category) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", category + " habits");
        fields.put("description", "Habit management check");
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
