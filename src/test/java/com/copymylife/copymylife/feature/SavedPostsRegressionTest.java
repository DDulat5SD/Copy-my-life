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

import java.io.ByteArrayOutputStream;
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
 * US-16, the posts part ("bookmark routines and posts"): a saved post appears in Saved; once it
 * is private (or hidden by an administrator, or its author banned) it is no longer accessible
 * there. Only public posts can be saved, each once, and only the saver sees their bookmarks.
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
                "app.upload-dir=target/test-uploads"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedPostsRegressionTest {

    private static final String BOUNDARY = "SavedPostBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

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

    // ---- AC1: a saved post appears in Saved ----

    @Test
    void aSavedPostAppearsInSavedOnce() throws Exception {
        Account author = register("Author");
        Account reader = register("Reader");
        long post = createPost(author, "Morning pages", true);

        Reply saved = send("POST", "/saved-posts/" + post, reader);
        assertEquals(200, saved.status(), saved.body());
        assertTrue(json.readTree(saved.body()).path("savedByMe").asBoolean(), saved.body());
        assertEquals(200, send("POST", "/saved-posts/" + post, reader).status(), "saving again is fine");
        assertEquals(1, count("select count(*) from saved_posts where user_id = ? and post_id = ?", reader.id(), post));

        JsonNode list = saved(reader);
        assertEquals(1, list.size(), list.toString());
        assertEquals("Morning pages", list.get(0).path("text").asString());
        assertTrue(json.readTree(send("GET", "/posts/" + post, reader).body()).path("savedByMe").asBoolean());
        assertFalse(json.readTree(send("GET", "/posts/" + post, author).body()).path("savedByMe").asBoolean(),
                "a bookmark is the saver's own");

        assertEquals(204, send("DELETE", "/saved-posts/" + post, reader).status());
        assertEquals(0, saved(reader).size());
        assertEquals(204, send("DELETE", "/saved-posts/" + post, reader).status(), "removing again is fine");
    }

    @Test
    void newestBookmarkComesFirst() throws Exception {
        Account author = register("Two posts");
        Account reader = register("Two saves");
        long first = createPost(author, "First", true);
        long second = createPost(author, "Second", true);
        send("POST", "/saved-posts/" + first, reader);
        send("POST", "/saved-posts/" + second, reader);
        JsonNode list = saved(reader);
        assertEquals(List.of(second, first), List.of(list.get(0).path("id").asLong(), list.get(1).path("id").asLong()));
    }

    // ---- AC2: no longer accessible once it is private ----

    @Test
    void aSavedPostMadePrivateIsNoLongerAccessible() throws Exception {
        Account author = register("Changes mind");
        Account reader = register("Keeps bookmarks");
        long post = createPost(author, "Soon private", true);
        assertEquals(200, send("POST", "/saved-posts/" + post, reader).status());

        assertEquals(200, postForm("PUT", "/posts/" + post, author, Map.of("isPublic", "false")).status());
        assertEquals(0, saved(reader).size());
        assertEquals(404, send("GET", "/posts/" + post, reader).status());
        assertEquals(404, send("POST", "/saved-posts/" + post, reader).status(), "a private post reads as missing");

        // Public again: the bookmark was kept.
        assertEquals(200, postForm("PUT", "/posts/" + post, author, Map.of("isPublic", "true")).status());
        assertEquals(1, saved(reader).size());
    }

    @Test
    void hiddenPostsAndBannedAuthorsDropOutOfSaved() throws Exception {
        Account author = register("Moderated");
        Account reader = register("Moderated reader");
        long hidden = createPost(author, "Hidden later", true);
        long kept = createPost(register("Other author"), "Still here", true);
        send("POST", "/saved-posts/" + hidden, reader);
        send("POST", "/saved-posts/" + kept, reader);

        jdbc.update("update posts set hidden_at = now() where id = ?", hidden);
        JsonNode list = saved(reader);
        assertEquals(1, list.size(), list.toString());
        assertEquals(kept, list.get(0).path("id").asLong());

        jdbc.update("update posts set hidden_at = null where id = ?", hidden);
        jdbc.update("update users set banned = true where id = ?", author.id());
        assertEquals(1, saved(reader).size());
    }

    // ---- what can be saved, and by whom ----

    @Test
    void onlyPublicPostsOfOthersOrOneselfCanBeSaved() throws Exception {
        Account author = register("Private author");
        Account reader = register("Curious");
        long secret = createPost(author, "Private " + TRICKY, false);

        assertEquals(404, send("POST", "/saved-posts/" + secret, reader).status());
        assertEquals(400, send("POST", "/saved-posts/" + secret, author).status(), "not one's own private post either");
        assertEquals(404, send("POST", "/saved-posts/99999999", reader).status());
        assertEquals(0, count("select count(*) from saved_posts where post_id = ?", secret));
        for (String[] call : new String[][]{{"GET", "/saved-posts"}, {"POST", "/saved-posts/" + secret},
                {"DELETE", "/saved-posts/" + secret}}) {
            int status = send(call[0], call[1], null).status();
            assertTrue(status == 401 || status == 403, call[1] + " " + status);
        }
    }

    @Test
    void bookmarksArePrivateToTheirOwner() throws Exception {
        Account author = register("Shared author");
        Account one = register("Saver one");
        Account two = register("Saver two");
        long post = createPost(author, "Shared", true);
        send("POST", "/saved-posts/" + post, one);

        assertEquals(0, saved(two).size());
        assertFalse(json.readTree(send("GET", "/posts/" + post, two).body()).path("savedByMe").asBoolean());
        // Someone else removing "their" bookmark changes nothing for the saver.
        assertEquals(204, send("DELETE", "/saved-posts/" + post, two).status());
        assertEquals(1, saved(one).size());
    }

    @Test
    void deletingASavedPostStillWorks() throws Exception {
        Account author = register("Deletes");
        Account reader = register("Saved it");
        long post = createPost(author, "Gone soon", true);
        send("POST", "/saved-posts/" + post, reader);

        Reply deleted = send("DELETE", "/posts/" + post, author);
        assertTrue(deleted.status() == 200 || deleted.status() == 204, deleted.body());
        assertEquals(0, count("select count(*) from saved_posts where post_id = ?", post));
        assertEquals(0, saved(reader).size());
    }

    // ---- browser ----

    @Test
    void browserSavesFromAProfileAndListsInSavedPosts() {
        JsonNode view = page("flow");
        assertEquals("Save", view.path("before").asString(), view.toString());
        assertEquals("Saved", view.path("after").asString(), view.toString());
        assertTrue(view.path("savedTexts").toString().contains("Browser post"), view.toString());
        assertFalse(view.path("formInSaved").asBoolean(), "no new-post form on Saved: " + view);
        assertTrue(view.path("emptyAfterRemove").asBoolean(), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());

        JsonNode layouts = page("layouts");
        assertEquals(3, layouts.size(), layouts.toString());
        layouts.properties().forEach(entry -> {
            assertTrue(entry.getValue().path("overflow").asInt() <= 0, entry.getKey() + " " + entry.getValue());
            assertTrue(entry.getValue().path("inside").asBoolean(), entry.getKey() + " " + entry.getValue());
            assertEquals(2, entry.getValue().path("tabs").asInt(), entry.getKey() + " " + entry.getValue());
            assertEquals(1, entry.getValue().path("posts").asInt(), "the saved post is shown: " + entry);
        });
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    @BeforeAll
    void createContent() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account author = register("Browser author");
        Account reader = register("Browser reader");
        long post = createPost(author, "Browser post " + TRICKY, true);
        Account layoutReader = register("Layout reader");
        send("POST", "/saved-posts/" + createPost(author, "Kept for layout", true), layoutReader);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("readerToken", reader.token());
        fixtures.put("layoutToken", layoutReader.token());
        fixtures.put("authorId", author.id());
        fixtures.put("postId", post);

        Path dir = Files.createTempDirectory("saved-posts-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/saved-posts-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(5, TimeUnit.MINUTES)) {
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

    // ---- helpers ----

    private JsonNode saved(Account account) throws Exception {
        Reply reply = send("GET", "/saved-posts", account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Account register(String name) throws Exception {
        Map<String, String> body = Map.of(
                "name", name,
                "email", "savedpost_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        );
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = json.readTree(response.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long createPost(Account owner, String text, boolean isPublic) throws Exception {
        Reply reply = postForm("POST", "/posts", owner, Map.of("text", text, "isPublic", String.valueOf(isPublic)));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply postForm(String method, String path, Account account, Map<String, String> fields) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + account.token())
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply send(String method, String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).method(method, HttpRequest.BodyPublishers.noBody());
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
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
