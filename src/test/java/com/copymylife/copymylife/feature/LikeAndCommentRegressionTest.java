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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// US-15: a signed-in user likes and comments on routines and posts. Liking again takes the like
// back, a non-empty comment appears under the content with its author's name, and a visitor is
// asked to sign in. Private content can be neither liked, commented nor read by anyone else.
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
class LikeAndCommentRegressionTest {

    private static final String BOUNDARY = "LikeAndCommentBoundary";
    private static final String MARKUP = "<img src=x onerror=\"window.__xss=1\">Tricky";
    private static final long MISSING_POST = 999_999_999L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;

    // Queries over the browser's own fixtures, which no other test touches.
    private final Map<String, String> stored = new HashMap<>();

    private record Reply(int status, String body) {
    }

    private record Account(long id, String token) {
    }

    // ---- routine likes ----

    @Test
    void aRoutineLikeAddsOneAndLikingAgainAddsNothing() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Routine Fan");
        Account second = register("Second Fan");
        long routine = routine(owner, "Liked morning", true);

        assertEquals(200, post("/liked-routines/" + routine, fan, null).status());
        assertEquals(1, likeCountOfRoutine(routine, fan));

        assertEquals(200, post("/liked-routines/" + routine, fan, null).status(), "liking twice is not an error");
        assertEquals(1, likeCountOfRoutine(routine, fan), "and does not count twice");
        assertEquals(1, count("select count(*) from liked_routines where routine_id = ? and user_id = ?", routine, fan.id()));

        assertEquals(200, post("/liked-routines/" + routine, second, null).status());
        assertEquals(2, likeCountOfRoutine(routine, owner));
        assertTrue(ids(json(get("/liked-routines", fan), 200)).contains(routine));
    }

    @Test
    void unlikingARoutineTakesOneAwayAndTheCountSurvivesARefresh() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Routine Fan");
        long routine = routine(owner, "Unliked morning", true);
        post("/liked-routines/" + routine, fan, null);
        assertEquals(1, likeCountOfRoutine(routine, fan));

        assertEquals(204, send("DELETE", "/liked-routines/" + routine, fan, null).status());

        assertEquals(0, likeCountOfRoutine(routine, fan));
        assertEquals(0, likeCountOfRoutine(routine, owner), "every reader sees the stored count");
        assertFalse(ids(json(get("/liked-routines", fan), 200)).contains(routine));
        assertEquals(404, send("DELETE", "/liked-routines/" + routine, fan, null).status(), "nothing left to remove");
        assertEquals(0, count("select count(*) from liked_routines where routine_id = ?", routine));
    }

    // ---- routine comments ----

    @Test
    void aRoutineCommentIsStoredWithItsTextAndAuthorNameAndSurvivesARefresh() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Amina Fan");
        long routine = routine(owner, "Commented morning", true);

        JsonNode created = json(post("/routine-comments/" + routine, fan, Map.of("text", "  How long is the run?  ")), 200);
        assertEquals("How long is the run?", created.path("text").asString());
        assertEquals("Amina Fan", created.path("userName").asString());
        assertEquals(fan.id(), created.path("userId").asLong());

        JsonNode reloaded = json(get("/routine-comments/routine/" + routine, owner), 200);
        assertEquals(1, reloaded.size(), reloaded.toString());
        assertEquals("How long is the run?", reloaded.get(0).path("text").asString());
        assertEquals("Amina Fan", reloaded.get(0).path("userName").asString());
        assertEquals("How long is the run?", jdbc.queryForObject(
                "select text from routine_comments where routine_id = ? and user_id = ?", String.class, routine, fan.id()));
    }

    @Test
    void emptyBlankOrTooLongRoutineCommentsAreRefusedWith400() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Routine Fan");
        long routine = routine(owner, "Strict morning", true);

        for (Map<String, ?> body : List.<Map<String, ?>>of(
                Map.of("text", ""),
                Map.of("text", "   "),
                Map.of("text", " \n\t "),
                Map.of(),
                Map.of("text", "x".repeat(1001)))) {
            Reply reply = post("/routine-comments/" + routine, fan, body);
            assertEquals(400, reply.status(), body + " " + reply.body());
            assertFalse(message(reply).isBlank(), "the refusal says why: " + reply.body());
        }
        assertEquals(400, raw("POST", "/routine-comments/" + routine, fan, null).status(), "no body at all");
        assertEquals(400, raw("POST", "/routine-comments/" + routine, fan, "{not json").status());
        assertEquals(0, count("select count(*) from routine_comments where routine_id = ?", routine));

        assertEquals(200, post("/routine-comments/" + routine, fan, Map.of("text", "y".repeat(1000))).status(), "the limit itself is fine");
        assertEquals(1, count("select count(*) from routine_comments where routine_id = ?", routine));
    }

    @Test
    void aReplyPointingAtAnotherRoutinesCommentIsRefusedWithoutAServerError() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Routine Fan");
        long first = routine(owner, "First", true);
        long second = routine(owner, "Second", true);
        long elsewhere = json(post("/routine-comments/" + first, fan, Map.of("text", "On the first")), 200).path("id").asLong();

        Reply reply = post("/routine-comments/" + second, fan, Map.of("text", "Sneaky reply", "parentId", elsewhere));
        Reply missing = post("/routine-comments/" + second, fan, Map.of("text", "Sneaky reply", "parentId", 999_999_999L));

        assertEquals(404, reply.status(), reply.body());
        assertEquals(message(missing), message(reply), "another routine's comment looks like a missing one");
        assertEquals(0, count("select count(*) from routine_comments where routine_id = ?", second));
    }

    @Test
    void aRoutineCommentIsRemovedOnlyByItsAuthor() throws Exception {
        Account owner = register("Routine Owner");
        Account fan = register("Routine Fan");
        Account stranger = register("Stranger");
        long routine = routine(owner, "Moderated morning", true);
        long comment = json(post("/routine-comments/" + routine, fan, Map.of("text", "Mine")), 200).path("id").asLong();

        assertEquals(404, send("DELETE", "/routine-comments/" + comment, stranger, null).status());
        assertEquals(404, send("DELETE", "/routine-comments/" + comment, owner, null).status(), "not even the routine's owner");
        assertEquals(403, send("DELETE", "/routine-comments/" + comment, null, null).status());
        assertFalse(jdbc.queryForObject("select deleted from routine_comments where id = ?", Boolean.class, comment));

        assertEquals(204, send("DELETE", "/routine-comments/" + comment, fan, null).status());
        assertTrue(jdbc.queryForObject("select deleted from routine_comments where id = ?", Boolean.class, comment));
    }

    // ---- visitors ----

    @Test
    void aVisitorIsAskedToSignInBeforeLikingOrCommenting() throws Exception {
        Account owner = register("Owner");
        long routine = routine(owner, "Open morning", true);
        long postId = createPost(owner, "Open post", true);

        assertEquals(403, post("/liked-routines/" + routine, null, null).status());
        assertEquals(403, post("/routine-comments/" + routine, null, Map.of("text", "drive-by")).status());
        assertEquals(403, post("/posts/" + postId + "/like", null, null).status());
        assertEquals(403, send("DELETE", "/posts/" + postId + "/like", null, null).status());
        assertEquals(403, get("/posts/" + postId + "/comments", null).status());
        assertEquals(403, post("/posts/" + postId + "/comments", null, Map.of("text", "drive-by")).status());
        assertEquals(403, post("/posts/" + postId + "/like", new Account(0, "not-a-token"), null).status());

        assertEquals(0, count("select count(*) from liked_routines where routine_id = ?", routine));
        assertEquals(0, count("select count(*) from routine_comments where routine_id = ?", routine));
        assertEquals(0, count("select count(*) from post_likes where post_id = ?", postId));
        assertEquals(0, count("select count(*) from post_comments where post_id = ?", postId));
    }

    // ---- post likes ----

    @Test
    void aPostLikeAddsOneAndLikingAgainAddsNothing() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        Account second = register("Second Fan");
        long postId = createPost(owner, "Sunrise", true);

        JsonNode first = json(post("/posts/" + postId + "/like", fan, null), 200);
        assertEquals(1, first.path("likeCount").asLong());
        assertTrue(first.path("likedByMe").asBoolean());

        JsonNode again = json(post("/posts/" + postId + "/like", fan, null), 200);
        assertEquals(1, again.path("likeCount").asLong(), "liking twice does not count twice");
        assertTrue(again.path("likedByMe").asBoolean());
        assertEquals(1, count("select count(*) from post_likes where post_id = ? and user_id = ?", postId, fan.id()));

        assertEquals(2, json(post("/posts/" + postId + "/like", second, null), 200).path("likeCount").asLong());

        JsonNode seenByFan = json(get("/posts/" + postId, fan), 200);
        assertEquals(2, seenByFan.path("likeCount").asLong(), "the count survives a refresh");
        assertTrue(seenByFan.path("likedByMe").asBoolean());
        JsonNode seenByOwner = json(get("/posts/" + postId, owner), 200);
        assertEquals(2, seenByOwner.path("likeCount").asLong());
        assertFalse(seenByOwner.path("likedByMe").asBoolean());
        assertEquals(2, json(get("/users/" + owner.id() + "/posts", fan), 200).get(0).path("likeCount").asLong());
        assertEquals(2, json(get("/posts", owner), 200).get(0).path("likeCount").asLong());
    }

    @Test
    void unlikingAPostTakesOneAwayAndUnlikingAgainChangesNothing() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        long postId = createPost(owner, "Sunset", true);
        post("/posts/" + postId + "/like", fan, null);

        JsonNode unliked = json(send("DELETE", "/posts/" + postId + "/like", fan, null), 200);
        assertEquals(0, unliked.path("likeCount").asLong());
        assertFalse(unliked.path("likedByMe").asBoolean());

        JsonNode again = json(send("DELETE", "/posts/" + postId + "/like", fan, null), 200);
        assertEquals(0, again.path("likeCount").asLong(), "unliking twice is not an error and takes nothing more");

        JsonNode reloaded = json(get("/posts/" + postId, fan), 200);
        assertEquals(0, reloaded.path("likeCount").asLong());
        assertFalse(reloaded.path("likedByMe").asBoolean());
        assertEquals(0, count("select count(*) from post_likes where post_id = ?", postId));
    }

    @Test
    void simultaneousLikesAndUnlikesNeverFailAndLeaveTheRightCount() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Eager Fan");
        long postId = createPost(owner, "Popular", true);

        List<Reply> likes = together(16, () -> request("POST", "/posts/" + postId + "/like", fan, null));
        likes.forEach(reply -> assertEquals(200, reply.status(), reply.body()));
        assertEquals(1, count("select count(*) from post_likes where post_id = ?", postId), "one like, however many clicks");

        List<Reply> unlikes = together(16, () -> request("DELETE", "/posts/" + postId + "/like", fan, null));
        unlikes.forEach(reply -> assertEquals(200, reply.status(), reply.body()));
        assertEquals(0, count("select count(*) from post_likes where post_id = ?", postId));

        List<Account> fans = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fans.add(register("Fan " + i));
        }
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (Account each : fans) {
            for (int i = 0; i < 3; i++) {
                calls.add(http.sendAsync(request("POST", "/posts/" + postId + "/like", each, null), HttpResponse.BodyHandlers.ofString()));
            }
        }
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            assertEquals(200, call.get(30, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(6, count("select count(*) from post_likes where post_id = ?", postId));
        assertEquals(6, json(get("/posts/" + postId, owner), 200).path("likeCount").asLong());
    }

    @Test
    void postgresqlItselfKeepsOneLikePerUserAndPost() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        long postId = createPost(owner, "Guarded", true);
        post("/posts/" + postId + "/like", fan, null);

        List<String> columns = jdbc.queryForList("""
                select kcu.column_name from information_schema.table_constraints tc
                join information_schema.key_column_usage kcu
                  on kcu.constraint_name = tc.constraint_name and kcu.table_name = tc.table_name
                where tc.table_name = 'post_likes' and tc.constraint_type = 'UNIQUE'
                  and tc.constraint_name = 'uk_post_likes_post_user'
                order by kcu.ordinal_position
                """, String.class);
        assertEquals(List.of("post_id", "user_id"), columns);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into post_likes (post_id, user_id, created_at) values (?, ?, now())", postId, fan.id()));
        assertEquals(1, count("select count(*) from post_likes where post_id = ?", postId));
    }

    // ---- post comments ----

    @Test
    void aPostCommentIsStoredWithItsTextAndAuthorNameAndSurvivesARefresh() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Dana Fan");
        jdbc.update("update users set username = ? where id = ?", "dana_" + suffix(), fan.id());
        long postId = createPost(owner, "Morning run", true);

        JsonNode created = json(post("/posts/" + postId + "/comments", fan, Map.of("text", "  Great pace!  ")), 200);
        assertEquals("Great pace!", created.path("text").asString());
        assertEquals("Dana Fan", created.path("authorName").asString());
        assertEquals(fan.id(), created.path("authorId").asLong());
        assertTrue(created.path("ownedByMe").asBoolean());
        assertEquals(postId, created.path("postId").asLong());
        assertFalse(created.toString().contains("@audit.test"), "no email of the author");

        JsonNode reloaded = json(get("/posts/" + postId + "/comments", owner), 200);
        assertEquals(1, reloaded.size(), reloaded.toString());
        assertEquals("Great pace!", reloaded.get(0).path("text").asString());
        assertEquals("Dana Fan", reloaded.get(0).path("authorName").asString());
        assertTrue(reloaded.get(0).path("authorUsername").asString().startsWith("dana_"));
        assertFalse(reloaded.get(0).path("ownedByMe").asBoolean());
        assertEquals(1, json(get("/posts/" + postId, owner), 200).path("commentCount").asLong());
        assertEquals("Great pace!", jdbc.queryForObject(
                "select text from post_comments where post_id = ? and user_id = ?", String.class, postId, fan.id()));

        post("/posts/" + postId + "/comments", owner, Map.of("text", "Thanks!"));
        JsonNode both = json(get("/posts/" + postId + "/comments", fan), 200);
        assertEquals(List.of("Great pace!", "Thanks!"), texts(both, "text"), "oldest first, as a conversation reads");
    }

    @Test
    void emptyBlankOrTooLongPostCommentsAreRefusedWith400() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        long postId = createPost(owner, "Strict post", true);

        for (Map<String, ?> body : List.<Map<String, ?>>of(
                Map.of("text", ""),
                Map.of("text", "   "),
                Map.of("text", " \n\t "),
                Map.of(),
                Map.of("text", "x".repeat(1001)))) {
            Reply reply = post("/posts/" + postId + "/comments", fan, body);
            assertEquals(400, reply.status(), body + " " + reply.body());
            assertFalse(message(reply).isBlank(), "the refusal says why: " + reply.body());
        }
        assertEquals(400, raw("POST", "/posts/" + postId + "/comments", fan, null).status(), "no body at all");
        assertEquals(400, raw("POST", "/posts/" + postId + "/comments", fan, "{not json").status());
        assertEquals(0, count("select count(*) from post_comments where post_id = ?", postId));

        assertEquals(200, post("/posts/" + postId + "/comments", fan, Map.of("text", "y".repeat(1000))).status());
        assertEquals(1, count("select count(*) from post_comments where post_id = ?", postId));
    }

    @Test
    void markupInACommentIsKeptAsTypedForThePageToShowAsText() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register(MARKUP);
        long postId = createPost(owner, "Careful", true);

        assertEquals(MARKUP, json(post("/posts/" + postId + "/comments", fan, Map.of("text", MARKUP)), 200).path("text").asString());

        Reply reply = get("/posts/" + postId + "/comments", owner);
        assertTrue(reply.body().contains("<img src=x"), "stored as typed, not escaped twice: " + reply.body());
        assertEquals(MARKUP, json(reply, 200).get(0).path("text").asString());
        assertEquals(MARKUP, json(reply, 200).get(0).path("authorName").asString());
    }

    @Test
    void aPostCommentIsRemovedOnlyByItsAuthor() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        Account stranger = register("Stranger");
        long postId = createPost(owner, "Talked about", true);
        long otherPost = createPost(owner, "Other", true);
        long comment = json(post("/posts/" + postId + "/comments", fan, Map.of("text", "Mine")), 200).path("id").asLong();

        assertEquals(404, send("DELETE", "/posts/" + postId + "/comments/" + comment, stranger, null).status());
        assertEquals(404, send("DELETE", "/posts/" + postId + "/comments/" + comment, owner, null).status(), "not even the post's owner");
        assertEquals(403, send("DELETE", "/posts/" + postId + "/comments/" + comment, null, null).status());
        assertEquals(404, send("DELETE", "/posts/" + otherPost + "/comments/" + comment, fan, null).status(), "only under its own post");
        assertEquals(1, count("select count(*) from post_comments where id = ?", comment));

        assertEquals(204, send("DELETE", "/posts/" + postId + "/comments/" + comment, fan, null).status());
        assertEquals(0, count("select count(*) from post_comments where id = ?", comment));
        assertEquals(404, send("DELETE", "/posts/" + postId + "/comments/" + comment, fan, null).status());
        assertEquals(0, json(get("/posts/" + postId + "/comments", owner), 200).size());
        assertEquals(0, json(get("/posts/" + postId, owner), 200).path("commentCount").asLong());
    }

    @Test
    void onlyTheTokenDecidesWhoLikesAndComments() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Real Fan");
        Account victim = register("Victim");
        long postId = createPost(owner, "Who is who", true);

        post("/posts/" + postId + "/like", fan, Map.of("userId", victim.id()));
        JsonNode comment = json(post("/posts/" + postId + "/comments", fan,
                Map.of("text", "From me", "userId", victim.id(), "authorId", victim.id(), "authorName", "Victim")), 200);

        assertEquals(fan.id(), comment.path("authorId").asLong());
        assertEquals("Real Fan", comment.path("authorName").asString());
        assertEquals(List.of(fan.id()), jdbc.queryForList("select user_id from post_likes where post_id = ?", Long.class, postId));
        assertEquals(List.of(fan.id()), jdbc.queryForList("select user_id from post_comments where post_id = ?", Long.class, postId));
        assertFalse(json(get("/posts/" + postId, victim), 200).path("likedByMe").asBoolean());
    }

    // ---- privacy ----

    @Test
    void aPrivatePostCanBeNeitherLikedNorCommentedNorReadByOthersEvenByItsId() throws Exception {
        Account owner = register("Post Owner");
        Account stranger = register("Stranger");
        long postId = createPost(owner, "Diary entry", false);
        long ownComment = json(post("/posts/" + postId + "/comments", owner, Map.of("text", "HIDDEN-COMMENT")), 200).path("id").asLong();
        json(post("/posts/" + postId + "/like", owner, null), 200);

        for (String[] call : List.of(
                new String[]{"POST", "/posts/%d/like"},
                new String[]{"DELETE", "/posts/%d/like"},
                new String[]{"GET", "/posts/%d/comments"},
                new String[]{"POST", "/posts/%d/comments"},
                new String[]{"DELETE", "/posts/%d/comments/" + ownComment})) {
            Map<String, ?> body = call[0].equals("POST") && call[1].endsWith("comments") ? Map.of("text", "intruder") : null;
            Reply hidden = send(call[0], call[1].formatted(postId), stranger, body);
            Reply missing = send(call[0], call[1].formatted(MISSING_POST), stranger, body);

            assertEquals(404, hidden.status(), call[1]);
            assertEquals(missing.status(), hidden.status(), call[1]);
            assertEquals(message(missing), message(hidden), call[1]);
            assertFalse(hidden.body().contains("HIDDEN-COMMENT"), call[1]);
            assertFalse(hidden.body().contains("likeCount"), call[1]);
        }

        assertEquals(List.of(owner.id()), jdbc.queryForList("select user_id from post_likes where post_id = ?", Long.class, postId));
        assertEquals(List.of(owner.id()), jdbc.queryForList("select user_id from post_comments where post_id = ?", Long.class, postId));
        assertEquals(404, raw("GET", "/posts/" + postId + "/image", stranger, null).status(), "its photo stays private too");
    }

    @Test
    void theOwnerMayLikeAndCommentTheirOwnPrivatePost() throws Exception {
        Account owner = register("Post Owner");
        long postId = createPost(owner, "Only me", false);

        assertEquals(1, json(post("/posts/" + postId + "/like", owner, null), 200).path("likeCount").asLong());
        assertEquals(200, post("/posts/" + postId + "/comments", owner, Map.of("text", "Note to self")).status());

        JsonNode mine = json(get("/posts/" + postId, owner), 200);
        assertEquals(1, mine.path("likeCount").asLong());
        assertTrue(mine.path("likedByMe").asBoolean());
        assertEquals(1, mine.path("commentCount").asLong());
    }

    @Test
    void aPostMadePrivateHidesItsLikesAndCommentsUntilItIsPublicAgain() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        long postId = createPost(owner, "Flip", true);
        post("/posts/" + postId + "/like", fan, null);
        post("/posts/" + postId + "/comments", fan, Map.of("text", "TRANSITION-COMMENT"));

        assertEquals(200, postForm("PUT", "/posts/" + postId, owner, Map.of("isPublic", "false")).status());

        assertEquals(404, get("/posts/" + postId + "/comments", fan).status());
        assertEquals(404, post("/posts/" + postId + "/comments", fan, Map.of("text", "again")).status());
        assertEquals(404, send("DELETE", "/posts/" + postId + "/like", fan, null).status());
        assertTrue(get("/posts/" + postId + "/comments", owner).body().contains("TRANSITION-COMMENT"), "the owner still sees them");
        assertEquals(1, json(get("/posts/" + postId, owner), 200).path("likeCount").asLong());

        assertEquals(200, postForm("PUT", "/posts/" + postId, owner, Map.of("isPublic", "true")).status());

        assertTrue(get("/posts/" + postId + "/comments", fan).body().contains("TRANSITION-COMMENT"));
        JsonNode back = json(get("/posts/" + postId, fan), 200);
        assertEquals(1, back.path("likeCount").asLong());
        assertTrue(back.path("likedByMe").asBoolean());
    }

    @Test
    void aBannedAuthorsPostAndCommentsAreHidden() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        Account troll = register("Troll");
        long postId = createPost(owner, "Moderated", true);
        post("/posts/" + postId + "/comments", troll, Map.of("text", "TROLL-COMMENT"));
        post("/posts/" + postId + "/comments", fan, Map.of("text", "Kind words"));

        jdbc.update("update users set banned = true where id = ?", troll.id());
        assertEquals(List.of("Kind words"), texts(json(get("/posts/" + postId + "/comments", owner), 200), "text"));
        assertEquals(1, json(get("/posts/" + postId, owner), 200).path("commentCount").asLong());

        jdbc.update("update users set banned = true where id = ?", owner.id());
        assertEquals(404, post("/posts/" + postId + "/like", fan, null).status());
        assertEquals(404, get("/posts/" + postId + "/comments", fan).status());
        assertEquals(404, post("/posts/" + postId + "/comments", fan, Map.of("text", "Hello?")).status());
    }

    @Test
    void deletingAPostOrAUserRemovesTheirLikesAndComments() throws Exception {
        Account owner = register("Post Owner");
        Account fan = register("Post Fan");
        long gone = createPost(owner, "Going away", true);
        long stays = createPost(owner, "Staying", true);
        post("/posts/" + gone + "/like", fan, null);
        post("/posts/" + gone + "/comments", fan, Map.of("text", "Bye"));
        post("/posts/" + stays + "/like", fan, null);
        post("/posts/" + stays + "/comments", fan, Map.of("text", "Hi"));

        assertEquals(204, send("DELETE", "/posts/" + gone, owner, null).status());
        assertEquals(0, count("select count(*) from post_likes where post_id = ?", gone));
        assertEquals(0, count("select count(*) from post_comments where post_id = ?", gone));

        jdbc.update("delete from users where id = ?", fan.id());
        assertEquals(0, count("select count(*) from post_likes where post_id = ?", stays));
        assertEquals(0, count("select count(*) from post_comments where post_id = ?", stays));
        assertEquals(1, count("select count(*) from posts where id = ?", stays), "the post itself stays");

        List<String> deleteRules = jdbc.queryForList("""
                select rc.delete_rule from information_schema.referential_constraints rc
                where rc.constraint_name in ('fk_post_likes_post', 'fk_post_likes_user', 'fk_post_comments_post', 'fk_post_comments_user')
                order by rc.constraint_name
                """, String.class);
        assertEquals(List.of("CASCADE", "CASCADE", "CASCADE", "CASCADE"), deleteRules);
    }

    // ---- real browser ----

    @BeforeAll
    void likeAndCommentInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account owner = register("Post Owner");
        jdbc.update("update users set username = ? where id = ?", "owner_" + suffix(), owner.id());
        Account fan = register("Comment Fan");
        Account leaving = register("Leaving Fan");
        Account guestLike = register("Unused");
        long postId = createPost(owner, "Sunrise walk", true);
        long routine = routine(owner, "Browser morning", true);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerId", owner.id());
        fixtures.put("ownerToken", owner.token());
        fixtures.put("fanToken", fan.token());
        fixtures.put("leavingToken", leaving.token());
        fixtures.put("unusedToken", guestLike.token());
        fixtures.put("postId", postId);
        fixtures.put("routineId", routine);
        fixtures.put("markup", MARKUP);

        Path dir = Files.createTempDirectory("like-comment-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/like-comment-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));

        // What the database holds once the browser is done, to compare with what the pages showed.
        stored.put("postLikes", "select count(*) from post_likes where post_id = " + postId);
        stored.put("postComments", "select count(*) from post_comments where post_id = " + postId);
        stored.put("routineLikes", "select count(*) from liked_routines where routine_id = " + routine);
        stored.put("routineComments", "select count(*) from routine_comments where routine_id = " + routine + " and deleted = false");
        stored.put("leavingLikes", "select count(*) from post_likes where user_id = " + leaving.id());
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    private int stored(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return count(stored.get(name));
    }

    @Test
    void browserLikesAPostAndTakesTheLikeBack() {
        assertEquals(0, page("postStart").path("likeCount").asInt(), browser.toString());
        assertFalse(page("postStart").path("pressed").asBoolean());

        JsonNode liked = page("postLiked");
        assertEquals(1, liked.path("likeCount").asInt(), liked.toString());
        assertTrue(liked.path("pressed").asBoolean());
        assertEquals("Liked", liked.path("label").asString());

        JsonNode reloaded = page("postReloaded");
        assertEquals(1, reloaded.path("likeCount").asInt(), "the like survives a refresh: " + reloaded);
        assertTrue(reloaded.path("pressed").asBoolean());

        JsonNode unliked = page("postUnliked");
        assertEquals(0, unliked.path("likeCount").asInt(), unliked.toString());
        assertFalse(unliked.path("pressed").asBoolean());
        assertEquals(0, stored("postLikes"), "the page and the database agree");
    }

    @Test
    void browserCommentsUnderAPostWithTheAuthorsName() {
        assertEquals("No comments yet. Be the first to comment.", page("commentsEmpty").path("empty").asString(), browser.toString());

        JsonNode refused = page("commentRefused");
        assertEquals("Write a comment first.", refused.path("error").asString(), refused.toString());
        assertEquals(0, refused.path("comments").size());

        JsonNode added = page("commentAdded");
        assertEquals(List.of("Nice walk!", MARKUP), texts(added.path("comments"), "text"), added.toString());
        assertEquals(List.of("Comment Fan", "Comment Fan"), texts(added.path("comments"), "author"));
        assertEquals("", added.path("draft").asString(), "the field is cleared after sending");
        assertEquals(0, added.path("injectedImages").asInt(), "markup is shown as text");
        assertFalse(page("xss").asBoolean());

        JsonNode reloaded = page("commentsReloaded");
        assertEquals(List.of("Nice walk!", MARKUP), texts(reloaded.path("comments"), "text"), "comments survive a refresh: " + reloaded);
        assertEquals(2, reloaded.path("count").asInt());
    }

    @Test
    void browserLetsOnlyTheAuthorDeleteAComment() {
        JsonNode asked = page("commentDeleteAsked");
        assertTrue(asked.path("confirmVisible").asBoolean(), asked.toString());
        assertEquals(2, asked.path("comments").size(), "nothing is deleted before confirming");

        JsonNode deleted = page("commentDeleted");
        assertEquals(List.of(MARKUP), texts(deleted.path("comments"), "text"), deleted.toString());
        assertEquals(1, deleted.path("count").asInt());
        assertEquals(1, stored("postComments"));

        JsonNode ownerView = page("ownerView");
        assertEquals(List.of(MARKUP), texts(ownerView.path("comments"), "text"), ownerView.toString());
        assertEquals(0, ownerView.path("deleteButtons").asInt(), "the post's owner cannot delete someone else's comment");
        assertEquals(1, ownerView.path("count").asInt());
    }

    @Test
    void browserAsksSomeoneWithoutASessionToSignIn() {
        assertTrue(page("visitorPosts").path("path").asString().endsWith("/login.html"), page("visitorPosts").toString());

        JsonNode ended = page("sessionEndedLike");
        assertTrue(ended.path("path").asString().endsWith("/login.html"), ended.toString());
        assertEquals("?session=expired", ended.path("search").asString());
        assertEquals(0, stored("leavingLikes"), "nothing was liked without a session");

        JsonNode routineGuest = page("routineGuest");
        assertEquals("Sign in to like this routine.Sign inCreate an account", routineGuest.path("notice").asString(), routineGuest.toString());
        assertEquals("0", routineGuest.path("likes").asString());
        assertTrue(routineGuest.path("commentFormHidden").asBoolean());
    }

    @Test
    void browserLikesAndCommentsOnARoutine() {
        JsonNode liked = page("routineLiked");
        assertEquals("1", liked.path("likes").asString(), liked.toString());
        assertTrue(liked.path("active").asBoolean());

        JsonNode reloaded = page("routineReloaded");
        assertEquals("1", reloaded.path("likes").asString(), "the routine like survives a refresh: " + reloaded);
        assertTrue(reloaded.path("active").asBoolean());
        assertEquals(List.of("Routine question?"), texts(reloaded.path("comments"), "text"));
        assertEquals(List.of("Comment Fan"), texts(reloaded.path("comments"), "author"));
        assertEquals(1, stored("routineComments"));

        JsonNode unliked = page("routineUnliked");
        assertEquals("0", unliked.path("likes").asString(), unliked.toString());
        assertFalse(unliked.path("active").asBoolean());
        assertEquals(0, stored("routineLikes"), "the page and the database agree");
    }

    @Test
    void browserShowsLikesAndCommentsInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("socialOriginalPhone", "socialAutumnPhone", "socialAutumnDesktop", "socialOriginalDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
            assertTrue(view.path("smallestTarget").asInt() >= 32, layout + " " + view);
        }
        assertEquals(390, page("socialOriginalPhone").path("width").asInt());
        assertEquals("autumn", page("socialAutumnPhone").path("theme").asString());
        assertTrue(page("dialogs").isEmpty(), page("dialogs").toString());
    }

    // ---- helpers ----

    private long likeCountOfRoutine(long routine, Account viewer) throws Exception {
        return json(get("/routines/" + routine, viewer), 200).path("likeCount").asLong();
    }

    private List<Reply> together(int times, java.util.function.Supplier<HttpRequest> call) throws Exception {
        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            calls.add(http.sendAsync(call.get(), HttpResponse.BodyHandlers.ofString()));
        }
        List<Reply> replies = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> each : calls) {
            HttpResponse<String> response = each.get(30, TimeUnit.SECONDS);
            replies.add(new Reply(response.statusCode(), response.body()));
        }
        return replies;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
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

    private List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asString()));
        return values;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Account register(String name) throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Like QA",
                "email", "like_" + suffix() + "@audit.test",
                "password", "Audit#12345"));
        JsonNode node = json(reply, 200);
        Account account = new Account(node.path("user").path("id").asLong(), node.path("token").asString());
        // Set directly: the display name is not what these tests are about, and markup is allowed here.
        jdbc.update("update users set name = ? where id = ?", name, account.id());
        return account;
    }

    private long routine(Account owner, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Like and comment check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));
        return json(postForm("POST", "/routines", owner, fields), 200).path("id").asLong();
    }

    private long createPost(Account owner, String text, boolean isPublic) throws Exception {
        return json(postForm("POST", "/posts", owner, Map.of("text", text, "isPublic", String.valueOf(isPublic))), 200)
                .path("id").asLong();
    }

    private Reply postForm(String method, String path, Account account, Map<String, String> fields) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
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

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpResponse<String> response = http.send(request(method, path, account, payload), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    // A JSON body exactly as given, or none at all.
    private Reply raw(String method, String path, Account account, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private HttpRequest request(String method, String path, Account account, Map<String, ?> payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        try {
            builder.method(method, payload == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(new HashMap<>(payload))));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
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
