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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class RoutineOwnerControlsRegressionTest {

    private static final String BOUNDARY = "OwnerRegressionBoundary";
    private static final String RENAMED = "Owner renamed this";

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

    // ---- API ----

    @Test
    void detailsTellTheOwnerWithoutAUsernameThatTheRoutineIsTheirs() throws Exception {
        String owner = register();
        assertTrue(json.readTree(get("/users/me", owner).body()).path("username").isNull());

        for (boolean isPublic : List.of(true, false)) {
            long routine = routine(owner, "Mine", isPublic);
            JsonNode details = json.readTree(get("/routines/" + routine, owner).body());

            assertTrue(details.path("ownedByMe").asBoolean(), "public=" + isPublic);
        }
    }

    @Test
    void detailsTellOtherUsersItIsNotTheirs() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "Theirs", true);

        JsonNode details = json.readTree(get("/routines/" + routine, stranger).body());

        assertTrue(details.has("ownedByMe"));
        assertFalse(details.path("ownedByMe").asBoolean());
    }

    @Test
    void ownershipDoesNotFollowTheUsername() throws Exception {
        String shared = "shared" + suffix();
        String owner = register();
        String twin = register();
        setUsername(owner, shared);
        setUsername(twin, shared);
        long routine = routine(owner, "Same name", true);

        assertTrue(json.readTree(get("/routines/" + routine, owner).body()).path("ownedByMe").asBoolean());
        assertFalse(json.readTree(get("/routines/" + routine, twin).body()).path("ownedByMe").asBoolean());
    }

    @Test
    void addingTheFlagDoesNotChangeOtherResponses() throws Exception {
        String owner = register();
        String fan = register();
        long routine = routine(owner, "Listed", true);
        post("/saved-routines/" + routine, fan, null);
        post("/liked-routines/" + routine, fan, null);

        for (String list : List.of("/routines/public", "/routines/built-in")) {
            assertFalse(get(list, null).body().contains("ownedByMe"), list);
        }
        for (String list : List.of("/saved-routines", "/liked-routines", "/routines")) {
            assertFalse(get(list, list.equals("/routines") ? owner : fan).body().contains("ownedByMe"), list);
        }

        JsonNode details = json.readTree(get("/routines/" + routine, fan).body());
        for (String field : List.of("id", "title", "description", "durationMinutes", "category", "public", "imageUrl",
                "completed", "averageRating", "ratingCount", "authorName", "authorAvatarUrl", "authorUsername",
                "likeCount", "saveCount", "builtIn")) {
            assertTrue(details.has(field), field);
        }
    }

    @Test
    void ownerWithoutAUsernameCanEditManageHabitsAndStart() throws Exception {
        String owner = register();
        long routine = routine(owner, "Usable", false, 5);

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "Usable renamed", false, 5).status());
        assertEquals(200, post("/routines/" + routine + "/habits", owner, Map.of("title", "One", "durationMinutes", 5)).status());
        assertEquals(200, get("/routines/" + routine + "/routine-habits", owner).status());
        assertEquals(200, post("/routine-runs/" + routine + "/start", owner, null).status());
    }

    @Test
    void theServerStillRefusesEveryoneButTheOwner() throws Exception {
        String shared = "guard" + suffix();
        String owner = register();
        String twin = register();
        setUsername(owner, shared);
        setUsername(twin, shared);
        long open = routine(owner, "Open", true);
        long hidden = routine(owner, "Hidden", false);

        for (long routine : List.of(open, hidden)) {
            assertEquals(404, saveRoutine("PUT", "/routines/" + routine, twin, "Hacked", true, 60).status());
            assertEquals(404, send("DELETE", "/routines/" + routine, twin, null).status());
            assertEquals(404, post("/routines/" + routine + "/habits", twin, Map.of("title", "x", "durationMinutes", 1)).status());
            assertEquals(400, post("/routine-runs/" + routine + "/start", twin, null).status());
        }
        assertEquals(200, get("/routines/" + open, twin).status());
        assertEquals(404, get("/routines/" + hidden, twin).status());
        assertEquals("Open", jdbc.queryForObject("select title from routines where id = ?", String.class, open));
    }

    @Test
    void openingARoutineChangesNoUserOrRoutineData() throws Exception {
        String owner = register();
        long id = json.readTree(get("/users/me", owner).body()).path("id").asLong();
        long routine = routine(owner, "Unchanged", true);
        Map<String, Object> userBefore = jdbc.queryForMap("select * from users where id = ?", id);
        Map<String, Object> routineBefore = jdbc.queryForMap("select * from routines where id = ?", routine);

        get("/routines/" + routine, owner);

        assertEquals(userBefore, jdbc.queryForMap("select * from users where id = ?", id));
        assertEquals(routineBefore, jdbc.queryForMap("select * from routines where id = ?", routine));
    }

    @Test
    void pageNoLongerComparesUsernames() throws Exception {
        String page = get("/routine.html", null).body();

        assertTrue(page.contains("routineData.ownedByMe === true"));
        assertFalse(page.contains("userData.username\n                    .toLowerCase()"));
        assertFalse(page.replace("\r\n", "\n").contains("===\n            userData.username"));
    }

    // ---- real browser ----

    private JsonNode browser;

    @BeforeAll
    void openPagesInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        String owner = register();
        String stranger = register();
        String shared = "browser" + suffix();
        String namedOwner = register();
        String twin = register();
        setUsername(namedOwner, shared);
        setUsername(twin, shared);

        long publicRoutine = routine(owner, "Public of owner", true);
        long privateRoutine = routine(owner, "Private of owner", false, 5);
        long namedRoutine = routine(namedOwner, "Named owner routine", true);
        assertEquals(200, post("/routines/" + privateRoutine + "/habits", owner, Map.of("title", "Only habit", "durationMinutes", 5)).status());

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerToken", owner);
        fixtures.put("strangerToken", stranger);
        fixtures.put("namedOwnerToken", namedOwner);
        fixtures.put("sameUsernameToken", twin);
        fixtures.put("publicRoutine", publicRoutine);
        fixtures.put("privateRoutine", privateRoutine);
        fixtures.put("namedOwnerRoutine", namedRoutine);
        fixtures.put("renamedTitle", RENAMED);

        Path dir = Files.createTempDirectory("owner-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/owner-controls-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Browser check timed out");
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    private void assertButtons(String scenario, boolean expected) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        JsonNode page = browser.path(scenario);

        assertEquals(expected, page.path("edit").asBoolean(), scenario + " edit: " + page);
        assertEquals(expected, page.path("editHabits").asBoolean(), scenario + " edit habits: " + page);
        assertEquals(expected, page.path("start").asBoolean(), scenario + " start: " + page);
    }

    @Test
    void ownerWithoutAUsernameSeesTheButtonsOnPublicAndPrivateRoutines() {
        assertButtons("ownerNoUsernamePublic", true);
        assertButtons("ownerNoUsernamePrivate", true);
        assertEquals("Private of owner", browser.path("ownerNoUsernamePrivate").path("title").asString());
    }

    @Test
    void ownerWithAUsernameStillSeesTheButtons() {
        assertButtons("ownerWithUsernamePublic", true);
    }

    @Test
    void otherUsersSeeAPublicRoutineWithoutTheButtons() {
        assertButtons("strangerPublic", false);
        assertEquals("Public of owner", browser.path("strangerPublic").path("title").asString());
    }

    @Test
    void sharingAUsernameDoesNotShowTheButtons() {
        assertButtons("sameUsernameStranger", false);
    }

    @Test
    void otherUsersCannotOpenAPrivateRoutine() {
        assertButtons("strangerPrivate", false);
        assertTrue(browser.path("strangerPrivate").path("unavailable").asBoolean());
    }

    @Test
    void theButtonsReallyWorkForAnOwnerWithoutAUsername() {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");

        assertTrue(browser.path("editResult").path("opened").asBoolean(), browser.toString());
        assertEquals(RENAMED, browser.path("editResult").path("title").asString());
        assertTrue(browser.path("editHabitsResult").path("opened").asBoolean());
        assertEquals(1, browser.path("editHabitsResult").path("rows").asInt());
        assertEquals("/routine-run.html", browser.path("startResult").path("path").asString());
        assertTrue(browser.path("startResult").path("running").asBoolean());
    }

    // ---- helpers ----

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Owner QA",
                "email", "owner_" + suffix() + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("token").asString();
    }

    // Written straight to the table: the API now refuses a taken username, but accounts from
    // before that rule can still share one, and ownership must not depend on it.
    private void setUsername(String token, String username) throws Exception {
        long id = json.readTree(get("/users/me", token).body()).path("id").asLong();
        assertEquals(1, jdbc.update("update users set username = ? where id = ?", username, id));
    }

    private long routine(String token, String title, boolean isPublic) throws Exception {
        return routine(token, title, isPublic, 60);
    }

    private long routine(String token, String title, boolean isPublic, int minutes) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", token, title, isPublic, minutes);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply saveRoutine(String method, String path, String token, String title, boolean isPublic, int minutes) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "d");
        fields.put("category", "Morning");
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Reply post(String path, String token, Map<String, ?> payload) throws Exception {
        return send("POST", path, token, payload);
    }

    private Reply send(String method, String path, String token, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
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
