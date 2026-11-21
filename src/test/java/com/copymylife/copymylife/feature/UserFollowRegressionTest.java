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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
class UserFollowRegressionTest {

    private static final long MISSING = 987_654_321L;

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

    // ---- follow and unfollow ----

    @Test
    void followUpdatesCountsAndState() throws Exception {
        Account fan = register();
        Account creator = register();

        Reply reply = follow(fan, creator);

        assertEquals(200, reply.status(), reply.body());
        JsonNode status = json.readTree(reply.body());
        assertTrue(status.path("isFollowing").asBoolean());
        assertEquals(1, status.path("followersCount").asInt());
        assertEquals(0, status.path("followingCount").asInt());
        assertEquals(1, rows(fan, creator));
    }

    @Test
    void unfollowRemovesTheLink() throws Exception {
        Account fan = register();
        Account creator = register();
        follow(fan, creator);

        Reply reply = unfollow(fan, creator);

        assertEquals(200, reply.status(), reply.body());
        JsonNode status = json.readTree(reply.body());
        assertFalse(status.path("isFollowing").asBoolean());
        assertEquals(0, status.path("followersCount").asInt());
        assertEquals(0, rows(fan, creator));
    }

    @Test
    void repeatingFollowOrUnfollowIsHarmless() throws Exception {
        Account fan = register();
        Account creator = register();

        assertEquals(200, follow(fan, creator).status());
        Reply again = follow(fan, creator);

        assertEquals(200, again.status());
        assertEquals(1, json.readTree(again.body()).path("followersCount").asInt());
        assertEquals(1, rows(fan, creator));

        assertEquals(200, unfollow(fan, creator).status());
        assertEquals(200, unfollow(fan, creator).status());
        assertEquals(0, rows(fan, creator));
    }

    @Test
    void cannotFollowYourself() throws Exception {
        Account user = register();

        assertEquals(400, follow(user, user).status());
        assertEquals(0, rows(user, user));
    }

    @Test
    void followRequiresAToken() throws Exception {
        Account fan = register();
        Account creator = register();
        follow(fan, creator);

        assertEquals(403, send("POST", "/users/" + creator.id() + "/follow", null).status());
        assertEquals(403, send("DELETE", "/users/" + creator.id() + "/follow", null).status());
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/users/" + creator.id() + "/follow"))
                .header("Authorization", "Bearer not.a.token")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1, rows(fan, creator));
    }

    @Test
    void cannotFollowMissingBannedOrAdminUsers() throws Exception {
        Account fan = register();
        Account banned = register();
        Account admin = register();
        jdbc.update("update users set banned = true where id = ?", banned.id());
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());

        assertEquals(404, send("POST", "/users/" + MISSING + "/follow", fan).status());
        assertEquals(404, follow(fan, banned).status());
        assertEquals(404, follow(fan, admin).status());
        assertEquals(0, rows(fan, banned));
        assertEquals(0, rows(fan, admin));
        assertEquals(404, send("DELETE", "/users/" + MISSING + "/follow", fan).status());
    }

    // ---- counts and state ----

    @Test
    void countsAndStateAppearOnTheProfileAndSurviveARefresh() throws Exception {
        Account fan = register();
        Account other = register();
        Account creator = register();
        follow(fan, creator);
        follow(other, creator);
        follow(creator, other);

        JsonNode asFan = profile(creator, fan);
        assertTrue(asFan.path("isFollowing").asBoolean());
        assertEquals(2, asFan.path("followersCount").asInt());
        assertEquals(1, asFan.path("followingCount").asInt());
        assertEquals(1, profile(fan, creator).path("followingCount").asInt());
        assertFalse(profile(fan, creator).path("isFollowing").asBoolean());

        JsonNode stranger = profile(creator, register());
        assertFalse(stranger.path("isFollowing").asBoolean());
        assertEquals(2, stranger.path("followersCount").asInt());
    }

    @Test
    void ownCountsAppearOnMyProfile() throws Exception {
        Account fan = register();
        Account creator = register();
        follow(fan, creator);

        JsonNode me = json.readTree(send("GET", "/users/me", creator).body());
        assertEquals(1, me.path("followersCount").asInt());
        assertEquals(0, me.path("followingCount").asInt());

        JsonNode mine = json.readTree(send("GET", "/users/me", fan).body());
        assertEquals(0, mine.path("followersCount").asInt());
        assertEquals(1, mine.path("followingCount").asInt());
    }

    @Test
    void relationshipsDoNotAffectEachOther() throws Exception {
        Account a = register();
        Account b = register();
        Account c = register();
        follow(a, b);
        follow(c, b);
        follow(a, c);

        unfollow(a, b);

        assertEquals(0, rows(a, b));
        assertEquals(1, rows(c, b));
        assertEquals(1, rows(a, c));
        assertEquals(1, profile(b, c).path("followersCount").asInt());
        assertEquals(1, profile(c, b).path("followersCount").asInt());
    }

    @Test
    void followIsOneWay() throws Exception {
        Account a = register();
        Account b = register();

        follow(a, b);

        assertTrue(profile(b, a).path("isFollowing").asBoolean());
        assertFalse(profile(a, b).path("isFollowing").asBoolean());
        assertEquals(0, rows(b, a));
    }

    @Test
    void bannedFollowersAreNotCounted() throws Exception {
        Account fan = register();
        Account creator = register();
        follow(fan, creator);
        jdbc.update("update users set banned = true where id = ?", fan.id());

        assertEquals(0, json.readTree(send("GET", "/users/me", creator).body()).path("followersCount").asInt());
        assertEquals(1, rows(fan, creator));
    }

    // ---- concurrency and database rules ----

    @Test
    void concurrentFollowsCreateOneLink() throws Exception {
        Account fan = register();
        Account creator = register();
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attempts.add(() -> follow(fan, creator).status());
        }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> attempt : pool.invokeAll(attempts)) {
                statuses.add(attempt.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(12, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertEquals(1, rows(fan, creator));
        assertEquals(1, profile(creator, fan).path("followersCount").asInt());
    }

    @Test
    void databaseRejectsDuplicatesSelfFollowsAndUnknownUsers() throws Exception {
        Account a = register();
        Account b = register();
        follow(a, b);
        String insert = "insert into user_follows (follower_id, following_id, created_at) values (?, ?, now())";

        assertThrows(DuplicateKeyException.class, () -> jdbc.update(insert, a.id(), b.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, a.id(), a.id()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, a.id(), MISSING));
    }

    @Test
    void schemaHasTheExpectedConstraintsAndIndexes() {
        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'user_follows'::regclass", String.class);
        assertTrue(constraints.contains("uk_user_follows_pair"), constraints.toString());
        assertTrue(constraints.contains("fk_user_follows_follower"), constraints.toString());
        assertTrue(constraints.contains("fk_user_follows_following"), constraints.toString());
        assertTrue(constraints.contains("ck_user_follows_not_self"), constraints.toString());

        String indexes = jdbc.queryForList("select indexdef from pg_indexes where tablename = 'user_follows'", String.class).toString();
        assertTrue(indexes.contains("idx_user_follows_following"), indexes);
    }

    @Test
    void deletingAUserRemovesTheirFollowLinksButNoOneElse() throws Exception {
        Account leaver = register();
        Account creator = register();
        Account other = register();
        follow(leaver, creator);
        follow(other, creator);

        assertEquals(204, send("DELETE", "/users/" + leaver.id(), leaver).status());

        assertEquals(0, rows(leaver, creator));
        assertEquals(1, rows(other, creator));
    }

    // ---- friends stay separate ----

    @Test
    void friendSystemStillWorksAndStaysSeparateFromFollow() throws Exception {
        Account a = register();
        Account b = register();
        follow(a, b);

        assertEquals("NONE", profile(b, a).path("relationshipStatus").asString());
        assertEquals("[]", send("GET", "/friends", a).body());

        Reply request = send("POST", "/friends/request/" + b.id(), a);
        assertEquals(200, request.status(), request.body());
        assertEquals("PENDING", profile(b, a).path("relationshipStatus").asString());
        assertEquals("RECEIVED", profile(a, b).path("relationshipStatus").asString());
        assertEquals(0, rows(b, a));

        long requestId = json.readTree(request.body()).path("requestId").asLong();
        assertEquals(200, send("PUT", "/friends/requests/" + requestId + "/accept", b).status());
        assertEquals("FRIENDS", profile(b, a).path("relationshipStatus").asString());
        assertEquals(0, rows(b, a));
        assertEquals(1, rows(a, b));

        unfollow(a, b);
        assertEquals("FRIENDS", profile(b, a).path("relationshipStatus").asString());

        follow(a, b);
        assertEquals(204, send("DELETE", "/friends/" + b.id(), a).status());
        assertEquals("NONE", profile(b, a).path("relationshipStatus").asString());
        assertEquals(1, rows(a, b));
    }

    // ---- real browser ----

    @BeforeAll
    void useTheFollowButtonInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account viewer = register();
        Account creator = register();
        Account follower = register();
        follow(follower, creator);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("viewerToken", viewer.token());
        fixtures.put("viewerId", viewer.id());
        fixtures.put("creatorId", creator.id());

        Path dir = Files.createTempDirectory("follow-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/follow-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        assertTrue(process.waitFor(3, TimeUnit.MINUTES), "Browser check timed out");
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    @Test
    void buttonFollowsAndCountsUpdateWithoutReloading() {
        JsonNode start = page("initial");
        assertEquals("Follow", start.path("label").asString(), browser.toString());
        assertEquals(1, start.path("followers").asInt());
        assertEquals("Follower", start.path("followersLabel").asString());
        assertEquals(0, start.path("following").asInt());

        JsonNode followed = page("afterFollow");
        assertEquals("Following", followed.path("label").asString());
        assertEquals(2, followed.path("followers").asInt());
        assertEquals("Followers", followed.path("followersLabel").asString());
    }

    @Test
    void followStateSurvivesARefresh() {
        JsonNode reloaded = page("afterReload");

        assertEquals("Following", reloaded.path("label").asString(), browser.toString());
        assertEquals(2, reloaded.path("followers").asInt());
    }

    @Test
    void pressingAgainUnfollows() {
        JsonNode unfollowed = page("afterUnfollow");

        assertEquals("Follow", unfollowed.path("label").asString(), browser.toString());
        assertEquals(1, unfollowed.path("followers").asInt());
    }

    @Test
    void ownProfileHasCountsButNoFollowButton() {
        assertFalse(page("ownProfile").path("buttonVisible").asBoolean(), browser.toString());
        assertTrue(page("ownProfile").path("hasCounts").asBoolean());
        assertTrue(page("myAccount").path("hasCounts").asBoolean());
    }

    @Test
    void friendButtonIsStillOffered() {
        assertTrue(page("initial").path("friendButton").asBoolean(), browser.toString());
    }

    @Test
    void failuresExplainThemselvesAndKeepTheButtonState() {
        JsonNode failed = page("failedFollow");

        assertEquals("Follow", failed.path("label").asString(), browser.toString());
        assertEquals(1, failed.path("followers").asInt());
        assertTrue(failed.path("error").asString().toLowerCase().contains("couldn't"), failed.toString());
        assertTrue(failed.path("buttonEnabled").asBoolean());
    }

    @Test
    void layoutFitsA390PixelPhone() {
        JsonNode mobile = page("mobile");

        assertEquals(390, mobile.path("width").asInt(), browser.toString());
        assertEquals(0, mobile.path("overflow").asInt(), mobile.toString());
        assertTrue(mobile.path("buttonRight").asInt() <= 390, mobile.toString());
        assertTrue(mobile.path("buttonVisible").asBoolean(), mobile.toString());
    }

    // ---- helpers ----

    private long rows(Account follower, Account following) {
        return jdbc.queryForObject(
                "select count(*) from user_follows where follower_id = ? and following_id = ?",
                Long.class, follower.id(), following.id());
    }

    private JsonNode profile(Account target, Account viewer) throws Exception {
        Reply reply = send("GET", "/users/" + target.id() + "/profile", viewer);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply follow(Account fan, Account creator) throws Exception {
        return send("POST", "/users/" + creator.id() + "/follow", fan);
    }

    private Reply unfollow(Account fan, Account creator) throws Exception {
        return send("DELETE", "/users/" + creator.id() + "/follow", fan);
    }

    private Account register() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "name", "Follow QA",
                        "email", "follow_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                        "password", "Audit#12345"
                )))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        JsonNode node = json.readTree(response.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
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
