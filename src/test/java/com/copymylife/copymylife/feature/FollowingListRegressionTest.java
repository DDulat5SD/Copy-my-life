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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// US-13: a user sees the creators they follow, and following, unfollowing and the counts
// stay in step with the rows in user_follows.
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
class FollowingListRegressionTest {

    // A 1x1 PNG: a safe avatar that never leaves the machine.
    private static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
    private static final String MARKUP_NAME = "<img src=x onerror=\"window.__xss=1\">Bea";

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

    // ---- the list ----

    @Test
    void aNewUserFollowsNobody() throws Exception {
        Account fan = register();

        Reply reply = get("/users/me/following", fan);

        assertEquals(200, reply.status(), reply.body());
        assertEquals(0, json.readTree(reply.body()).size());
    }

    @Test
    void followingAddsTheCreatorToTheListAndOneToTheirFollowers() throws Exception {
        Account fan = register();
        Account creator = register();
        named(creator, "Ada Morning", "ada_" + suffix(), PNG);
        long before = profile(creator, fan).path("followersCount").asLong();

        assertEquals(200, follow(fan, creator).status());

        JsonNode list = following(fan);
        assertEquals(1, list.size(), list.toString());
        JsonNode entry = list.get(0);
        assertEquals(creator.id(), entry.path("userId").asLong());
        assertEquals("Ada Morning", entry.path("name").asString());
        assertTrue(entry.path("username").asString().startsWith("ada_"));
        assertEquals(PNG, entry.path("avatarUrl").asString());
        assertEquals(before + 1, profile(creator, fan).path("followersCount").asLong());
        assertTrue(profile(creator, fan).path("isFollowing").asBoolean());
    }

    @Test
    void unfollowingRemovesTheCreatorAndOneFromTheirFollowers() throws Exception {
        Account fan = register();
        Account kept = register();
        Account dropped = register();
        follow(fan, kept);
        follow(fan, dropped);
        long before = profile(dropped, fan).path("followersCount").asLong();

        assertEquals(200, unfollow(fan, dropped).status());

        assertEquals(List.of(kept.id()), userIds(following(fan)));
        assertEquals(before - 1, profile(dropped, fan).path("followersCount").asLong());
        assertFalse(profile(dropped, fan).path("isFollowing").asBoolean());
    }

    @Test
    void theListComesFromTheStoredRowsNewestFirstAndSurvivesARefresh() throws Exception {
        Account fan = register();
        List<Account> creators = List.of(register(), register(), register());
        for (Account creator : creators) {
            assertEquals(200, follow(fan, creator).status());
        }

        List<Long> stored = jdbc.queryForList(
                "select following_id from user_follows where follower_id = ? order by created_at desc, id desc",
                Long.class, fan.id());

        assertEquals(stored, userIds(following(fan)));
        assertEquals(stored, userIds(following(fan)), "a second read gives the same list");
        assertEquals(List.of(creators.get(2).id(), creators.get(1).id(), creators.get(0).id()), stored);
    }

    @Test
    void theListAndTheFollowingCountAlwaysAgree() throws Exception {
        Account fan = register();
        Account a = register();
        Account b = register();
        Account c = register();

        follow(fan, a);
        follow(fan, b);
        assertInStep(fan);
        follow(fan, c);
        unfollow(fan, a);
        assertInStep(fan);
        jdbc.update("update users set banned = true where id = ?", b.id());
        assertInStep(fan);

        assertEquals(List.of(c.id()), userIds(following(fan)), "a banned creator leaves the list as they leave the count");
        assertEquals(2, rows(fan), "the stored rows are not touched");
    }

    @Test
    void followingTwiceKeepsOneEntry() throws Exception {
        Account fan = register();
        Account creator = register();

        assertEquals(200, follow(fan, creator).status());
        assertEquals(200, follow(fan, creator).status());

        assertEquals(List.of(creator.id()), userIds(following(fan)));
        assertEquals(1, rows(fan));
        assertEquals(1, profile(creator, fan).path("followersCount").asLong());
    }

    @Test
    void nobodyCanFollowThemselves() throws Exception {
        Account fan = register();

        Reply reply = follow(fan, fan);

        assertEquals(400, reply.status(), reply.body());
        assertEquals(0, following(fan).size());
    }

    // ---- privacy and authorization ----

    @Test
    void theListShowsOnlyPublicProfileFields() throws Exception {
        Account fan = register();
        Account creator = register();
        jdbc.update("update users set bio = 'private-ish bio', profession = 'Nurse' where id = ?", creator.id());
        follow(fan, creator);

        JsonNode entry = following(fan).get(0);

        Set<String> fields = new TreeSet<>();
        entry.properties().forEach(property -> fields.add(property.getKey()));
        assertEquals(new TreeSet<>(Set.of("userId", "name", "username", "avatarUrl")), fields);
        String body = get("/users/me/following", fan).body();
        assertFalse(body.contains("@audit.test"), "no email");
        assertFalse(body.toLowerCase().contains("password"), "no password data");
        assertFalse(body.contains("$2a$"), "no password hash");
    }

    @Test
    void anUnsafeStoredAvatarIsNotHandedOut() throws Exception {
        Account fan = register();
        Account creator = register();
        jdbc.update("update users set avatar_url = 'javascript:alert(1)' where id = ?", creator.id());
        follow(fan, creator);

        assertTrue(following(fan).get(0).path("avatarUrl").isNull(), following(fan).toString());
    }

    @Test
    void theListNeedsASignedInUser() throws Exception {
        assertEquals(403, get("/users/me/following", null).status());
        assertEquals(403, get("/users/me/following", new Account(0, "not.a.token")).status());
    }

    @Test
    void eachUserSeesOnlyTheirOwnListAndNobodyActsForThem() throws Exception {
        Account fan = register();
        Account stranger = register();
        Account creator = register();
        follow(fan, creator);

        // The stranger's requests only ever change the stranger's own links.
        assertEquals(200, follow(stranger, creator).status());
        assertEquals(200, unfollow(stranger, creator).status());

        assertEquals(List.of(creator.id()), userIds(following(fan)));
        assertEquals(0, following(stranger).size());
        assertEquals(1, rows(fan));
        assertEquals(403, send("POST", "/users/" + creator.id() + "/follow", null).status());
        assertEquals(403, send("DELETE", "/users/" + creator.id() + "/follow", null).status());
        assertEquals(List.of(creator.id()), userIds(following(fan)));
    }

    // ---- real browser ----

    @BeforeAll
    void useTheFollowingListInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account fan = register();
        named(fan, "Fan Account", "fan_" + suffix(), null);
        Account ada = register();
        named(ada, "Ada Morning", "ada_" + suffix(), PNG);
        Account bea = register();
        named(bea, MARKUP_NAME, "bea_" + suffix(), "javascript:alert(1)");
        follow(fan, ada);
        follow(fan, bea);

        Account lonely = register();

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("fanToken", fan.token());
        fixtures.put("adaId", ada.id());
        fixtures.put("beaId", bea.id());
        fixtures.put("lonelyToken", lonely.token());

        Path dir = Files.createTempDirectory("following-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/following-list-check.mjs", chrome,
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
    void browserListsFollowedCreatorsWithAProfileLink() {
        JsonNode list = page("initial");

        assertEquals(2, list.path("cards").size(), list.toString());
        assertEquals("2 following", list.path("countText").asString());
        assertEquals("2", list.path("followingCount").asString());
        JsonNode bea = list.path("cards").get(0);
        JsonNode ada = list.path("cards").get(1);
        assertEquals("Ada Morning", ada.path("name").asString());
        assertTrue(ada.path("username").asString().startsWith("@ada_"), ada.toString());
        assertTrue(ada.path("href").asString().endsWith("user-profile.html?id=" + page("ids").path("ada").asLong()), ada.toString());
        assertTrue(ada.path("hasImage").asBoolean(), "a safe avatar is shown");
        assertTrue(bea.path("href").asString().endsWith("user-profile.html?id=" + page("ids").path("bea").asLong()), bea.toString());
    }

    @Test
    void browserShowsMarkupInANameAsTextAndDropsAnUnsafeAvatar() {
        JsonNode bea = page("initial").path("cards").get(0);

        assertEquals(MARKUP_NAME, bea.path("name").asString(), bea.toString());
        assertFalse(bea.path("hasImage").asBoolean(), "no image from a javascript: URL");
        assertEquals(0, page("initial").path("injectedImages").asInt());
        assertFalse(page("initial").path("xss").asBoolean());
    }

    @Test
    void browserUnfollowRemovesTheCreatorAndKeepsItAfterAReload() {
        JsonNode after = page("afterUnfollow");
        assertEquals(1, after.path("cards").size(), after.toString());
        assertEquals("Ada Morning", after.path("cards").get(0).path("name").asString());
        assertEquals("1 following", after.path("countText").asString());
        assertEquals("1", after.path("followingCount").asString());

        JsonNode reloaded = page("afterReload");
        assertEquals(1, reloaded.path("cards").size(), reloaded.toString());
        assertEquals("1", reloaded.path("followingCount").asString());
        assertTrue(page("dialogs").isEmpty(), page("dialogs").toString());
    }

    @Test
    void browserExplainsAnEmptyList() {
        JsonNode empty = page("empty");

        assertEquals(0, empty.path("cards").size());
        assertEquals("You are not following anyone yet. Follow creators from their profile to see them here.",
                empty.path("emptyText").asString(), empty.toString());
        assertEquals("0 following", empty.path("countText").asString());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("originalPhone", "autumnPhone", "autumnDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            // Negative on desktop: innerWidth also counts the vertical scrollbar.
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("originalPhone").path("width").asInt());
        assertEquals("autumn", page("autumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private void assertInStep(Account fan) throws Exception {
        JsonNode me = json.readTree(get("/users/me", fan).body());
        assertEquals(following(fan).size(), me.path("followingCount").asInt(), me.toString());
    }

    private JsonNode following(Account account) throws Exception {
        Reply reply = get("/users/me/following", account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private List<Long> userIds(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(entry -> ids.add(entry.path("userId").asLong()));
        return ids;
    }

    private long rows(Account follower) {
        return jdbc.queryForObject("select count(*) from user_follows where follower_id = ?", Long.class, follower.id());
    }

    private void named(Account account, String name, String username, String avatarUrl) {
        jdbc.update("update users set name = ?, username = ?, avatar_url = ? where id = ?",
                name, username, avatarUrl, account.id());
    }

    private JsonNode profile(Account target, Account viewer) throws Exception {
        Reply reply = get("/users/" + target.id() + "/profile", viewer);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply follow(Account fan, Account creator) throws Exception {
        return send("POST", "/users/" + creator.id() + "/follow", fan);
    }

    private Reply unfollow(Account fan, Account creator) throws Exception {
        return send("DELETE", "/users/" + creator.id() + "/follow", fan);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Account register() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "Follow QA",
                "email", "following_" + suffix() + "@audit.test",
                "password", "Audit#12345"));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = json.readTree(response.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account);
    }

    private Reply send(String method, String path, Account account) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody());
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
