package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import com.copymylife.copymylife.service.LifestyleMatchingClient;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-26: GET /users/me/lifestyle-matches suggests members whose lifestyle is like the user's
 * (AC1), as cards that lead to their profile and public routines (AC2), worked out afresh after
 * the user changes interests or routines (AC3). Here the Python service is replaced by a stand-in
 * that ranks by shared words, so that what the backend sends, filters and shows can be checked
 * exactly; the real model is covered by ai/tests and LifestyleMatchingAiIntegrationTest.
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
                "app.upload-dir=target/test-uploads",
                "ai.matching.timeout-seconds=3"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifestyleMatchingRegressionTest {

    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";
    private static final String BOUNDARY = "LifestyleMatchBoundary";
    private static final String PATH = "/users/me/lifestyle-matches";
    // Words no other test writes, so that only this class's members share them.
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private static final StubAi AI = new StubAi();

    @DynamicPropertySource
    static void matchingService(DynamicPropertyRegistry registry) {
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

    @BeforeEach
    void healthyService() {
        AI.mode = "words";
        AI.delayMs = 0;
    }

    @AfterAll
    void stopService() {
        AI.server.stop(0);
    }

    // ---- AC1: similar people are recommended ----

    @Test
    void recommendsPeopleWithASimilarLifestyleBestFirst() throws Exception {
        Account me = register("Matcher");
        profile(me, "Matcher", w("kayak") + ", " + w("origami") + ", " + w("tea"), w("calm"), "");
        Account close = register("Close match");
        profile(close, "Close match", w("kayak") + ", " + w("origami"), w("calm"), "");
        Account partial = register("Partial match");
        profile(partial, "Partial match", w("tea") + ", " + w("rugby"), "", "");
        Account different = register("Different");
        profile(different, "Different", w("drums") + ", " + w("nightclub"), "", "");

        JsonNode reply = matches(me);
        assertEquals("OK", reply.path("status").asString(), reply.toString());
        List<Long> ids = cardIds(reply);
        assertTrue(ids.indexOf(close.id()) >= 0 && ids.indexOf(close.id()) < ids.indexOf(partial.id()), ids.toString());
        assertFalse(ids.contains(different.id()), "nothing in common is not a recommendation");

        JsonNode first = card(reply, close.id());
        assertEquals("Close match", first.path("name").asString());
        assertTrue(first.path("score").asInt() > card(reply, partial.id()).path("score").asInt());
        assertTrue(first.path("score").asInt() >= 30 && first.path("score").asInt() <= 100, first.toString());
        assertTrue(first.path("components").has("schedule") && first.path("components").path("schedule").isNull(),
                "a part without data is null, not made up: " + first);
        assertEquals(List.of(w("kayak"), w("origami")), texts(first.path("sharedInterests"), "theirs"));
        assertEquals(60, reply.path("weights").path("semantic").asInt());
        assertEquals(25, reply.path("weights").path("schedule").asInt());
        assertEquals(15, reply.path("weights").path("interests").asInt());
    }

    @Test
    void routinesCountAsLifestyleToo() throws Exception {
        Account me = register("Routine owner");
        long mine = routine(me, "Dawn " + w("fencing"), false);
        habit(me, mine, w("fencing") + " drills", "06:15", List.of("MON", "WED"), null);
        Account fencer = register("Fencer");
        long theirs = routine(fencer, "Club " + w("fencing"), true);
        habit(fencer, theirs, w("fencing") + " drills", "06:30", List.of("MON"), null);

        assertTrue(cardIds(matches(me)).contains(fencer.id()));
        JsonNode user = AI.lastRequest.path("user");
        JsonNode activity = user.path("routines").get(0).path("activities").get(0);
        assertEquals(w("fencing") + " drills", activity.path("title").asString());
        assertEquals("06:15", activity.path("startTime").asString());
        assertEquals(List.of("MON", "WED"), texts(activity.path("repeatDays"), null));
    }

    @Test
    void theUserIsNeverRecommendedToThemself() throws Exception {
        Account me = register("Self");
        profile(me, "Self", w("selfsame"), "", "");
        matches(me);
        assertFalse(sentIds().contains(me.id()));
        assertEquals(me.id(), AI.lastRequest.path("user").path("id").asLong());
    }

    // ---- privacy: only public, visible members and content are compared ----

    @Test
    void bannedHiddenAndAdministratorAccountsAreNeverCandidates() throws Exception {
        Account me = register("Privacy viewer");
        profile(me, "Privacy viewer", w("lantern"), "", "");
        Account visible = register("Visible");
        profile(visible, "Visible", w("lantern"), "", "");
        Account banned = register("Banned");
        profile(banned, "Banned", w("lantern"), "", "");
        Account hidden = register("Hidden");
        profile(hidden, "Hidden", w("lantern"), "", "");
        Account admin = register("Admin");
        profile(admin, "Admin", w("lantern"), "", "");
        jdbc.update("update users set banned = true where id = ?", banned.id());
        jdbc.update("update users set profile_hidden_at = now() where id = ?", hidden.id());
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());

        JsonNode reply = matches(me);
        Set<Long> sent = sentIds();
        assertTrue(sent.contains(visible.id()));
        for (Account excluded : List.of(banned, hidden, admin)) {
            assertFalse(sent.contains(excluded.id()), "sent " + excluded.id());
            assertFalse(cardIds(reply).contains(excluded.id()));
        }
    }

    @Test
    void theBuiltInCatalogueIsNotAPerson() throws Exception {
        Account me = register("Catalogue viewer");
        profile(me, "Catalogue viewer", w("atlas"), "", "");
        Account member = register("Catalogue member");
        profile(member, "Catalogue member", w("atlas"), "", "");
        long catalogue = routine(member, "Catalogue " + w("builtinplan"), true);
        jdbc.update("update routines set built_in = true where id = ?", catalogue);

        matches(me);
        long system = jdbc.queryForObject("select id from users where email = 'explore@copymylife.local'", Long.class);
        assertFalse(sentIds().contains(system), "the account of the Explore routines is not a member");
        assertTrue(sentIds().contains(member.id()));
        assertFalse(AI.lastBody.contains(w("builtinplan")), "built-in routines are not anyone's lifestyle");
    }

    @Test
    void privateHiddenDeletedRemovedAndPausedContentIsNeverSent() throws Exception {
        Account me = register("Content viewer");
        profile(me, "Content viewer", w("harbor"), "", "");
        Account owner = register("Content owner");
        profile(owner, "Content owner", w("harbor"), "", "");

        long open = routine(owner, "Open " + w("harbor"), true);
        habit(owner, open, "Rowing " + w("harbor"), "07:00", List.of("TUE"), "Note " + w("privatenote"));
        habit(owner, open, "Step " + w("removedstep"), null, null, null);
        habit(owner, open, "Step " + w("pausedstep"), null, null, null);
        List<Long> steps = steps(open);
        assertEquals(200, send("DELETE", "/routines/" + open + "/routine-habits/" + steps.get(1), owner, null).status());
        assertEquals(200, send("POST", "/routines/" + open + "/routine-habits/" + steps.get(2) + "/pause", owner, null).status());

        long secret = routine(owner, "Secret " + w("secretplan"), false);
        habit(owner, secret, "Step " + w("secretstep"), null, null, null);
        long hiddenRoutine = routine(owner, "Hidden " + w("hiddenplan"), true);
        jdbc.update("update routines set hidden_at = now() where id = ?", hiddenRoutine);
        long deleted = routine(owner, "Deleted " + w("deletedplan"), true);
        jdbc.update("update routines set deleted = true where id = ?", deleted);

        JsonNode reply = matches(me);
        String sent = AI.lastBody;
        JsonNode candidate = sentCandidate(owner.id());
        assertEquals(1, candidate.path("routines").size(), candidate.toString());
        assertEquals("Open " + w("harbor"), candidate.path("routines").get(0).path("title").asString());
        assertEquals(List.of("Rowing " + w("harbor")), texts(candidate.path("routines").get(0).path("activities"), "title"));
        for (String secretWord : List.of("privatenote", "removedstep", "pausedstep", "secretplan", "secretstep", "hiddenplan", "deletedplan")) {
            assertFalse(sent.contains(w(secretWord)), secretWord + " was sent");
        }
        for (String field : List.of("\"email\"", "\"password\"", "\"notes\"", "@audit.test", "\"role\"", "\"banned\"")) {
            assertFalse(sent.contains(field), field + " was sent");
        }

        JsonNode card = card(reply, owner.id());
        assertEquals(List.of(open), longs(card.path("publicRoutines"), "id"), "only public routines are offered");
    }

    @Test
    void theUsersOwnPrivateRoutinesAreUsedForTheirOwnSideOnly() throws Exception {
        Account me = register("Private planner");
        long mine = routine(me, "Private " + w("compass"), false);
        habit(me, mine, "Map " + w("compass"), null, null, null);
        Account other = register("Compass fan");
        profile(other, "Compass fan", w("compass"), "", "");

        JsonNode reply = matches(me);
        assertEquals("Private " + w("compass"), AI.lastRequest.path("user").path("routines").get(0).path("title").asString());
        assertTrue(cardIds(reply).contains(other.id()));
        assertFalse(reply.toString().contains("Private " + w("compass")), "the reply shows nothing of the user's own routine");
    }

    @Test
    void onlyPeopleThatWereSentComeBack() throws Exception {
        Account me = register("Strict viewer");
        profile(me, "Strict viewer", w("quill"), "", "");
        Account other = register("Quill writer");
        profile(other, "Quill writer", w("quill"), "", "");
        Account banned = register("Banned writer");
        profile(banned, "Banned writer", w("quill"), "", "");
        jdbc.update("update users set banned = true where id = ?", banned.id());

        AI.mode = "inject";
        AI.injectId = banned.id();
        JsonNode reply = matches(me);
        List<Long> ids = cardIds(reply);
        assertFalse(ids.contains(banned.id()), "a member not sent is dropped");
        assertFalse(ids.contains(999_999_999L));
        assertEquals(1, ids.stream().filter(id -> id == other.id()).count(), "duplicates are dropped");
        reply.path("matches").forEach(card -> assertTrue(card.path("score").asInt() <= 100, card.toString()));
    }

    // ---- empty states ----

    @Test
    void aUserWithNothingWrittenIsToldWhatToAdd() throws Exception {
        Account me = register("Blank");
        int calls = AI.calls.get();
        JsonNode reply = matches(me);
        assertEquals("NEEDS_DATA", reply.path("status").asString());
        assertTrue(reply.path("message").asString().contains("interests"), reply.toString());
        assertEquals(0, reply.path("matches").size());
        assertEquals(calls, AI.calls.get(), "the matching service is not asked");
    }

    @Test
    void noSimilarPeopleIsAnEmptyStateNotAnError() throws Exception {
        Account me = register("Unique");
        profile(me, "Unique", w("zeppelin") + w("only"), "", "");
        JsonNode reply = matches(me);
        assertEquals("NO_MATCHES", reply.path("status").asString(), reply.toString());
        assertTrue(reply.path("message").asString().length() > 10);
    }

    // ---- AC3: changes count at once ----

    @Test
    void changedInterestsChangeTheRecommendations() throws Exception {
        Account me = register("Changer");
        profile(me, "Changer", w("sailing") + ", " + w("knots"), "", "");
        Account sailor = register("Sailor");
        profile(sailor, "Sailor", w("sailing") + ", " + w("knots"), "", "");
        Account baker = register("Baker");
        profile(baker, "Baker", w("baking") + ", " + w("sourdough"), "", "");

        List<Long> before = cardIds(matches(me));
        assertTrue(before.contains(sailor.id()) && !before.contains(baker.id()), before.toString());

        profile(me, "Changer", w("baking") + ", " + w("sourdough"), "", "");
        List<Long> after = cardIds(matches(me));
        assertTrue(after.contains(baker.id()) && !after.contains(sailor.id()), after.toString());
        assertTrue(AI.lastRequest.path("user").path("interests").asString().contains(w("baking")));
    }

    @Test
    void changedRoutineActivitiesAndTimesChangeTheRecommendations() throws Exception {
        Account me = register("Routine changer");
        long mine = routine(me, "Plan", false);
        habit(me, mine, w("archery"), "06:00", List.of("MON"), null);
        long step = steps(mine).get(0);
        long habitId = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, step);
        Account archer = register("Archer");
        habit(archer, routine(archer, "Range", true), w("archery"), "06:00", List.of("MON"), null);
        Account climber = register("Climber");
        habit(climber, routine(climber, "Wall", true), w("climbing"), "21:00", List.of("FRI"), null);

        List<Long> before = cardIds(matches(me));
        assertTrue(before.contains(archer.id()) && !before.contains(climber.id()), before.toString());

        assertEquals(200, send("PUT", "/routines/" + mine + "/habits/" + habitId, me,
                Map.of("title", w("climbing"), "description", "Changed", "durationMinutes", 30)).status());
        assertEquals(200, send("PUT", "/routines/" + mine + "/routine-habits/" + step, me,
                Map.of("startTime", "21:30", "durationMinutes", 30, "repeatDays", List.of("FRI"))).status());

        List<Long> after = cardIds(matches(me));
        assertTrue(after.contains(climber.id()) && !after.contains(archer.id()), after.toString());
        JsonNode activity = AI.lastRequest.path("user").path("routines").get(0).path("activities").get(0);
        assertEquals("21:30", activity.path("startTime").asString());
        assertEquals(List.of("FRI"), texts(activity.path("repeatDays"), null));
    }

    // ---- the matching service is unavailable ----

    @Test
    void anUnavailableServiceIsExplainedAndNothingElseBreaks() throws Exception {
        Account me = register("Patient");
        profile(me, "Patient", w("glacier"), "", "");
        Account other = register("Glacier fan");
        profile(other, "Glacier fan", w("glacier"), "", "");

        for (String failure : List.of("error", "garbage", "slow", "nolist")) {
            AI.mode = failure;
            Reply reply = get(PATH, me);
            assertEquals(503, reply.status(), failure + ": " + reply.body());
            assertTrue(json.readTree(reply.body()).path("message").asString().contains("not available"), reply.body());
            assertFalse(reply.body().contains(w("glacier")), "no data in the refusal");
        }

        assertEquals(200, get("/users/me", me).status());
        assertEquals(200, get("/routines/public", null).status());
        assertEquals(200, get("/users/" + other.id() + "/profile", me).status());
        assertEquals(200, get("/friends", me).status());

        AI.mode = "words";
        assertEquals("OK", matches(me).path("status").asString(), "works again once the service is back");
    }

    @Test
    void theClientTreatsEveryFailureAlike() {
        LifestyleMatchingClient closed = new LifestyleMatchingClient(json, "http://127.0.0.1:1", "", 2);
        assertThrows(LifestyleMatchingClient.AiUnavailableException.class,
                () -> closed.match(Map.of("user", Map.of("id", 1), "candidates", List.of())));

        LifestyleMatchingClient withToken = new LifestyleMatchingClient(json, AI.url() + "/", "local-secret", 2);
        withToken.match(Map.of("user", Map.of("id", 1, "interests", "x"), "candidates", List.of()));
        assertEquals("local-secret", AI.lastToken, "the configured token is sent");
    }

    // ---- authentication ----

    @Test
    void onlySignedInMembersCanUseMatching() throws Exception {
        int calls = AI.calls.get();
        Reply guest = get(PATH, null);
        assertTrue(guest.status() == 401 || guest.status() == 403, guest.status() + " " + guest.body());
        assertFalse(guest.body().contains("userId"), "no matches for a visitor");

        Reply forged = raw("GET", PATH, "Bearer not-a-token");
        assertTrue(forged.status() == 401 || forged.status() == 403, String.valueOf(forged.status()));

        Account admin = register("Admin caller");
        profile(admin, "Admin caller", w("ledger"), "", "");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        Reply refused = get(PATH, admin);
        assertEquals(403, refused.status(), refused.body());
        assertEquals(calls, AI.calls.get(), "nothing reached the matching service");
    }

    // ---- browser ----

    @Test
    void browserShowsLoadingThenCardsThatLeadToProfileAndRoutines() {
        JsonNode loading = page("loading");
        assertEquals(List.of(), texts(loading.path("before"), null), "nothing shown before the button is pressed");
        assertEquals(List.of("lmLoading"), texts(loading.path("during"), null), loading.toString());
        assertTrue(loading.path("disabled").asBoolean());

        JsonNode results = page("results");
        assertEquals(List.of("lmResults"), texts(results.path("state"), null), results.toString());
        JsonNode first = results.path("first");
        assertEquals("Browser twin", first.path("name").asString(), results.toString());
        assertTrue(first.path("score").asString().matches("\\d+% similar lifestyle"), first.toString());
        assertTrue(first.path("reasons").get(0).asString().startsWith("Shared interests"), first.toString());
        assertTrue(first.path("parts").asString().contains("Schedule not compared"), first.toString());
        assertTrue(first.path("profile").asString().startsWith("user-profile.html?id="));
        assertTrue(first.path("routinesLink").asString().endsWith("#publicRoutinesSection"));
        assertEquals(1, first.path("routines").size());
        assertEquals("Find My Matches Again", results.path("button").asString());

        assertEquals("Browser twin", page("profilePage").path("name").asString());
        assertTrue(page("profilePage").path("routines").asString().contains("Twin " + w("browser")));
        assertTrue(page("profilePage").path("anchor").asBoolean());
        assertEquals("Twin " + w("browser"), page("routinePage").path("title").asString());
    }

    @Test
    void browserShowsNamesAsTextNeverAsMarkup() {
        JsonNode results = page("results");
        assertTrue(results.path("names").toString().contains("onerror"), results.toString());
        assertFalse(results.path("markupInjected").asBoolean());
    }

    @Test
    void browserShowsTheEmptyErrorAndGuestStates() {
        JsonNode empty = page("empty");
        assertEquals(List.of("lmEmpty"), texts(empty.path("state"), null), empty.toString());
        assertTrue(empty.path("text").asString().contains("interests"));
        assertTrue(empty.path("action").asBoolean());

        JsonNode error = page("error");
        assertEquals(List.of("lmError"), texts(error.path("state"), null), error.toString());
        assertTrue(error.path("text").asString().contains("not available"), error.toString());
        assertTrue(error.path("button").asBoolean(), "the button works again");
        assertEquals(List.of("lmResults"), texts(page("retry").path("state"), null), "Try again recovers");

        // Matching is in the profile now; a visitor following the old page address is asked to sign in.
        JsonNode guest = page("guest");
        assertEquals("/login.html", guest.path("page").asString(), guest.toString());
        assertFalse(guest.path("matches").asBoolean(), "no matching for a visitor");
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
            if (view.path("cards").asInt() < 2 || !view.path("cardsInside").asBoolean() || !view.path("actionsInside").asBoolean()) {
                problems.add(entry.getKey() + " cards or buttons outside the screen " + view);
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
        });
        page("explore").properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (!view.path("visible").asBoolean() || !view.path("inside").asBoolean() || view.path("overflow").asInt() > 0
                    || !"profile.html#ai-generate".equals(view.path("href").asString()) || view.path("aiLinks").asInt() != 1) {
                problems.add("explore " + entry.getKey() + " " + view);
            }
        });
        assertEquals(List.of(), problems, layouts.toString());
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

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account viewer = register("Browser viewer");
        profile(viewer, "Browser viewer", w("browser") + ", " + w("puzzle") + ", " + w("garden"), w("browsergoal"), "Gardener");
        Account twin = register("Browser twin");
        profile(twin, "Browser twin", w("browser") + ", " + w("puzzle") + ", " + w("garden"), w("browsergoal"), "Gardener");
        long twinRoutine = routine(twin, "Twin " + w("browser"), true);
        habit(twin, twinRoutine, "Water the " + w("garden"), null, null, null);
        routine(twin, "Twin private " + w("browser"), false);
        Account tricky = register("Tricky " + TRICKY);
        profile(tricky, "Tricky " + TRICKY, w("puzzle"), "", "A very long profession name that has to wrap inside the card on a phone");
        Account empty = register("Browser empty");

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("stub", AI.url());
        fixtures.put("viewer", Map.of("id", viewer.id(), "token", viewer.token()));
        fixtures.put("empty", Map.of("id", empty.id(), "token", empty.token()));

        Path dir = Files.createTempDirectory("lifestyle-matches-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/lifestyle-matches-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
        AI.mode = "words";
        AI.delayMs = 0;
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- the stand-in matching service ----

    /**
     * Answers POST /match like ai/app.py, scoring by shared words (Jaccard) instead of the model,
     * and remembers the last request. GET /__mode?mode=..&delay=.. switches how it behaves:
     * words, error (500), garbage (not JSON), nolist (JSON without matches), slow (past the
     * timeout) or inject (adds people who were not sent, a duplicate and an impossible score).
     */
    static final class StubAi {

        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final ObjectMapper mapper = new ObjectMapper();
        volatile String mode = "words";
        volatile int delayMs = 0;
        volatile long injectId = 0;
        volatile String lastBody = "";
        volatile JsonNode lastRequest;
        volatile String lastToken;

        StubAi() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            server.createContext("/match", this::match);
            server.createContext("/__mode", this::switchMode);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void switchMode(HttpExchange exchange) throws IOException {
            Map<String, String> query = new HashMap<>();
            for (String part : String.valueOf(exchange.getRequestURI().getQuery()).split("&")) {
                String[] pair = part.split("=", 2);
                query.put(pair[0], pair.length > 1 ? pair[1] : "");
            }
            mode = query.getOrDefault("mode", "words");
            delayMs = Integer.parseInt(query.getOrDefault("delay", "0"));
            reply(exchange, 200, "{}");
        }

        private void match(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            lastToken = exchange.getRequestHeaders().getFirst("X-AI-Token");
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequest = mapper.readTree(lastBody);
            pause("slow".equals(mode) ? 5000 : delayMs);

            switch (mode) {
                case "error" -> reply(exchange, 500, "{\"message\":\"Matching failed\"}");
                case "garbage" -> reply(exchange, 200, "this is not json");
                case "nolist" -> reply(exchange, 200, "{\"status\":\"ok\"}");
                default -> reply(exchange, 200, mapper.writeValueAsString(answer(lastRequest)));
            }
        }

        private ObjectNode answer(JsonNode request) {
            Set<String> mine = words(request.path("user"));
            List<String> myInterests = interests(request.path("user"));
            List<ObjectNode> found = new ArrayList<>();

            for (JsonNode candidate : request.path("candidates")) {
                Set<String> theirs = words(candidate);
                Set<String> shared = new HashSet<>(mine);
                shared.retainAll(theirs);
                if (shared.isEmpty()) {
                    continue;
                }
                Set<String> all = new HashSet<>(mine);
                all.addAll(theirs);
                double similarity = (double) shared.size() / all.size();

                ObjectNode match = mapper.createObjectNode();
                match.put("userId", candidate.path("id").asLong());
                match.put("score", Math.min(1.0, 0.3 + 0.7 * similarity));
                ObjectNode components = match.putObject("components");
                components.put("semantic", similarity);
                components.putNull("schedule");
                components.putNull("interests");
                ObjectNode factors = match.putObject("factors");
                ArrayNode pairs = factors.putArray("sharedInterests");
                for (String interest : interests(candidate)) {
                    if (myInterests.contains(interest)) {
                        pairs.addObject().put("mine", interest).put("theirs", interest).put("similarity", 1.0);
                    }
                }
                factors.putArray("similarActivities");
                factors.putArray("sharedTimesOfDay");
                factors.putArray("sharedDays");
                found.add(match);
            }

            found.sort((a, b) -> Double.compare(b.path("score").asDouble(), a.path("score").asDouble()));
            ObjectNode reply = mapper.createObjectNode();
            reply.put("status", "ok");
            ArrayNode matches = reply.putArray("matches");
            found.stream().limit(request.path("limit").asInt(10)).forEach(matches::add);

            if ("inject".equals(mode)) {
                matches.insert(0, mapper.createObjectNode().put("userId", 999_999_999L).put("score", 0.99));
                matches.insert(0, mapper.createObjectNode().put("userId", injectId).put("score", 0.98));
                if (!found.isEmpty()) {
                    matches.add(found.get(0).deepCopy());
                    matches.add(found.get(0).deepCopy().put("score", 7.0));
                }
            }
            return reply;
        }

        private static Set<String> words(JsonNode person) {
            StringBuilder text = new StringBuilder();
            for (String field : List.of("interests", "goals", "bio", "profession")) {
                text.append(' ').append(person.path(field).asString());
            }
            for (JsonNode routine : person.path("routines")) {
                text.append(' ').append(routine.path("title").asString());
                for (JsonNode activity : routine.path("activities")) {
                    text.append(' ').append(activity.path("title").asString());
                }
            }
            Set<String> words = new HashSet<>();
            for (String word : text.toString().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
                if (word.length() >= 6) {
                    words.add(word);
                }
            }
            return words;
        }

        private static List<String> interests(JsonNode person) {
            return Arrays.stream(person.path("interests").asString().split(","))
                    .map(String::strip).filter(item -> !item.isEmpty()).toList();
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
                // The backend gave up waiting (the slow mode); nobody reads this answer.
            } finally {
                exchange.close();
            }
        }
    }

    // ---- helpers ----

    private static String w(String word) {
        return word + RUN;
    }

    private JsonNode matches(Account who) throws Exception {
        Reply reply = get(PATH, who);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private static List<Long> cardIds(JsonNode reply) {
        return longs(reply.path("matches"), "userId");
    }

    private static JsonNode card(JsonNode reply, long userId) {
        for (JsonNode card : reply.path("matches")) {
            if (card.path("userId").asLong() == userId) {
                return card;
            }
        }
        throw new AssertionError("no card for " + userId + " in " + reply);
    }

    private static Set<Long> sentIds() {
        return new HashSet<>(longs(AI.lastRequest.path("candidates"), "id"));
    }

    private static JsonNode sentCandidate(long id) {
        for (JsonNode candidate : AI.lastRequest.path("candidates")) {
            if (candidate.path("id").asLong() == id) {
                return candidate;
            }
        }
        throw new AssertionError(id + " was not sent");
    }

    private static List<Long> longs(JsonNode list, String field) {
        List<Long> values = new ArrayList<>();
        list.forEach(item -> values.add(item.path(field).asLong()));
        return values;
    }

    private static List<String> texts(JsonNode list, String field) {
        List<String> values = new ArrayList<>();
        list.forEach(item -> values.add(field == null ? item.asString() : item.path(field).asString()));
        return values;
    }

    private List<Long> steps(long routine) {
        return jdbc.queryForList("select id from routine_habits where routine_id = ? order by position", Long.class, routine);
    }

    private void profile(Account who, String name, String interests, String goals, String profession) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("interests", interests);
        body.put("goals", goals);
        body.put("profession", profession);
        body.put("bio", "");
        Reply reply = send("PUT", "/users/me", who, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "match26_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Matching check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "60");
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

    private void habit(Account owner, long routine, String title, String time, List<String> days, String notes) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", title + " description");
        body.put("durationMinutes", 5);
        if (time != null) {
            body.put("startTime", time);
            body.put("repeatDays", days);
        }
        if (notes != null) {
            body.put("notes", notes);
        }
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner, body);
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply get(String path, Account who) throws Exception {
        return send("GET", path, who, null);
    }

    private Reply send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
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
