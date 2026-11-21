package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// US-23: routines, to-do lists and posts can each be public or private. Private content is seen
// only by its owner, however it is asked for; only the owner can change it or its visibility.
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
class PrivacySettingsRegressionTest {

    private static final String BOUNDARY = "PrivacySettingsBoundary";
    private static final String MARKUP = "<img src=x onerror=\"window.__xss=1\">Tricky";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String token) {
    }

    // ---- routines (audit of the existing privacy) ----

    @Test
    void onlyTheOwnerCanChangeARoutinesVisibility() throws Exception {
        Account owner = register();
        Account stranger = register();
        long routine = routine(owner, "Shared morning", true);

        Reply byStranger = saveRoutine("PUT", "/routines/" + routine, stranger, "Taken over", false);
        Reply byGuest = saveRoutine("PUT", "/routines/" + routine, null, "Taken over", false);

        assertEquals(404, byStranger.status(), byStranger.body());
        assertEquals(403, byGuest.status(), byGuest.body());
        assertTrue(jdbc.queryForObject("select is_public from routines where id = ?", Boolean.class, routine));
        assertEquals("Shared morning", jdbc.queryForObject("select title from routines where id = ?", String.class, routine));
    }

    @Test
    void aRoutineMadePrivateCanNoLongerBeViewedOrCopiedByOthersButStillByItsOwner() throws Exception {
        Account owner = register();
        Account stranger = register();
        long routine = routine(owner, "Going private", true);
        assertEquals(200, get("/routines/" + routine, stranger).status());

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "Going private", false).status());

        assertEquals(404, get("/routines/" + routine, stranger).status());
        assertEquals(404, get("/routines/" + routine, null).status());
        assertTrue(post("/routines/" + routine + "/copy", stranger, null).status() >= 400);
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where user_id = ?", Integer.class, stranger.id()));
        assertEquals(200, get("/routines/" + routine, owner).status());
        assertFalse(get("/routines/public", stranger).body().contains("\"id\":" + routine + ","));
    }

    // ---- to-do lists: owner ----

    @Test
    void aToDoListIsCreatedEditedAndDeletedAndSurvivesARefresh() throws Exception {
        Account owner = register();

        JsonNode created = json(post("/todo-lists", owner, Map.of("title", "Groceries", "isPublic", false)), 200);
        long id = created.path("id").asLong();
        assertEquals("Groceries", created.path("title").asString());
        assertFalse(created.path("isPublic").asBoolean());
        assertTrue(created.path("ownedByMe").asBoolean());

        json(put("/todo-lists/" + id, owner, Map.of("title", "Weekly groceries")), 200);

        JsonNode reloaded = json(get("/todo-lists/" + id, owner), 200);
        assertEquals("Weekly groceries", reloaded.path("title").asString());
        assertEquals(1, json(get("/todo-lists", owner), 200).size());

        assertEquals(204, send("DELETE", "/todo-lists/" + id, owner, null).status());
        assertEquals(404, get("/todo-lists/" + id, owner).status());
        assertEquals(0, json(get("/todo-lists", owner), 200).size());
    }

    @Test
    void tasksAreAddedTickedEditedAndRemoved() throws Exception {
        Account owner = register();
        long id = list(owner, "Chores", false);

        JsonNode withTask = json(post("/todo-lists/" + id + "/tasks", owner, Map.of("text", "Water plants")), 200);
        long task = withTask.path("tasks").get(0).path("id").asLong();
        assertFalse(withTask.path("tasks").get(0).path("done").asBoolean());

        json(put("/todo-lists/" + id + "/tasks/" + task, owner, Map.of("done", true)), 200);
        json(put("/todo-lists/" + id + "/tasks/" + task, owner, Map.of("text", "Water all plants")), 200);
        json(post("/todo-lists/" + id + "/tasks", owner, Map.of("text", "Take out bins")), 200);

        JsonNode reloaded = json(get("/todo-lists/" + id, owner), 200);
        assertEquals(2, reloaded.path("tasks").size());
        assertEquals("Water all plants", reloaded.path("tasks").get(0).path("text").asString());
        assertTrue(reloaded.path("tasks").get(0).path("done").asBoolean(), "ticking stays after a refresh");
        assertEquals("Take out bins", reloaded.path("tasks").get(1).path("text").asString());

        JsonNode after = json(send("DELETE", "/todo-lists/" + id + "/tasks/" + task, owner, null), 200);
        assertEquals(1, after.path("tasks").size());
    }

    @Test
    void badToDoInputIsRefusedWithAReason() throws Exception {
        Account owner = register();
        long id = list(owner, "Valid", false);

        for (String title : new String[]{"", "   ", "x".repeat(101)}) {
            Reply reply = post("/todo-lists", owner, Map.of("title", title, "isPublic", false));
            assertEquals(400, reply.status(), title);
            assertFalse(message(reply).isBlank());
        }
        assertEquals(400, post("/todo-lists/" + id + "/tasks", owner, Map.of("text", " ")).status());
        assertEquals(400, post("/todo-lists/" + id + "/tasks", owner, Map.of("text", "y".repeat(201))).status());
        assertEquals(404, get("/todo-lists/999999999", owner).status());
    }

    @Test
    void deletingAListRemovesItsTasks() throws Exception {
        Account owner = register();
        long id = list(owner, "Short lived", false);
        post("/todo-lists/" + id + "/tasks", owner, Map.of("text", "One"));

        assertEquals(204, send("DELETE", "/todo-lists/" + id, owner, null).status());

        assertEquals(0, jdbc.queryForObject("select count(*) from todo_tasks where todo_list_id = ?", Integer.class, id));
    }

    // ---- to-do lists: visibility ----

    @Test
    void aPrivateListIsSeenOnlyByItsOwnerHoweverItIsAskedFor() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = list(owner, "Secret plans", false);

        assertEquals(404, get("/todo-lists/" + id, stranger).status(), "looks exactly like a missing list");
        assertEquals(message(get("/todo-lists/999999999", stranger)), message(get("/todo-lists/" + id, stranger)));
        assertEquals(403, get("/todo-lists/" + id, null).status());
        assertEquals(0, json(get("/users/" + owner.id() + "/todo-lists", stranger), 200).size());
        assertFalse(get("/users/" + owner.id() + "/todo-lists", stranger).body().contains("Secret plans"));
        assertEquals(200, get("/todo-lists/" + id, owner).status());
    }

    @Test
    void aPublicListCanBeReadButNotChangedByOthers() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = list(owner, "Reading list", true);
        long task = json(post("/todo-lists/" + id + "/tasks", owner, Map.of("text", "Dune")), 200)
                .path("tasks").get(0).path("id").asLong();

        JsonNode seen = json(get("/todo-lists/" + id, stranger), 200);
        assertFalse(seen.path("ownedByMe").asBoolean());
        assertEquals("Dune", seen.path("tasks").get(0).path("text").asString());
        assertEquals(List.of(id), ids(json(get("/users/" + owner.id() + "/todo-lists", stranger), 200)));

        assertEquals(404, put("/todo-lists/" + id, stranger, Map.of("title", "Mine", "isPublic", false)).status());
        assertEquals(404, send("DELETE", "/todo-lists/" + id, stranger, null).status());
        assertEquals(404, post("/todo-lists/" + id + "/tasks", stranger, Map.of("text", "Spam")).status());
        assertEquals(404, put("/todo-lists/" + id + "/tasks/" + task, stranger, Map.of("done", true)).status());
        assertEquals(404, send("DELETE", "/todo-lists/" + id + "/tasks/" + task, stranger, null).status());
        assertEquals(403, put("/todo-lists/" + id, null, Map.of("isPublic", false)).status());

        JsonNode unchanged = json(get("/todo-lists/" + id, owner), 200);
        assertEquals("Reading list", unchanged.path("title").asString());
        assertTrue(unchanged.path("isPublic").asBoolean());
        assertEquals(1, unchanged.path("tasks").size());
        assertFalse(unchanged.path("tasks").get(0).path("done").asBoolean());
    }

    @Test
    void switchingAListsVisibilityIsStoredAndTakesEffectAtOnce() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = list(owner, "Toggle me", false);

        json(put("/todo-lists/" + id, owner, Map.of("isPublic", true)), 200);
        assertTrue(jdbc.queryForObject("select is_public from todo_lists where id = ?", Boolean.class, id));
        assertEquals(200, get("/todo-lists/" + id, stranger).status());

        json(put("/todo-lists/" + id, owner, Map.of("isPublic", false)), 200);
        assertFalse(jdbc.queryForObject("select is_public from todo_lists where id = ?", Boolean.class, id));
        assertEquals(404, get("/todo-lists/" + id, stranger).status());
        assertEquals("Toggle me", json(get("/todo-lists/" + id, owner), 200).path("title").asString());
    }

    // ---- posts: owner ----

    @Test
    void aPostIsCreatedEditedAndDeletedAndSurvivesARefresh() throws Exception {
        Account owner = register();

        JsonNode created = json(postForm("POST", "/posts", owner, Map.of("text", "Morning run", "isPublic", "false"), null), 200);
        long id = created.path("id").asLong();
        assertEquals("Morning run", created.path("text").asString());
        assertFalse(created.path("isPublic").asBoolean());

        json(postForm("PUT", "/posts/" + id, owner, Map.of("text", "Morning run, 5 km", "isPublic", "false"), null), 200);
        assertEquals("Morning run, 5 km", json(get("/posts/" + id, owner), 200).path("text").asString());
        assertEquals(1, json(get("/posts", owner), 200).size());

        assertEquals(204, send("DELETE", "/posts/" + id, owner, null).status());
        assertEquals(404, get("/posts/" + id, owner).status());
        assertEquals(0, json(get("/posts", owner), 200).size());
    }

    @Test
    void aPostCanCarryAPhotoThatIsRemovedWithIt() throws Exception {
        Account owner = register();

        JsonNode created = json(postForm("POST", "/posts", owner, Map.of("text", "Breakfast", "isPublic", "true"),
                new Upload("meal.png", "image/png", png())), 200);
        long id = created.path("id").asLong();
        assertEquals("/posts/" + id + "/image", created.path("imageUrl").asString(), "the stored file name is not handed out");
        Path stored = storedPhoto(id);
        assertTrue(Files.exists(stored));

        JsonNode withoutPhoto = json(postForm("PUT", "/posts/" + id, owner,
                Map.of("text", "Breakfast", "isPublic", "true", "removeImage", "true"), null), 200);
        assertTrue(withoutPhoto.path("imageUrl").isNull(), withoutPhoto.toString());
        assertFalse(Files.exists(stored), "a removed photo is deleted from disk");

        json(postForm("PUT", "/posts/" + id, owner,
                Map.of("text", "Breakfast", "isPublic", "true"), new Upload("again.png", "image/png", png())), 200);
        Path second = storedPhoto(id);
        assertEquals(204, send("DELETE", "/posts/" + id, owner, null).status());
        assertFalse(Files.exists(second), "deleting the post deletes its photo");
    }

    // ---- post photos are reachable only through their post ----

    @Test
    void aPrivatePhotoCannotBeOpenedByItsFileUrl() throws Exception {
        Account owner = register();
        Account stranger = register();
        byte[] image = png();
        long id = json(postForm("POST", "/posts", owner, Map.of("text", "Private moment", "isPublic", "false"),
                new Upload("secret.png", "image/png", image)), 200).path("id").asLong();
        String file = jdbc.queryForObject("select image_url from posts where id = ?", String.class, id);
        assertTrue(file.matches("/uploads/posts/[0-9a-f-]{36}\\.png"), file);
        assertTrue(Files.exists(storedPhoto(id)));

        String name = file.substring(file.lastIndexOf('/') + 1);
        List<String> spellings = List.of(file, "/uploads/POSTS/" + name, "/uploads/Posts/" + name,
                "/uploads/posts/" + name.toUpperCase(), "/uploads/./posts/" + name, "/uploads//posts/" + name,
                "/uploads/routines/../posts/" + name, "/uploads/posts%2F" + name);
        for (String spelling : spellings) {
            for (Account who : new Account[]{null, stranger, owner}) {
                HttpResponse<byte[]> reply = raw(spelling, who);
                assertTrue(reply.statusCode() >= 400, spelling + " as " + (who == null ? "guest" : who.id()) + " gave " + reply.statusCode());
                assertFalse(java.util.Arrays.equals(image, reply.body()), "no photo bytes for " + spelling);
            }
        }

        HttpResponse<byte[]> own = raw("/posts/" + id + "/image", owner);
        assertEquals(200, own.statusCode());
        assertTrue(java.util.Arrays.equals(image, own.body()), "the owner gets the photo through the post");
        assertEquals("image/png", own.headers().firstValue("Content-Type").orElse(""));
        assertTrue(own.headers().firstValue("Cache-Control").orElse("").contains("no-store"), own.headers().map().toString());
        assertTrue(own.headers().firstValue("Cache-Control").orElse("").contains("private"));
        assertEquals("nosniff", own.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals(404, raw("/posts/" + id + "/image", stranger).statusCode(), "the same answer as a missing photo");
        assertEquals(403, raw("/posts/" + id + "/image", null).statusCode());
        assertFalse(get("/posts", owner).body().contains(name), "the file name never reaches a client");
    }

    @Test
    void aPublicPhotoFollowsItsPostsVisibility() throws Exception {
        Account owner = register();
        Account stranger = register();
        byte[] image = png();
        long id = json(postForm("POST", "/posts", owner, Map.of("text", "Open moment", "isPublic", "true"),
                new Upload("open.png", "image/png", image)), 200).path("id").asLong();
        String file = jdbc.queryForObject("select image_url from posts where id = ?", String.class, id);

        HttpResponse<byte[]> seen = raw("/posts/" + id + "/image", stranger);
        assertEquals(200, seen.statusCode());
        assertTrue(java.util.Arrays.equals(image, seen.body()));
        assertTrue(raw(file, stranger).statusCode() >= 400, "even a public photo is not served as a file");
        assertFalse(get("/users/" + owner.id() + "/posts", stranger).body().contains("/uploads/"));

        json(postForm("PUT", "/posts/" + id, owner, Map.of("isPublic", "false"), null), 200);
        assertEquals(404, raw("/posts/" + id + "/image", stranger).statusCode(), "making the post private hides its photo at once");

        json(postForm("PUT", "/posts/" + id, owner, Map.of("isPublic", "true"), null), 200);
        assertEquals(200, raw("/posts/" + id + "/image", stranger).statusCode());
        jdbc.update("update users set banned = true where id = ?", owner.id());
        assertEquals(404, raw("/posts/" + id + "/image", stranger).statusCode(), "a banned author's photos are hidden too");
    }

    @Test
    void routineImagesAndAvatarsAreStillServed() throws Exception {
        Account owner = register();
        Reply avatar = postForm("POST", "/users/me/avatar", owner, Map.of(), new Upload("me.png", "image/png", png()));
        assertEquals(200, avatar.status(), avatar.body());
        String avatarUrl = json.readTree(avatar.body()).path("avatarUrl").asString();
        assertTrue(avatarUrl.startsWith("/uploads/avatars/"), avatarUrl);

        assertEquals(200, raw(avatarUrl, null).statusCode(), "public uploads keep working");
    }

    @Test
    void unsafeOrEmptyPostsAreRefused() throws Exception {
        Account owner = register();

        assertEquals(415, postForm("POST", "/posts", owner, Map.of("text", "x", "isPublic", "false"),
                new Upload("fake.png", "image/png", "not an image".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(415, postForm("POST", "/posts", owner, Map.of("text", "x", "isPublic", "false"),
                new Upload("vector.svg", "image/svg+xml", "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8))).status());
        assertEquals(415, postForm("POST", "/posts", owner, Map.of("text", "x", "isPublic", "false"),
                new Upload("clip.mp4", "video/mp4", new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'})).status(),
                "video is not supported");
        Reply empty = postForm("POST", "/posts", owner, Map.of("text", "   ", "isPublic", "false"), null);
        assertEquals(400, empty.status(), empty.body());
        assertEquals(400, postForm("POST", "/posts", owner, Map.of("text", "z".repeat(2001), "isPublic", "false"), null).status());
        assertEquals(0, json(get("/posts", owner), 200).size());
    }

    // ---- posts: visibility ----

    @Test
    void aPrivatePostIsSeenOnlyByItsOwnerHoweverItIsAskedFor() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = json(postForm("POST", "/posts", owner, Map.of("text", "Diary entry", "isPublic", "false"), null), 200).path("id").asLong();

        assertEquals(404, get("/posts/" + id, stranger).status());
        assertEquals(message(get("/posts/999999999", stranger)), message(get("/posts/" + id, stranger)));
        assertEquals(403, get("/posts/" + id, null).status());
        assertEquals(0, json(get("/users/" + owner.id() + "/posts", stranger), 200).size());
        assertFalse(get("/users/" + owner.id() + "/posts", stranger).body().contains("Diary entry"));
        assertFalse(get("/users/" + owner.id() + "/profile", stranger).body().contains("Diary entry"));
        assertFalse(get("/users/search?query=Diary", stranger).body().contains("Diary entry"));
        assertEquals(200, get("/posts/" + id, owner).status());
    }

    @Test
    void aPublicPostCanBeReadButNotChangedByOthers() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = json(postForm("POST", "/posts", owner, Map.of("text", "Hello world", "isPublic", "true"), null), 200).path("id").asLong();

        JsonNode seen = json(get("/posts/" + id, stranger), 200);
        assertFalse(seen.path("ownedByMe").asBoolean());
        assertEquals(owner.id(), seen.path("authorId").asLong());
        assertEquals(List.of(id), ids(json(get("/users/" + owner.id() + "/posts", stranger), 200)));
        assertFalse(get("/posts/" + id, stranger).body().contains("@audit.test"), "no email of the author");

        assertEquals(404, postForm("PUT", "/posts/" + id, stranger, Map.of("text", "Hacked", "isPublic", "false"), null).status());
        assertEquals(404, send("DELETE", "/posts/" + id, stranger, null).status());
        assertEquals(403, send("DELETE", "/posts/" + id, null, null).status());

        JsonNode unchanged = json(get("/posts/" + id, owner), 200);
        assertEquals("Hello world", unchanged.path("text").asString());
        assertTrue(unchanged.path("isPublic").asBoolean());
    }

    @Test
    void switchingAPostsVisibilityIsStoredAndTakesEffectAtOnce() throws Exception {
        Account owner = register();
        Account stranger = register();
        long id = json(postForm("POST", "/posts", owner, Map.of("text", "Flip", "isPublic", "true"), null), 200).path("id").asLong();

        json(postForm("PUT", "/posts/" + id, owner, Map.of("text", "Flip", "isPublic", "false"), null), 200);
        assertFalse(jdbc.queryForObject("select is_public from posts where id = ?", Boolean.class, id));
        assertEquals(404, get("/posts/" + id, stranger).status());

        json(postForm("PUT", "/posts/" + id, owner, Map.of("text", "Flip", "isPublic", "true"), null), 200);
        assertTrue(jdbc.queryForObject("select is_public from posts where id = ?", Boolean.class, id));
        assertEquals(200, get("/posts/" + id, stranger).status());
    }

    // ---- across the app ----

    @Test
    void aBannedUsersPublicContentIsHiddenFromOthers() throws Exception {
        Account owner = register();
        Account stranger = register();
        long list = list(owner, "Banned list", true);
        long postId = json(postForm("POST", "/posts", owner, Map.of("text", "Banned post", "isPublic", "true"), null), 200).path("id").asLong();

        jdbc.update("update users set banned = true where id = ?", owner.id());

        assertEquals(404, get("/todo-lists/" + list, stranger).status());
        assertEquals(404, get("/posts/" + postId, stranger).status());
        assertEquals(404, get("/users/" + owner.id() + "/todo-lists", stranger).status());
        assertEquals(404, get("/users/" + owner.id() + "/posts", stranger).status());
    }

    @Test
    void deletingAUserRemovesTheirListsAndPosts() throws Exception {
        Account owner = register();
        long list = list(owner, "Gone with me", true);
        post("/todo-lists/" + list + "/tasks", owner, Map.of("text", "One"));
        postForm("POST", "/posts", owner, Map.of("text", "Gone too", "isPublic", "true"), null);

        jdbc.update("delete from users where id = ?", owner.id());

        assertEquals(0, jdbc.queryForObject("select count(*) from todo_lists where user_id = ?", Integer.class, owner.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from todo_tasks where todo_list_id = ?", Integer.class, list));
        assertEquals(0, jdbc.queryForObject("select count(*) from posts where user_id = ?", Integer.class, owner.id()));
    }

    @Test
    void everyNewEndpointNeedsASignedInUser() throws Exception {
        for (String path : List.of("/todo-lists", "/todo-lists/1", "/posts", "/posts/1", "/users/1/todo-lists", "/users/1/posts")) {
            assertEquals(403, get(path, null).status(), path);
        }
        assertEquals(403, post("/todo-lists", null, Map.of("title", "x", "isPublic", false)).status());
        assertEquals(403, postForm("POST", "/posts", null, Map.of("text", "x", "isPublic", "false"), null).status());
    }

    @Test
    void theSchemaOnlyGainsNewTables() {
        for (String table : List.of("todo_lists", "todo_tasks", "posts")) {
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from information_schema.tables where table_name = ?", Integer.class, table), table);
        }
        List<String> deleteRules = jdbc.queryForList("""
                select rc.delete_rule from information_schema.referential_constraints rc
                where rc.constraint_name in ('fk_todo_lists_user', 'fk_todo_tasks_list', 'fk_posts_user')
                """, String.class);
        assertEquals(List.of("CASCADE", "CASCADE", "CASCADE"), deleteRules);
    }

    // ---- real browser ----

    @BeforeAll
    void useListsAndPostsInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account owner = register();
        jdbc.update("update users set name = 'List Owner', username = ? where id = ?", "owner_" + suffix(), owner.id());
        Account stranger = register();
        Account newcomer = register();

        long shared = list(owner, "Weekend plan", true);
        post("/todo-lists/" + shared + "/tasks", owner, Map.of("text", "Pack the bag"));
        list(owner, MARKUP, true);
        list(owner, "Hidden list", false);
        postForm("POST", "/posts", owner, Map.of("text", "Sunrise photo", "isPublic", "true"), new Upload("sun.png", "image/png", png()));
        postForm("POST", "/posts", owner, Map.of("text", MARKUP, "isPublic", "true"), null);
        postForm("POST", "/posts", owner, Map.of("text", "Hidden post", "isPublic", "false"), null);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerId", owner.id());
        fixtures.put("ownerToken", owner.token());
        fixtures.put("strangerToken", stranger.token());
        fixtures.put("newcomerToken", newcomer.token());

        Path dir = Files.createTempDirectory("privacy-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/privacy-content-check.mjs", chrome,
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
    void browserExplainsEmptyListsAndPosts() {
        assertEquals("You have no to-do lists yet. Create your first list above.", page("todoEmpty").path("empty").asString(), browser.toString());
        assertEquals("You have not posted anything yet. Share your first post above.", page("postsEmpty").path("empty").asString(), browser.toString());
        assertEquals("Photos: JPEG, PNG, WebP or GIF up to 5 MB. Video is not supported.", page("postsEmpty").path("hint").asString());
    }

    @Test
    void browserManagesAToDoListAndKeepsItAfterAReload() {
        JsonNode created = page("todoCreated");
        assertEquals(List.of("Errands"), texts(created.path("titles")), created.toString());
        assertEquals(List.of("Private"), texts(created.path("badges")));

        JsonNode reloaded = page("todoReloaded");
        assertEquals(List.of("Errands"), texts(reloaded.path("titles")), reloaded.toString());
        assertEquals(List.of("Public"), texts(reloaded.path("badges")), "the visibility change was stored");
        assertEquals(List.of("Buy stamps"), texts(reloaded.path("tasks")));
        assertEquals(List.of(true), bools(reloaded.path("checked")), "the ticked task stays ticked");

        assertTrue(page("todoDeleteAsked").path("confirmVisible").asBoolean(), page("todoDeleteAsked").toString());
        assertEquals(1, page("todoDeleteAsked").path("titles").size(), "nothing is deleted before confirming");
        assertEquals(0, page("todoDeleted").path("titles").size(), page("todoDeleted").toString());
        assertEquals("You have no to-do lists yet. Create your first list above.", page("todoDeleted").path("empty").asString());
    }

    @Test
    void browserManagesAPostAndKeepsItAfterAReload() {
        JsonNode created = page("postCreated");
        assertEquals(List.of("Coffee first"), texts(created.path("texts")), created.toString());
        assertEquals(List.of("Private"), texts(created.path("badges")));

        JsonNode reloaded = page("postReloaded");
        assertEquals(List.of("Coffee first, then work"), texts(reloaded.path("texts")), reloaded.toString());
        assertEquals(List.of("Public"), texts(reloaded.path("badges")));

        assertTrue(page("postDeleteAsked").path("confirmVisible").asBoolean(), page("postDeleteAsked").toString());
        assertEquals(1, page("postDeleteAsked").path("texts").size());
        assertEquals(0, page("postDeleted").path("texts").size(), page("postDeleted").toString());
    }

    @Test
    void browserShowsOthersOnlyPublicContentReadOnlyAndAsText() {
        JsonNode lists = page("strangerLists");
        assertEquals(List.of(MARKUP, "Weekend plan"), texts(lists.path("titles")), lists.toString());
        assertEquals(0, lists.path("controls").asInt(), "no edit, delete or visibility controls for a visitor");
        assertTrue(lists.path("checkboxesDisabled").asBoolean());
        assertEquals(0, lists.path("injectedImages").asInt());

        JsonNode posts = page("strangerPosts");
        assertEquals(List.of(MARKUP, "Sunrise photo"), texts(posts.path("texts")), posts.toString());
        assertEquals(0, posts.path("controls").asInt());
        assertEquals(1, posts.path("images").asInt(), "the photo of the public post is shown");
        assertEquals(1, posts.path("imagesLoaded").asInt(), "it arrives through the post, with the visitor's token");
        assertTrue(posts.path("imageSources").get(0).asString().startsWith("blob:"), posts.toString());
        assertEquals(0, posts.path("injectedImages").asInt());
        assertFalse(page("xss").asBoolean());
        assertTrue(page("dialogs").isEmpty(), page("dialogs").toString());
    }

    @Test
    void browserLinksToTheNewPagesFromBothProfiles() {
        // The profile opens its own lists and posts in place; todo-lists.html and posts.html still work.
        assertTrue(page("profileLinks").path("todo").asString().endsWith("profile.html#todo-lists"), page("profileLinks").toString());
        assertTrue(page("profileLinks").path("posts").asString().endsWith("profile.html#posts"));
        // Another user's public lists and posts are shown on their profile itself, not linked away.
        assertEquals("", page("userProfileLinks").path("todo").asString(), page("userProfileLinks").toString());
        assertEquals("", page("userProfileLinks").path("posts").asString(), page("userProfileLinks").toString());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("todoOriginalPhone", "todoAutumnPhone", "todoAutumnDesktop",
                "postsOriginalPhone", "postsAutumnPhone", "postsAutumnDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            // Negative on desktop: innerWidth also counts the vertical scrollbar.
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("todoOriginalPhone").path("width").asInt());
        assertEquals("autumn", page("postsAutumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private record Upload(String name, String type, byte[] bytes) {
    }

    private Path storedPhoto(long postId) {
        String file = jdbc.queryForObject("select image_url from posts where id = ?", String.class, postId);
        return Path.of(uploadDir, "posts", file.substring(file.lastIndexOf('/') + 1));
    }

    private HttpResponse<byte[]> raw(String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private long list(Account owner, String title, boolean isPublic) throws Exception {
        return json(post("/todo-lists", owner, Map.of("title", title, "isPublic", isPublic)), 200).path("id").asLong();
    }

    private JsonNode json(Reply reply, int status) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private String message(Reply reply) throws Exception {
        return reply.body().isBlank() ? "" : json.readTree(reply.body()).path("message").asString();
    }

    private List<Long> ids(JsonNode list) {
        List<Long> values = new ArrayList<>();
        list.forEach(node -> values.add(node.path("id").asLong()));
        return values;
    }

    private List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private List<Boolean> bools(JsonNode array) {
        List<Boolean> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asBoolean()));
        return values;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Account register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Privacy QA",
                "email", "privacy_" + suffix() + "@audit.test",
                "password", "Audit#12345"));
        JsonNode node = json(reply, 200);
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, boolean isPublic) throws Exception {
        return json(saveRoutine("POST", "/routines", owner, title, isPublic), 200).path("id").asLong();
    }

    private Reply saveRoutine(String method, String path, Account account, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Privacy check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));
        return postForm(method, path, account, fields, null);
    }

    private Reply postForm(String method, String path, Account account, Map<String, String> fields, Upload upload) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (upload != null) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + upload.name()
                    + "\"\r\nContent-Type: " + upload.type() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(upload.bytes());
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
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
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new HashMap<>(payload))));
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
