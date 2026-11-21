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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-04: Explore lists public routines, never private ones, and says so clearly when there
 * is nothing to list: no routines at all, routines that could not be loaded, or a search or
 * filter that matches nothing. The empty cases are answered inside the browser only, so no
 * routine is ever removed to test them.
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
class ExplorePublicRoutinesRegressionTest {

    private static final String BOUNDARY = "ExploreBoundary";
    private static final String NO_ROUTINES = "No routines yet";
    private static final String NO_ROUTINES_TEXT = "There are no public routines to explore right now. Please check back later.";
    private static final String NOT_LOADED = "Routines could not be loaded";
    private static final String NOT_LOADED_TEXT = "Please refresh the page to try again.";
    private static final String NO_MATCH = "No routines found";
    private static final String NO_MATCH_TEXT = "Try another search or filter.";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;
    private String routinesBefore;
    private String routinesAfter;

    private record Reply(int status, String body) {
    }

    // ---- what Explore lists ----

    @Test
    void explorePublishesPublicRoutinesAndNeverPrivateOnes() throws Exception {
        String token = register();
        long shared = routine(token, "Explore shared " + UUID.randomUUID(), true);
        long hidden = routine(token, "Explore hidden " + UUID.randomUUID(), false);

        Reply reply = get("/routines/public");

        assertEquals(200, reply.status(), reply.body());
        List<Long> ids = new ArrayList<>();
        json.readTree(reply.body()).forEach(routine -> {
            ids.add(routine.path("id").asLong());
            assertTrue(routine.path("public").asBoolean(), routine.toString());
        });
        assertTrue(ids.contains(shared), reply.body());
        assertFalse(ids.contains(hidden), reply.body());
    }

    @Test
    void visitorsCanOpenExploreAndBothRoutineLists() throws Exception {
        assertEquals(200, get("/Explore.html").status());
        assertEquals(200, get("/routines/public").status());
        assertEquals(200, get("/routines/built-in").status());
    }

    @Test
    void testingTheEmptyCasesRemovedNoRoutine() {
        Assumptions.assumeTrue(routinesAfter != null, "Chrome or node not found");
        assertEquals(routinesBefore, routinesAfter);
    }

    // ---- routines are listed ----

    @Test
    void browserListsRoutinesWithoutAnEmptyState() {
        JsonNode view = page("listed");

        assertFalse(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(6, view.path("cards").asInt(), view.toString());
        assertTrue(view.path("viewAllButton").asBoolean(), view.toString());
        if (count("select count(*) from routines where built_in = true and deleted = false") > 0) {
            assertTrue(view.path("routineLinks").asInt() > 0, "built-in routines still open from Explore: " + view);
        }
    }

    // ---- nothing to list ----

    @Test
    void browserExplainsWhenThereAreNoRoutinesAtAll() {
        JsonNode view = page("none");

        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(NO_ROUTINES, view.path("emptyTitle").asString());
        assertEquals(NO_ROUTINES_TEXT, view.path("emptyText").asString());
        assertEquals(0, view.path("cards").asInt(), "no card without a routine behind it: " + view);
        assertFalse(view.path("viewAllButton").asBoolean(), view.toString());
        assertEquals("status", view.path("emptyRole").asString());
        assertEquals("polite", view.path("emptyLive").asString());

        JsonNode filtered = page("noneFiltered");
        assertTrue(filtered.path("emptyVisible").asBoolean(), filtered.toString());
        assertEquals(NO_ROUTINES, filtered.path("emptyTitle").asString());
        assertEquals(0, filtered.path("cards").asInt(), filtered.toString());
    }

    @Test
    void browserExplainsWhenRoutinesCannotBeLoaded() {
        JsonNode view = page("failed");

        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(NOT_LOADED, view.path("emptyTitle").asString());
        assertEquals(NOT_LOADED_TEXT, view.path("emptyText").asString());
        assertEquals(0, view.path("cards").asInt(), view.toString());
        assertFalse(view.path("viewAllButton").asBoolean(), view.toString());
    }

    @Test
    void theEmptyPagesNeverFailOrInterrupt() {
        assertEquals("[]", page("errors").toString(), "the page throws nothing");
        assertEquals("[]", page("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    // ---- a search or filter that matches nothing ----

    @Test
    void browserExplainsASearchWithNoResultsAndRecovers() {
        JsonNode view = page("noMatch");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(NO_MATCH, view.path("emptyTitle").asString());
        assertEquals(NO_MATCH_TEXT, view.path("emptyText").asString());
        assertEquals(0, view.path("cards").asInt(), view.toString());

        JsonNode cleared = page("cleared");
        assertFalse(cleared.path("emptyVisible").asBoolean(), cleared.toString());
        assertEquals(6, cleared.path("cards").asInt(), cleared.toString());
    }

    @Test
    void browserShowsOneMessageWhenViewAllIsOpenToo() {
        JsonNode open = page("viewAllOpen");
        assertFalse(open.path("emptyVisible").asBoolean(), open.toString());
        assertTrue(open.path("viewAllCards").size() > 0, open.toString());

        JsonNode view = page("viewAllNoMatch");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(NO_MATCH, view.path("emptyTitle").asString());
        assertFalse(view.path("viewAllEmpty").asBoolean(), "the message is not repeated under View all: " + view);
        assertEquals(0, view.path("viewAllCards").size(), view.toString());

        JsonNode cleared = page("viewAllCleared");
        assertFalse(cleared.path("emptyVisible").asBoolean(), cleared.toString());
        assertEquals(open.path("viewAllCards").size(), cleared.path("viewAllCards").size(), cleared.toString());
    }

    @Test
    void browserPointsToViewAllWhenOnlyMoreRoutinesMatch() {
        JsonNode view = page("onlyMore");
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals("No featured routines match", view.path("emptyTitle").asString());
        assertEquals("Open View all to see more results.", view.path("emptyText").asString());
        assertTrue(view.path("viewAllButton").asBoolean(), view.toString());

        JsonNode open = page("onlyMoreOpen");
        assertFalse(open.path("emptyVisible").asBoolean(), open.toString());
        assertTrue(open.path("viewAllCards").toString().contains("Michael Jordan"), open.toString());
    }

    @Test
    void browserAppliesTheSearchToTheChosenFilter() {
        JsonNode study = page("filterNoMatch");
        assertTrue(study.path("emptyVisible").asBoolean(), study.toString());
        assertEquals(NO_MATCH, study.path("emptyTitle").asString());
        assertEquals(0, study.path("cards").asInt(), study.toString());

        JsonNode fitness = page("filterMatch");
        assertFalse(fitness.path("emptyVisible").asBoolean(), fitness.toString());
        assertEquals("[\"David Goggins\"]", fitness.path("names").toString(), "the search still applies: " + fitness);
    }

    @Test
    void browserExplainsAnEmptyHalloweenSearch() {
        JsonNode view = page("halloweenNoMatch");
        assertTrue(view.path("halloween").asBoolean(), view.toString());
        assertTrue(view.path("emptyVisible").asBoolean(), view.toString());
        assertEquals(NO_MATCH, view.path("emptyTitle").asString());

        JsonNode cleared = page("halloweenCleared");
        assertFalse(cleared.path("emptyVisible").asBoolean(), cleared.toString());
        assertEquals(6, cleared.path("cards").asInt(), cleared.toString());
    }

    // ---- every design ----

    @Test
    void browserShowsTheEmptyStateInEveryDesignAndOnA390PixelPhone() {
        // Every layout is checked before failing, so one problem cannot hide another.
        List<String> problems = new ArrayList<>();
        for (String layout : List.of("originalPhone", "autumnPhone", "originalDesktop", "autumnDesktop",
                "originalSearchPhone", "autumnSearchPhone", "halloweenPhone", "autumnHalloweenPhone")) {
            JsonNode view = page(layout);
            if (!view.path("rendered").asBoolean()) {
                problems.add(layout + " not shown " + view);
            }
            // On a desktop only the message is measured: the hero above it is wider than a
            // 1280 px window with or without routines, and US-04 leaves the hero as it was.
            if (view.path("width").asInt() < 600 && view.path("overflow").asInt() > 0) {
                problems.add(layout + " scrolls sideways " + view);
            }
            if (view.path("left").asInt() < 0 || view.path("rightmost").asInt() > view.path("width").asInt()) {
                problems.add(layout + " leaves the screen " + view);
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(layout + " hard to read " + view);
            }
        }
        assertEquals(List.of(), problems);
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
        assertTrue(page("halloweenPhone").path("halloween").asBoolean());
        assertEquals("autumn", page("autumnHalloweenPhone").path("theme").asString());
    }

    // ---- real browser ----

    @BeforeAll
    void openExploreAsAVisitor() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);

        routinesBefore = routineSnapshot();

        Path dir = Files.createTempDirectory("explore-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/explore-empty-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));

        routinesAfter = routineSnapshot();
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    // Every routine Explore could list, with what it shows, in one comparable string.
    private String routineSnapshot() {
        return String.valueOf(jdbc.queryForList(
                "select id, title, is_public, built_in, deleted from routines order by id"));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String register() throws Exception {
        Reply reply = post("/auth/register", Map.of(
                "name", "Explore QA",
                "email", "explore_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("token").asString();
    }

    private long routine(String token, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Explore check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder(uri("/routines"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private Reply get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply post(String path, Map<String, ?> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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
