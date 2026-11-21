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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Another user's profile shows, in order, their card, Public Routines, Public To-do Lists and
 * Public Posts. A list or a post opens on the same page, read-only for the list and with likes
 * and comments for the post; nothing private is ever listed, opened or downloaded.
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
class UserProfilePublicContentRegressionTest {

    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "UserProfileContentBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">Tricky";
    private static final String LONG_POST = "A calm morning by the lake, then a long walk with friends before breakfast. "
            + "It was the best start of the week and I want many more days that begin exactly like this one.";

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
    private Account owner;
    private Account viewer;
    private long publicList;
    private long privateList;
    private long photoPost;
    private long privatePost;

    // ---- the server keeps private content private ----

    @Test
    void onlyPublicListsAndPostsAreListedAndPrivateOnesCannotBeOpened() throws Exception {
        String lists = get("/users/" + owner.id() + "/todo-lists", viewer).body();
        String posts = get("/users/" + owner.id() + "/posts", viewer).body();
        assertTrue(lists.contains("Morning plan") && !lists.contains("Secret plan"), lists);
        assertTrue(posts.contains("Text only public post") && !posts.contains("Private diary"), posts);

        assertEquals(200, get("/todo-lists/" + publicList, viewer).status());
        assertEquals(404, get("/todo-lists/" + privateList, viewer).status());
        assertEquals(200, get("/posts/" + photoPost, viewer).status());
        assertEquals(404, get("/posts/" + privatePost, viewer).status());
        assertEquals(200, raw("/posts/" + photoPost + "/image", viewer).statusCode());
        assertNotEquals(200, raw("/posts/" + privatePost + "/image", viewer).statusCode());
        assertNotEquals(200, raw("/posts/" + privatePost + "/image", null).statusCode());
    }

    @Test
    void aVisitorCannotChangeSomeoneElsesPublicListOrPost() throws Exception {
        assertNotEquals(200, send("PUT", "/todo-lists/" + publicList, viewer, Map.of("title", "Hijacked")).status());
        assertNotEquals(200, send("POST", "/todo-lists/" + publicList + "/tasks", viewer, Map.of("text", "Sneaky")).status());
        assertNotEquals(200, send("DELETE", "/todo-lists/" + publicList, viewer, null).status());
        assertNotEquals(200, send("DELETE", "/posts/" + photoPost, viewer, null).status());
        assertEquals("Morning plan", jdbc.queryForObject("select title from todo_lists where id = ?", String.class, publicList));
        assertEquals(1, count("select count(*) from posts where id = ?", photoPost));
    }

    @Test
    void aVisitorReadsTheProfileButListsAndPostsNeedSigningIn() throws Exception {
        assertNotEquals(200, get("/users/" + owner.id() + "/todo-lists", null).status());
        assertNotEquals(200, get("/users/" + owner.id() + "/posts", null).status());
        // Changed by the US-05 corrections: a visitor stays on the public profile instead of being
        // sent to sign in, and is asked to sign in for the lists and posts.
        JsonNode guest = page("guest");
        assertTrue(guest.path("path").asString().endsWith("/user-profile.html"), guest.toString());
        assertTrue(guest.path("todo").asString().toLowerCase().contains("sign in"), guest.toString());
        assertTrue(guest.path("posts").asString().toLowerCase().contains("sign in"), guest.toString());
        assertFalse(guest.path("todo").asString().contains("Morning plan"), guest.toString());
    }

    // ---- the page ----

    @Test
    void theCardNoLongerLinksAwayToListsOrPosts() {
        JsonNode view = page("layout");
        assertEquals(0, view.path("standaloneLinks").asInt(), view.toString());
        assertFalse(view.path("oldLinks").asBoolean(), view.toString());
    }

    @Test
    void sectionsComeInOrderCardRoutinesListsPosts() {
        JsonNode view = page("layout");
        assertEquals("[\"Public Routines\",\"Public To-do Lists\",\"Public Posts\"]", view.path("headings").toString());
        assertEquals("[\"Routines shared publicly by this user.\",\"To-do lists shared publicly by this user.\","
                + "\"Posts shared publicly by this user.\"]", view.path("subtitles").toString());
        JsonNode tops = view.path("tops");
        assertTrue(tops.get(0).asInt() < tops.get(1).asInt() && tops.get(1).asInt() < tops.get(2).asInt()
                && tops.get(2).asInt() < tops.get(3).asInt(), "card, routines, lists, posts from top to bottom " + view);
        assertEquals("[\"Owner morning routine\"]", view.path("routines").toString(), "Public Routines as before " + view);
    }

    @Test
    void publicListsShowTheirTitleTaskCountAndProgress() {
        JsonNode view = page("layout");
        JsonNode morning = null;
        for (JsonNode card : view.path("lists")) {
            if ("Morning plan".equals(card.path("title").asString())) {
                morning = card;
            }
        }
        assertTrue(morning != null, view.toString());
        assertEquals("3 tasks", morning.path("count").asString());
        assertEquals("1 of 3 done", morning.path("done").asString());
        assertEquals("Public", morning.path("badge").asString());
        assertTrue(morning.path("viewButton").asBoolean());
        assertEquals(2, view.path("lists").size(), "the private list is not listed " + view);
        assertTrue(view.path("listTitles").toString().contains("Tricky"), view.toString());
    }

    @Test
    void publicPostsShowPhotoExcerptDateLikesAndComments() {
        JsonNode view = page("layout");
        assertEquals(2, view.path("posts").size(), "the private post is not listed " + view);
        JsonNode photo = view.path("posts").get(0).path("hasPhoto").asBoolean() ? view.path("posts").get(0) : view.path("posts").get(1);
        assertTrue(photo.path("photoFromSafeEndpoint").asBoolean(), photo.toString());
        assertTrue(photo.path("excerptClamped").asBoolean(), "a long text is shortened on the card " + photo);
        assertFalse(photo.path("date").asString().isBlank(), photo.toString());
        assertEquals("♥ 1", photo.path("likes").asString(), photo.toString());
        assertEquals("💬 1", photo.path("comments").asString(), photo.toString());
        assertTrue(photo.path("viewButton").asBoolean());
    }

    @Test
    void viewListOpensItsPublicTasksOnThePage() {
        JsonNode view = page("viewList");
        assertTrue(view.path("open").asBoolean(), view.toString());
        assertEquals("Morning plan", view.path("title").asString());
        assertEquals("[\"Stretch\",\"Read 10 pages\",\"Plan the day\"]", view.path("tasks").toString());
        assertEquals(1, view.path("done").asInt());
        assertEquals(0, view.path("ownerControls").asInt(), "read-only for a visitor " + view);
        assertTrue(view.path("samePage").asBoolean(), view.toString());
        assertTrue(view.path("closedByEscape").asBoolean(), view.toString());
        assertEquals("View list →", view.path("focusAfterClose").asString(), view.toString());
    }

    @Test
    void viewPostOpensTheWholePostWithLikesAndComments() throws Exception {
        JsonNode view = page("viewPost");
        assertTrue(view.path("open").asBoolean(), view.toString());
        assertEquals(LONG_POST, view.path("text").asString());
        assertTrue(view.path("photo").asBoolean(), view.toString());
        assertEquals(1, view.path("commentsShown").asInt(), "the comments open with the post " + view);
        assertEquals(0, view.path("ownerControls").asInt(), view.toString());
        assertEquals("2", view.path("likesAfterLike").asString(), view.toString());
        assertEquals(2, view.path("commentsAfterComment").asInt(), view.toString());
        assertTrue(view.path("samePage").asBoolean(), view.toString());
        assertEquals("♥ 2", view.path("cardLikesAfterClose").asString(), "the card shows the new counts " + view);
        assertEquals("💬 2", view.path("cardCommentsAfterClose").asString(), view.toString());
        assertEquals("View post →", view.path("focusAfterClose").asString(), view.toString());
        assertEquals(1, count("select count(*) from post_likes where post_id = ? and user_id = ?", photoPost, viewer.id()));

        JsonNode reloaded = page("afterReload");
        assertEquals("♥ 2", reloaded.path("likes").asString(), "stored, so it is there after a refresh " + reloaded);
        assertEquals("💬 2", reloaded.path("comments").asString(), reloaded.toString());
    }

    @Test
    void privateContentNeverReachesThePage() {
        JsonNode view = page("layout");
        assertFalse(view.path("pageText").asString().contains("Secret plan"), view.toString());
        assertFalse(view.path("pageText").asString().contains("Private diary"), view.toString());
        assertFalse(view.path("uploadPath").asBoolean(), "no photo file path reaches the page");
        assertFalse(page("viewList").path("privateTextInDetail").asBoolean());
    }

    @Test
    void markupInTitlesIsShownAsText() {
        JsonNode view = page("layout");
        assertFalse(view.path("xss").asBoolean(), view.toString());
        assertEquals(0, view.path("injectedImages").asInt(), view.toString());
    }

    @Test
    void emptySectionsSayItBriefly() {
        JsonNode view = page("empty");
        assertEquals("No public to-do lists yet.", view.path("lists").asString());
        assertEquals("No public posts yet.", view.path("posts").asString());
        assertTrue(view.path("listsHeight").asInt() < 90 && view.path("postsHeight").asInt() < 90,
                "a compact empty state " + view);
    }

    @Test
    void theProfileFitsEveryScreenInBothDesigns() {
        List<String> problems = new ArrayList<>();
        page("layouts").properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("scrollWidth").asInt() > view.path("clientWidth").asInt()) {
                problems.add(entry.getKey() + " scrolls sideways " + view);
            }
            if (!view.path("ordered").asBoolean()) {
                problems.add(entry.getKey() + " sections out of order " + view);
            }
            if (view.path("cardsOutside").asInt() > 0) {
                problems.add(entry.getKey() + " cards cut off " + view);
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " hard to read " + view);
            }
        });
        assertEquals(List.of(), problems);
        assertEquals(12, page("layouts").size());
    }

    @Test
    void thePageNeverFailsOrInterrupts() {
        assertEquals("[]", page("errors").toString());
        assertEquals("[]", page("dialogs").toString());
    }

    // ---- real browser ----

    @BeforeAll
    void openTheProfileInABrowser() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        owner = register("Content Owner");
        viewer = register("Content Viewer");
        Account empty = register("Content Empty");

        Map<String, String> routine = new LinkedHashMap<>();
        routine.put("title", "Owner morning routine");
        routine.put("description", "Public routine check");
        routine.put("category", "Morning");
        routine.put("durationMinutes", "30");
        routine.put("isPublic", "true");
        assertEquals(200, form("/routines", owner, routine, false).status());

        publicList = id(send("POST", "/todo-lists", owner, Map.of("title", "Morning plan", "isPublic", true)));
        long firstTask = 0;
        for (String task : List.of("Stretch", "Read 10 pages", "Plan the day")) {
            JsonNode list = json.readTree(send("POST", "/todo-lists/" + publicList + "/tasks", owner, Map.of("text", task)).body());
            if (firstTask == 0) {
                firstTask = list.path("tasks").get(0).path("id").asLong();
            }
        }
        assertEquals(200, send("PUT", "/todo-lists/" + publicList + "/tasks/" + firstTask, owner, Map.of("done", true)).status());
        privateList = id(send("POST", "/todo-lists", owner, Map.of("title", "Secret plan", "isPublic", false)));
        send("POST", "/todo-lists/" + privateList + "/tasks", owner, Map.of("text", "Hidden task"));
        id(send("POST", "/todo-lists", owner, Map.of("title", TRICKY, "isPublic", true)));

        id(form("/posts", owner, Map.of("text", "Text only public post", "isPublic", "true"), false));
        photoPost = id(form("/posts", owner, Map.of("text", LONG_POST, "isPublic", "true"), true));
        privatePost = id(form("/posts", owner, Map.of("text", "Private diary", "isPublic", "false"), true));

        Account fan = register("Content Fan");
        assertEquals(200, send("POST", "/posts/" + photoPost + "/like", fan, null).status());
        assertEquals(200, send("POST", "/posts/" + photoPost + "/comments", fan, Map.of("text", "Lovely!")).status());

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("viewerToken", viewer.token());
        fixtures.put("ownerId", owner.id());
        fixtures.put("emptyId", empty.id());

        Path dir = Files.createTempDirectory("user-profile-content-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/user-profile-content-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(6, TimeUnit.MINUTES)) {
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

    private Account register(String name) throws Exception {
        String email = "content_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = send("POST", "/auth/register", null, Map.of("name", name, "email", email, "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long id(Reply reply) throws Exception {
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Reply get(String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private HttpResponse<byte[]> raw(String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply form(String path, Account account, Map<String, String> fields, boolean withPhoto) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (withPhoto) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.png\""
                    + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(png());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + account.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
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
