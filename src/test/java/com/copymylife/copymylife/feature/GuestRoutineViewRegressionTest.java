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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-06 for visitors: a public routine and its timeline can be read without an account,
 * while private routines stay hidden and every action still needs one.
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
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuestRoutineViewRegressionTest {

    private static final String BOUNDARY = "GuestRoutineBoundary";
    private static final long MISSING_ROUTINE = 987_654_321L;
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");
    private static final List<String> EVERY_DAY = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final String SECRET = "GUEST-HIDDEN";

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

    private record Published(Account owner, long routine, List<Long> steps) {
    }

    // ---- reading a public routine ----

    @Test
    void aVisitorReadsAPublicRoutine() throws Exception {
        Published published = scheduledRoutine();

        Reply reply = get("/routines/" + published.routine(), null);

        assertEquals(200, reply.status(), reply.body());
        JsonNode routine = json.readTree(reply.body());
        assertEquals("Guest morning", routine.path("title").asString());
        assertEquals("What I do before work", routine.path("description").asString());
        assertEquals(80, routine.path("durationMinutes").asInt());
        assertTrue(routine.path("public").asBoolean());
        assertFalse(routine.path("ownedByMe").asBoolean(), reply.body());
    }

    @Test
    void aVisitorReadsThePublicTimeline() throws Exception {
        Published published = scheduledRoutine();

        Reply reply = get("/routines/" + published.routine() + "/routine-habits", null);

        assertEquals(200, reply.status(), reply.body());
        JsonNode run = step(json.readTree(reply.body()), published.steps().get(1));
        assertEquals("Run", run.path("title").asString());
        assertEquals("06:30", run.path("startTime").asString());
        assertEquals("06:50", run.path("endTime").asString());
        assertEquals(20, run.path("durationMinutes").asInt());
        assertEquals(WEEKDAYS, texts(run.path("repeatDays")));
        assertEquals("Easy pace", run.path("notes").asString());
        assertEquals(3, json.readTree(reply.body()).size());
    }

    @Test
    void aVisitorDoesNotSeeTheOwnersPausedHabits() throws Exception {
        Published published = scheduledRoutine();
        Reply paused = post("/routines/" + published.routine() + "/routine-habits/" + published.steps().get(0) + "/pause",
                published.owner(), null);
        assertEquals(200, paused.status(), paused.body());

        JsonNode first = step(json.readTree(get("/routines/" + published.routine() + "/routine-habits", null).body()),
                published.steps().get(0));

        assertFalse(first.path("paused").asBoolean());
        assertTrue(first.path("pausedAt").isNull() || first.path("pausedAt").isMissingNode(), first.toString());
    }

    @Test
    void aVisitorSeesAnEmptyPublicRoutineAsEmpty() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Guest empty", 30, true);

        assertEquals(200, get("/routines/" + routine, null).status());
        assertEquals("[]", get("/routines/" + routine + "/routine-habits", null).body());
    }

    @Test
    void builtInRoutinesOpenForVisitors() throws Exception {
        JsonNode builtIn = json.readTree(get("/routines/built-in", null).body());
        Assumptions.assumeTrue(builtIn.size() > 0, "no built-in routine seeded");
        long id = builtIn.get(0).path("id").asLong();

        assertEquals(200, get("/routines/" + id, null).status());
        assertEquals(200, get("/routines/" + id + "/routine-habits", null).status());
    }

    // ---- private routines stay hidden ----

    @Test
    void aPrivateRoutineLooksMissingToAVisitor() throws Exception {
        Account owner = register();
        long hidden = routine(owner, SECRET + "-TITLE", 60, false);
        habit(owner, hidden, SECRET + "-HABIT");

        for (String path : List.of("/routines/%d", "/routines/%d/routine-habits")) {
            Reply secret = get(path.formatted(hidden), null);
            Reply missing = get(path.formatted(MISSING_ROUTINE), null);

            assertEquals(404, secret.status(), path);
            assertEquals(404, missing.status(), path);
            assertEquals(withoutPathAndTime(missing.body()), withoutPathAndTime(secret.body()), path);
            assertFalse(secret.body().contains(SECRET), secret.body());
        }
    }

    @Test
    void aBrokenOrForeignTokenNeverOpensAPrivateRoutine() throws Exception {
        Account owner = register();
        Account stranger = register();
        long hidden = routine(owner, SECRET + "-TITLE", 60, false);
        habit(owner, hidden, SECRET + "-HABIT");

        String forged = owner.token().substring(0, owner.token().length() - 10) + "AAAAAAAAAA";

        for (String token : List.of("not-a-real-token", stranger.token(), forged)) {
            Account viewer = new Account(0, token);
            for (String path : List.of("/routines/" + hidden, "/routines/" + hidden + "/routine-habits")) {
                Reply reply = get(path, viewer);
                assertEquals(404, reply.status(), token + " " + path);
                assertFalse(reply.body().contains(SECRET), reply.body());
            }
        }
    }

    @Test
    void goingPrivateHidesTheRoutineFromVisitorsAgain() throws Exception {
        Published published = scheduledRoutine();
        assertEquals(200, get("/routines/" + published.routine(), null).status());

        Reply privateNow = saveRoutine("PUT", "/routines/" + published.routine(), published.owner(),
                routineFields("Guest morning", 80, false));
        assertEquals(200, privateNow.status(), privateNow.body());

        assertEquals(404, get("/routines/" + published.routine(), null).status());
        assertEquals(404, get("/routines/" + published.routine() + "/routine-habits", null).status());
    }

    @Test
    void aDeletedRoutineIsGoneForVisitors() throws Exception {
        Published published = scheduledRoutine();
        assertEquals(204, send("DELETE", "/routines/" + published.routine(), published.owner(), null).status());

        assertEquals(404, get("/routines/" + published.routine(), null).status());
        assertEquals(404, get("/routines/" + published.routine() + "/routine-habits", null).status());
    }

    @Test
    void theOwnerAndSignedInViewersSeeWhatTheySawBefore() throws Exception {
        Account owner = register();
        Account stranger = register();
        long hidden = routine(owner, SECRET + "-TITLE", 60, false);
        Published published = scheduledRoutine();

        JsonNode own = json.readTree(get("/routines/" + hidden, owner).body());
        assertTrue(own.path("ownedByMe").asBoolean());
        assertEquals(200, get("/routines/" + hidden + "/routine-habits", owner).status());
        assertEquals(404, get("/routines/" + hidden, stranger).status());

        Reply asViewer = get("/routines/" + published.routine(), stranger);
        assertEquals(200, asViewer.status());
        assertFalse(json.readTree(asViewer.body()).path("ownedByMe").asBoolean());
    }

    // ---- visitors can only read ----

    @Test
    void aVisitorCannotChangeOrUseAPublicRoutine() throws Exception {
        Published published = scheduledRoutine();
        long id = published.routine();
        long step = published.steps().get(0);
        String before = snapshot(published);

        List<Reply> replies = List.of(
                post("/routines/" + id + "/copy", null, null),
                saveRoutine("PUT", "/routines/" + id, null, routineFields("Taken over", 80, false)),
                send("DELETE", "/routines/" + id, null, null),
                post("/routines/" + id + "/habits", null, Map.of("title", "Sneaky", "durationMinutes", 5)),
                put("/routines/" + id + "/routine-habits/" + step, null, scheduleBody("05:00", null, EVERY_DAY, "x")),
                send("DELETE", "/routines/" + id + "/routine-habits/" + step, null, null),
                post("/routines/" + id + "/routine-habits/" + step + "/pause", null, null),
                put("/routines/" + id + "/routine-habits/order", null, Map.of("habitIds", published.steps().reversed())),
                post("/liked-routines/" + id, null, null),
                post("/saved-routines/" + id, null, null),
                post("/routine-ratings/" + id, null, Map.of("value", 5)),
                post("/routine-comments/" + id, null, Map.of("text", "drive-by")),
                post("/routine-runs/" + id + "/start", null, null)
        );

        for (Reply reply : replies) {
            assertEquals(403, reply.status(), reply.body());
        }
        assertEquals(before, snapshot(published));
    }

    @Test
    void aBrokenTokenOnlyReadsLikeAVisitor() throws Exception {
        Published published = scheduledRoutine();
        Account broken = new Account(0, "not-a-real-token");
        int routinesBefore = count("select count(*) from routines");

        assertEquals(200, get("/routines/" + published.routine(), broken).status());
        assertEquals(403, post("/routines/" + published.routine() + "/copy", broken, null).status());
        assertEquals(403, post("/liked-routines/" + published.routine(), broken, null).status());
        assertEquals(routinesBefore, count("select count(*) from routines"));
    }

    @Test
    void onlyTheRoutineAndItsTimelineOpenedUp() throws Exception {
        Published published = scheduledRoutine();
        long id = published.routine();

        for (String path : List.of(
                "/routines",
                "/routines/" + id + "/habits",
                "/routines/abc",
                "/routines/" + id + "/routine-habits/" + published.steps().get(0),
                "/routine-comments/routine/" + id,
                "/routine-ratings/" + id,
                "/routine-runs/active",
                "/users/me",
                "/liked-routines",
                "/saved-routines"
        )) {
            assertEquals(403, get(path, null).status(), path);
        }
    }

    @Test
    void copyingStillWorksOnceSignedIn() throws Exception {
        Published published = scheduledRoutine();
        Account reader = register();

        Reply copy = post("/routines/" + published.routine() + "/copy", reader, null);

        assertEquals(200, copy.status(), copy.body());
        long copied = json.readTree(copy.body()).path("id").asLong();
        assertNotEquals(published.routine(), copied);
        assertEquals(404, get("/routines/" + copied, null).status(), "a copy starts private");
    }

    // ---- real browser ----

    @BeforeAll
    void openRoutinesAsAVisitor() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Published published = scheduledRoutine();
        Account emptyOwner = register();
        long empty = routine(emptyOwner, "Guest empty", 30, true);
        Account secretOwner = register();
        long hidden = routine(secretOwner, SECRET + "-TITLE", 60, false);
        habit(secretOwner, hidden, SECRET + "-HABIT");

        Long builtIn = jdbc.queryForList(
                "select id from routines where built_in = true and category = 'Halloween' and deleted = false order by id limit 1",
                Long.class).stream().findFirst().orElse(null);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("publicRoutine", published.routine());
        fixtures.put("ownerToken", published.owner().token());
        fixtures.put("emptyRoutine", empty);
        fixtures.put("privateRoutine", hidden);
        fixtures.put("missingRoutine", MISSING_ROUTINE);
        fixtures.put("builtInRoutine", builtIn);
        fixtures.put("viewerToken", register().token());

        String before = snapshot(published);
        int routinesBefore = count("select count(*) from routines");

        Path dir = Files.createTempDirectory("guest-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/guest-routine-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));

        // Clicking Copy, Like, Save and the stars as a visitor changed nothing.
        assertEquals(before, snapshot(published));
        assertEquals(routinesBefore, count("select count(*) from routines"));
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    @Test
    void browserShowsAVisitorThePublicRoutineAndItsTimeline() {
        JsonNode view = page("guestTimeline");

        assertTrue(view.path("url").asString().startsWith("/routine.html"), browser.toString());
        assertEquals("Guest morning", view.path("title").asString());
        assertEquals("What I do before work", view.path("description").asString());
        assertEquals(List.of("Run", "Stretch", "Read"), texts(view.path("titles")), view.toString());
        assertEquals(List.of("06:30 – 06:50", "21:00 – 21:20", "Any time"), texts(view.path("whens")));
        assertEquals(List.of("Mon – Fri", "Every day", ""), texts(view.path("days")));
        assertEquals(List.of("20 min", "20 min", "20 min"), texts(view.path("durations")));
        assertTrue(view.path("notes").toString().contains("Easy pace"), view.toString());
        assertTrue(view.path("tabsVisible").asBoolean());
        assertEquals("06:30 – 21:20", view.path("timeRange").asString());
    }

    @Test
    void browserShowsAVisitorTheWeekAndToday() {
        JsonNode week = page("guestWeek");
        assertTrue(week.path("visible").asBoolean(), browser.toString());
        assertEquals(List.of("Run", "Stretch"), texts(week.path("days").path("MON")));
        assertEquals(List.of("Stretch"), texts(week.path("days").path("SAT")));

        List<String> expected = List.of("SAT", "SUN").contains(browser.path("todayCode").asString())
                ? List.of("Stretch", "Read") : List.of("Run", "Stretch", "Read");
        assertEquals(expected, texts(page("guestToday").path("titles")));
    }

    @Test
    void browserOffersAVisitorSignInInsteadOfAccountControls() {
        JsonNode view = page("guestTimeline");

        assertTrue(view.path("signInVisible").asBoolean(), view.toString());
        assertFalse(view.path("userMenuVisible").asBoolean());
        assertFalse(view.path("editVisible").asBoolean());
        assertFalse(view.path("editHabitsVisible").asBoolean());
        assertFalse(view.path("startVisible").asBoolean());
        assertFalse(view.path("commentFormVisible").asBoolean());
        assertTrue(view.path("commentsText").asString().contains("Sign in"), view.toString());
        assertTrue(texts(view.path("commentLinks")).contains("login.html"), view.toString());
    }

    @Test
    void browserAsksAVisitorToSignInBeforeCopyLikeSaveOrRate() {
        assertTrue(page("guestTimeline").path("copyVisible").asBoolean(), browser.toString());

        for (String step : List.of("afterCopy", "afterLike", "afterSave", "afterRate")) {
            JsonNode view = page(step);
            assertTrue(view.path("notice").asString().contains("Sign in to"), step + " " + view);
            assertTrue(texts(view.path("noticeLinks")).contains("login.html"), step + " " + view);
            assertTrue(view.path("url").asString().startsWith("/routine.html"), step);
        }
        assertEquals(page("guestTimeline").path("likes").asString(), page("afterLike").path("likes").asString());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    @Test
    void browserShowsAVisitorAnEmptyRoutine() {
        JsonNode empty = page("guestEmpty");

        assertTrue(empty.path("emptyText").asString().contains("doesn't have any habits"), empty.toString());
        assertFalse(empty.path("tabsVisible").asBoolean());
        assertEquals("Guest empty", empty.path("title").asString());
    }

    @Test
    void browserNeverShowsAVisitorAPrivateRoutine() {
        for (String name : List.of("guestPrivate", "guestMissing")) {
            JsonNode view = page(name);
            assertEquals("This routine isn't available", view.path("unavailable").asString(), name + " " + view);
            assertFalse(view.path("bodyText").asString().contains(SECRET), name);
            assertTrue(texts(view.path("unavailableLinks")).contains("login.html"), name + " " + view);
            assertTrue(view.path("signInVisible").asBoolean(), name + " " + view);
            assertFalse(view.path("userMenuVisible").asBoolean(), name + " " + view);
        }
        assertEquals(withoutRoutineId(page("guestMissing").path("bodyText").asString()),
                withoutRoutineId(page("guestPrivate").path("bodyText").asString()));
    }

    @Test
    void browserTreatsABrokenTokenLikeAVisitor() {
        JsonNode view = page("staleToken");

        assertEquals("Guest morning", view.path("title").asString(), view.toString());
        assertTrue(view.path("signInVisible").asBoolean());
        assertEquals(3, view.path("titles").size());
    }

    @Test
    void browserTakesAVisitorFromExploreToTheDetails() {
        JsonNode view = page("fromExplore");
        Assumptions.assumeTrue(!view.path("cardLink").isNull(), "Explore shows no routine card");

        assertTrue(view.path("url").asString().startsWith("/routine.html"), view.toString());
        assertFalse(view.path("title").asString().isBlank(), view.toString());
    }

    @Test
    void browserShowsBuiltInHalloweenRoutinesToVisitors() {
        JsonNode view = page("guestBuiltIn");
        Assumptions.assumeTrue(view.path("loaded").asBoolean(), "no built-in Halloween routine seeded");

        assertTrue(view.path("halloweenTheme").asBoolean(), view.toString());
        assertFalse(view.path("titles").isEmpty(), view.toString());
    }

    @Test
    void browserKeepsSignedInPagesAsTheyWere() {
        JsonNode viewer = page("signedInViewer");
        assertTrue(viewer.path("userMenuVisible").asBoolean(), viewer.toString());
        assertFalse(viewer.path("signInVisible").asBoolean());
        assertTrue(viewer.path("copyVisible").asBoolean());
        assertTrue(viewer.path("commentFormVisible").asBoolean());
        assertFalse(viewer.path("editVisible").asBoolean());

        JsonNode owner = page("owner");
        assertTrue(owner.path("editVisible").asBoolean(), owner.toString());
        assertTrue(owner.path("startVisible").asBoolean());
        assertFalse(owner.path("copyVisible").asBoolean());
        assertFalse(owner.path("signInVisible").asBoolean());
    }

    @Test
    void browserWorksForVisitorsInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "weekPhone", "autumnDesktop", "originalDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());

        JsonNode halloween = page("halloweenPhone");
        if (!halloween.isMissingNode()) {
            assertTrue(halloween.path("overflow").asInt() <= 0, halloween.toString());
        }
    }

    // ---- helpers ----

    // Three habits of 20 minutes: Read (any time), Run 06:30 on weekdays, Stretch 21:00 every day.
    private Published scheduledRoutine() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Guest morning", 80, true);
        for (String title : List.of("Read", "Run", "Stretch")) {
            habit(owner, routine, title);
        }
        List<Long> steps = jdbc.queryForList(
                "select id from routine_habits where routine_id = ? order by position", Long.class, routine);
        assertEquals(200, put("/routines/" + routine + "/routine-habits/" + steps.get(1), owner,
                scheduleBody("06:30", null, WEEKDAYS, "Easy pace")).status());
        assertEquals(200, put("/routines/" + routine + "/routine-habits/" + steps.get(2), owner,
                scheduleBody("21:00", null, EVERY_DAY, null)).status());
        return new Published(owner, routine, steps);
    }

    // Everything a visitor could have touched on this routine, in one comparable string.
    private String snapshot(Published published) {
        long id = published.routine();
        return String.join("|",
                String.valueOf(jdbc.queryForMap(
                        "select title, description, is_public, deleted, duration_minutes from routines where id = ?", id)),
                String.valueOf(jdbc.queryForList(
                        "select id, position, start_time, repeat_days, notes, paused_at from routine_habits where routine_id = ? order by id", id)),
                String.valueOf(count("select count(*) from habits where routine_id = ?", id)),
                String.valueOf(count("select count(*) from liked_routines where routine_id = ?", id)),
                String.valueOf(count("select count(*) from saved_routines where routine_id = ?", id)),
                String.valueOf(count("select count(*) from routine_ratings where routine_id = ?", id)),
                String.valueOf(count("select count(*) from routine_comments where routine_id = ?", id)),
                String.valueOf(count("select count(*) from routine_completions where routine_id = ?", id)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private JsonNode step(JsonNode list, long routineHabitId) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == routineHabitId) {
                return item;
            }
        }
        throw new AssertionError("No routine habit " + routineHabitId + " in " + list);
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private String withoutPathAndTime(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "").replaceAll("\"path\":\"[^\"]*\"", "");
    }

    private String withoutRoutineId(String text) {
        return text.replaceAll("\\d+", "");
    }

    private Map<String, Object> scheduleBody(String startTime, Integer minutes, List<String> days, String notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("startTime", startTime);
        body.put("durationMinutes", minutes);
        body.put("repeatDays", days);
        body.put("notes", notes);
        return body;
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Guest QA",
                "email", "guest_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes, boolean isPublic) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", owner, routineFields(title, minutes, isPublic));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private void habit(Account owner, long routine, String title) throws Exception {
        Reply reply = post("/routines/" + routine + "/habits", owner,
                Map.of("title", title, "description", title + " notes", "durationMinutes", 20));
        assertEquals(200, reply.status(), reply.body());
    }

    private Map<String, String> routineFields(String title, int minutes, boolean isPublic) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "What I do before work");
        fields.put("category", "Morning");
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));
        return fields;
    }

    private Reply saveRoutine(String method, String path, Account account, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
