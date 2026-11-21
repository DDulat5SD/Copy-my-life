package com.copymylife.copymylife.security;

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
 * Stores hostile values, then opens the real pages in headless Chrome and checks that nothing ran.
 * Needs TEST_DB_URL (a local *_test database), node, and Chrome; skipped when node or Chrome is absent.
 * URL fields are written straight to the database to imitate data saved before validation existed.
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
class StoredXssBrowserRegressionTest {

    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "XssRegressionBoundary";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode pages;

    private record Account(long id, String token) {
    }

    private static String hostile(String id) {
        return "Evil\" onmouseover=\"__mark('" + id + "-attr')\" '><img src=x onerror=__mark('" + id + "-html')>";
    }

    @BeforeAll
    void storeHostileDataAndOpenPages() throws Exception {
        assertTrue(
                jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database"
        );

        String chrome = findChrome();
        Assumptions.assumeTrue(chrome != null, "Chrome not found; set CHROME_BIN");
        Assumptions.assumeTrue(nodeAvailable(), "node not found");

        Map<String, Object> fixtures = seed();
        fixtures.put("base", "http://127.0.0.1:" + port);

        Path dir = Files.createTempDirectory("xss-regression");
        Path fixturesFile = dir.resolve("fixtures.json");
        Path resultsFile = dir.resolve("results.json");
        Files.writeString(fixturesFile, json.writeValueAsString(fixtures));

        Process process = new ProcessBuilder(
                "node", "src/test/e2e/xss-browser-check.mjs", chrome, fixturesFile.toString(), resultsFile.toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "Browser check timed out");
        assertEquals(0, process.exitValue(), () -> "Browser check failed: " + readQuietly(dir.resolve("node.log")));

        pages = json.readTree(Files.readString(resultsFile));
    }

    @Test
    void noStoredValueRunsAsScriptOnAnyPage() {
        List<String> problems = new ArrayList<>();
        for (JsonNode page : pages) {
            if (!page.path("marks").isEmpty()) {
                problems.add(page.path("name").asString() + " executed " + page.path("marks"));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void noStoredValueBecomesAnEventHandlerOrDangerousUrl() {
        List<String> problems = new ArrayList<>();
        for (JsonNode page : pages) {
            if (!page.path("hits").isEmpty()) {
                problems.add(page.path("name").asString() + " " + page.path("hits"));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void hostileAndNormalTextIsShownLiterallyAndImagesStillLoad() {
        List<String> problems = new ArrayList<>();
        for (JsonNode page : pages) {
            if (!page.path("missingText").isEmpty() || !page.path("missingImages").isEmpty()) {
                problems.add(page.path("name").asString()
                        + " missing text " + page.path("missingText")
                        + " missing images " + page.path("missingImages"));
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void pagesDoNotFailBecauseOfTheEscapingHelper() {
        for (JsonNode page : pages) {
            assertFalse(page.path("exceptions").toString().contains("SafeDom"), page.toString());
        }
    }

    private Map<String, Object> seed() throws Exception {
        Account viewer = register("Viewer QA");
        put("/users/me", viewer.token(), Map.of("name", "Viewer QA", "username", "viewer" + suffix()));
        Account attacker = register(hostile("name"));
        put("/users/me", attacker.token(), Map.of(
                "name", hostile("name"),
                "username", hostile("username"),
                "profession", hostile("profession"),
                "bio", hostile("bio")
        ));
        Account normal = register("Normal QA");
        put("/users/me", normal.token(), Map.of("name", "Normal QA", "bio", "Plain bio & <i>text</i>"));

        long hostileRoutine = routine(attacker, hostile("title"), hostile("desc"), hostile("category"), 30, true);
        long hostileHabit = habit(attacker, hostileRoutine, hostile("habit"), 10);
        long jsImageRoutine = routine(attacker, "Js image routine", "d", "Morning", 30, true);
        long normalRoutine = routine(normal, "Morning \"plan\" & <b>focus</b>", "Normal description", "Morning", 30, true);
        long ownRoutine = routine(viewer, hostile("own-title"), hostile("own-desc"), hostile("own-category"), 1, false);
        habit(viewer, ownRoutine, hostile("own-habit"), 1);

        post("/routine-ratings/" + hostileRoutine, viewer.token(), Map.of("value", 5));
        post("/routine-comments/" + hostileRoutine, attacker.token(), Map.of("text", hostile("comment")));
        post("/routine-comments/" + normalRoutine, normal.token(), Map.of("text", "Normal comment"));
        post("/saved-routines/" + hostileRoutine, viewer.token(), null);
        post("/liked-routines/" + hostileRoutine, viewer.token(), null);
        post("/routine-comments/" + hostileRoutine, viewer.token(), Map.of("text", "viewer reply"));

        post("/friends/request/" + viewer.id(), attacker.token(), null);
        Account sentTo = register(hostile("sent"));
        post("/friends/request/" + sentTo.id(), viewer.token(), null);
        Account friend = register(hostile("friend"));
        JsonNode friendRequest = json.readTree(post("/friends/request/" + friend.id(), viewer.token(), null).body());
        put("/friends/requests/" + friendRequest.path("requestId").asLong() + "/accept", friend.token(), Map.of());

        post("/calendar-events", viewer.token(), Map.of(
                "routineId", ownRoutine, "day", LocalDate.now().toString(), "time", "09:00", "repeat", "DAILY"));
        // US-24: a reason is now one of a fixed list; reasons typed before that are written as they were.
        post("/reports/users/" + attacker.id(), viewer.token(), Map.of("reason", "OTHER", "details", hostile("details")));
        jdbc.update("update reports set reason = ? where reporter_id = ? and reported_user_id = ?",
                hostile("report"), viewer.id(), attacker.id());
        post("/routine-runs/" + ownRoutine + "/start", viewer.token(), null);

        // Legacy values: URLs that today's validation would refuse.
        String breakout = "https://x.invalid/a.png\" onerror=\"__mark('url-attr')";
        jdbc.update("update users set avatar_url = ? where id = ?", breakout, attacker.id());
        jdbc.update("update users set avatar_url = ? where id = ?", "javascript:__mark('avatar-js')", viewer.id());
        jdbc.update("update users set avatar_url = ? where id = ?", "data:text/html,<script>__mark('avatar-data')</script>", friend.id());
        jdbc.update("update routines set image_url = ? where id = ?", breakout, hostileRoutine);
        jdbc.update("update routines set image_url = ? where id = ?", "javascript:__mark('image-js')", jsImageRoutine);
        jdbc.update("update routines set image_url = ? where id = ?", "data:image/svg+xml,<svg onload=__mark('image-svg')>", ownRoutine);
        jdbc.update("update routines set image_url = ? where id = ?", "https://x.invalid/ok.png", normalRoutine);

        Account admin = register("Admin QA");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("viewerToken", viewer.token());
        fixtures.put("adminToken", admin.token());
        fixtures.put("attackerId", attacker.id());
        fixtures.put("normalId", normal.id());
        fixtures.put("hostileRoutineId", hostileRoutine);
        fixtures.put("hostileHabitId", hostileHabit);
        fixtures.put("jsImageRoutineId", jsImageRoutine);
        fixtures.put("normalRoutineId", normalRoutine);
        fixtures.put("ownRoutineId", ownRoutine);
        return fixtures;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Account register(String name) throws Exception {
        HttpResponse<String> response = post("/auth/register", null, Map.of(
                "name", name, "email", "xss_" + suffix() + "@audit.test", "password", PASSWORD));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = json.readTree(response.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, String description, String category, int minutes, boolean isPublic) throws Exception {
        StringBuilder body = new StringBuilder();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("category", category);
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));
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

    private long habit(Account owner, long routineId, String title, int minutes) throws Exception {
        HttpResponse<String> response = post("/routines/" + routineId + "/habits", owner.token(), Map.of(
                "title", title, "description", hostile("habit-desc"), "durationMinutes", minutes));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> post(String path, String token, Map<String, ?> payload) throws Exception {
        return send(path, token, "POST", payload);
    }

    private HttpResponse<String> put(String path, String token, Map<String, ?> payload) throws Exception {
        return send(path, token, "PUT", payload);
    }

    private HttpResponse<String> send(String path, String token, String method, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
