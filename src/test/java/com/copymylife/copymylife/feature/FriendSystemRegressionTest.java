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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-29: a user sends a friend request from someone's profile; the other accepts it, and both
 * are in each other's friend list, or declines it, and the request is gone. Either friend removes
 * the friendship for both. A pair of users has one relationship at most, whatever arrives at
 * once; refusals answer with a clear status and message, and only the two people concerned act
 * on a request or a friendship. Friends and follows stay separate.
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
class FriendSystemRegressionTest {

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

    // ---- AC1: Add Friend sends a request ----

    @Test
    void addFriendSendsARequestBothSidesCanSee() throws Exception {
        Account a = register("Sender");
        Account b = register("Receiver");

        JsonNode sent = request(a, b);
        assertEquals("PENDING", sent.path("status").asString(), sent.toString());
        assertEquals(b.id(), sent.path("userId").asLong());

        JsonNode incoming = list("/friends/requests", b);
        assertEquals(1, incoming.size(), incoming.toString());
        assertEquals(a.id(), incoming.get(0).path("userId").asLong());
        assertEquals(sent.path("requestId").asLong(), incoming.get(0).path("requestId").asLong());
        JsonNode outgoing = list("/friends/requests/sent", a);
        assertEquals(b.id(), outgoing.get(0).path("userId").asLong(), outgoing.toString());

        assertEquals("PENDING", relationship(a, b), "the sender sees Request sent");
        assertEquals("RECEIVED", relationship(b, a), "the receiver sees Accept");
        assertEquals(0, list("/friends", a).size());
    }

    @Test
    void theSenderIsTheSignedInUserWhateverTheBodySays() throws Exception {
        Account a = register("Real sender");
        Account b = register("Target");
        Account c = register("Forged sender");
        Reply reply = send("POST", "/friends/request/" + b.id(), a, Map.of("senderId", c.id(), "userId", c.id()));
        assertEquals(200, reply.status(), reply.body());
        assertEquals(a.id(), (long) jdbc.queryForObject(
                "select sender_id from friendships where receiver_id = ?", Long.class, b.id()));
        assertEquals(0, list("/friends/requests/sent", c).size());
    }

    // ---- AC2: accepting makes both friends ----

    @Test
    void acceptingPutsBothInEachOthersFriendList() throws Exception {
        Account a = register("Friend A");
        Account b = register("Friend B");
        long id = request(a, b).path("requestId").asLong();

        Reply accepted = send("PUT", "/friends/requests/" + id + "/accept", b, null);
        assertEquals(200, accepted.status(), accepted.body());

        assertEquals(List.of(b.id()), ids(list("/friends", a)), "A's list");
        assertEquals(List.of(a.id()), ids(list("/friends", b)), "B's list");
        assertEquals("FRIENDS", relationship(a, b));
        assertEquals("FRIENDS", relationship(b, a));
        assertEquals(0, list("/friends/requests", b).size(), "no longer a request");
        assertEquals(0, list("/friends/requests/sent", a).size());
        assertEquals(1L, pairRows(a, b), "one relationship for the pair");
    }

    @Test
    void acceptingTwiceChangesNothing() throws Exception {
        Account a = register("Twice A");
        Account b = register("Twice B");
        long id = request(a, b).path("requestId").asLong();
        assertEquals(200, send("PUT", "/friends/requests/" + id + "/accept", b, null).status());
        assertRefused(send("PUT", "/friends/requests/" + id + "/accept", b, null), 409, "not pending");
        assertEquals(1, list("/friends", a).size());
        assertEquals(1L, pairRows(a, b));
    }

    @Test
    void twoAcceptsAtOnceMakeOneFriendship() throws Exception {
        Account a = register("Race A");
        Account b = register("Race B");
        long id = request(a, b).path("requestId").asLong();
        CompletableFuture<HttpResponse<String>> first = async("PUT", "/friends/requests/" + id + "/accept", b);
        CompletableFuture<HttpResponse<String>> second = async("PUT", "/friends/requests/" + id + "/accept", b);
        List<Integer> statuses = sorted(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode());
        assertEquals(List.of(200, 409), statuses);
        assertEquals(1L, pairRows(a, b));
        assertEquals(1, list("/friends", b).size());
    }

    // ---- AC3: declining removes the request ----

    @Test
    void decliningRemovesTheRequestAndMakesNoFriendship() throws Exception {
        Account a = register("Declined A");
        Account b = register("Declining B");
        long id = request(a, b).path("requestId").asLong();

        Reply declined = send("PUT", "/friends/requests/" + id + "/reject", b, null);
        assertEquals(200, declined.status(), declined.body());

        assertEquals(0, list("/friends/requests", b).size(), "gone from B's requests");
        assertEquals(0, list("/friends/requests/sent", a).size(), "gone from A's sent requests");
        assertEquals(0, list("/friends", a).size());
        assertEquals(0, list("/friends", b).size());
        assertEquals("NONE", relationship(a, b));
        assertEquals("NONE", relationship(b, a));
        assertRefused(send("PUT", "/friends/requests/" + id + "/accept", b, null), 409, "not pending");

        // A declined request may be sent again later.
        assertEquals("PENDING", request(a, b).path("status").asString());
        assertEquals(1L, pairRows(a, b));
    }

    @Test
    void theSenderCancelsAPendingRequest() throws Exception {
        Account a = register("Cancel A");
        Account b = register("Cancel B");
        long id = request(a, b).path("requestId").asLong();
        assertRefused(send("DELETE", "/friends/requests/" + id, b, null), 404, "not found");
        assertEquals(204, send("DELETE", "/friends/requests/" + id, a, null).status());
        assertEquals(0, list("/friends/requests", b).size());
        assertEquals(0L, pairRows(a, b));
    }

    // ---- AC4: Remove Friend ends it for both ----

    @Test
    void removingAFriendEndsItForBoth() throws Exception {
        Account a = register("Remover");
        Account b = register("Removed");
        befriend(a, b);

        assertEquals(204, send("DELETE", "/friends/" + b.id(), a, null).status());

        assertEquals(0, list("/friends", a).size());
        assertEquals(0, list("/friends", b).size(), "B's list too");
        assertEquals("NONE", relationship(a, b));
        assertEquals("NONE", relationship(b, a));
        assertEquals(0L, pairRows(a, b));
        assertRefused(send("DELETE", "/friends/" + b.id(), a, null), 404, "not your friend");
        assertRefused(send("DELETE", "/friends/" + a.id(), b, null), 404, "not your friend");
        // They can be friends again.
        befriend(b, a);
        assertEquals(1, list("/friends", a).size());
    }

    @Test
    void removingAPendingRequestAsAFriendIsRefused() throws Exception {
        Account a = register("Not yet A");
        Account b = register("Not yet B");
        request(a, b);
        assertRefused(send("DELETE", "/friends/" + b.id(), a, null), 404, "not your friend");
        assertEquals(1, list("/friends/requests", b).size(), "the request stays");
    }

    // ---- one relationship per pair ----

    @Test
    void aSecondRequestAndARequestToAFriendAreRefused() throws Exception {
        Account a = register("Dup A");
        Account b = register("Dup B");
        request(a, b);
        assertRefused(send("POST", "/friends/request/" + b.id(), a, null), 409, "already sent");
        assertRefused(send("POST", "/friends/request/" + a.id(), b, null), 409, "already sent you");
        assertEquals(1L, pairRows(a, b));

        Account c = register("Friend C");
        Account d = register("Friend D");
        befriend(c, d);
        assertRefused(send("POST", "/friends/request/" + d.id(), c, null), 409, "already friends");
        assertRefused(send("POST", "/friends/request/" + c.id(), d, null), 409, "already friends");
        assertEquals(1L, pairRows(c, d));
    }

    @Test
    void twoRequestsAtOnceMakeOneRequest() throws Exception {
        Account a = register("Burst A");
        Account b = register("Burst B");
        CompletableFuture<HttpResponse<String>> first = async("POST", "/friends/request/" + b.id(), a);
        CompletableFuture<HttpResponse<String>> second = async("POST", "/friends/request/" + b.id(), a);
        assertEquals(List.of(200, 409), sorted(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode()));
        assertEquals(1L, pairRows(a, b));
    }

    @Test
    void requestsBothWaysAtOnceMakeOneRequest() throws Exception {
        for (int round = 0; round < 3; round++) {
            Account a = register("Both A " + round);
            Account b = register("Both B " + round);
            CompletableFuture<HttpResponse<String>> first = async("POST", "/friends/request/" + b.id(), a);
            CompletableFuture<HttpResponse<String>> second = async("POST", "/friends/request/" + a.id(), b);
            assertEquals(List.of(200, 409), sorted(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode()));
            assertEquals(1L, pairRows(a, b));
            // Both profiles still answer.
            assertEquals(200, get("/users/" + b.id() + "/profile", a).status());
            assertEquals(200, get("/users/" + a.id() + "/profile", b).status());
        }
    }

    @Test
    void theDatabaseKeepsOneRelationshipPerPair() throws Exception {
        assertEquals(1L, (long) jdbc.queryForObject(
                "select count(*) from pg_indexes where tablename = 'friendships' and indexname = 'uk_friendships_pair'", Long.class));
        Account a = register("Index A");
        Account b = register("Index B");
        request(a, b);
        try {
            jdbc.update("insert into friendships (sender_id, receiver_id, status, created_at) values (?, ?, 'PENDING', now())", b.id(), a.id());
            throw new AssertionError("a reverse row was accepted");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertEquals(1L, pairRows(a, b));
        }
    }

    // ---- who may act ----

    @Test
    void nobodyBefriendsThemselvesOrSomeoneWhoIsNotThere() throws Exception {
        Account a = register("Lonely");
        assertRefused(send("POST", "/friends/request/" + a.id(), a, null), 400, "yourself");
        assertRefused(send("POST", "/friends/request/987654321", a, null), 404, "not found");
        assertEquals(400, send("POST", "/friends/request/abc", a, null).status());
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from friendships where sender_id = ?", Long.class, a.id()));
    }

    @Test
    void visitorsBadTokensAndBannedUsersCannotAct() throws Exception {
        Account a = register("Will be banned");
        Account b = register("Bystander");
        long id = request(b, a).path("requestId").asLong();
        jdbc.update("update users set banned = true where id = ?", a.id());

        for (Account who : Arrays.asList(null, new Account(0, "not-a-token"), a)) {
            for (Reply reply : List.of(
                    send("POST", "/friends/request/" + b.id(), who, null),
                    send("GET", "/friends", who, null),
                    send("GET", "/friends/requests", who, null),
                    send("PUT", "/friends/requests/" + id + "/accept", who, null),
                    send("DELETE", "/friends/" + b.id(), who, null))) {
                assertTrue(reply.status() == 401 || reply.status() == 403, reply.status() + " " + reply.body());
            }
        }
        // Nobody can send a request to a banned user either.
        Account c = register("Late comer");
        assertRefused(send("POST", "/friends/request/" + a.id(), c, null), 404, "not found");
        assertEquals("PENDING", jdbc.queryForObject("select status from friendships where id = ?", String.class, id));
    }

    @Test
    void aProfileAnAdministratorHidIsNotOfferedFriendship() throws Exception {
        Account hidden = register("Hidden profile");
        Account a = register("Looking");
        jdbc.update("update users set profile_hidden_at = now() where id = ?", hidden.id());
        assertRefused(send("POST", "/friends/request/" + hidden.id(), a, null), 404, "not found");
        assertEquals(0L, pairRows(a, hidden));
        jdbc.update("update users set profile_hidden_at = null where id = ?", hidden.id());
        request(a, hidden);
    }

    @Test
    void administratorAccountsTakeNoPartInFriendships() throws Exception {
        Account admin = register("Admin account");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        Account a = register("Member");
        assertRefused(send("POST", "/friends/request/" + admin.id(), a, null), 404, "not found");
        assertEquals(400, send("POST", "/friends/request/" + a.id(), admin, null).status());
    }

    @Test
    void onlyTheReceiverAnswersARequest() throws Exception {
        Account a = register("Asker");
        Account b = register("Asked");
        Account c = register("Outsider");
        long id = request(a, b).path("requestId").asLong();

        for (Account who : List.of(a, c)) {
            assertRefused(send("PUT", "/friends/requests/" + id + "/accept", who, null), 404, "not found");
            assertRefused(send("PUT", "/friends/requests/" + id + "/reject", who, null), 404, "not found");
        }
        assertRefused(send("PUT", "/friends/requests/987654321/accept", b, null), 404, "not found");
        assertRefused(send("PUT", "/friends/requests/987654321/reject", b, null), 404, "not found");
        assertRefused(send("DELETE", "/friends/requests/987654321", a, null), 404, "not found");
        assertEquals(400, send("PUT", "/friends/requests/abc/accept", b, null).status());
        assertEquals("PENDING", jdbc.queryForObject("select status from friendships where id = ?", String.class, id));
    }

    @Test
    void anOutsiderCannotEndSomeoneElsesFriendship() throws Exception {
        Account a = register("Pair A");
        Account b = register("Pair B");
        Account c = register("Meddler");
        befriend(a, b);
        assertRefused(send("DELETE", "/friends/" + b.id(), c, null), 404, "not your friend");
        assertRefused(send("DELETE", "/friends/" + a.id(), c, null), 404, "not your friend");
        assertEquals(1, list("/friends", a).size());
        assertEquals(1, list("/friends", b).size());
    }

    // ---- all or nothing ----

    @Test
    void aFailureWhileAcceptingKeepsTheRequestPending() throws Exception {
        Account a = register("Rollback A");
        Account b = register("Rollback B");
        long id = request(a, b).path("requestId").asLong();
        jdbc.execute("""
                create or replace function friend_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'friend test failure'; end $$
                """);
        jdbc.execute("create trigger friend_test_fail before update on friendships for each row when (new.id = "
                + id + ") execute function friend_test_fail()");
        try {
            Reply reply = send("PUT", "/friends/requests/" + id + "/accept", b, null);
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists friend_test_fail on friendships");
            jdbc.execute("drop function if exists friend_test_fail()");
        }
        assertEquals("PENDING", jdbc.queryForObject("select status from friendships where id = ?", String.class, id));
        assertEquals(0, list("/friends", a).size());
        assertEquals(0, list("/friends", b).size());
        assertEquals(200, send("PUT", "/friends/requests/" + id + "/accept", b, null).status());
    }

    // ---- friends are not follows ----

    @Test
    void friendsAndFollowsStayIndependent() throws Exception {
        Account a = register("Follow A");
        Account b = register("Follow B");
        befriend(a, b);
        JsonNode profile = json.readTree(get("/users/" + b.id() + "/profile", a).body());
        assertEquals(0, profile.path("followersCount").asInt(), "a friendship is not a follow");
        assertFalse(profile.path("isFollowing").asBoolean());
        assertEquals(0L, (long) jdbc.queryForObject(
                "select count(*) from user_follows where follower_id = ? or following_id = ?", Long.class, a.id(), a.id()));

        assertEquals(200, send("POST", "/users/" + b.id() + "/follow", a, null).status());
        assertEquals(204, send("DELETE", "/friends/" + b.id(), a, null).status());
        JsonNode after = json.readTree(get("/users/" + b.id() + "/profile", a).body());
        assertEquals(1, after.path("followersCount").asInt(), "ending the friendship keeps the follow");
        assertTrue(after.path("isFollowing").asBoolean());
    }

    @Test
    void aNewUserHasNoFriendsAndNoRequests() throws Exception {
        Account a = register("Newcomer");
        assertEquals("[]", get("/friends", a).body());
        assertEquals("[]", get("/friends/requests", a).body());
        assertEquals("[]", get("/friends/requests/sent", a).body());
    }

    @Test
    void namesAreKeptAsPlainText() throws Exception {
        Account a = register("Plain " + TRICKY);
        Account b = register("Reader");
        request(a, b);
        assertEquals("Plain " + TRICKY, list("/friends/requests", b).get(0).path("name").asString());
    }

    // ---- browser ----

    @Test
    void browserSendsOneRequestForADoubleClick() {
        JsonNode view = page("send");
        assertEquals("Sending...", view.path("whileSending").asString(), view.toString());
        assertTrue(view.path("statusText").asString().contains("Request sent"), view.toString());
        assertEquals(1, view.path("requests").asInt(), "one request for a double click: " + view);
        assertTrue(view.path("cancelVisible").asBoolean(), view.toString());
    }

    @Test
    void browserAcceptsFromFriendRequestsAndBothSeeFriends() {
        JsonNode view = page("accept");
        assertTrue(view.path("requestListed").asBoolean(), view.toString());
        assertTrue(view.path("requestText").asString().contains("Plain " + TRICKY), view.toString());
        assertEquals("1 friend", view.path("countAfter").asString(), view.toString());
        assertTrue(view.path("receiverSeesFriends").asBoolean(), view.toString());
        assertTrue(view.path("senderSeesFriends").asBoolean(), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());
    }

    @Test
    void browserRemovesAFriendForBoth() {
        JsonNode view = page("remove");
        assertTrue(view.path("addFriendBack").asBoolean(), view.toString());
        assertEquals(0, view.path("friendsOfRemover").asInt(), view.toString());
        assertEquals(0, view.path("friendsOfOther").asInt(), view.toString());
    }

    @Test
    void browserDeclinesARequest() {
        JsonNode view = page("decline");
        assertEquals("Decline", view.path("declineLabel").asString(), view.toString());
        assertTrue(view.path("addFriendBack").asBoolean(), view.toString());
        assertEquals(0, view.path("pendingAfter").asInt(), view.toString());
    }

    @Test
    void browserExplainsARefusalAndRecovers() {
        JsonNode view = page("refused");
        assertTrue(view.path("error").asString().contains("already sent"), view.toString());
        assertTrue(view.path("statusText").asString().contains("Request sent"), "the page shows what is true now: " + view);
    }

    @Test
    void browserAsksVisitorsToSignIn() {
        JsonNode view = page("guest");
        assertFalse(view.path("addFriendVisible").asBoolean(), view.toString());
        assertTrue(view.path("signIn").asString().contains("Sign in"), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(20, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("actionsInside").asBoolean()) {
                problems.add(entry.getKey() + " has friend actions outside the screen");
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
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

        Account sender = register("Plain " + TRICKY);
        Account receiver = register("Browser receiver");
        Account decliner = register("Browser decliner");
        Account asker = register("Browser asker");
        Account busy = register("Browser busy");
        Account layout = register("Browser layout");
        // The decliner has a request waiting; the layout user has one to show on Friend requests.
        send("POST", "/friends/request/" + decliner.id(), asker, null);
        send("POST", "/friends/request/" + layout.id(), asker, null);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("sender", Map.of("id", sender.id(), "token", sender.token()));
        fixtures.put("receiver", Map.of("id", receiver.id(), "token", receiver.token()));
        fixtures.put("decliner", Map.of("id", decliner.id(), "token", decliner.token()));
        fixtures.put("asker", Map.of("id", asker.id(), "token", asker.token()));
        fixtures.put("busy", Map.of("id", busy.id(), "token", busy.token()));
        fixtures.put("layout", Map.of("id", layout.id(), "token", layout.token()));

        Path dir = Files.createTempDirectory("friend-system-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/friend-system-check.mjs", chrome,
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

    private JsonNode request(Account from, Account to) throws Exception {
        Reply reply = send("POST", "/friends/request/" + to.id(), from, null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private void befriend(Account from, Account to) throws Exception {
        long id = request(from, to).path("requestId").asLong();
        assertEquals(200, send("PUT", "/friends/requests/" + id + "/accept", to, null).status());
    }

    private String relationship(Account viewer, Account target) throws Exception {
        Reply reply = get("/users/" + target.id() + "/profile", viewer);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("relationshipStatus").asString();
    }

    private long pairRows(Account a, Account b) {
        return jdbc.queryForObject("""
                select count(*) from friendships
                where (sender_id = ? and receiver_id = ?) or (sender_id = ? and receiver_id = ?)
                """, Long.class, a.id(), b.id(), b.id(), a.id());
    }

    private JsonNode list(String path, Account who) throws Exception {
        Reply reply = get(path, who);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("userId").asLong()));
        return ids;
    }

    private static List<Integer> sorted(int first, int second) {
        List<Integer> statuses = new ArrayList<>(List.of(first, second));
        statuses.sort(Integer::compareTo);
        return statuses;
    }

    private void assertRefused(Reply reply, int status, String words) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        String message = json.readTree(reply.body()).path("message").asString();
        assertTrue(message.toLowerCase().contains(words.toLowerCase()), words + " in " + reply.body());
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "friend29_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private Reply get(String path, Account who) throws Exception {
        return send("GET", path, who, null);
    }

    private Reply send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, who,
                payload == null ? null : json.writeValueAsString(payload)), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private CompletableFuture<HttpResponse<String>> async(String method, String path, Account who) {
        return http.sendAsync(request(method, path, who, null), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, Account who, String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
        }
        return builder.build();
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
