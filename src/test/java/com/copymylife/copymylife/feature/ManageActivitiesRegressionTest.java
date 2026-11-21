package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// US-09: the owner can delete an activity from a routine even after it has been tracked.
// It leaves the routine for good, while its completion history, daily statistics and the
// results of finished runs stay exactly as they were.
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
class ManageActivitiesRegressionTest {

    private static final String BOUNDARY = "ManageActivitiesBoundary";
    private static final String ZONE = "Asia/Almaty";
    private static final List<String> EVERY_DAY = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String token) {
    }

    private record Plan(Account owner, long routine, List<Long> steps, List<Long> habits) {
    }

    // Production keeps positions unique per routine; the test schema gets the same rule.
    @BeforeAll
    void usePositionsUniquePerRoutine() {
        jdbc.execute("""
                DO $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_routine_habit_position') THEN
                        ALTER TABLE routine_habits ADD CONSTRAINT uk_routine_habit_position UNIQUE (routine_id, position);
                    END IF;
                END $$
                """);
    }

    // ---- deleting a tracked activity ----

    @Test
    void anActivityWithHistoryIsDeletedAndStaysGoneAfterARefresh() throws Exception {
        Plan plan = trackedPlan(3);

        Reply reply = delete(plan, 0);

        assertEquals(200, reply.status(), reply.body());
        assertEquals(List.of(plan.steps().get(1), plan.steps().get(2)), ids(json.readTree(reply.body())));
        assertEquals(List.of(plan.steps().get(1), plan.steps().get(2)), ids(steps(plan.owner(), plan.routine())));
        assertFalse(habitTitles(plan).contains("Habit 1"), "the routine's habit list no longer shows it");
        assertEquals(404, delete(plan, 0).status(), "deleting it again finds nothing");
    }

    @Test
    void deletingKeepsEveryCompletionRecord() throws Exception {
        Plan plan = trackedPlan(2);
        String history = completions(plan);
        String runs = runs(plan);

        assertEquals(200, delete(plan, 0).status());

        assertEquals(history, completions(plan), "no completion row is changed or removed");
        assertEquals(runs, runs(plan), "no run row is changed or removed");
    }

    @Test
    void aFinishedRunStillShowsTheDeletedActivity() throws Exception {
        Plan plan = trackedPlan(2);
        JsonNode before = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals(200, delete(plan, 0).status());
        JsonNode after = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body());

        assertEquals("COMPLETED", after.path("status").asString(), after.toString());
        assertEquals(ids(before.path("habits"), "routineHabitId"), ids(after.path("habits"), "routineHabitId"));
        assertEquals("COMPLETED", after.path("habits").get(0).path("status").asString());
        assertEquals(2, after.path("completedCount").asInt());
        assertEquals(100, after.path("progressPercent").asInt());
    }

    @Test
    void dailyStatisticsStayTheSame() throws Exception {
        Plan plan = trackedPlan(2);
        String day = json.readTree(get("/routine-runs/" + plan.routine(), plan.owner()).body()).path("date").asString();
        String progress = get("/progress?period=day&date=" + day, plan.owner()).body();
        String week = get("/progress?period=week&date=" + day, plan.owner()).body();

        assertEquals(200, delete(plan, 0).status());

        assertEquals(progress, get("/progress?period=day&date=" + day, plan.owner()).body());
        assertEquals(week, get("/progress?period=week&date=" + day, plan.owner()).body());
    }

    @Test
    void aPausedActivityWithHistoryCanBeDeletedToo() throws Exception {
        Plan plan = trackedPlan(2);
        assertEquals(200, post(stepPath(plan, 1) + "/pause", plan.owner(), null).status());

        assertEquals(200, delete(plan, 1).status());

        assertEquals(List.of(plan.steps().get(0)), ids(steps(plan.owner(), plan.routine())));
    }

    @Test
    void anActivityWithoutHistoryIsStillRemovedCompletely() throws Exception {
        Plan plan = plan(3);

        assertEquals(200, delete(plan, 1).status());

        assertEquals(0, jdbc.queryForObject("select count(*) from routine_habits where id = ?", Integer.class, plan.steps().get(1)));
        assertEquals(List.of(0, 1), activePositions(plan));
    }

    // ---- what the routine does afterwards ----

    @Test
    void newRunsLeaveTheDeletedActivityOut() throws Exception {
        Plan plan = trackedPlan(3);
        assertEquals(200, delete(plan, 0).status());
        assertEquals(200, post("/routines/" + plan.routine() + "/habits", plan.owner(),
                Map.of("title", "Replacement", "durationMinutes", 1)).status());

        JsonNode run = start(plan);

        List<Long> runSteps = ids(run.path("habits"), "routineHabitId");
        assertFalse(runSteps.contains(plan.steps().get(0)), run.toString());
        assertEquals(3, runSteps.size());
        assertEquals(3, run.path("totalCount").asInt());
    }

    @Test
    void theOrderClosesAroundTheDeletedActivity() throws Exception {
        Plan plan = trackedPlan(3);

        assertEquals(200, delete(plan, 1).status());
        Reply reorder = put("/routines/" + plan.routine() + "/routine-habits/order", plan.owner(),
                Map.of("habitIds", List.of(plan.steps().get(2), plan.steps().get(0))));

        assertEquals(200, reorder.status(), reorder.body());
        assertEquals(List.of(plan.steps().get(2), plan.steps().get(0)), ids(steps(plan.owner(), plan.routine())));
        assertEquals(List.of(0, 1), activePositions(plan));
    }

    @Test
    void severalDeletionsThenAddingAndReorderingKeepPositionsUnique() throws Exception {
        Plan plan = trackedPlan(4);

        assertEquals(200, delete(plan, 1).status());
        assertEquals(200, delete(plan, 2).status());
        Reply added = post("/routines/" + plan.routine() + "/habits", plan.owner(),
                Map.of("title", "Fresh", "durationMinutes", 1));
        assertEquals(200, added.status(), added.body());
        List<Long> now = ids(steps(plan.owner(), plan.routine()));
        assertEquals(3, now.size());
        Reply reorder = put("/routines/" + plan.routine() + "/routine-habits/order", plan.owner(),
                Map.of("habitIds", List.of(now.get(2), now.get(1), now.get(0))));

        assertEquals(200, reorder.status(), reorder.body());
        assertEquals(List.of(now.get(2), now.get(1), now.get(0)), ids(steps(plan.owner(), plan.routine())));
        assertEquals(List.of(0, 1, 2), activePositions(plan));
    }

    @Test
    void aDeletedActivityNoLongerBlocksItsTimeSlot() throws Exception {
        Plan plan = plan(2);
        schedule(plan, 0, "07:00", EVERY_DAY);
        start(plan);
        finish(plan, 0);
        finish(plan, 1);

        assertEquals(200, delete(plan, 0).status());

        Reply reply = schedule(plan, 1, "07:00", EVERY_DAY);
        assertEquals(200, reply.status(), reply.body());
    }

    @Test
    void theSameHabitCanBeAddedBackAsANewActivity() throws Exception {
        Plan plan = trackedPlan(2);
        String history = completions(plan);

        assertEquals(200, delete(plan, 0).status());
        Reply added = post("/routines/" + plan.routine() + "/routine-habits/" + plan.habits().get(0), plan.owner(), null);

        assertEquals(200, added.status(), added.body());
        JsonNode list = steps(plan.owner(), plan.routine());
        assertEquals(2, list.size());
        assertFalse(ids(list).contains(plan.steps().get(0)), "it comes back as a fresh activity");
        assertEquals(history, completions(plan));
    }

    @Test
    void deletingFromOneRoutineLeavesTheHabitInOthers() throws Exception {
        Plan plan = trackedPlan(1);
        long other = routine(plan.owner(), 1, false);
        Reply added = post("/routines/" + other + "/routine-habits/" + plan.habits().get(0), plan.owner(), null);
        assertEquals(200, added.status(), added.body());
        long otherStep = ids(json.readTree(added.body())).get(0);

        assertEquals(200, delete(plan, 0).status());

        assertEquals(List.of(otherStep), ids(steps(plan.owner(), other)));
        Reply run = startRoutine(plan.owner(), other);
        assertEquals(200, run.status(), run.body());
        assertEquals(List.of(otherStep), ids(json.readTree(run.body()).path("habits"), "routineHabitId"));
    }

    @Test
    void aCopyLeavesTheDeletedActivityOut() throws Exception {
        Account owner = register();
        long routine = routine(owner, 2, true);
        Plan plan = withHabits(owner, routine, 2);
        start(plan);
        finish(plan, 0);
        finish(plan, 1);
        assertEquals(200, delete(plan, 0).status());

        Account copier = register();
        Reply copy = post("/routines/" + routine + "/copy", copier, null);

        assertEquals(200, copy.status(), copy.body());
        JsonNode copied = steps(copier, json.readTree(copy.body()).path("id").asLong());
        assertEquals(1, copied.size(), copied.toString());
        assertEquals("Habit 2", copied.get(0).path("title").asString());
    }

    @Test
    void visitorsNoLongerSeeTheDeletedActivity() throws Exception {
        Account owner = register();
        long routine = routine(owner, 2, true);
        Plan plan = withHabits(owner, routine, 2);
        start(plan);
        finish(plan, 0);
        finish(plan, 1);

        assertEquals(200, delete(plan, 0).status());

        assertEquals(List.of(plan.steps().get(1)), ids(steps(null, routine)));
        assertEquals(List.of(plan.steps().get(1)), ids(steps(register(), routine)));
    }

    // ---- safety ----

    @Test
    void nothingCanBeDeletedWhileTheRoutineIsRunning() throws Exception {
        Plan plan = plan(2);
        start(plan);
        finish(plan, 0);
        String history = completions(plan);

        Reply reply = delete(plan, 0);

        assertEquals(409, reply.status(), reply.body());
        assertEquals("Cannot edit a running routine", message(reply));
        assertEquals(2, steps(plan.owner(), plan.routine()).size());
        assertEquals(history, completions(plan));
    }

    @Test
    void nobodyElseCanDeleteOrChangeTheActivity() throws Exception {
        Plan plan = trackedPlan(2);
        Account stranger = register();

        assertEquals(404, send("DELETE", stepPath(plan, 0), stranger, null).status());
        assertEquals(404, post(stepPath(plan, 0) + "/pause", stranger, null).status());
        assertEquals(404, put(stepPath(plan, 0), stranger, Map.of("notes", "mine now")).status());
        assertEquals(403, send("DELETE", stepPath(plan, 0), null, null).status());

        assertEquals(2, steps(plan.owner(), plan.routine()).size());
        assertEquals(0, jdbc.queryForObject("select count(*) from routine_habits where id = ? and removed_at is not null",
                Integer.class, plan.steps().get(0)));
    }

    @Test
    void aDeletedActivityCannotBeEditedPausedOrTracked() throws Exception {
        Plan plan = trackedPlan(2);
        assertEquals(200, delete(plan, 0).status());

        assertEquals(404, put(stepPath(plan, 0), plan.owner(), Map.of("notes", "again")).status());
        assertEquals(404, post(stepPath(plan, 0) + "/pause", plan.owner(), null).status());
        assertEquals(404, post(stepPath(plan, 0) + "/resume", plan.owner(), null).status());
    }

    // ---- schema ----

    @Test
    void theSchemaOnlyGainsANullableColumn() {
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'routine_habits' and column_name = 'removed_at'
                """, String.class));
    }

    // ---- helpers ----

    // A routine whose habits were all completed in one finished run.
    private Plan trackedPlan(int habits) throws Exception {
        Plan plan = plan(habits);
        start(plan);
        for (int index = 0; index < habits; index++) {
            finish(plan, index);
        }
        return plan;
    }

    // Habits of one minute each, so the routine's duration always matches and it can start.
    private Plan plan(int habits) throws Exception {
        Account owner = register();
        return withHabits(owner, routine(owner, habits, false), habits);
    }

    private Plan withHabits(Account owner, long routine, int habits) throws Exception {
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

    private Reply delete(Plan plan, int index) throws Exception {
        return send("DELETE", stepPath(plan, index), plan.owner(), null);
    }

    private Reply schedule(Plan plan, int index, String startTime, List<String> days) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("startTime", startTime);
        body.put("repeatDays", days);
        return put(stepPath(plan, index), plan.owner(), body);
    }

    private String stepPath(Plan plan, int index) {
        return "/routines/" + plan.routine() + "/routine-habits/" + plan.steps().get(index);
    }

    private JsonNode steps(Account account, long routine) throws Exception {
        Reply reply = get("/routines/" + routine + "/routine-habits", account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private List<String> habitTitles(Plan plan) throws Exception {
        Reply reply = get("/routines/" + plan.routine() + "/habits", plan.owner());
        assertEquals(200, reply.status(), reply.body());
        List<String> titles = new ArrayList<>();
        json.readTree(reply.body()).forEach(habit -> titles.add(habit.path("title").asString()));
        return titles;
    }

    private List<Integer> activePositions(Plan plan) {
        return jdbc.queryForList(
                "select position from routine_habits where routine_id = ? and removed_at is null order by position",
                Integer.class, plan.routine());
    }

    private String completions(Plan plan) {
        return jdbc.queryForList("""
                select hc.id, hc.habit_id, hc.routine_habit_id, hc.routine_completion_id, hc.status,
                       hc.completed_date, hc.started_at, hc.completed_at
                from habit_completions hc where hc.user_id = ? order by hc.id
                """, plan.owner().id()).toString();
    }

    private String runs(Plan plan) {
        return jdbc.queryForList("""
                select id, completed_date, started_at, completed_at, ended_at, expires_at
                from routine_completions where routine_id = ? order by id
                """, plan.routine()).toString();
    }

    private List<Long> ids(JsonNode list) {
        return ids(list, "id");
    }

    private List<Long> ids(JsonNode list, String field) {
        List<Long> values = new ArrayList<>();
        list.forEach(node -> values.add(node.path(field).asLong()));
        return values;
    }

    private JsonNode start(Plan plan) throws Exception {
        Reply reply = startRoutine(plan.owner(), plan.routine());
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply startRoutine(Account owner, long routine) throws Exception {
        return post("/routine-runs/" + routine + "/start", owner, null);
    }

    // Starts the habit and moves its start back so its timer has already run out.
    private void finish(Plan plan, int index) throws Exception {
        String path = "/routine-runs/" + plan.routine() + "/routine-habits/" + plan.steps().get(index);
        Reply started = post(path + "/start", plan.owner(), null);
        assertEquals(200, started.status(), started.body());
        jdbc.update("""
                update habit_completions set started_at = started_at - interval '2 hours'
                where routine_habit_id = ? and started_at is not null and completed_at is null
                """, plan.steps().get(index));
        Reply reply = post(path + "/complete", plan.owner(), null);
        assertEquals(200, reply.status(), reply.body());
    }

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Manage QA",
                "email", "manage_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Manage " + UUID.randomUUID().toString().substring(0, 6));
        fields.put("description", "Manage activities check");
        fields.put("category", "Morning");
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

    private Reply put(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("PUT", path, account, payload);
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("X-Time-Zone", ZONE);
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
}
