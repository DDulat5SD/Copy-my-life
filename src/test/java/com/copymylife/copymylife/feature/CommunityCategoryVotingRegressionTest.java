package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
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
import java.util.Arrays;
import java.util.HashMap;
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
 * US-28: a user who has liked and rated a public routine votes once for the category that fits it
 * best. The first vote opens the routine's voting period; when the period has ended the votes are
 * counted and the routine is filed under the category with the most votes, automatically. A tie
 * keeps the current category if it is one of the leaders, otherwise the leader first in the
 * administrators' order wins. Nobody votes twice, nobody votes after the end, and votes keep
 * pointing at their category when it is renamed.
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
class CommunityCategoryVotingRegressionTest {

    private static final String BOUNDARY = "CategoryVoteBoundary";
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token) {
    }

    private record Reply(int status, String body) {
    }

    private JsonNode browser;
    private Account owner;
    private Account admin;
    private final Map<String, Long> categories = new HashMap<>();

    // ---- AC1: a user who liked and rated the routine votes, and the vote is recorded ----

    @Test
    void aUserWhoLikedAndRatedVotesAndTheVoteIsRecorded() throws Exception {
        long routine = routine(owner, "Vote here", "Morning", true);
        Account voter = fan(routine);

        JsonNode summary = vote(voter, routine, "Fitness");

        assertEquals("OPEN", summary.path("status").asString(), summary.toString());
        assertEquals("Fitness", summary.path("myVote").path("name").asString(), summary.toString());
        assertEquals(1, summary.path("totalVotes").asInt());
        assertFalse(summary.path("canVote").asBoolean());
        assertEquals("ALREADY_VOTED", summary.path("reason").asString());
        assertEquals(1, votes(summary, "Fitness"));
        Map<String, Object> row = jdbc.queryForMap(
                "select routine_id, user_id, category_id from routine_category_votes where routine_id = ? and user_id = ?",
                routine, voter.id());
        assertEquals(routine, ((Number) row.get("routine_id")).longValue());
        assertEquals(voter.id(), ((Number) row.get("user_id")).longValue());
        assertEquals((long) categories.get("Fitness"), ((Number) row.get("category_id")).longValue());
        assertEquals("Morning", category(routine), "nothing is assigned while voting is open");
    }

    @Test
    void theFirstVoteOpensASevenDayPeriod() throws Exception {
        long routine = routine(owner, "Period starts", "Morning", true);
        assertEquals("NOT_STARTED", summary(fan(routine), routine).path("status").asString());
        JsonNode summary = vote(fan(routine), routine, "Study");
        assertEquals(7, summary.path("votingDays").asInt(), summary.toString());
        Long hours = jdbc.queryForObject(
                "select round(extract(epoch from closes_at - opened_at) / 3600) from routine_category_votings where routine_id = ?",
                Long.class, routine);
        assertEquals(168L, hours);
        assertEquals(1L, (long) jdbc.queryForObject("select count(*) from routine_category_votings where routine_id = ?", Long.class, routine));
    }

    @Test
    void withoutALikeOrARatingTheVoteIsRefused() throws Exception {
        long routine = routine(owner, "Earn your vote", "Morning", true);

        Account nothing = register("No like no rating");
        assertRefused(post(nothing, routine, "Fitness"), 400, "Like and rate");
        assertEquals("LIKE_AND_RATE", summary(nothing, routine).path("reason").asString());

        Account onlyLiked = register("Only liked");
        like(onlyLiked, routine);
        assertRefused(post(onlyLiked, routine, "Fitness"), 400, "Rate this routine");

        Account onlyRated = register("Only rated");
        rate(onlyRated, routine, 4);
        assertRefused(post(onlyRated, routine, "Fitness"), 400, "Like this routine");

        JsonNode summary = summary(onlyRated, routine);
        assertTrue(summary.path("rated").asBoolean());
        assertFalse(summary.path("liked").asBoolean());
        assertEquals(0L, voteCount(routine), "nothing was recorded");
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from routine_category_votings where routine_id = ?", Long.class, routine),
                "no period was opened");
    }

    @Test
    void likesAndRatingsKeepWorkingAsBefore() throws Exception {
        long routine = routine(owner, "Likes stay", "Morning", true);
        Account voter = fan(routine);
        vote(voter, routine, "Fitness");
        JsonNode rating = json.readTree(get("/routine-ratings/" + routine, voter).body());
        assertEquals(5, rating.path("userRating").asInt(), rating.toString());
        assertEquals(1, rating.path("ratingCount").asInt());
        assertEquals(200, send("POST", "/routine-ratings/" + routine, voter, Map.of("value", 3)).status(), "a rating can still change");
        assertEquals(204, send("DELETE", "/liked-routines/" + routine, voter, null).status(), "a like can still be taken back");
        assertEquals(1L, voteCount(routine), "the vote that was cast stays");
    }

    // ---- AC3: one vote per user and routine ----

    @Test
    void aSecondVoteIsRefused() throws Exception {
        long routine = routine(owner, "Vote once", "Morning", true);
        Account voter = fan(routine);
        vote(voter, routine, "Fitness");
        assertRefused(post(voter, routine, "Study"), 409, "already voted");
        assertRefused(post(voter, routine, "Fitness"), 409, "already voted");
        assertEquals(1L, voteCount(routine));
        assertEquals("Fitness", summary(voter, routine).path("myVote").path("name").asString(), "the first vote stays");
    }

    @Test
    void twoRequestsAtOnceRecordOneVote() throws Exception {
        long routine = routine(owner, "Double click", "Morning", true);
        Account voter = fan(routine);
        CompletableFuture<HttpResponse<String>> first = postAsync(voter, routine, categories.get("Fitness"));
        CompletableFuture<HttpResponse<String>> second = postAsync(voter, routine, categories.get("Study"));
        List<Integer> statuses = new ArrayList<>(List.of(first.get(30, TimeUnit.SECONDS).statusCode(), second.get(30, TimeUnit.SECONDS).statusCode()));
        statuses.sort(Integer::compareTo);
        assertEquals(List.of(200, 409), statuses);
        assertEquals(1L, voteCount(routine));
    }

    @Test
    void firstVotesAtOnceOpenOnePeriod() throws Exception {
        long routine = routine(owner, "Opened once", "Morning", true);
        Account a = fan(routine);
        Account b = fan(routine);
        CompletableFuture<HttpResponse<String>> first = postAsync(a, routine, categories.get("Fitness"));
        CompletableFuture<HttpResponse<String>> second = postAsync(b, routine, categories.get("Study"));
        assertEquals(200, first.get(30, TimeUnit.SECONDS).statusCode());
        assertEquals(200, second.get(30, TimeUnit.SECONDS).statusCode());
        assertEquals(2L, voteCount(routine));
        assertEquals(1L, (long) jdbc.queryForObject("select count(*) from routine_category_votings where routine_id = ?", Long.class, routine));
    }

    @Test
    void theDatabaseItselfRefusesASecondVote() {
        List<String> constraints = jdbc.queryForList(
                "select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'routine_category_votes'::regclass", String.class);
        assertTrue(constraints.stream().anyMatch(c -> c.startsWith("UNIQUE") && c.contains("routine_id") && c.contains("user_id")), constraints.toString());
        assertTrue(constraints.stream().anyMatch(c -> c.contains("REFERENCES categories")), constraints.toString());
        assertTrue(constraints.stream().anyMatch(c -> c.contains("REFERENCES routines")), constraints.toString());
        assertTrue(constraints.stream().anyMatch(c -> c.contains("REFERENCES users")), constraints.toString());
    }

    // ---- who may vote, and where ----

    @Test
    void aVisitorABadTokenAndABannedUserCannotVote() throws Exception {
        long routine = routine(owner, "Members only", "Morning", true);
        Account banned = fan(routine);
        jdbc.update("update users set banned = true where id = ?", banned.id());
        for (Account who : Arrays.asList(null, new Account(0, "not-a-token"), banned)) {
            Reply reply = post(who, routine, "Fitness");
            assertTrue(reply.status() == 401 || reply.status() == 403, reply.status() + " " + reply.body());
            Reply read = get("/routines/" + routine + "/category-vote", who);
            assertTrue(read.status() == 401 || read.status() == 403, read.status() + " " + read.body());
        }
        assertEquals(0L, voteCount(routine));
    }

    @Test
    void privateDeletedHiddenAndUnknownRoutinesTakeNoVotes() throws Exception {
        long priv = routine(owner, "Private one", "Morning", true);
        Account fan = fan(priv);
        // Liked and rated while public, then made private.
        assertEquals(200, multipart("PUT", "/routines/" + priv, owner, fields("Private one", "Morning", false)).status());
        assertEquals(404, post(fan, priv, "Fitness").status());
        assertRefused(post(owner, priv, "Fitness"), 400, "Only public routines");

        long gone = routine(owner, "Deleted one", "Morning", true);
        Account goneFan = fan(gone);
        assertEquals(204, send("DELETE", "/routines/" + gone, owner, null).status());
        assertEquals(404, post(goneFan, gone, "Fitness").status());

        long hidden = routine(owner, "Hidden one", "Morning", true);
        Account hiddenFan = fan(hidden);
        assertEquals(200, send("PUT", "/admin-api/routines/" + hidden + "/hide", admin, null).status());
        assertEquals(404, post(hiddenFan, hidden, "Fitness").status());
        assertRefused(post(owner, hidden, "Fitness"), 400, "Only public routines");

        assertEquals(404, post(fan, 987_654_321L, "Fitness").status());
        assertEquals(0L, voteCount(priv) + voteCount(gone) + voteCount(hidden));
    }

    @Test
    void theCategoryMustBeOneOfTheList() throws Exception {
        long routine = routine(owner, "Real categories", "Morning", true);
        Account voter = fan(routine);
        assertRefused(send("POST", "/routines/" + routine + "/category-vote", voter, Map.of("categoryId", 987_654_321L)), 404, "Category not found");
        assertRefused(send("POST", "/routines/" + routine + "/category-vote", voter, Map.of()), 400, "Choose a category");
        assertRefused(send("POST", "/routines/" + routine + "/category-vote", voter, null), 400, "Choose a category");
        assertEquals(400, raw("POST", "/routines/" + routine + "/category-vote", voter, "{\"categoryId\":\"x\"}").status());

        long removed = adminJson("POST", "/admin-api/categories", Map.of("name", "Short lived " + suffix())).path("id").asLong();
        assertEquals(204, send("DELETE", "/admin-api/categories/" + removed, admin, null).status());
        assertRefused(send("POST", "/routines/" + routine + "/category-vote", voter, Map.of("categoryId", removed)), 404, "Category not found");
        assertEquals(0L, voteCount(routine));
    }

    @Test
    void theVoterIsTheSignedInUserWhateverTheBodySays() throws Exception {
        long routine = routine(owner, "My own vote", "Morning", true);
        Account voter = fan(routine);
        Account someoneElse = register("Someone else");
        Reply reply = send("POST", "/routines/" + routine + "/category-vote", voter,
                Map.of("categoryId", categories.get("Study"), "userId", someoneElse.id()));
        assertEquals(200, reply.status(), reply.body());
        assertEquals(voter.id(), (long) jdbc.queryForObject("select user_id from routine_category_votes where routine_id = ?", Long.class, routine));
    }

    // ---- categories change under the votes ----

    @Test
    void renamingACategoryKeepsItsVotesAndTheWinnerTakesTheNewName() throws Exception {
        String name = "Calm " + suffix();
        long id = adminJson("POST", "/admin-api/categories", Map.of("name", name)).path("id").asLong();
        long routine = routine(owner, "Renamed while open", "Morning", true);
        vote(fan(routine), routine, id);

        adminJson("PUT", "/admin-api/categories/" + id, Map.of("name", "Very " + name));
        assertEquals(1, votes(summary(owner, routine), "Very " + name), "the vote follows the category");

        close(routine);
        assertEquals("Very " + name, summary(owner, routine).path("winner").path("name").asString());
        assertEquals("Very " + name, category(routine));
    }

    @Test
    void aCategoryWithVotesIsNotRemoved() throws Exception {
        String name = "Voted " + suffix();
        long id = adminJson("POST", "/admin-api/categories", Map.of("name", name)).path("id").asLong();
        long routine = routine(owner, "Holds a vote", "Morning", true);
        vote(fan(routine), routine, id);
        assertRefused(send("DELETE", "/admin-api/categories/" + id, admin, null), 409, "vote");
        assertEquals(1L, voteCount(routine), "the vote keeps its category");
    }

    // ---- AC2: when the period has ended, the votes decide the category ----

    @Test
    void whenThePeriodEndsTheCategoryWithTheMostVotesIsAssigned() throws Exception {
        long routine = routine(owner, "Community decides " + TRICKY, "Morning", true);
        vote(fan(routine), routine, "Productivity");
        vote(fan(routine), routine, "Productivity");
        vote(fan(routine), routine, "Fitness");
        assertFalse(get("/top-routines?period=all&category=productivity", owner).body().contains("\"id\":" + routine));

        close(routine);
        JsonNode summary = summary(owner, routine);

        assertEquals("CLOSED", summary.path("status").asString(), summary.toString());
        assertEquals("Productivity", summary.path("winner").path("name").asString());
        assertEquals(2, votes(summary, "Productivity"));
        assertEquals(1, votes(summary, "Fitness"));
        assertEquals("Productivity", category(routine));
        assertEquals("Productivity", json.readTree(get("/routines/" + routine, null).body()).path("category").asString(), "Explore and the routine page");
        assertTrue(get("/routines/search?category=productivity", null).body().contains(String.valueOf(routine)), "search");
        assertTrue(get("/top-routines?period=all&category=productivity", owner).body().contains("\"id\":" + routine), "Top Routines");
        JsonNode listed = find(json.readTree(get("/routines/public", null).body()), routine);
        assertEquals("Productivity", listed.path("category").asString());
    }

    @Test
    void theScheduledCountAssignsEndedPeriodsWithoutAnyoneOpeningThePage() throws Exception {
        long routine = routine(owner, "Counted by the clock", "Morning", true);
        vote(fan(routine), routine, "Wellness");
        jdbc.update("update routine_category_votings set closes_at = now() - interval '1 minute' where routine_id = ?", routine);

        Object service = context.getBean("categoryVoteService");
        service.getClass().getMethod("finalizeDueVotings").invoke(service);

        assertEquals("Wellness", category(routine));
        assertTrue(jdbc.queryForObject("select closed_at is not null from routine_category_votings where routine_id = ?", Boolean.class, routine));
    }

    @Test
    void aTieKeepsTheCurrentCategoryWhenItIsALeader() throws Exception {
        long routine = routine(owner, "Tie kept", "Wellness", true);
        vote(fan(routine), routine, "Fitness");
        vote(fan(routine), routine, "Wellness");
        close(routine);
        assertEquals("Wellness", summary(owner, routine).path("winner").path("name").asString());
        assertEquals("Wellness", category(routine));
    }

    @Test
    void aTieOtherwiseGoesToTheLeaderFirstInTheList() throws Exception {
        long routine = routine(owner, "Tie by order", "Study", true);
        // Wellness comes after Fitness in the administrators' order, whatever order the votes came in.
        vote(fan(routine), routine, "Wellness");
        vote(fan(routine), routine, "Fitness");
        close(routine);
        assertEquals("Fitness", category(routine));
    }

    @Test
    void anEndedPeriodTakesNoMoreVotes() throws Exception {
        long routine = routine(owner, "Closed for votes", "Morning", true);
        vote(fan(routine), routine, "Fitness");
        Account late = fan(routine);
        jdbc.update("update routine_category_votings set closes_at = now() - interval '1 minute' where routine_id = ?", routine);

        assertRefused(post(late, routine, "Study"), 409, "closed");
        assertEquals("CLOSED", summary(late, routine).path("status").asString());
        assertEquals("CLOSED", summary(late, routine).path("reason").asString());
        assertEquals(1L, voteCount(routine));
        assertEquals("Fitness", category(routine), "the refusal still counted the period");
    }

    @Test
    void countingAgainChangesNothing() throws Exception {
        long routine = routine(owner, "Counted once", "Morning", true);
        vote(fan(routine), routine, "Study");
        close(routine);
        String after = jdbc.queryForList("select * from routine_category_votings where routine_id = ?", routine).toString();

        // The owner files it elsewhere afterwards; counting again does not undo that.
        assertEquals(200, multipart("PUT", "/routines/" + routine, owner, fields("Counted once", "Fitness", true)).status());
        Object service = context.getBean("categoryVoteService");
        service.getClass().getMethod("finalizeDueVotings").invoke(service);
        summary(owner, routine);
        assertEquals(after, jdbc.queryForList("select * from routine_category_votings where routine_id = ?", routine).toString());
        assertEquals("Fitness", category(routine));
    }

    @Test
    void aFailureWhileCountingLeavesThePeriodOpenAndTheCategoryAsItWas() throws Exception {
        long routine = routine(owner, "Count fails", "Morning", true);
        vote(fan(routine), routine, "Study");
        jdbc.update("update routine_category_votings set closes_at = now() - interval '1 minute' where routine_id = ?", routine);
        jdbc.execute("""
                create or replace function category_vote_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'category vote test failure'; end $$
                """);
        jdbc.execute("create trigger category_vote_test_fail before update on routines for each row when (new.id = "
                + routine + ") execute function category_vote_test_fail()");
        try {
            Reply reply = get("/routines/" + routine + "/category-vote", owner);
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists category_vote_test_fail on routines");
            jdbc.execute("drop function if exists category_vote_test_fail()");
        }
        assertTrue(jdbc.queryForObject("select closed_at is null from routine_category_votings where routine_id = ?", Boolean.class, routine),
                "the period stays open");
        assertEquals("Morning", category(routine));

        assertEquals("CLOSED", summary(owner, routine).path("status").asString());
        assertEquals("Study", category(routine));
    }

    @Test
    void aFailureWhileVotingKeepsNothing() throws Exception {
        long routine = routine(owner, "Vote fails", "Morning", true);
        Account voter = fan(routine);
        jdbc.execute("""
                create or replace function category_vote_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'category vote test failure'; end $$
                """);
        jdbc.execute("create trigger category_vote_test_fail before insert on routine_category_votes for each row when (new.routine_id = "
                + routine + ") execute function category_vote_test_fail()");
        try {
            Reply reply = post(voter, routine, "Study");
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists category_vote_test_fail on routine_category_votes");
            jdbc.execute("drop function if exists category_vote_test_fail()");
        }
        assertEquals(0L, voteCount(routine));
        assertEquals(0L, (long) jdbc.queryForObject("select count(*) from routine_category_votings where routine_id = ?", Long.class, routine),
                "the period opened with it was rolled back");
        vote(voter, routine, "Study");
    }

    // ---- browser ----

    @Test
    void browserAsksForALikeAndARatingFirst() {
        JsonNode view = page("before");
        assertTrue(view.path("panelVisible").asBoolean(), view.toString());
        assertTrue(view.path("message").asString().contains("Like and rate"), view.toString());
        assertTrue(view.path("buttonDisabled").asBoolean(), view.toString());
        assertTrue(view.path("guestMessage").asString().contains("Sign in"), view.toString());
    }

    @Test
    void browserCancelRecordsNothing() {
        JsonNode view = page("cancel");
        assertFalse(view.path("modalOpen").asBoolean(), view.toString());
        assertEquals(0, view.path("votesAfter").asInt(), view.toString());
        assertTrue(view.path("options").toString().contains("Fitness"), view.toString());
    }

    @Test
    void browserRecordsOneVoteForADoubleClickAndShowsIt() {
        JsonNode view = page("vote");
        assertEquals(1, view.path("votesAfter").asInt(), "one vote for a double click: " + view);
        assertTrue(view.path("notice").asString().contains("Fitness"), view.toString());
        assertTrue(view.path("message").asString().contains("You voted for Fitness"), view.toString());
        assertTrue(view.path("buttonHidden").asBoolean(), view.toString());
        assertTrue(view.path("results").asString().contains("Fitness"), view.toString());
        assertFalse(view.path("xss").asBoolean(), view.toString());
    }

    @Test
    void browserShowsTheCommunityChoiceWhenVotingHasClosed() {
        JsonNode view = page("closed");
        assertTrue(view.path("message").asString().contains("The community chose Fitness"), view.toString());
        assertTrue(view.path("buttonHidden").asBoolean(), view.toString());
        assertEquals("Fitness", view.path("categoryShown").asString(), view.toString());
    }

    @Test
    void browserExplainsARefusal() {
        JsonNode view = page("refused");
        assertTrue(view.path("error").asString().contains("Like this routine"), view.toString());
        assertTrue(view.path("modalOpen").asBoolean(), view.toString());
    }

    @Test
    void browserKeepsTheLayoutInEveryDesignAndWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(10, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("inside").asBoolean()) {
                problems.add(entry.getKey() + " has parts outside the screen: " + view.path("outside").asString());
            }
            if (!view.path("actionsVisible").asBoolean()) {
                problems.add(entry.getKey() + " hides Confirm vote or Cancel");
            }
            if (view.path("minContrast").asDouble() < 4.5) {
                problems.add(entry.getKey() + " has faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
            }
            if (!view.path("othersVisible").asBoolean()) {
                problems.add(entry.getKey() + " lost Like, rating or Copy routine");
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
        owner = register("Routine owner");
        admin = register("Category admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        for (JsonNode category : json.readTree(get("/categories", null).body())) {
            categories.put(category.path("name").asString(), category.path("id").asLong());
        }

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account voter = register("Browser voter");
        long open = routine(owner, "Browser vote " + TRICKY, "Morning", true);
        long closed = routine(owner, "Browser closed vote", "Morning", true);
        long refused = routine(owner, "Browser refused vote", "Morning", true);
        long layout = routine(owner, "Browser layout vote", "Morning", true);
        for (long routine : List.of(closed, refused, layout)) {
            like(voter, routine);
            rate(voter, routine, 5);
        }
        // Closed with Fitness the winner. On the other one the voter takes the like back while
        // the form is open, so the server refuses the vote.
        // Before US-28 there is nothing to vote with; the browser part then simply fails.
        if (post(fan(closed), closed, "Fitness").status() == 200) {
            close(closed);
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("voterToken", voter.token());
        fixtures.put("open", open);
        fixtures.put("closed", closed);
        fixtures.put("refused", refused);
        fixtures.put("layout", layout);

        Path dir = Files.createTempDirectory("category-vote-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/category-vote-check.mjs", chrome,
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

    // The categories added here would show as extra chips in the Explore checks of later classes;
    // their test votes go first, as a category with votes is kept.
    @AfterAll
    void removeAddedCategories() {
        String added = "select id from categories where name_key not in ('morning', 'fitness', 'study', 'wellness', 'productivity', 'creators')";
        jdbc.update("delete from routine_category_votes where category_id in (" + added + ")");
        jdbc.update("delete from routine_category_votings where winner_category_id in (" + added + ")");
        jdbc.update("delete from categories where id in (" + added + ")");
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    // ---- helpers ----

    // A new user who liked and rated the routine, so may vote.
    private Account fan(long routine) throws Exception {
        Account account = register("Fan " + suffix());
        like(account, routine);
        rate(account, routine, 5);
        return account;
    }

    private void like(Account who, long routine) throws Exception {
        Reply reply = send("POST", "/liked-routines/" + routine, who, null);
        assertEquals(200, reply.status(), reply.body());
    }

    private void rate(Account who, long routine, int value) throws Exception {
        Reply reply = send("POST", "/routine-ratings/" + routine, who, Map.of("value", value));
        assertEquals(200, reply.status(), reply.body());
    }

    private JsonNode vote(Account who, long routine, String category) throws Exception {
        return vote(who, routine, categories.get(category));
    }

    private JsonNode vote(Account who, long routine, long categoryId) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/category-vote", who, Map.of("categoryId", categoryId));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private Reply post(Account who, long routine, String category) throws Exception {
        return send("POST", "/routines/" + routine + "/category-vote", who, Map.of("categoryId", categories.get(category)));
    }

    private CompletableFuture<HttpResponse<String>> postAsync(Account who, long routine, long categoryId) throws Exception {
        return http.sendAsync(request("POST", "/routines/" + routine + "/category-vote", who,
                json.writeValueAsString(Map.of("categoryId", categoryId))), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode summary(Account who, long routine) throws Exception {
        Reply reply = get("/routines/" + routine + "/category-vote", who);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    // Ends the routine's voting period and counts it, as a visit to the routine does.
    private void close(long routine) throws Exception {
        jdbc.update("update routine_category_votings set closes_at = now() - interval '1 minute' where routine_id = ?", routine);
        assertEquals("CLOSED", summary(owner, routine).path("status").asString());
    }

    private static int votes(JsonNode summary, String name) {
        for (JsonNode result : summary.path("results")) {
            if (result.path("name").asString().equals(name)) {
                return result.path("votes").asInt();
            }
        }
        return 0;
    }

    private long voteCount(long routine) {
        return jdbc.queryForObject("select count(*) from routine_category_votes where routine_id = ?", Long.class, routine);
    }

    private String category(long routine) {
        return jdbc.queryForObject("select category from routines where id = ?", String.class, routine);
    }

    private JsonNode adminJson(String method, String path, Map<String, ?> body) throws Exception {
        Reply reply = send(method, path, admin, body);
        assertEquals(200, reply.status(), method + " " + path + " " + reply.body());
        return json.readTree(reply.body());
    }

    private void assertRefused(Reply reply, int status, String words) throws Exception {
        assertEquals(status, reply.status(), reply.body());
        String message = json.readTree(reply.body()).path("message").asString();
        assertTrue(message.toLowerCase().contains(words.toLowerCase()), words + " in " + reply.body());
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError(id + " not in " + list);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "vote28_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private static Map<String, String> fields(String title, String category, boolean isPublic) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Category vote check");
        fields.put("category", category);
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));
        return fields;
    }

    private long routine(Account who, String title, String category, boolean isPublic) throws Exception {
        Reply reply = multipart("POST", "/routines", who, fields(title, category, isPublic));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply multipart(String method, String path, Account who, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + who.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, Account who) throws Exception {
        return send("GET", path, who, null);
    }

    private Reply send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        return raw(method, path, who, payload == null ? null : json.writeValueAsString(payload));
    }

    private Reply raw(String method, String path, Account who, String payload) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, who, payload), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
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
