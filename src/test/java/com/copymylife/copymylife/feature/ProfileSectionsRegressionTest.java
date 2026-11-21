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
 * The profile keeps every part of the account in one place: Friends gains a Followers tab,
 * and To-do Lists and Posts open in the profile's right-hand panel instead of their own pages,
 * with an address for each section (profile.html#posts). todo-lists.html and posts.html still
 * work on their own, with the site header of the other pages.
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
class ProfileSectionsRegressionTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "ProfileSectionsBoundary";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token, String email) {
    }

    private record Reply(int status, String body) {
    }

    private JsonNode browser;
    private Account owner;
    private Account fan;
    private Account creator;
    private Account stranger;
    private long privatePhotoPost;

    // ---- 1. Followers: the API ----

    @Test
    void followersAreThePeopleWhoFollowMeAndFollowingIsTheOtherWay() throws Exception {
        Account me = register("Me");
        Account follower = register("Follower");
        Account followed = register("Followed");
        follow(follower, me);
        follow(me, followed);

        assertEquals(List.of(follower.id()), ids(get("/users/me/followers", me)));
        assertEquals(List.of(followed.id()), ids(get("/users/me/following", me)));
        assertEquals(List.of(me.id()), ids(get("/users/me/followers", followed)));
        assertEquals(List.of(), ids(get("/users/me/followers", follower)));
        assertEquals(1, count("select count(*) from user_follows where follower_id = ? and following_id = ?",
                follower.id(), me.id()));
    }

    @Test
    void followersShowOnlyWhatAPublicProfileShowsAndMarkFollowBack() throws Exception {
        Account me = register("Me");
        Account follower = register("Follower");
        follow(follower, me);

        JsonNode first = json(get("/users/me/followers", me), 200).get(0);
        List<String> fields = new ArrayList<>();
        first.propertyNames().forEach(fields::add);
        assertEquals(List.of("userId", "name", "username", "avatarUrl", "followedBack"), fields);
        assertFalse(first.path("followedBack").asBoolean());
        assertFalse(first.toString().contains(follower.email()), "no email leaves the server");

        follow(me, follower);
        assertTrue(json(get("/users/me/followers", me), 200).get(0).path("followedBack").asBoolean());
    }

    @Test
    void followersCountMatchesTheListAndFollowsChangeBoth() throws Exception {
        Account me = register("Me");
        Account a = register("Ann");
        Account b = register("Bob");
        follow(a, me);
        follow(b, me);

        assertEquals(2, json(get("/users/me", me), 200).path("followersCount").asInt());
        assertEquals(2, json(get("/users/me/followers", me), 200).size());

        assertEquals(200, send("DELETE", "/users/" + me.id() + "/follow", a, null).status());
        assertEquals(1, json(get("/users/me", me), 200).path("followersCount").asInt());
        assertEquals(List.of(b.id()), ids(get("/users/me/followers", me)));

        follow(me, b);
        follow(me, b);
        assertEquals(1, json(get("/users/me", me), 200).path("followingCount").asInt(), "following twice stores one link");
    }

    @Test
    void bannedFollowersAndUnsafeAvatarsAreLeftOut() throws Exception {
        Account me = register("Me");
        Account banned = register("Banned");
        Account odd = register("Odd avatar");
        follow(banned, me);
        follow(odd, me);
        jdbc.update("update users set banned = true where id = ?", banned.id());
        jdbc.update("update users set avatar_url = 'javascript:alert(1)' where id = ?", odd.id());

        JsonNode followers = json(get("/users/me/followers", me), 200);
        assertEquals(List.of(odd.id()), ids(get("/users/me/followers", me)));
        assertTrue(followers.get(0).path("avatarUrl").isNull(), followers.toString());
        assertEquals(1, json(get("/users/me", me), 200).path("followersCount").asInt());
    }

    @Test
    void followersAreOnlyForTheSignedInUser() throws Exception {
        assertNotEquals(200, get("/users/me/followers", null).status());
        assertNotEquals(200, get("/users/me/followers", new Account(0, "not-a-token", "")).status());
    }

    // ---- 1. Followers: the profile ----

    @Test
    void profileShowsAFollowersTabNextToFollowing() {
        JsonNode view = page("followersTab");
        assertEquals("[\"My Friends\",\"Requests\",\"Sent\",\"Following\",\"Followers\"]", view.path("tabs").toString());
        assertEquals("/profile.html", view.path("path").asString());
        assertEquals("#followers", view.path("hash").asString());
        assertTrue(view.path("friendsVisible").asBoolean(), view.toString());
        assertEquals("[\"Profile Fan\"]", view.path("names").toString(), view.toString());
        assertEquals("[\"@" + view.path("fanUsername").asString() + "\"]", view.path("usernames").toString(), view.toString());
        assertTrue(view.path("links").toString().contains("user-profile.html?id=" + fan.id()), view.toString());
        assertEquals(1, view.path("avatars").asInt(), view.toString());
        assertEquals("1", view.path("followersCount").asString(), view.toString());

        JsonNode following = page("followingTab");
        assertEquals("[\"Profile Creator\"]", following.path("names").toString(), "Following is the other direction " + following);
    }

    @Test
    void profileExplainsWhenNobodyFollowsYet() {
        JsonNode view = page("followersEmpty");
        assertEquals("Nobody follows you yet. When someone follows you, they will appear here.", view.path("text").asString());
        assertEquals("0", view.path("followersCount").asString());
    }

    @Test
    void followBackAndUnfollowKeepTheCountsInStep() throws Exception {
        JsonNode view = page("followBack");
        assertEquals(view.path("followingBefore").asInt() + 1, view.path("followingAfter").asInt(), view.toString());
        assertEquals("Unfollow", view.path("buttonAfter").asString(), view.toString());
        assertEquals("Unfollow", view.path("buttonAfterReload").asString(), "kept after a refresh " + view);
        assertEquals(view.path("followingBefore").asInt(), view.path("followingAfterUnfollow").asInt(), view.toString());
        assertEquals(0, count("select count(*) from user_follows where follower_id = ? and following_id = ?",
                owner.id(), fan.id()));
    }

    // ---- 2 and 3. To-do Lists and Posts inside the profile ----

    @Test
    void toDoListsOpenInsideTheProfile() {
        JsonNode view = page("todoSection");
        assertEquals("/profile.html", view.path("path").asString(), view.toString());
        assertEquals("#todo-lists", view.path("hash").asString());
        assertEquals("todoListsNav", view.path("active").asString());
        assertTrue(view.path("sidebar").asBoolean() && view.path("card").asBoolean(), view.toString());
        assertEquals("[\"todoListsContent\"]", view.path("visibleSections").toString(), view.toString());
        assertEquals("My to-do lists", view.path("heading").asString());
        assertEquals(0, view.path("iframes").asInt());
    }

    @Test
    void toDoListsCanBeCreatedChangedCompletedAndDeletedInTheProfile() {
        JsonNode view = page("todoCrud");
        assertEquals("Weekend errands", view.path("created").asString(), view.toString());
        assertEquals("Weekend plan", view.path("renamed").asString(), view.toString());
        assertTrue(view.path("taskDone").asBoolean(), view.toString());
        assertEquals("Public", view.path("badge").asString(), view.toString());
        assertEquals("Weekend plan", view.path("afterReload").asString(), "stored, so it is there after a refresh " + view);
        assertEquals(0, view.path("tasksAfterRemove").asInt(), view.toString());
        assertEquals(0, view.path("listsAfterDelete").asInt(), view.toString());
        assertEquals("Give the list a title.", view.path("emptyTitle").asString(), view.toString());
        assertEquals(1, view.path("createRequests").asInt(), "one request per click after reopening the section " + view);
    }

    @Test
    void postsOpenInsideTheProfileWithLikesAndComments() {
        JsonNode section = page("postsSection");
        assertEquals("/profile.html", section.path("path").asString(), section.toString());
        assertEquals("#posts", section.path("hash").asString());
        assertEquals("postsNav", section.path("active").asString());
        assertEquals("[\"postsContent\"]", section.path("visibleSections").toString(), section.toString());
        assertEquals("My Posts", section.path("heading").asString());

        JsonNode view = page("postsCrud");
        assertEquals("Morning run done", view.path("created").asString(), view.toString());
        assertEquals("Morning run, then breakfast", view.path("edited").asString(), view.toString());
        assertEquals("1", view.path("likes").asString(), view.toString());
        assertEquals(1, view.path("likeRequests").asInt(), "one like request per click " + view);
        assertEquals(1, view.path("comments").asInt(), view.toString());
        assertEquals(0, view.path("commentsAfterDelete").asInt(), view.toString());
        assertEquals("1", view.path("likesAfterReload").asString(), view.toString());
        assertEquals(0, view.path("postsAfterDelete").asInt(), view.toString());
        assertTrue(view.path("photoFromSafeEndpoint").asBoolean(), "the photo is fetched with the token " + view);
        assertFalse(view.path("uploadPathInPage").asBoolean(), view.toString());
    }

    @Test
    void privatePhotosAndPrivateContentStayPrivate() throws Exception {
        assertEquals(200, raw("/posts/" + privatePhotoPost + "/image", owner).statusCode());
        assertNotEquals(200, raw("/posts/" + privatePhotoPost + "/image", stranger).statusCode());
        assertNotEquals(200, raw("/posts/" + privatePhotoPost + "/image", null).statusCode());
        String file = jdbc.queryForObject("select image_url from posts where id = ?", String.class, privatePhotoPost);
        assertNotEquals(200, raw("/uploads/posts/" + file.substring(file.lastIndexOf('/') + 1), owner).statusCode());

        JsonNode visitor = page("strangerViews");
        assertEquals("[\"Shared list\"]", visitor.path("lists").toString(), visitor.toString());
        assertEquals("[\"Shared post\"]", visitor.path("posts").toString(), visitor.toString());
    }

    // ---- 4. One way of switching sections ----

    @Test
    void backForwardRefreshAndDeepLinksOpenTheRightSection() {
        JsonNode view = page("history");
        assertEquals("todoListsContent", view.path("afterBack").asString(), view.toString());
        assertEquals("postsContent", view.path("afterForward").asString(), view.toString());
        assertEquals("friendsContent", view.path("afterSecondBack").asString(), view.toString());
        assertEquals("postsContent", view.path("afterReload").asString(), view.toString());

        Map<String, String> deepLinks = new LinkedHashMap<>();
        deepLinks.put("calendar", "calendarContent");
        deepLinks.put("my-routines", "accountContent");
        deepLinks.put("create-routine", "accountContent");
        deepLinks.put("habits", "habitsContent");
        deepLinks.put("progress", "progressContent");
        deepLinks.put("friends", "friendsContent");
        deepLinks.put("followers", "friendsContent");
        deepLinks.put("todo-lists", "todoListsContent");
        deepLinks.put("posts", "postsContent");
        deepLinks.put("settings", "themeSettingsContent");
        deepLinks.forEach((hash, section) ->
                assertEquals("[\"" + section + "\"]", page("deepLinks").path(hash).path("visible").toString(),
                        hash + " " + page("deepLinks").path(hash)));
        assertTrue(page("deepLinks").path("create-routine").path("modal").asBoolean());
        assertTrue(page("deepLinks").path("followers").path("followersTab").asBoolean());
    }

    @Test
    void everyOtherSectionStillWorksAndNothingIsDuplicated() {
        JsonNode view = page("allSections");
        List<String> problems = new ArrayList<>();
        view.path("sections").properties().forEach(entry -> {
            if (entry.getValue().path("visible").size() != 1) {
                problems.add(entry.getKey() + " shows " + entry.getValue());
            }
            if (!entry.getValue().path("active").asBoolean()) {
                problems.add(entry.getKey() + " is not marked active " + entry.getValue());
            }
            if (!"/profile.html".equals(entry.getValue().path("path").asString())) {
                problems.add(entry.getKey() + " left the profile " + entry.getValue());
            }
        });
        assertEquals(List.of(), problems);
        assertEquals("[]", view.path("duplicateIds").toString());
    }

    @Test
    void linksFromOtherPagesLeadIntoTheProfile() {
        JsonNode view = page("links");
        assertEquals("profile.html#todo-lists", view.path("footerTodo").asString());
        assertEquals("profile.html#posts", view.path("footerPosts").asString());
        assertEquals("todoListsContent", view.path("footerTodoOpens").asString(), view.toString());
        // user-profile.html shows them in place now; the standalone pages still answer ?user=.
        assertEquals("", view.path("publicTodo").asString(), view.toString());
        assertEquals("", view.path("publicPosts").asString(), view.toString());
    }

    // ---- 5. todo-lists.html and posts.html ----

    @Test
    void standalonePagesKeepWorkingWithTheSiteHeader() {
        for (String name : List.of("standaloneTodo", "standalonePosts")) {
            JsonNode view = page(name);
            assertEquals("[\"Home\",\"Explore\",\"Top Routines\"]", view.path("links").toString(), name + " " + view);
            assertEquals("Profile Owner", view.path("user").asString(), name + " " + view);
            assertEquals("[\"Profile|profile.html\",\"Settings|profile.html#settings\",\"Log Out|\"]",
                    view.path("menu").toString(), name + " " + view);
            assertTrue(view.path("menuOpens").asBoolean(), name + " " + view);
            assertEquals("https://i.ibb.co/chRY80xC/image.png", view.path("logo").asString(), name);
            assertTrue(view.path("items").asInt() > 0, name + " still lists the owner's content " + view);
        }
        JsonNode guest = page("headerGuest");
        assertEquals("[\"Sign in|login.html\",\"Sign up|signin.html\"]", guest.path("buttons").toString(), guest.toString());
        assertEquals("profileSettings", page("settingsLink").path("opened").asString(), page("settingsLink").toString());
        assertTrue(page("signedOutFromMenu").path("path").asString().endsWith("/login.html"), page("signedOutFromMenu").toString());
    }

    @Test
    void oneImplementationServesTheProfileAndTheStandalonePages() throws Exception {
        String profile = Files.readString(STATIC.resolve("profile.html"));
        String todo = Files.readString(STATIC.resolve("todo-lists.html"));
        String posts = Files.readString(STATIC.resolve("posts.html"));
        assertTrue(profile.contains("<script src=\"todo-lists.js\"></script>") && todo.contains("<script src=\"todo-lists.js\"></script>"));
        assertTrue(profile.contains("<script src=\"posts.js\"></script>") && posts.contains("<script src=\"posts.js\"></script>"));
        assertFalse(todo.contains("async function request"), "todo-lists.html keeps no second copy of the logic");
        assertFalse(posts.contains("async function request"), "posts.html keeps no second copy of the logic");
        assertFalse(profile.contains("<iframe"));
        assertFalse(profile.contains("href=\"todo-lists.html\"") || profile.contains("href=\"posts.html\""));
        for (String script : List.of("/todo-lists.js", "/posts.js", "/site-header.js")) {
            assertEquals(200, raw(script, null).statusCode(), script);
        }
    }

    // ---- 6. Designs and screens ----

    @Test
    void profileSectionsFitEveryScreenInBothDesigns() {
        List<String> problems = new ArrayList<>();
        page("layouts").properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("scrollWidth").asInt() > view.path("clientWidth").asInt()) {
                problems.add(entry.getKey() + " scrolls sideways " + view);
            }
            if (!view.path("contentVisible").asBoolean()) {
                problems.add(entry.getKey() + " section not shown " + view);
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " hard to read " + view);
            }
        });
        assertEquals(List.of(), problems);
        assertEquals(36, page("layouts").size());
    }

    @Test
    void theProfileNeverFailsOrInterrupts() {
        assertEquals("[]", page("errors").toString());
        assertEquals("[]", page("dialogs").toString());
    }

    // ---- real browser ----

    @BeforeAll
    void openTheProfileInABrowser() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");

        owner = register("Profile Owner");
        fan = register("Profile Fan");
        creator = register("Profile Creator");
        stranger = register("Profile Stranger");
        Account lonely = register("Profile Lonely");
        String fanUsername = "fan" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.update("update users set username = ?, avatar_url = ? where id = ?",
                fanUsername, "https://example.com/fan.png", fan.id());
        follow(fan, owner);
        follow(owner, creator);

        assertEquals(200, send("POST", "/todo-lists", owner, Map.of("title", "Shared list", "isPublic", true)).status());
        assertEquals(200, send("POST", "/todo-lists", owner, Map.of("title", "Secret list", "isPublic", false)).status());
        assertEquals(200, postForm(owner, Map.of("text", "Shared post", "isPublic", "true"), false).status());
        Reply photo = postForm(owner, Map.of("text", "Secret post", "isPublic", "false"), true);
        assertEquals(200, photo.status(), photo.body());
        privatePhotoPost = json.readTree(photo.body()).path("id").asLong();

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerToken", owner.token());
        fixtures.put("ownerId", owner.id());
        fixtures.put("strangerToken", stranger.token());
        fixtures.put("lonelyToken", lonely.token());
        fixtures.put("fanId", fan.id());
        fixtures.put("fanUsername", fanUsername);

        Path dir = Files.createTempDirectory("profile-sections-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/profile-sections-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
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
        String email = "profile_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = send("POST", "/auth/register", null, Map.of("name", name, "email", email, "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString(), email);
    }

    private void follow(Account who, Account whom) throws Exception {
        assertEquals(200, send("POST", "/users/" + whom.id() + "/follow", who, null).status());
    }

    private List<Long> ids(Reply reply) throws Exception {
        assertEquals(200, reply.status(), reply.body());
        List<Long> ids = new ArrayList<>();
        json.readTree(reply.body()).forEach(user -> ids.add(user.path("userId").asLong()));
        return ids;
    }

    private JsonNode json(Reply reply, int status) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        return json.readTree(reply.body());
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

    private Reply postForm(Account account, Map<String, String> fields, boolean withPhoto) throws Exception {
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

        HttpRequest request = HttpRequest.newBuilder(uri("/posts"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + account.token())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
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
