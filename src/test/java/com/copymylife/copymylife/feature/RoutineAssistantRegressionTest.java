package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import com.copymylife.copymylife.service.SemanticSearchClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Personal routine assistant, version 1, end to end against a local *_test database: generating
 * a plan (features A/B), saving it as a new routine, reviewing and applying approved changes (C),
 * measured habit lengths and suggestions (D), the daily summary (E), public routine examples and
 * privacy (F), ownership, authentication and an unavailable AI service. The Python service is a
 * stand-in that ranks by shared words, so what the backend sends can be checked exactly; the real
 * model is covered by ai/tests/test_semantic_search.py. Tracking records are written with SQL the
 * way the run page writes them, with chosen timestamps, because a real run would take real minutes.
 * Needs TEST_DB_URL; the browser part also needs node and Chrome.
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
                "app.upload-dir=target/test-uploads",
                "ai.assistant.timeout-seconds=3",
                "app.tracking.default-time-zone=Asia/Almaty"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutineAssistantRegressionTest {

    private static final String BOUNDARY = "AssistantBoundary";
    private static final String ZONE = "Asia/Almaty";
    private static final ZoneId ZONE_ID = ZoneId.of(ZONE);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final StubAi AI = new StubAi();
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

    @DynamicPropertySource
    static void aiService(DynamicPropertyRegistry registry) {
        registry.add("ai.matching.url", AI::url);
    }

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

    private JsonNode browser;
    private long redirectReviewRoutine;

    @BeforeEach
    void healthyService() {
        AI.mode = "words";
    }

    @AfterAll
    void stopService() {
        AI.server.stop(0);
    }

    // ---- A: generate ----

    @Test
    void theExampleRequestIsPlannedWithoutSavingAnything() throws Exception {
        Account me = register("Planner");
        int routinesBefore = count("select count(*) from routines where user_id = ?", me.id());

        JsonNode plan = generate(me, Map.of(
                "preferences", Map.of("wake", "07:00", "lunch", "13:00", "restFrequency", "FREQUENT"),
                "habits", List.of(habit("Java Study", 120, "STUDY"), habit("Gym", 60, "EXERCISE"))));

        assertEquals("PREVIEW", plan.path("status").asString());
        List<JsonNode> activities = list(plan.path("activities"));
        assertNoOverlap(activities);
        JsonNode lunch = find(activities, "Lunch");
        assertEquals("13:00", lunch.path("start").asString());
        assertTrue(activities.stream().anyMatch(a -> a.path("kind").asString().equals("BREAK")), plan.toString());
        assertTrue(activities.stream().filter(a -> a.path("title").asString().startsWith("Java Study"))
                .allMatch(a -> a.path("minutes").asInt() <= 45), "frequent breaks: focus sessions of 45 min at most");
        assertTrue(plan.path("explanations").toString().contains("frequent breaks"), plan.path("explanations").toString());
        assertEquals("REQUEST", source(plan, "lunch"));
        assertEquals(routinesBefore, count("select count(*) from routines where user_id = ?", me.id()), "a preview saves nothing");
        assertEquals("Study", plan.path("suggestedCategory").asString());
    }

    @Test
    void commitmentsAndAvailableHoursAreNeverCrossed() throws Exception {
        Account me = register("Student");
        JsonNode plan = generate(me, Map.of(
                "preferences", Map.of("wake", "07:00", "sleep", "22:00", "availableTo", "20:00"),
                "commitments", List.of(Map.of("title", "University", "start", "09:00", "end", "14:00")),
                "habits", List.of(habit("Homework", 120, "STUDY"), habit("Reading", 60, "PERSONAL"),
                        habit("Huge project", 600, "OTHER"))));

        List<JsonNode> activities = list(plan.path("activities"));
        assertNoOverlap(activities);
        for (JsonNode activity : activities) {
            if (activity.path("fixed").asBoolean()) {
                assertFalse(activity.path("save").asBoolean(), "commitments are not saved as habits");
                continue;
            }
            int start = minutes(activity.path("start").asString());
            int end = start + activity.path("minutes").asInt();
            assertFalse(start < 14 * 60 && 9 * 60 < end, activity + " crosses University");
            if (!activity.path("kind").asString().equals("MEAL")) {
                assertTrue(end <= 20 * 60, activity + " ends after the available hours");
            }
        }
        assertEquals("Huge project", plan.path("unscheduled").get(0).path("title").asString(), plan.path("unscheduled").toString());
    }

    @Test
    void wrongRequestsAreExplained() throws Exception {
        Account me = register("Careless");
        assertEquals(400, send("POST", "/assistant/plans", me, Map.of("habits", List.of())).status());
        Reply badTime = send("POST", "/assistant/plans", me, Map.of("preferences", Map.of("wake", "25:00"),
                "habits", List.of(habit("Run", 30, "EXERCISE"))));
        assertEquals(400, badTime.status());
        assertTrue(badTime.body().contains("Wake-up time must be a time of day"), badTime.body());
        Reply badKind = send("POST", "/assistant/plans", me, Map.of("habits", List.of(habit("Run", 30, "FLYING"))));
        assertEquals(400, badKind.status());
        Reply sameTimes = send("POST", "/assistant/plans", me, Map.of("preferences", Map.of("wake", "07:00", "sleep", "07:00"),
                "habits", List.of(habit("Run", 30, "EXERCISE"))));
        assertEquals(400, sameTimes.status(), sameTimes.body());
    }

    @Test
    void quickFillRecognisesTheExampleSentence() throws Exception {
        Account me = register("Writer");
        Reply reply = send("POST", "/assistant/parse", me, Map.of("text",
                "I wake up at 07:00, study Java, go to the gym, need lunch at 13:00 and prefer frequent breaks."));
        assertEquals(200, reply.status(), reply.body());
        JsonNode parsed = json.readTree(reply.body());
        assertEquals("07:00", parsed.path("preferences").path("wake").asString());
        assertEquals("FREQUENT", parsed.path("preferences").path("restFrequency").asString());
        assertEquals(List.of("Java Study", "Gym"), texts(parsed.path("habits"), "title"));
        assertTrue(parsed.path("note").asString().contains("not a language model"));
    }

    // ---- A: save, never overwrite ----

    @Test
    void aPlanIsSavedAsANewPrivateRoutineThatCanBeStarted() throws Exception {
        Account me = register("Saver");
        long existing = routine(me, "Existing " + w("keep"), 60, false);
        habitWithTime(me, existing, "Old habit", 60, null);
        String before = jdbc.queryForObject("select title || ':' || duration_minutes from routines where id = ?", String.class, existing);

        Reply saved = send("POST", "/assistant/plans/save", me, Map.of(
                "title", "Planned day", "category", "Study", "days", List.of(),
                "activities", List.of(
                        Map.of("title", "Breakfast", "minutes", 30, "startTime", "07:30"),
                        Map.of("title", "Java Study", "minutes", 45, "startTime", "08:05"),
                        Map.of("title", "Break", "minutes", 10, "startTime", "08:50"))));
        assertEquals(200, saved.status(), saved.body());
        JsonNode result = json.readTree(saved.body());
        long id = result.path("routineId").asLong();
        assertNotEquals(existing, id);
        assertEquals(85, result.path("durationMinutes").asInt());
        assertFalse(jdbc.queryForObject("select is_public from routines where id = ?", Boolean.class, id), "private unless asked");
        assertEquals(List.of("07:30:00", "08:05:00", "08:50:00"),
                jdbc.queryForList("select start_time::text from routine_habits where routine_id = ? order by position", String.class, id));
        assertEquals("MON,TUE,WED,THU,FRI,SAT,SUN",
                jdbc.queryForObject("select repeat_days from routine_habits where routine_id = ? limit 1", String.class, id));
        assertEquals(before, jdbc.queryForObject("select title || ':' || duration_minutes from routines where id = ?", String.class, existing),
                "the existing routine is not touched");

        Reply started = send("POST", "/routine-runs/" + id + "/start", me, null);
        assertEquals(200, started.status(), "a saved plan is complete enough to run: " + started.body());
        send("POST", "/routine-runs/" + id + "/stop", me, null);
    }

    @Test
    void anOverlappingOrInvalidPlanIsRefusedWholly() throws Exception {
        Account me = register("Overlapper");
        int before = count("select count(*) from routines where user_id = ?", me.id());
        Reply overlap = send("POST", "/assistant/plans/save", me, Map.of("title", "Clash", "category", "Study",
                "activities", List.of(Map.of("title", "A", "minutes", 60, "startTime", "08:00"),
                        Map.of("title", "B", "minutes", 30, "startTime", "08:30"))));
        assertEquals(400, overlap.status(), overlap.body());
        assertEquals(2, json.readTree(overlap.body()).path("activity").asInt());
        assertTrue(overlap.body().contains("Overlaps with \\\"A\\\"") || overlap.body().contains("Overlaps with \"A\""), overlap.body());
        assertEquals(400, send("POST", "/assistant/plans/save", me, Map.of("title", "", "category", "Study",
                "activities", List.of(Map.of("title", "A", "minutes", 60)))).status());
        assertEquals(400, send("POST", "/assistant/plans/save", me, Map.of("title", "Empty", "category", "Study",
                "activities", List.of())).status());
        assertEquals(before, count("select count(*) from routines where user_id = ?", me.id()), "nothing is kept");
    }

    // ---- B: preferences and history ----

    @Test
    void savedPreferencesComeFirstAndTheRequestCanOverrideThem() throws Exception {
        Account me = register("Late luncher");
        Reply saved = send("PUT", "/assistant/preferences", me, Map.of("lunch", "14:00", "restFrequency", "RARE",
                "commitments", List.of(Map.of("title", "Work", "start", "09:00", "end", "13:00"))));
        assertEquals(200, saved.status(), saved.body());
        assertEquals("14:00", json.readTree(saved.body()).path("saved").path("lunch").asString());

        JsonNode plan = generate(me, Map.of("habits", List.of(habit("Deep work", 180, "WORK"))));
        assertEquals("14:00", find(list(plan.path("activities")), "Lunch").path("start").asString());
        assertEquals("SAVED", source(plan, "lunch"));
        assertTrue(list(plan.path("activities")).stream().anyMatch(a -> a.path("title").asString().equals("Work")),
                "saved commitments are used when the request sends none");
        assertEquals(2, list(plan.path("activities")).stream().filter(a -> a.path("title").asString().startsWith("Deep work")).count(),
                "long focus sessions: 180 min in two sessions");

        JsonNode overridden = generate(me, Map.of("preferences", Map.of("lunch", "12:30"), "commitments", List.of(),
                "habits", List.of(habit("Deep work", 60, "WORK"))));
        assertEquals("12:30", find(list(overridden.path("activities")), "Lunch").path("start").asString());

        assertEquals(200, send("PUT", "/assistant/preferences", me, Map.of("lunch", "15:00")).status());
        JsonNode changed = generate(me, Map.of("habits", List.of(habit("Deep work", 60, "WORK"))));
        assertEquals("15:00", find(list(changed.path("activities")), "Lunch").path("start").asString(), "changed preferences count at once");
    }

    @Test
    void twoUsersGetDifferentDaysFromTheSameRequest() throws Exception {
        Account frequent = register("Frequent breaker");
        Account focused = register("Long focus");
        send("PUT", "/assistant/preferences", frequent, Map.of("restFrequency", "FREQUENT", "lunch", "12:00"));
        send("PUT", "/assistant/preferences", focused, Map.of("restFrequency", "RARE", "lunch", "14:00"));
        Map<String, Object> request = Map.of("meals", List.of("lunch"), "habits", List.of(habit("Study", 180, "STUDY")));

        List<JsonNode> a = list(generate(frequent, request).path("activities"));
        List<JsonNode> b = list(generate(focused, request).path("activities"));
        assertEquals("12:00", find(a, "Lunch").path("start").asString());
        assertEquals("14:00", find(b, "Lunch").path("start").asString());
        assertTrue(a.stream().filter(x -> x.path("kind").asString().equals("BREAK")).count()
                > b.stream().filter(x -> x.path("kind").asString().equals("BREAK")).count());
    }

    @Test
    void trackedLunchTimesFillTheGapOnlyWhenRepeated() throws Exception {
        Account me = register("Habitual");
        long routine = routine(me, "Midday", 30, false);
        long lunch = habitWithTime(me, routine, "Lunch", 30, "13:00");
        LocalDate today = LocalDate.now(ZONE_ID);
        record(me, routine, lunch, today.minusDays(3), "14:10", 30, "COMPLETED", null);
        record(me, routine, lunch, today.minusDays(2), "14:05", 30, "COMPLETED", null);

        JsonNode prefs = get("/assistant/preferences", me);
        assertFalse(prefs.path("history").path("lunch").path("used").asBoolean(), "two runs are not enough");
        assertEquals("DEFAULT", source(generate(me, Map.of("habits", List.of(habit("Work", 60, "WORK")))), "lunch"));

        record(me, routine, lunch, today.minusDays(1), "14:15", 30, "COMPLETED", null);
        JsonNode plan = generate(me, Map.of("habits", List.of(habit("Work", 60, "WORK"))));
        assertEquals("HISTORY", source(plan, "lunch"));
        assertEquals("14:10", find(list(plan.path("activities")), "Lunch").path("start").asString());
        assertTrue(plan.path("explanations").toString().contains("you usually start lunch around 14:10 (3 completed runs)"));

        send("PUT", "/assistant/preferences", me, Map.of("lunch", "12:00"));
        assertEquals("12:00", find(list(generate(me, Map.of("habits", List.of(habit("Work", 60, "WORK")))).path("activities")), "Lunch")
                .path("start").asString(), "what the user chose wins over what was seen");
    }

    // ---- C: review and approval ----

    @Test
    void reviewFindsMissingBreaksAndAppliesOnlyApprovedChanges() throws Exception {
        Account me = register("Reviewer");
        send("PUT", "/assistant/preferences", me, Map.of("restFrequency", "NORMAL"));
        long routine = routine(me, "Study block", 180, false);
        long a = habitWithTime(me, routine, "Java Study", 60, "08:00");
        long b = habitWithTime(me, routine, "Math homework", 60, "09:00");
        habitWithTime(me, routine, "English lesson", 60, "10:00");

        JsonNode review = get("/assistant/routines/" + routine + "/review", me);
        JsonNode issue = issue(review, "NO_BREAK");
        assertTrue(issue.path("evidence").asString().contains("without a planned break"), issue.toString());
        assertEquals("PREFERENCE", issue.path("basis").asString());
        List<JsonNode> changes = changesOf(review, issue);
        assertFalse(changes.isEmpty());
        assertEquals("08:00:00", startOf(a), "a review changes nothing");

        // Only the break, without moving the next activity: refused as an overlap, nothing kept.
        List<JsonNode> onlyAdd = changes.stream().filter(c -> c.path("type").asString().equals("ADD")).toList();
        JsonNode preview = post("/assistant/routines/" + routine + "/changes/preview", me, Map.of("changes", onlyAdd));
        assertFalse(preview.path("conflicts").isEmpty(), preview.toString());
        Reply refused = send("POST", "/assistant/routines/" + routine + "/changes/apply", me, Map.of("changes", onlyAdd));
        assertEquals(400, refused.status(), refused.body());
        assertTrue(refused.body().contains("overlap"), refused.body());
        assertEquals(3, count("select count(*) from routine_habits where routine_id = ?", routine));

        JsonNode together = post("/assistant/routines/" + routine + "/changes/preview", me, Map.of("changes", changes));
        assertTrue(together.path("conflicts").isEmpty(), together.toString());
        JsonNode applied = post("/assistant/routines/" + routine + "/changes/apply", me, Map.of("changes", changes));
        assertEquals(180, applied.path("previousDurationMinutes").asInt());
        assertEquals(190, applied.path("durationMinutes").asInt(), "the routine grows by the added break");
        assertEquals(4, count("select count(*) from routine_habits where routine_id = ?", routine));
        assertEquals("09:10:00", startOf(b));
        assertEquals(0, count("select count(*) from assistant_decisions d join routine_habits rh on rh.id = d.routine_habit_id"
                + " where rh.routine_id = ?", routine), "moves and breaks are not length decisions");
        assertEquals(200, send("POST", "/routine-runs/" + routine + "/start", me, null).status(), "still a routine that can be started");
        send("POST", "/routine-runs/" + routine + "/stop", me, null);
    }

    @Test
    void aMealConflictAndUntimedActivitiesAreReviewed() throws Exception {
        Account me = register("Hungry");
        send("PUT", "/assistant/preferences", me, Map.of("lunch", "14:00"));
        long timed = routine(me, "Afternoon", 90, false);
        habitWithTime(me, timed, "Java Study", 60, "13:30");
        JsonNode conflict = issue(get("/assistant/routines/" + timed + "/review", me), "MEAL_CONFLICT");
        assertTrue(conflict.path("evidence").asString().contains("Your preferred lunch time (14:00, your saved preference) conflicts with Java Study"),
                conflict.toString());

        long untimed = routine(me, "No times", 120, false);
        habitWithTime(me, untimed, "Reading", 30, null);
        habitWithTime(me, untimed, "Python practice", 60, null);
        JsonNode review = get("/assistant/routines/" + untimed + "/review", me);
        JsonNode issue = issue(review, "UNTIMED");
        List<JsonNode> changes = changesOf(review, issue);
        JsonNode applied = post("/assistant/routines/" + untimed + "/changes/apply", me, Map.of("changes", changes));
        assertTrue(applied.path("activities").toString().contains("\"start\":\""), applied.toString());
        assertEquals(0, count("select count(*) from routine_habits where routine_id = ? and start_time is null and removed_at is null", untimed));
        assertTrue(review.path("notChecked").toString().contains("no activity has a start time"));
    }

    @Test
    void goalsAreCheckedWithTheModelOrSaidToBeUnchecked() throws Exception {
        Account me = register("Goal setter");
        profile(me, w("spanish") + ", " + w("fitness"));
        long routine = routine(me, "Goals", 60, false);
        habitWithTime(me, routine, "Morning " + w("fitness"), 60, "07:00");

        JsonNode review = get("/assistant/routines/" + routine + "/review", me);
        List<JsonNode> goals = list(review.path("issues")).stream().filter(i -> i.path("code").asString().equals("GOAL")).toList();
        assertEquals(1, goals.size(), review.path("issues").toString());
        assertTrue(goals.get(0).path("evidence").asString().contains(w("spanish")));
        assertEquals("MODEL", goals.get(0).path("basis").asString());
        assertTrue(AI.lastBody.contains(w("spanish")) && AI.lastBody.contains("Morning " + w("fitness")));

        AI.mode = "error";
        JsonNode without = get("/assistant/routines/" + routine + "/review", me);
        assertTrue(without.path("notChecked").toString().contains("the AI service is not available right now"), without.toString());
        assertTrue(list(without.path("issues")).stream().noneMatch(i -> i.path("code").asString().equals("GOAL")));
    }

    @Test
    void applyingIsRefusedWhileTheRoutineRuns() throws Exception {
        Account me = register("Runner");
        long routine = routine(me, "Running now", 60, false);
        long link = habitWithTime(me, routine, "Stretch", 60, "07:00");
        assertEquals(200, send("POST", "/routine-runs/" + routine + "/start", me, null).status());
        Reply refused = send("POST", "/assistant/routines/" + routine + "/changes/apply", me,
                Map.of("changes", List.of(Map.of("type", "MOVE", "activityId", link, "toStart", "08:00"))));
        assertEquals(409, refused.status(), refused.body());
        assertTrue(get("/assistant/routines/" + routine + "/review", me).path("running").asBoolean());
        send("POST", "/routine-runs/" + routine + "/stop", me, null);
        assertEquals("07:00:00", startOf(link));
    }

    // ---- ownership and privacy ----

    @Test
    void anotherMembersRoutineCanNeitherBeReviewedNorChanged() throws Exception {
        Account owner = register("Owner");
        Account stranger = register("Stranger");
        long open = routine(owner, "Public " + w("open"), 60, true);
        long link = habitWithTime(owner, open, "Owner habit", 60, "07:00");
        long hidden = routine(owner, "Private " + w("closed"), 60, false);

        for (long routine : List.of(open, hidden, 999_999_999L)) {
            Reply review = send("GET", "/assistant/routines/" + routine + "/review", stranger, null);
            assertEquals(404, review.status(), review.body());
            assertFalse(review.body().contains(w("closed")));
            assertEquals(404, send("POST", "/assistant/routines/" + routine + "/changes/apply", stranger,
                    Map.of("changes", List.of(Map.of("type", "MOVE", "activityId", link, "toStart", "09:00")))).status());
            assertEquals(404, send("GET", "/assistant/insights/durations?routineId=" + routine, stranger, null).status());
        }
        assertEquals(404, send("POST", "/assistant/insights/durations/" + link + "/keep", stranger, Map.of("suggestedMinutes", 45)).status());
        assertEquals("07:00:00", startOf(link));

        // An activity of another routine can't be smuggled into one's own.
        long mine = routine(stranger, "Mine", 30, false);
        habitWithTime(stranger, mine, "Mine habit", 30, "07:00");
        Reply smuggled = send("POST", "/assistant/routines/" + mine + "/changes/apply", stranger,
                Map.of("changes", List.of(Map.of("type", "MOVE", "activityId", link, "toStart", "09:00"))));
        assertEquals(400, smuggled.status(), smuggled.body());
        assertEquals("07:00:00", startOf(link));
    }

    @Test
    void examplesComeOnlyFromRoutinesAnyoneMaySee() throws Exception {
        Account me = register("Inspired");
        profile(me, w("lantern"));
        long own = routine(me, "Own " + w("lantern") + " " + w("ownplan"), 30, false);
        habitWithTime(me, own, w("lantern") + " walk", 30, null);

        Account author = register("Author");
        long open = routine(author, "Shared " + w("lantern"), 30, true);
        habitWithTime(author, open, w("lantern") + " stroll", 30, "18:00");
        jdbc.update("update routines set average_rating = 4.5, rating_count = 2 where id = ?", open);
        long secret = routine(author, "Secret " + w("lantern") + " " + w("secretplan"), 30, false);
        habitWithTime(author, secret, w("lantern") + " " + w("secretstep"), 30, null);
        long hiddenByAdmin = routine(author, "Hidden " + w("lantern") + " " + w("hiddenplan"), 30, true);
        jdbc.update("update routines set hidden_at = now() where id = ?", hiddenByAdmin);
        long deleted = routine(author, "Deleted " + w("lantern") + " " + w("deletedplan"), 30, true);
        jdbc.update("update routines set deleted = true where id = ?", deleted);
        Account banned = register("Banned author");
        routine(banned, "Banned " + w("lantern") + " " + w("bannedplan"), 30, true);
        jdbc.update("update users set banned = true where id = ?", banned.id());
        Account hiddenProfile = register("Hidden profile");
        routine(hiddenProfile, "Hidden profile " + w("lantern") + " " + w("profileplan"), 30, true);
        jdbc.update("update users set profile_hidden_at = now() where id = ?", hiddenProfile.id());

        JsonNode plan = generate(me, Map.of("habits", List.of(habit(w("lantern") + " walk", 30, "EXERCISE"))));
        JsonNode examples = plan.path("examples");
        assertEquals("OK", examples.path("status").asString(), examples.toString());
        JsonNode first = examples.path("items").get(0);
        assertEquals(open, first.path("routineId").asLong());
        assertEquals(4.5, first.path("averageRating").asDouble());
        assertEquals(w("lantern") + " stroll", first.path("activities").get(0).path("title").asString());

        String sent = AI.lastBody;
        assertTrue(sent.contains("Shared " + w("lantern")));
        for (String word : List.of("secretplan", "secretstep", "hiddenplan", "deletedplan", "bannedplan", "profileplan", "ownplan")) {
            assertFalse(sent.contains(w(word)), word + " was sent to the AI service");
            assertFalse(plan.toString().contains(w(word)), word + " was shown");
        }
        for (String field : List.of("@audit.test", "\"email\"", "\"password\"", "\"notes\"")) {
            assertFalse(sent.contains(field), field + " was sent");
        }
        assertFalse(list(plan.path("activities")).stream().anyMatch(a -> a.path("title").asString().contains("stroll")),
                "an example is an idea, not copied into the plan");
    }

    @Test
    void anUnavailableAiServiceLeavesEverythingElseWorking() throws Exception {
        Account me = register("Offline");
        profile(me, w("offline"));
        long routine = routine(me, "Offline routine", 60, false);
        habitWithTime(me, routine, "Offline habit", 60, "07:00");

        for (String failure : List.of("error", "garbage", "slow")) {
            AI.mode = failure;
            JsonNode plan = generate(me, Map.of("habits", List.of(habit("Read", 30, "PERSONAL"))));
            assertEquals("UNAVAILABLE", plan.path("examples").path("status").asString(), failure + ": " + plan.path("examples"));
            assertFalse(plan.path("activities").isEmpty(), "the plan does not depend on the AI service");
            assertEquals(200, send("GET", "/assistant/routines/" + routine + "/review", me, null).status());
        }
        assertEquals(200, send("GET", "/assistant/summary", me, null).status());
        assertEquals(200, send("GET", "/assistant/insights/durations", me, null).status());
        assertEquals(200, send("GET", "/users/me", me, null).status());
        assertEquals(200, send("GET", "/routines/public", null, null).status());

        SemanticSearchClient closed = new SemanticSearchClient(json, "http://127.0.0.1:1", "", 2);
        assertThrows(SemanticSearchClient.Unavailable.class,
                () -> closed.rank(List.of("x"), List.of(new SemanticSearchClient.Item(1, "x")), 1, 0));
        AI.mode = "words";
        SemanticSearchClient withToken = new SemanticSearchClient(json, AI.url() + "/", "local-secret", 2);
        withToken.rank(List.of("lantern"), List.of(new SemanticSearchClient.Item(1, "lantern")), 1, 0);
        assertEquals("local-secret", AI.lastToken);
        AI.mode = "inject";
        List<List<SemanticSearchClient.Scored>> ranked = withToken.rank(List.of("lantern"),
                List.of(new SemanticSearchClient.Item(1, "lantern")), 5, 0);
        assertEquals(List.of(1L), ranked.get(0).stream().map(SemanticSearchClient.Scored::id).toList(),
                "ids that were not sent and impossible scores are dropped");
    }

    @Test
    void onlySignedInMembersCanUseTheAssistant() throws Exception {
        int calls = AI.calls.get();
        for (String[] call : new String[][]{{"GET", "/assistant/preferences"}, {"POST", "/assistant/plans"},
                {"POST", "/assistant/plans/save"}, {"GET", "/assistant/summary"}, {"GET", "/assistant/insights/durations"},
                {"GET", "/assistant/routines/1/review"}, {"PUT", "/assistant/runs/1/habits/1/actual-minutes"}}) {
            Reply guest = send(call[0], call[1], null, call[0].equals("GET") ? null : Map.of());
            assertTrue(guest.status() == 401 || guest.status() == 403, call[1] + " " + guest.status());
            Reply forged = raw(call[0], call[1], "Bearer not-a-token");
            assertTrue(forged.status() == 401 || forged.status() == 403, call[1] + " " + forged.status());
        }
        Account admin = register("Admin caller");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        Reply refused = send("POST", "/assistant/plans", admin, Map.of("habits", List.of(habit("Read", 30, "PERSONAL"))));
        assertEquals(403, refused.status(), refused.body());
        assertEquals(calls, AI.calls.get(), "nothing reached the AI service");
    }

    // ---- D: durations ----

    @Test
    void repeatedOverrunsBecomeASuggestionTheUserDecides() throws Exception {
        Account me = register("Overrunner");
        long routine = routine(me, "Java evening", 30, false);
        long java = habitWithTime(me, routine, "Java Study", 30, "18:00");
        LocalDate today = LocalDate.now(ZONE_ID);

        assertEquals("NO_DATA", insight(me, routine, java).path("status").asString());
        record(me, routine, java, today.minusDays(5), "18:00", 45, "COMPLETED", null);
        assertEquals("NOT_ENOUGH_DATA", insight(me, routine, java).path("status").asString(), "one overrun is no proof");
        record(me, routine, java, today.minusDays(4), "18:00", 44, "COMPLETED", null);
        record(me, routine, java, today.minusDays(3), "18:00", 46, "COMPLETED", null);

        JsonNode suggestion = insight(me, routine, java);
        assertEquals("OFTEN_LONGER", suggestion.path("status").asString(), suggestion.toString());
        assertEquals(45, suggestion.path("suggestedMinutes").asInt());
        assertTrue(suggestion.path("message").asString().startsWith("You often spend about 45 minutes on Java Study, although 30 minutes is scheduled"));
        assertEquals(30, minutesOf(java), "nothing changes by itself");
        assertTrue(changesOf(get("/assistant/routines/" + routine + "/review", me),
                issue(get("/assistant/routines/" + routine + "/review", me), "DURATION")).stream()
                .anyMatch(c -> c.path("type").asString().equals("RESIZE") && c.path("toMinutes").asInt() == 45));

        JsonNode kept = post("/assistant/insights/durations/" + java + "/keep", me, Map.of("suggestedMinutes", 45));
        assertEquals("KEPT_BY_YOU", kept.path("insight").path("status").asString());
        assertEquals(30, minutesOf(java));
        assertEquals(1, count("select count(*) from assistant_decisions where routine_habit_id = ? and decision = 'DECLINED'", java));
        // As if the user had decided on the evening of the third run; the runs below come after it.
        jdbc.update("update assistant_decisions set decided_at = ? where routine_habit_id = ?",
                Timestamp.from(today.minusDays(3).atTime(23, 0).atZone(ZONE_ID).toInstant()), java);

        // New measured runs after the decision bring the question back.
        record(me, routine, java, today.minusDays(2), "18:00", 45, "COMPLETED", null);
        record(me, routine, java, today.minusDays(1), "18:00", 45, "COMPLETED", null);
        assertEquals("KEPT_BY_YOU", insight(me, routine, java).path("status").asString());
        record(me, routine, java, today, "18:00", 47, "COMPLETED", null);
        assertEquals("OFTEN_LONGER", insight(me, routine, java).path("status").asString(), "historical data updates the suggestion");

        JsonNode accepted = post("/assistant/routines/" + routine + "/changes/apply", me,
                Map.of("changes", List.of(Map.of("type", "RESIZE", "activityId", java, "toMinutes", 45))));
        assertEquals(45, accepted.path("durationMinutes").asInt());
        assertEquals(45, minutesOf(java));
        assertEquals("ON_PLAN", insight(me, routine, java).path("status").asString());
        assertEquals(1, count("select count(*) from assistant_decisions where routine_habit_id = ? and decision = 'ACCEPTED'", java));
    }

    @Test
    void habitsOnlyMarkedDoneHaveNoMeasuredLength() throws Exception {
        Account me = register("Marker");
        long routine = routine(me, "Marked", 20, false);
        long reading = habitWithTime(me, routine, "Reading", 20, null);
        LocalDate today = LocalDate.now(ZONE_ID);
        for (int day = 1; day <= 4; day++) {
            markedDone(me, routine, reading, today.minusDays(day));
        }
        JsonNode insight = insight(me, routine, reading);
        assertEquals("NO_DATA", insight.path("status").asString(), insight.toString());
        assertEquals(0, insight.path("measuredRuns").asInt());
        assertTrue(insight.path("usualMinutes").isNull());
    }

    @Test
    void theUserCanEnterTheActualTimeOfACompletedHabit() throws Exception {
        Account me = register("Reporter");
        long routine = routine(me, "Real run", 1, false);
        long link = habitWithTime(me, routine, "Quick stretch", 1, null);
        JsonNode run = post("/routine-runs/" + routine + "/start", me, null);
        long runId = run.path("runId").asLong();
        post("/routine-runs/" + routine + "/routine-habits/" + link + "/start", me, null);

        Reply early = send("PUT", "/assistant/runs/" + runId + "/habits/" + link + "/actual-minutes", me, Map.of("minutes", 5));
        assertEquals(400, early.status(), "only after completing: " + early.body());
        jdbc.update("update habit_completions set started_at = started_at - interval '3 minutes' where routine_completion_id = ?", runId);
        assertEquals(200, send("POST", "/routine-runs/" + routine + "/routine-habits/" + link + "/complete", me, null).status());
        assertEquals(1, jdbc.queryForObject("select planned_minutes from habit_completions where routine_completion_id = ?", Integer.class, runId),
                "the planned length is kept with the record");

        JsonNode saved = put("/assistant/runs/" + runId + "/habits/" + link + "/actual-minutes", me, Map.of("minutes", 12));
        assertEquals(12, saved.path("reportedMinutes").asInt());
        assertEquals(3, saved.path("timerMinutes").asInt(), "the timer reading stays as measured");
        assertEquals(12, get("/routine-runs/" + routine, me).path("habits").get(0).path("reportedMinutes").asInt());

        assertEquals(400, send("PUT", "/assistant/runs/" + runId + "/habits/" + link + "/actual-minutes", me, Map.of("minutes", 0)).status());
        Account stranger = register("Curious");
        assertEquals(404, send("PUT", "/assistant/runs/" + runId + "/habits/" + link + "/actual-minutes", stranger, Map.of("minutes", 99)).status());
        assertEquals(12, jdbc.queryForObject("select reported_minutes from habit_completions where routine_completion_id = ?", Integer.class, runId));

        JsonNode summary = get("/assistant/summary", me);
        JsonNode habit = summary.path("runs").get(0).path("habits").get(0);
        assertEquals("COMPLETED", habit.path("status").asString());
        assertEquals(12, habit.path("reportedMinutes").asInt());
        assertTrue(summary.path("highlights").toString().contains("Quick stretch exceeded its planned duration by 11 minutes (12 min as you entered"),
                summary.path("highlights").toString());

        assertEquals(200, send("PUT", "/assistant/runs/" + runId + "/habits/" + link + "/actual-minutes", me, new HashMap<>()).status());
        assertTrue(jdbc.queryForObject("select reported_minutes from habit_completions where routine_completion_id = ?", Integer.class, runId) == null,
                "an entered length can be cleared");
    }

    // ---- E: daily summary ----

    @Test
    void theDailySummaryUsesOnlyRecordedData() throws Exception {
        Account me = register("Summariser");
        long routine = routine(me, "Weekday", 150, false);
        long exercise = habitWithTime(me, routine, "Morning exercise", 20, "07:00");
        long java = habitWithTime(me, routine, "Java Study", 30, "08:00");
        long reading = habitWithTime(me, routine, "Reading", 20, "09:00");
        long emails = habitWithTime(me, routine, "Emails", 20, "10:00");
        habitWithTime(me, routine, "Guitar", 60, "11:00");
        LocalDate day = LocalDate.now(ZONE_ID).minusDays(1);
        long run = record(me, routine, exercise, day, "07:02", 21, "COMPLETED", null);
        recordInRun(me, run, java, day, "08:00", 45, "COMPLETED", null);
        recordInRun(me, run, reading, day, null, null, "SKIPPED", null);
        recordInRun(me, run, emails, day, "10:00", null, "COMPLETED", null);

        JsonNode summary = get("/assistant/summary?date=" + day, me);
        assertEquals("OK", summary.path("status").asString(), summary.toString());
        assertEquals("On " + day + " you completed 3 out of 5 habits (60%).", summary.path("highlights").get(0).asString());
        assertEquals(1, summary.path("counts").path("skipped").asInt());
        assertEquals(1, summary.path("counts").path("notStarted").asInt(), "Guitar was planned and not started");
        assertTrue(summary.path("highlights").toString().contains("Java Study exceeded its planned duration by 15 minutes"));
        assertTrue(summary.path("highlights").toString().contains("You completed Morning exercise as scheduled."));
        assertTrue(summary.path("timing").asString().contains("Lengths are known for 2 of 3 completed habits"), summary.path("timing").asString());
        assertTrue(summary.path("timing").asString().contains("no length is assumed"));
        JsonNode emailsLine = list(summary.path("runs").get(0).path("habits")).stream()
                .filter(h -> h.path("title").asString().equals("Emails")).findFirst().orElseThrow();
        assertTrue(emailsLine.path("timerMinutes").isNull() && emailsLine.path("reportedMinutes").isNull());

        Account other = register("Other summariser");
        assertEquals("NO_RUNS", get("/assistant/summary?date=" + day, other).path("status").asString(), "another member's runs are not counted");
        assertEquals(400, send("GET", "/assistant/summary?date=" + LocalDate.now(ZONE_ID).plusDays(2), me, null).status());
    }

    // ---- one AI section in the profile ----

    @Test
    void theOldAiPagesRedirectToTheirTabInTheProfile() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/assistant.html", "/profile.html#ai-generate");
        expected.put("/assistant.html?review=42", "/profile.html?review=42#ai-review");
        expected.put("/assistant.html?review=%3Cscript%3E", "/profile.html#ai-generate");
        expected.put("/lifestyle-matches.html", "/profile.html#ai-matches");

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            HttpResponse<String> reply = http.send(HttpRequest.newBuilder(uri(entry.getKey())).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(302, reply.statusCode(), entry.getKey());
            assertEquals(entry.getValue(), reply.headers().firstValue("Location").orElse(""), entry.getKey());
        }

        // The modules the profile draws them with are served; the standalone page files are gone.
        for (String file : List.of("/assistant.js", "/assistant.css", "/lifestyle-matches.js", "/lifestyle-matches.css")) {
            assertEquals(200, send("GET", file, null, null).status(), file);
        }
        assertFalse(Files.exists(Path.of("src/main/resources/static/assistant.html")));
        assertFalse(Files.exists(Path.of("src/main/resources/static/lifestyle-matches.html")));

        String profile = send("GET", "/profile.html", null, null).body();
        int progress = profile.indexOf("id=\"progressNav\"");
        int ai = profile.indexOf("id=\"aiAssistantNav\"");
        int friends = profile.indexOf("id=\"friendsNav\"");
        assertTrue(progress > 0 && progress < ai && ai < friends, "AI Assistant sits between Progress and Friends");
        assertFalse(profile.contains("<iframe"), "no frame in the profile");

        String explore = send("GET", "/Explore.html", null, null).body();
        assertEquals(1, explore.split("id=\"aiAssistantButton\"", -1).length - 1);
        assertFalse(explore.contains("assistant.html") || explore.contains("lifestyle-matches.html"));
        for (String page : List.of("/routine.html", "/routine-run.html", "/main.html", "/site-header.js", "/routine-run.js")) {
            String body = send("GET", page, null, null).body();
            assertFalse(body.contains("assistant.html") || body.contains("lifestyle-matches.html"), page + " still links an old AI page");
        }
    }

    @Test
    void browserKeepsEveryAiFeatureInOneProfileSection() {
        JsonNode navigation = page("navigation");
        List<String> order = texts(navigation.path("order"), null);
        assertEquals(order.indexOf("progressNav") + 1, order.indexOf("aiAssistantNav"), order.toString());
        assertEquals(order.indexOf("aiAssistantNav") + 1, order.indexOf("friendsNav"), order.toString());
        assertTrue(navigation.path("openedByAddress").asBoolean(), navigation.toString());
        assertEquals("aiAssistantNav", navigation.path("activeNav").asString());
        assertTrue(navigation.path("calendarShown").asBoolean() && navigation.path("backToAi").asBoolean(), navigation.toString());
        assertTrue(navigation.path("drawnOnce").asBoolean(), "the section is drawn once");
        assertEquals(1, navigation.path("plansPerClick").asInt(), "one request per click after leaving and coming back");

        assertEquals("#ai-summary", navigation.path("hashAfterTabs").asString());
        assertEquals("#ai-review", navigation.path("backHash").asString());
        assertTrue(navigation.path("backTab").asBoolean(), "Back shows the previous tab");
        assertEquals("#ai-generate", navigation.path("back2Hash").asString());
        assertEquals("#calendar", navigation.path("back3Hash").asString());
        assertTrue(navigation.path("back3Calendar").asBoolean());
        assertEquals("#ai-generate", navigation.path("forwardHash").asString());
        assertTrue(navigation.path("forwardAi").asBoolean(), "Forward returns to the AI section");
        navigation.path("otherSections").properties().forEach(entry ->
                assertTrue(entry.getValue().asBoolean(), entry.getKey() + " no longer opens its section"));
        assertEquals(8, navigation.path("otherSections").size());
        assertTrue(navigation.path("aiAgain").asBoolean());

        JsonNode redirects = page("redirects");
        assertEquals("/profile.html#ai-generate", redirects.path("assistant").path("page").asString(), redirects.toString());
        assertEquals("/profile.html?review=" + redirectReviewRoutine + "#ai-review", redirects.path("review").path("page").asString());
        assertEquals("/profile.html#ai-matches", redirects.path("matches").path("page").asString());
        redirects.properties().forEach(entry -> assertTrue(entry.getValue().path("shown").asBoolean()
                && entry.getValue().path("tab").asBoolean(), entry.getKey() + " " + entry.getValue()));

        JsonNode explore = page("explore");
        assertEquals(1, explore.path("buttons").asInt());
        assertEquals(0, explore.path("oldLinks").asInt(), explore.toString());
        assertEquals("AI Assistant", explore.path("text").asString());
        assertTrue(explore.path("icon").asBoolean());
        assertEquals("/profile.html#ai-generate", explore.path("after").path("page").asString(), explore.toString());
        assertTrue(explore.path("after").path("shown").asBoolean());
    }

    // ---- browser ----

    @Test
    void browserGeneratesPreviewsAndSavesARoutine() {
        JsonNode generate = page("generate");
        assertEquals(true, generate.path("filled").asBoolean(), generate.toString());
        assertTrue(generate.path("loadingSeen").asBoolean(), "a loading state is shown");
        assertTrue(generate.path("slots").asInt() >= 5, generate.toString());
        assertEquals("13:00", generate.path("lunch").asString());
        assertTrue(generate.path("why").asInt() > 2);
        assertEquals("OK", generate.path("examples").asString(), generate.toString());
        assertTrue(generate.path("savedLink").asString().startsWith("routine.html?id="), generate.toString());
        assertTrue(generate.path("savedRoutine").asBoolean());
    }

    @Test
    void browserReviewsPreviewsAndAppliesApprovedChanges() {
        JsonNode review = page("review");
        assertTrue(review.path("issues").toString().contains("NO_BREAK"), review.toString());
        assertTrue(review.path("previewShown").asBoolean(), review.toString());
        assertTrue(review.path("appliedMessage").asString().startsWith("Applied"), review.toString());
        assertTrue(review.path("breakSaved").asBoolean(), review.toString());
        assertTrue(review.path("ownerLink").asBoolean(), "the routine page leads its owner to the review");
    }

    @Test
    void browserShowsSuggestionsSummaryAndActualTimeEntry() {
        JsonNode durations = page("durations");
        assertTrue(durations.path("longer").asBoolean(), durations.toString());
        assertTrue(durations.path("keptAfterClick").asBoolean(), durations.toString());
        JsonNode summary = page("summary");
        assertTrue(summary.path("highlights").asString().contains("completed"), summary.toString());
        assertEquals(17, summary.path("enteredSaved").asInt(), summary.toString());
    }

    @Test
    void browserShowsEmptyErrorAndGuestStates() {
        JsonNode states = page("states");
        assertTrue(states.path("guest").asBoolean(), states.toString());
        assertTrue(states.path("summaryEmpty").asBoolean(), states.toString());
        assertTrue(states.path("durationsEmpty").asBoolean(), states.toString());
        assertTrue(states.path("generateError").asString().length() > 5, states.toString());
        assertEquals("UNAVAILABLE", states.path("examplesUnavailable").asString(), states.toString());
        assertFalse(states.path("markupInjected").asBoolean(), "names are shown as text");
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(12, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(entry.getKey() + " has controls outside the screen: " + view.path("outside").asString());
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
    }

    @Test
    void browserNeverFailsOrInterrupts() {
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    @BeforeAll
    void createBrowserContent() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account viewer = register("Browser " + TRICKY);
        profile(viewer, w("browsergoal"));
        long routine = routine(viewer, "Browser block", 180, false);
        habitWithTime(viewer, routine, "Java Study", 60, "08:00");
        habitWithTime(viewer, routine, "Math homework", 60, "09:00");
        habitWithTime(viewer, routine, "English lesson", 60, "10:00");
        long evening = routine(viewer, "Browser evening", 30, false);
        long java = habitWithTime(viewer, evening, "Evening Java", 30, "19:00");
        LocalDate today = LocalDate.now(ZONE_ID);
        for (int day = 1; day <= 3; day++) {
            record(viewer, evening, java, today.minusDays(day), "19:00", 45, "COMPLETED", null);
        }
        long todayRun = record(viewer, evening, java, today, "19:00", 31, "COMPLETED", null);

        Account author = register("Example author");
        long example = routine(author, "Java Study morning " + w("browsergoal"), 60, true);
        habitWithTime(author, example, "Java Study warm-up", 20, "07:00");

        Account empty = register("Browser empty");

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("stub", AI.url());
        fixtures.put("viewer", Map.of("id", viewer.id(), "token", viewer.token()));
        fixtures.put("empty", Map.of("id", empty.id(), "token", empty.token()));
        fixtures.put("reviewRoutine", routine);
        redirectReviewRoutine = routine;
        fixtures.put("eveningLink", java);
        fixtures.put("todayRun", todayRun);

        Path dir = Files.createTempDirectory("assistant-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/assistant-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
        ((ObjectNode) browser.path("review")).put("breakSaved",
                count("select count(*) from routine_habits rh join habits h on h.id = rh.habit_id where rh.routine_id = ? and h.title = 'Break'", routine) == 1);
        ((ObjectNode) browser.path("generate")).put("savedRoutine",
                count("select count(*) from routines where user_id = ? and title = 'Browser planned day'", viewer.id()) == 1);
        Integer entered = jdbc.queryForObject("select reported_minutes from habit_completions where routine_completion_id = ?", Integer.class, todayRun);
        ((ObjectNode) browser.path("summary")).put("enteredSaved", entered == null ? -1 : entered);
        AI.mode = "words";
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- the stand-in AI service ----

    /**
     * Answers POST /similar like ai/app.py, scoring by shared words (Jaccard) instead of the model,
     * and remembers the last request. GET /__mode?mode=.. switches how it behaves: words, error
     * (500), garbage (not JSON), slow (past the timeout) or inject (unknown ids, bad scores).
     */
    static final class StubAi {

        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final ObjectMapper mapper = new ObjectMapper();
        volatile String mode = "words";
        volatile String lastBody = "";
        volatile String lastToken;

        StubAi() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            // Each request on its own thread, so a deliberately slow answer never holds up the next one.
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/similar", this::similar);
            server.createContext("/__mode", this::switchMode);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void switchMode(HttpExchange exchange) throws IOException {
            String query = String.valueOf(exchange.getRequestURI().getQuery());
            mode = query.startsWith("mode=") ? query.substring(5) : "words";
            reply(exchange, 200, "{}");
        }

        private void similar(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            lastToken = exchange.getRequestHeaders().getFirst("X-AI-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode request = mapper.readTree(lastBody);
            switch (mode) {
                case "error" -> reply(exchange, 500, "{\"message\":\"Similarity search failed\"}");
                case "garbage" -> reply(exchange, 200, "this is not json");
                case "slow" -> {
                    pause(5000);
                    reply(exchange, 200, "{\"status\":\"ok\",\"results\":[]}");
                }
                default -> reply(exchange, 200, mapper.writeValueAsString(answer(request)));
            }
        }

        private ObjectNode answer(JsonNode request) {
            ObjectNode reply = mapper.createObjectNode();
            reply.put("status", "ok");
            ArrayNode results = reply.putArray("results");
            for (JsonNode query : request.path("queries")) {
                Set<String> mine = words(query.asString());
                List<ObjectNode> found = new ArrayList<>();
                for (JsonNode item : request.path("items")) {
                    Set<String> theirs = words(item.path("text").asString());
                    Set<String> shared = new HashSet<>(mine);
                    shared.retainAll(theirs);
                    Set<String> all = new HashSet<>(mine);
                    all.addAll(theirs);
                    double score = all.isEmpty() ? 0 : (double) shared.size() / all.size();
                    if (score >= request.path("minScore").asDouble() || shared.size() > 0) {
                        found.add(mapper.createObjectNode().put("id", item.path("id").asLong()).put("score", shared.isEmpty() ? 0.0 : Math.max(0.5, score)));
                    }
                }
                found.removeIf(node -> node.path("score").asDouble() < request.path("minScore").asDouble());
                found.sort((a, b) -> Double.compare(b.path("score").asDouble(), a.path("score").asDouble()));
                ArrayNode list = results.addArray();
                if ("inject".equals(mode)) {
                    list.addObject().put("id", 424242).put("score", 0.9);
                    list.addObject().put("id", 1).put("score", 7.0);
                }
                found.stream().limit(request.path("limit").asInt(5)).forEach(list::add);
            }
            return reply;
        }

        private static Set<String> words(String text) {
            Set<String> words = new HashSet<>();
            for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
                if (word.length() >= 6) {
                    words.add(word);
                }
            }
            return words;
        }

        private static void pause(int ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException exception) {
                // The backend gave up waiting (slow mode); nobody reads this answer.
            } finally {
                exchange.close();
            }
        }
    }

    // ---- helpers ----

    private static String w(String word) {
        return word + RUN;
    }

    private static Map<String, Object> habit(String title, int minutes, String kind) {
        return Map.of("title", title, "minutes", minutes, "kind", kind);
    }

    private JsonNode generate(Account who, Map<String, Object> request) throws Exception {
        return post("/assistant/plans", who, request);
    }

    private static String source(JsonNode plan, String key) {
        for (JsonNode line : plan.path("preferences")) {
            if (line.path("key").asString().equals(key)) {
                return line.path("source").asString();
            }
        }
        throw new AssertionError(key + " not in " + plan.path("preferences"));
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    private static List<String> texts(JsonNode array, String field) {
        return list(array).stream().map(item -> field == null ? item.asString() : item.path(field).asString()).toList();
    }

    private static JsonNode find(List<JsonNode> activities, String title) {
        return activities.stream().filter(a -> a.path("title").asString().equals(title)).findFirst()
                .orElseThrow(() -> new AssertionError(title + " not in " + activities));
    }

    private static int minutes(String time) {
        return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
    }

    private static void assertNoOverlap(List<JsonNode> activities) {
        for (int i = 0; i < activities.size(); i++) {
            for (int j = i + 1; j < activities.size(); j++) {
                JsonNode a = activities.get(i);
                JsonNode b = activities.get(j);
                if (a.path("fixed").asBoolean() && b.path("fixed").asBoolean()) {
                    continue;
                }
                int as = minutes(a.path("start").asString());
                int bs = minutes(b.path("start").asString());
                assertFalse(as < bs + b.path("minutes").asInt() && bs < as + a.path("minutes").asInt(), a + " overlaps " + b);
            }
        }
    }

    private static JsonNode issue(JsonNode review, String code) {
        return list(review.path("issues")).stream().filter(i -> i.path("code").asString().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + review.path("issues")));
    }

    private static List<JsonNode> changesOf(JsonNode review, JsonNode issue) {
        List<String> ids = list(issue.path("changeIds")).stream().map(JsonNode::asString).toList();
        return list(review.path("changes")).stream().filter(c -> ids.contains(c.path("id").asString())).toList();
    }

    private JsonNode insight(Account who, long routine, long link) throws Exception {
        JsonNode reply = get("/assistant/insights/durations?routineId=" + routine, who);
        for (JsonNode item : reply.path("items")) {
            if (item.path("routineHabitId").asLong() == link) {
                return item.path("insight");
            }
        }
        throw new AssertionError(link + " not in " + reply);
    }

    private String startOf(long link) {
        return jdbc.queryForObject("select start_time::text from routine_habits where id = ?", String.class, link);
    }

    private int minutesOf(long link) {
        return jdbc.queryForObject("select h.duration_minutes from routine_habits rh join habits h on h.id = rh.habit_id where rh.id = ?",
                Integer.class, link);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // A finished run of one habit on a day, as the run page stores it; returns the run id.
    private long record(Account who, long routine, long link, LocalDate day, String start, Integer minutes, String status,
                        Integer reported) {
        Instant at = day.atTime(LocalTime.parse(start == null ? "06:00" : start)).atZone(ZONE_ID).toInstant();
        Long run = jdbc.queryForObject("""
                insert into routine_completions (user_id, routine_id, completed_date, started_at, ended_at, expires_at)
                values (?, ?, ?, ?, ?, ?) returning id""", Long.class, who.id(), routine, day, Timestamp.from(at),
                Timestamp.from(at.plus(2, ChronoUnit.HOURS)), Timestamp.from(day.plusDays(1).atStartOfDay(ZONE_ID).toInstant()));
        recordInRun(who, run, link, day, start, minutes, status, reported);
        return run;
    }

    private void recordInRun(Account who, long run, long link, LocalDate day, String start, Integer minutes, String status, Integer reported) {
        Instant begun = start == null ? null : day.atTime(LocalTime.parse(start)).atZone(ZONE_ID).toInstant();
        Instant done = begun == null || minutes == null || !status.equals("COMPLETED") ? null : begun.plus(minutes, ChronoUnit.MINUTES);
        long habit = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, link);
        Integer planned = jdbc.queryForObject("select duration_minutes from habits where id = ?", Integer.class, habit);
        jdbc.update("""
                insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date,
                    started_at, completed_at, status, planned_minutes, reported_minutes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", who.id(), habit, link, run, day,
                begun == null ? null : Timestamp.from(begun), done == null ? null : Timestamp.from(done), status, planned, reported);
    }

    // A habit marked done without the timer: completed, but no start or end time was recorded.
    private void markedDone(Account who, long routine, long link, LocalDate day) {
        Long run = jdbc.queryForObject("""
                insert into routine_completions (user_id, routine_id, completed_date, started_at, ended_at)
                values (?, ?, ?, now(), now()) returning id""", Long.class, who.id(), routine, day);
        long habit = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, link);
        jdbc.update("""
                insert into habit_completions (user_id, habit_id, routine_habit_id, routine_completion_id, completed_date, status)
                values (?, ?, ?, ?, ?, 'COMPLETED')""", who.id(), habit, link, run, day);
    }

    private void profile(Account who, String goals) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Assistant member");
        body.put("goals", goals);
        body.put("interests", "");
        body.put("profession", "");
        body.put("bio", "");
        Reply reply = send("PUT", "/users/me", who, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "assist_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Assistant check");
        fields.put("category", "Study");
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

    // Adds a habit through the normal API and returns its routine habit id.
    private long habitWithTime(Account owner, long routine, String title, int minutes, String time) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", title + " description");
        body.put("durationMinutes", minutes);
        if (time != null) {
            body.put("startTime", time);
            body.put("repeatDays", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));
        }
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner, body);
        assertEquals(200, reply.status(), reply.body());
        return jdbc.queryForObject("""
                select rh.id from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? and h.title = ? order by rh.id desc limit 1""", Long.class, routine, title);
    }

    private JsonNode get(String path, Account who) throws Exception {
        Reply reply = send("GET", path, who, null);
        assertEquals(200, reply.status(), path + ": " + reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode post(String path, Account who, Object body) throws Exception {
        Reply reply = send("POST", path, who, body);
        assertEquals(200, reply.status(), path + ": " + reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode put(String path, Account who, Object body) throws Exception {
        Reply reply = send("PUT", path, who, body);
        assertEquals(200, reply.status(), path + ": " + reply.body());
        return json.readTree(reply.body());
    }

    private Reply send(String method, String path, Account who, Object payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("X-Time-Zone", ZONE)
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply raw(String method, String path, String authorization) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Authorization", authorization)
                .build(), HttpResponse.BodyHandlers.ofString());
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
