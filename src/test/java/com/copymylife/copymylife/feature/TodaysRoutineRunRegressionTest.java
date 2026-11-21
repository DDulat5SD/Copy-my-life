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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// US-20: a run holds the activities planned for its day, in the routine timeline's order.
// A habit is planned on a day when its repeat days include it; one without repeat days is not
// tied to a day and is planned every day, as before schedules existed.
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
class TodaysRoutineRunRegressionTest {

    private static final String BOUNDARY = "TodaysRunBoundary";
    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    // Always on different calendar days: 26 hours apart.
    private static final String EAST = "Pacific/Kiritimati";
    private static final String WEST = "Etc/GMT+12";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    // A zone that is well away from midnight right now, so "today" cannot turn over mid-test.
    private final String zone = calmZone();
    private final String today = dayCode(LocalDate.now(ZoneId.of(zone)));
    private final String otherDay = WEEK.get((WEEK.indexOf(today) + 3) % 7);

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

    // ---- which habits are in today's run ----

    @Test
    void aRunHoldsOnlyTheHabitsPlannedForToday() throws Exception {
        Plan plan = plan(4);
        schedule(plan, 0, "09:00", List.of(today));
        schedule(plan, 1, null, List.of(otherDay));
        schedule(plan, 2, "07:00", List.of(otherDay));
        // Habit 4 keeps no schedule: it is not tied to a day.

        JsonNode run = start(plan);

        assertEquals(List.of(plan.steps().get(0), plan.steps().get(3)), runSteps(run));
        assertEquals(2, run.path("totalCount").asInt());
    }

    @Test
    void habitsWithoutAScheduleStillRunEveryDayInRoutineOrder() throws Exception {
        Plan plan = plan(3);

        JsonNode run = start(plan);

        assertEquals(plan.steps(), runSteps(run));
        assertEquals(List.of(1, 2, 3), positions(run));
        assertEquals("READY", stepStatus(run, plan.steps().get(0)));
    }

    @Test
    void aHabitWithDaysButNoTimeRunsOnlyOnThoseDays() throws Exception {
        Plan plan = plan(2);
        schedule(plan, 0, null, List.of(today));
        schedule(plan, 1, null, List.of(otherDay));

        assertEquals(List.of(plan.steps().get(0)), runSteps(start(plan)));
    }

    @Test
    void plannedHabitsGoInTimeOrderThenRoutineOrder() throws Exception {
        Plan plan = plan(4);
        schedule(plan, 0, "21:00", List.of(today));
        schedule(plan, 1, "06:30", List.of(today));
        // Habit 3 is untimed and not tied to a day; it follows the timed ones.
        schedule(plan, 3, "12:15", WEEK);

        JsonNode run = start(plan);

        List<Long> steps = plan.steps();
        assertEquals(List.of(steps.get(1), steps.get(3), steps.get(0), steps.get(2)), runSteps(run));
        assertEquals(List.of(1, 2, 3, 4), positions(run));
        assertEquals("READY", stepStatus(run, steps.get(1)), "the earliest habit comes first");
        assertEquals("LOCKED", stepStatus(run, steps.get(0)));
        assertEquals("06:30", item(run, steps.get(1)).path("startTime").asString());
        assertEquals(List.of(today), texts(item(run, steps.get(1)).path("repeatDays")));
        assertTrue(item(run, steps.get(2)).path("startTime").isNull());
    }

    @Test
    void theOrderIsFollowedWhenTracking() throws Exception {
        Plan plan = plan(2);
        schedule(plan, 0, "20:00", List.of(today));
        schedule(plan, 1, "08:00", List.of(today));
        start(plan);

        Reply early = act(plan, 0, "start");
        assertEquals(400, early.status(), early.body());
        assertEquals("Complete the previous habit first", message(early));

        finish(plan, 1);
        JsonNode done = finish(plan, 0);

        assertEquals("COMPLETED", done.path("status").asString(), done.toString());
    }

    @Test
    void anUnplannedHabitCannotBeTrackedToday() throws Exception {
        Plan plan = plan(2);
        schedule(plan, 1, null, List.of(otherDay));
        start(plan);

        for (String action : List.of("start", "skip", "postpone", "complete")) {
            Reply reply = act(plan, 1, action);
            assertEquals(400, reply.status(), action + " " + reply.body());
            assertTrue(message(reply).startsWith("This habit is not planned for "), message(reply));
        }
        assertEquals(0, recordsFor(plan.steps().get(1)));
    }

    // ---- progress ----

    @Test
    void unplannedHabitsDoNotLowerTodaysProgress() throws Exception {
        Plan plan = plan(4);
        schedule(plan, 2, null, List.of(otherDay));
        schedule(plan, 3, "05:00", List.of(otherDay));
        start(plan);

        JsonNode half = finish(plan, 0);
        assertEquals(1, half.path("completedCount").asInt());
        assertEquals(2, half.path("totalCount").asInt());
        assertEquals(50, half.path("progressPercent").asInt());

        JsonNode done = finish(plan, 1);

        assertEquals("COMPLETED", done.path("status").asString(), done.toString());
        assertEquals(100, done.path("progressPercent").asInt());
        assertTrue(jdbc.queryForObject("select completed from routines where id = ?", Boolean.class, plan.routine()));
        assertEquals(1, completedRoutines(plan.owner(), done.path("date").asString()));
        assertEquals(2, completedHabits(plan.owner(), done.path("date").asString()));
    }

    @Test
    void skippedAndPostponedStaySavedAfterARefresh() throws Exception {
        Plan plan = plan(4);
        schedule(plan, 0, "18:00", List.of(today));
        schedule(plan, 1, "06:00", List.of(today));
        schedule(plan, 2, "07:00", List.of(otherDay));
        start(plan);

        assertEquals(200, act(plan, 1, "skip").status());
        assertEquals(200, act(plan, 0, "postpone").status());

        JsonNode reloaded = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals(List.of(plan.steps().get(1), plan.steps().get(0), plan.steps().get(3)), runSteps(reloaded));
        assertEquals("SKIPPED", stepStatus(reloaded, plan.steps().get(1)));
        assertEquals("POSTPONED", stepStatus(reloaded, plan.steps().get(0)));
        assertEquals("READY", stepStatus(reloaded, plan.steps().get(3)));
        assertEquals(1, reloaded.path("skippedCount").asInt());
        assertEquals(1, reloaded.path("postponedCount").asInt());
        assertEquals(3, reloaded.path("totalCount").asInt());
        assertEquals("SKIPPED", jdbc.queryForObject(
                "select status from habit_completions where routine_habit_id = ?", String.class, plan.steps().get(1)));
        assertEquals("POSTPONED", jdbc.queryForObject(
                "select status from habit_completions where routine_habit_id = ?", String.class, plan.steps().get(0)));
    }

    @Test
    void aHabitCompletedTwiceInOneDayCountsOnce() throws Exception {
        Plan plan = plan(1);
        JsonNode first = start(plan);
        finish(plan, 0);
        String day = first.path("date").asString();

        assertEquals(400, act(plan, 0, "complete").status(), "a completed habit cannot be completed again in its run");
        JsonNode second = start(plan);
        assertNotEquals(first.path("runId").asLong(), second.path("runId").asLong());
        finish(plan, 0);

        assertEquals(1, completedHabits(plan.owner(), day));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from habit_completions where routine_completion_id = ? and status = 'COMPLETED'
                """, Integer.class, first.path("runId").asLong()));
    }

    // ---- nothing planned, paused ----

    @Test
    void aDayWithNothingPlannedIsExplainedAndStartsNothing() throws Exception {
        Plan plan = plan(2);
        schedule(plan, 0, "07:00", List.of(otherDay));
        schedule(plan, 1, null, List.of(otherDay));

        Reply reply = startIn(plan, zone);

        assertEquals(400, reply.status(), reply.body());
        assertEquals("Nothing is planned for " + dayName(today) + " in this routine. Its habits are on other days.", message(reply));
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_completions where routine_id = ?", Integer.class, plan.routine()));
        assertEquals(204, get("/routine-runs/active", plan.owner()).status());
    }

    @Test
    void pausedHabitsSitOutAndOnlyPlannedOnesCount() throws Exception {
        Plan plan = plan(3);
        schedule(plan, 1, null, List.of(otherDay));
        assertEquals(200, post(stepPath(plan, 0) + "/pause", plan.owner(), null).status());

        JsonNode run = start(plan);

        assertEquals(List.of(plan.steps().get(2)), runSteps(run));
        stop(plan);

        Plan onlyPaused = plan(2);
        schedule(onlyPaused, 1, null, List.of(otherDay));
        assertEquals(200, post(stepPath(onlyPaused, 0) + "/pause", onlyPaused.owner(), null).status());
        Reply refused = startIn(onlyPaused, zone);
        assertEquals(400, refused.status(), refused.body());
        assertTrue(message(refused).startsWith("Nothing is planned for " + dayName(today)), message(refused));

        Plan allPaused = plan(1);
        assertEquals(200, post(stepPath(allPaused, 0) + "/pause", allPaused.owner(), null).status());
        assertEquals("Every habit in this routine is paused. Resume one to start it.", message(startIn(allPaused, zone)));
    }

    @Test
    void stopAndResumeStillWorkWithAPlannedDay() throws Exception {
        Plan plan = plan(3);
        schedule(plan, 0, "10:00", List.of(today));
        schedule(plan, 1, "08:00", List.of(today));
        schedule(plan, 2, "09:00", List.of(otherDay));
        start(plan);

        assertEquals(200, act(plan, 1, "postpone").status());
        finish(plan, 0);
        assertEquals(200, act(plan, 1, "start").status(), "a postponed habit can be resumed");
        JsonNode stopped = stop(plan);

        assertEquals("STOPPED", stopped.path("status").asString(), stopped.toString());
        assertEquals(List.of(plan.steps().get(1), plan.steps().get(0)), runSteps(stopped));
        assertEquals("NOT_FINISHED", stepStatus(stopped, plan.steps().get(1)));
        assertEquals("COMPLETED", stepStatus(stopped, plan.steps().get(0)));
        assertEquals(400, act(plan, 1, "complete").status(), "a stopped run takes no more changes");
    }

    // ---- days and time zones ----

    @Test
    void theDayComesFromTheUsersTimeZone() throws Exception {
        for (String userZone : List.of(EAST, WEST)) {
            String eastDay = dayCode(LocalDate.now(ZoneId.of(EAST)));
            Plan plan = plan(2);
            schedule(plan, 0, null, List.of(eastDay));

            Reply reply = startIn(plan, userZone);
            assertEquals(200, reply.status(), reply.body());
            JsonNode run = json.readTree(reply.body());

            String runDay = dayCode(LocalDate.parse(run.path("date").asString()));
            List<Long> expected = runDay.equals(eastDay) ? plan.steps() : List.of(plan.steps().get(1));
            assertEquals(expected, runSteps(run), userZone + " ran on " + runDay);
        }
    }

    // ---- history ----

    @Test
    void resultsStayVisibleAfterTheScheduleChanges() throws Exception {
        Plan plan = plan(3);
        start(plan);
        finish(plan, 0);
        assertEquals(200, act(plan, 1, "skip").status());
        assertEquals(200, act(plan, 2, "postpone").status());
        stop(plan);
        String history = jdbc.queryForList("""
                select routine_habit_id, status, started_at, completed_at from habit_completions
                where routine_habit_id in (?, ?, ?) order by routine_habit_id
                """, plan.steps().get(0), plan.steps().get(1), plan.steps().get(2)).toString();

        // Later the owner moves every habit to another day.
        jdbc.update("update routine_habits set repeat_days = ? where routine_id = ?", otherDay, plan.routine());

        JsonNode shown = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals(plan.steps(), runSteps(shown));
        assertEquals("COMPLETED", stepStatus(shown, plan.steps().get(0)));
        assertEquals("SKIPPED", stepStatus(shown, plan.steps().get(1)));
        assertEquals("POSTPONED", stepStatus(shown, plan.steps().get(2)));
        assertEquals(1, shown.path("completedCount").asInt());
        assertEquals(history, jdbc.queryForList("""
                select routine_habit_id, status, started_at, completed_at from habit_completions
                where routine_habit_id in (?, ?, ?) order by routine_habit_id
                """, plan.steps().get(0), plan.steps().get(1), plan.steps().get(2)).toString());
    }

    // ---- timeline and run agree ----

    @Test
    void theRunMatchesTheTimelineForToday() throws Exception {
        Plan plan = plan(5);
        schedule(plan, 0, "19:00", List.of(today, otherDay));
        schedule(plan, 1, "06:00", List.of(otherDay));
        schedule(plan, 2, "06:45", WEEK);
        schedule(plan, 3, null, List.of(today));
        assertEquals(200, post(stepPath(plan, 4) + "/pause", plan.owner(), null).status());

        JsonNode run = start(plan);
        JsonNode timeline = json.readTree(get("/routines/" + plan.routine() + "/routine-habits", plan.owner()).body());

        assertEquals(timelineForToday(timeline), runSteps(run));
    }

    // ---- real browser ----

    @BeforeAll
    void trackTodaysRunInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Plan today = plan(4);
        rename(today, 0, "Evening read");
        rename(today, 1, "Morning stretch");
        rename(today, 2, "Journal");
        rename(today, 3, "Weekend hike");
        schedule(today, 0, "21:00", List.of(this.today));
        schedule(today, 1, "06:30", List.of(this.today));
        schedule(today, 3, "08:00", List.of(otherDay));
        assertEquals(200, startIn(today, zone).status());

        Plan nothing = plan(2);
        schedule(nothing, 0, "07:00", List.of(otherDay));
        schedule(nothing, 1, null, List.of(otherDay));

        Account emptyOwner = register();
        long empty = routine(emptyOwner, 5);

        Plan paused = plan(2);
        for (int index = 0; index < 2; index++) {
            assertEquals(200, post(stepPath(paused, index) + "/pause", paused.owner(), null).status());
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("zone", zone);
        fixtures.put("todayToken", today.owner().token());
        fixtures.put("todayRoutine", today.routine());
        fixtures.put("nothingToken", nothing.owner().token());
        fixtures.put("nothingRoutine", nothing.routine());
        fixtures.put("emptyToken", emptyOwner.token());
        fixtures.put("emptyRoutine", empty);
        fixtures.put("pausedToken", paused.owner().token());
        fixtures.put("pausedRoutine", paused.routine());

        Path dir = Files.createTempDirectory("todays-run-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/todays-run-check.mjs", chrome,
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
    void browserShowsOnlyTodaysHabitsInTimeOrder() {
        JsonNode initial = page("initial");

        assertEquals(List.of("Morning stretch", "Evening read", "Journal"), texts(initial.path("titles")), initial.toString());
        assertEquals("0 / 3 completed", initial.path("count").asString());
        assertEquals("3 habits", initial.path("habitsCount").asString());
        assertEquals("READY", initial.path("rows").get(0).path("status").asString());
        assertTrue(initial.path("rows").get(0).path("time").asString().contains("06:30"), initial.toString());
        assertTrue(initial.path("rows").get(1).path("time").asString().contains("21:00"), initial.toString());
    }

    @Test
    void browserKeepsSkippedAndPostponedAfterAReload() {
        JsonNode reloaded = page("afterReload");

        assertEquals(List.of("Morning stretch", "Evening read", "Journal"), texts(reloaded.path("titles")), reloaded.toString());
        assertEquals("SKIPPED", reloaded.path("rows").get(0).path("status").asString(), reloaded.toString());
        assertEquals("POSTPONED", reloaded.path("rows").get(1).path("status").asString(), reloaded.toString());
        assertTrue(reloaded.path("summary").asString().contains("1 skipped"), reloaded.toString());
        assertTrue(reloaded.path("summary").asString().contains("1 postponed"), reloaded.toString());
    }

    @Test
    void browserExplainsADayWithNothingPlanned() {
        JsonNode view = page("nothingPlanned");

        assertTrue(view.path("startVisible").asBoolean(), view.toString());
        assertTrue(view.path("startDisabled").asBoolean(), view.toString());
        assertEquals("Nothing planned today", view.path("startText").asString(), view.toString());
        assertEquals("Nothing is planned for today in this routine. Its habits are on other days.",
                view.path("hint").asString(), view.toString());
        assertTrue(view.path("dialogs").isEmpty(), view.toString());
    }

    @Test
    void browserLeavesEmptyAndFullyPausedRoutinesToTheServersExplanation() {
        Map<String, String> expected = Map.of(
                "emptyRoutine", "Add habits before starting this routine",
                "pausedRoutine", "Every habit in this routine is paused. Resume one to start it.");

        expected.forEach((name, message) -> {
            JsonNode view = page(name);
            assertTrue(view.path("startVisible").asBoolean(), name + " " + view);
            assertFalse(view.path("startDisabled").asBoolean(), name + " " + view);
            assertEquals("Start routine", view.path("startText").asString(), name + " " + view);
            assertEquals("", view.path("hint").asString(), name + " " + view);
            assertEquals(List.of(message), texts(view.path("dialogs")), name + " " + view);
            assertFalse(view.path("leftPage").asBoolean(), name + " stays on the routine page");
        });
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "autumnDesktop", "nothingOriginalPhone", "nothingAutumnPhone")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            // Negative on desktop: innerWidth also counts the vertical scrollbar.
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
        assertEquals("autumn", page("nothingAutumnPhone").path("theme").asString());
    }

    // ---- the timeline's rule, as routine.html applies it ----

    private List<Long> timelineForToday(JsonNode timeline) {
        List<JsonNode> planned = new ArrayList<>();
        timeline.forEach(habit -> {
            List<String> days = texts(habit.path("repeatDays"));
            if (!habit.path("paused").asBoolean() && (days.isEmpty() || days.contains(today))) {
                planned.add(habit);
            }
        });
        planned.sort(Comparator
                .comparing((JsonNode habit) -> habit.path("startTime").isNull() || habit.path("startTime").isMissingNode())
                .thenComparing(habit -> habit.path("startTime").isString() ? habit.path("startTime").asString() : "")
                .thenComparing(habit -> habit.path("position").asInt()));
        List<Long> ids = new ArrayList<>();
        planned.forEach(habit -> ids.add(habit.path("id").asLong()));
        return ids;
    }

    // ---- helpers ----

    private static String calmZone() {
        for (String candidate : List.of("Asia/Almaty", "Europe/London", "America/New_York", "Asia/Tokyo", "Pacific/Honolulu")) {
            int hour = ZonedDateTime.now(ZoneId.of(candidate)).getHour();
            if (hour >= 1 && hour <= 21) {
                return candidate;
            }
        }
        return "Asia/Almaty";
    }

    private static String dayCode(LocalDate date) {
        return WEEK.get(date.getDayOfWeek().getValue() - 1);
    }

    private static String dayName(String code) {
        return code.charAt(0) + code.substring(1).toLowerCase(Locale.ROOT)
                + switch (code) {
                    case "MON", "FRI", "SUN" -> "day";
                    case "TUE" -> "sday";
                    case "WED" -> "nesday";
                    case "THU" -> "rsday";
                    case "SAT" -> "urday";
                    default -> "";
                };
    }

    private List<Long> runSteps(JsonNode run) {
        List<Long> ids = new ArrayList<>();
        run.path("habits").forEach(habit -> ids.add(habit.path("routineHabitId").asLong()));
        return ids;
    }

    private List<Integer> positions(JsonNode run) {
        List<Integer> values = new ArrayList<>();
        run.path("habits").forEach(habit -> values.add(habit.path("position").asInt()));
        return values;
    }

    private JsonNode item(JsonNode run, long routineHabitId) {
        for (JsonNode habit : run.path("habits")) {
            if (habit.path("routineHabitId").asLong() == routineHabitId) {
                return habit;
            }
        }
        throw new AssertionError(routineHabitId + " missing in " + run);
    }

    private String stepStatus(JsonNode run, long routineHabitId) {
        return item(run, routineHabitId).path("status").asString();
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private int recordsFor(long routineHabitId) {
        return jdbc.queryForObject("select count(*) from habit_completions where routine_habit_id = ?", Integer.class, routineHabitId);
    }

    // Habits of one minute each, so the routine's duration always matches and it can start.
    private Plan plan(int habits) throws Exception {
        Account owner = register();
        long routine = routine(owner, habits);
        for (int i = 1; i <= habits; i++) {
            Reply reply = post("/routines/" + routine + "/habits", owner,
                    Map.of("title", "Habit " + i, "description", "Habit " + i + " description", "durationMinutes", 1));
            assertEquals(200, reply.status(), reply.body());
        }
        List<Long> steps = jdbc.queryForList(
                "select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        return new Plan(owner, routine, steps);
    }

    private void schedule(Plan plan, int index, String startTime, List<String> days) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("startTime", startTime);
        body.put("repeatDays", days);
        Reply reply = put(stepPath(plan, index), plan.owner(), body);
        assertEquals(200, reply.status(), reply.body());
    }

    private void rename(Plan plan, int index, String title) {
        jdbc.update("update habits set title = ? where id = (select habit_id from routine_habits where id = ?)",
                title, plan.steps().get(index));
    }

    private String stepPath(Plan plan, int index) {
        return "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(index);
    }

    private JsonNode start(Plan plan) throws Exception {
        Reply reply = startIn(plan, zone);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply startIn(Plan plan, String userZone) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routine-runs/" + plan.routine() + "/start"))
                .header("Authorization", "Bearer " + plan.owner().token())
                .header("X-Time-Zone", userZone)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private JsonNode stop(Plan plan) throws Exception {
        Reply reply = post("/routine-runs/" + plan.routine() + "/stop", plan.owner(), null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply act(Plan plan, int index, String action) throws Exception {
        return post("/routine-runs/" + plan.routine() + "/routine-habits/" + plan.steps().get(index) + "/" + action,
                plan.owner(), null);
    }

    // Starts the habit and moves its start back so its timer has already run out.
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

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Today QA",
                "email", "today_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, int minutes) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Today " + UUID.randomUUID().toString().substring(0, 6));
        fields.put("description", "Today's plan check");
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("X-Time-Zone", zone);
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
