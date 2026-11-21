package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.config.RoutineCopyConstraints;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-12 Creator Statistics. AC1: a creator with published routines opens their statistics and sees
 * how many people follow them and how often each public routine has been copied. AC2: with no
 * followers or no copies the numbers are 0, without errors. A copy is recorded with the routine it
 * was made from (Copy and Adapt &amp; copy), so only copies made from now on are counted; nothing is
 * guessed for older routines. Only the caller's own numbers can be read. Needs TEST_DB_URL (a local
 * *_test database); the browser part also needs node and Chrome.
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
class CreatorStatisticsRegressionTest {

    private static final String BOUNDARY = "CreatorStatsBoundary";
    private static final String HOSTILE = "<img src=x onerror=\"window.__xss=1\"> \"Quoted\" & <script>window.__xss=2</script>";
    private static final String STATS = "/users/me/creator-stats";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoutineCopyConstraints routineCopyConstraints;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token, String email) {
    }

    private record Reply(int status, String body) {
    }

    private Account admin;
    private JsonNode browser;

    @BeforeAll
    void prepare() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");
        admin = register("Stats admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        runBrowserCheck();
    }

    // ---- AC2: nothing yet is 0 ----

    @Test
    void aNewMemberSeesZerosNotNothing() throws Exception {
        JsonNode stats = stats(register("Brand new creator"));

        for (String field : List.of("followersCount", "totalCopies", "totalPublicRoutines")) {
            assertTrue(stats.path(field).isNumber(), field + " in " + stats);
            assertEquals(0, stats.path(field).asLong(), field + " in " + stats);
        }
        assertTrue(stats.path("routines").isArray(), stats.toString());
        assertEquals(0, stats.path("routines").size(), stats.toString());
    }

    @Test
    void aPublicRoutineThatNobodyCopiedShowsZero() throws Exception {
        Account creator = register("Uncopied creator");
        long routine = routine(creator, "Quiet morning", true);

        JsonNode stats = stats(creator);
        assertEquals(1, stats.path("totalPublicRoutines").asInt(), stats.toString());
        assertEquals(0, stats.path("totalCopies").asLong(), stats.toString());
        JsonNode row = row(stats, routine);
        assertEquals("Quiet morning", row.path("title").asString());
        for (String field : List.of("copiesCount", "likesCount", "savesCount", "ratingCount", "averageRating")) {
            assertTrue(row.path(field).isNumber(), field + " in " + row);
            assertEquals(0, row.path(field).asDouble(), field + " in " + row);
        }
    }

    // ---- AC1: followers and the copies of each routine ----

    @Test
    void aCreatorSeesFollowersAndTheCopiesOfEachPublicRoutine() throws Exception {
        Account creator = register("Popular creator");
        long morning = routine(creator, "Morning flow", true);
        habit(creator, morning, "Stretch", 10);
        habit(creator, morning, "Read", 20);
        long evening = routine(creator, "Evening wind-down", true);
        long secret = routine(creator, "Secret plan", false);

        Account first = register("First fan");
        Account second = register("Second fan");
        Account third = register("Third fan");
        follow(first, creator);
        follow(second, creator);
        copy(first, morning);
        adapt(second, morning);
        copy(third, evening);

        JsonNode stats = stats(creator);
        assertEquals(2, stats.path("followersCount").asLong(), stats.toString());
        assertEquals(3, stats.path("totalCopies").asLong(), stats.toString());
        assertEquals(2, stats.path("totalPublicRoutines").asInt(), stats.toString());
        assertEquals(2, row(stats, morning).path("copiesCount").asLong());
        assertEquals(1, row(stats, evening).path("copiesCount").asLong());
        assertEquals(morning, stats.path("routines").get(0).path("routineId").asLong(), "most copied first");
        assertFalse(listed(stats, secret), "a private routine is not listed");
    }

    @Test
    void aStandardCopyCountsForTheRoutineCopiedOnly() throws Exception {
        Account creator = register("Two routine creator");
        long first = routine(creator, "Routine A", true);
        long second = routine(creator, "Routine B", true);
        Account copier = register("Standard copier");

        long copy = copy(copier, first);

        JsonNode stats = stats(creator);
        assertEquals(1, row(stats, first).path("copiesCount").asLong());
        assertEquals(0, row(stats, second).path("copiesCount").asLong(), "copying A leaves B alone");
        assertEquals(first, sourceOf(copy));
    }

    @Test
    void anAdaptedCopyCountsToo() throws Exception {
        Account creator = register("Adapted creator");
        long routine = routine(creator, "Adaptable", true);
        habit(creator, routine, "Walk", 15);
        habit(creator, routine, "Water", 5);

        long copy = adapt(register("Adapting copier"), routine);

        assertEquals(1, row(stats(creator), routine).path("copiesCount").asLong());
        assertEquals(routine, sourceOf(copy));
    }

    @Test
    void everyCopyByEveryMemberCounts() throws Exception {
        Account creator = register("Shared creator");
        long routine = routine(creator, "Shared routine", true);
        Account one = register("Copier one");
        Account two = register("Copier two");

        copy(one, routine);
        copy(two, routine);
        assertEquals(2, row(stats(creator), routine).path("copiesCount").asLong(), "two members");

        // A member who copies twice has two copies; each was made.
        copy(one, routine);
        assertEquals(3, row(stats(creator), routine).path("copiesCount").asLong());
    }

    @Test
    void copyingDoesNotMakeAFollower() throws Exception {
        Account creator = register("Unfollowed creator");
        long routine = routine(creator, "Copy me", true);

        copy(register("Copier who does not follow"), routine);

        JsonNode stats = stats(creator);
        assertEquals(0, stats.path("followersCount").asLong(), stats.toString());
        assertEquals(1, stats.path("totalCopies").asLong(), stats.toString());
    }

    @Test
    void likingSavingRatingViewingCommentingAndCopyingOneActivityAreNotCopies() throws Exception {
        Account creator = register("Engaged creator");
        long routine = routine(creator, "Much engagement", true);
        habit(creator, routine, "Plank", 5);
        Account fan = register("Engaged fan");
        long fanRoutine = routine(fan, "Fan's own", false);

        assertEquals(200, send("GET", "/routines/" + routine, fan, null).status());
        assertEquals(200, send("POST", "/liked-routines/" + routine, fan, null).status());
        assertEquals(200, send("POST", "/saved-routines/" + routine, fan, null).status());
        assertEquals(200, send("POST", "/routine-ratings/" + routine, fan, Map.of("value", 4)).status());
        assertEquals(200, send("POST", "/routine-comments/" + routine, fan, Map.of("text", "Nice")).status());
        long step = links(routine).get(0);
        Reply oneActivity = send("POST", "/routines/" + routine + "/routine-habits/" + step + "/copy", fan,
                Map.of("targetRoutineId", fanRoutine));
        assertEquals(200, oneActivity.status(), oneActivity.body());

        JsonNode row = row(stats(creator), routine);
        assertEquals(0, row.path("copiesCount").asLong(), row.toString());
        assertEquals(1, row.path("likesCount").asLong(), row.toString());
        assertEquals(1, row.path("savesCount").asLong(), row.toString());
        assertEquals(1, row.path("ratingCount").asLong(), row.toString());
        assertEquals(4.0, row.path("averageRating").asDouble(), row.toString());
        assertEquals(0L, jdbc.queryForObject("select count(*) from routines where copied_from_id = ?", Long.class, routine));
    }

    @Test
    void aRefusedCopyLeavesNoTrace() throws Exception {
        Account creator = register("Refusing creator");
        long routine = routine(creator, "Strict routine", true);
        habit(creator, routine, "Run", 20);
        long hidden = routine(creator, "Not for others", false);
        Account copier = register("Refused copier");
        long[] before = belongings(copier);

        List<Map<String, Object>> activities = plan(copier, routine);
        activities.get(0).put("durationMinutes", 0);
        assertEquals(400, send("POST", "/routines/" + routine + "/adapted-copy", copier, Map.of("activities", activities)).status());
        assertEquals(400, send("POST", "/routines/" + routine + "/adapted-copy", copier, Map.of("activities", List.of())).status());
        assertEquals(404, send("POST", "/routines/" + hidden + "/copy", copier, null).status());
        assertEquals(404, send("POST", "/routines/987654321/copy", copier, null).status());
        assertNotEquals(200, send("POST", "/routines/" + routine + "/copy", null, null).status());

        assertEquals(0, row(stats(creator), routine).path("copiesCount").asLong());
        assertEquals(0L, jdbc.queryForObject("select count(*) from routines where copied_from_id in (?, ?)", Long.class, routine, hidden));
        assertEquals(List.of(before[0], before[1], before[2]), List.of(belongings(copier)[0], belongings(copier)[1], belongings(copier)[2]),
                "nothing saved for the copier");
    }

    // ---- what the creator's numbers include ----

    @Test
    void privateRoutinesAndTheCreatorsOwnCopiesStayOut() throws Exception {
        Account creator = register("Private creator");
        long open = routine(creator, "Open routine", true);
        long closed = routine(creator, "Closed routine " + UUID.randomUUID(), false);

        long ownCopy = copy(creator, open);
        copy(creator, closed);

        JsonNode stats = stats(creator);
        assertEquals(1, stats.path("totalPublicRoutines").asInt(), stats.toString());
        assertEquals(0, row(stats, open).path("copiesCount").asLong(), "the creator's own copy does not count");
        assertFalse(listed(stats, closed));
        assertFalse(stats.toString().contains("Closed routine"), "nothing about the private routine");
        assertEquals(open, sourceOf(ownCopy), "the copy still knows its source");

        // Another member's numbers never show this creator's routines.
        JsonNode others = stats(register("Unrelated member"));
        assertFalse(others.toString().contains("Open routine"), others.toString());
    }

    @Test
    void hiddenAndDeletedRoutinesAreLeftOutAndDeletedCopiesStillCount() throws Exception {
        Account creator = register("Moderated creator");
        long hidden = routine(creator, "Hidden by admin", true);
        long deleted = routine(creator, "Deleted by owner", true);
        long kept = routine(creator, "Kept routine", true);
        Account copier = register("Moderation copier");
        copy(copier, hidden);
        long copyOfKept = copy(copier, kept);

        assertEquals(200, send("PUT", "/admin-api/routines/" + hidden + "/hide", admin, null).status());
        assertEquals(204, send("DELETE", "/routines/" + deleted, creator, null).status());
        assertEquals(204, send("DELETE", "/routines/" + copyOfKept, copier, null).status());

        JsonNode stats = stats(creator);
        assertFalse(listed(stats, hidden), "hidden by an administrator: not published");
        assertFalse(listed(stats, deleted), "deleted");
        assertEquals(1, stats.path("totalPublicRoutines").asInt(), stats.toString());
        assertEquals(1, row(stats, kept).path("copiesCount").asLong(), "a copy that was made counts even after its owner deleted it");

        assertEquals(200, send("PUT", "/admin-api/routines/" + hidden + "/unhide", admin, null).status());
        assertEquals(1, row(stats(creator), hidden).path("copiesCount").asLong(), "shown again, with its copy");
    }

    @Test
    void bannedFollowersAndBannedCopiersAreLeftOutAsTheProfileDoes() throws Exception {
        Account creator = register("Ban creator");
        long routine = routine(creator, "Ban routine", true);
        Account good = register("Good follower");
        Account bad = register("Banned follower");
        follow(good, creator);
        follow(bad, creator);
        copy(good, routine);
        copy(bad, routine);

        jdbc.update("update users set banned = true where id = ?", bad.id());
        JsonNode stats = stats(creator);
        assertEquals(1, stats.path("followersCount").asLong(), stats.toString());
        assertEquals(1, row(stats, routine).path("copiesCount").asLong(), stats.toString());
        assertEquals(stats.path("followersCount").asLong(),
                json.readTree(send("GET", "/users/me", creator, null).body()).path("followersCount").asLong(),
                "the same follower count as the profile");

        jdbc.update("update users set banned = false where id = ?", bad.id());
        stats = stats(creator);
        assertEquals(2, stats.path("followersCount").asLong());
        assertEquals(2, row(stats, routine).path("copiesCount").asLong());
    }

    @Test
    void aCopyOfACopyCountsForTheRoutineThatWasCopied() throws Exception {
        Account creator = register("Original creator");
        long original = routine(creator, "Original", true);
        Account middle = register("Middle copier");
        long middleCopy = copy(middle, original);
        jdbc.update("update routines set is_public = true where id = ?", middleCopy);
        Account last = register("Last copier");

        long lastCopy = copy(last, middleCopy);

        assertEquals(middleCopy, sourceOf(lastCopy));
        assertEquals(1, row(stats(creator), original).path("copiesCount").asLong(), "not counted twice for the original");
        assertEquals(1, row(stats(middle), middleCopy).path("copiesCount").asLong(), "counted for the routine chosen");
    }

    @Test
    void manyLikesSavesRatingsAndCopiesAreCountedExactly() throws Exception {
        Account creator = register("Busy creator");
        long routine = routine(creator, "Busy routine", true);
        long other = routine(creator, "Quiet routine", true);
        List<Account> fans = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fans.add(register("Busy fan " + i));
        }
        for (int i = 0; i < 4; i++) {
            send("POST", "/saved-routines/" + routine, fans.get(i), null);
        }
        for (int i = 0; i < 2; i++) {
            send("POST", "/liked-routines/" + routine, fans.get(i), null);
            send("POST", "/routine-ratings/" + routine, fans.get(i), Map.of("value", 3 + i));
        }
        for (int i = 0; i < 3; i++) {
            copy(fans.get(i), routine);
        }

        JsonNode stats = stats(creator);
        JsonNode row = row(stats, routine);
        assertEquals(3, row.path("copiesCount").asLong(), row.toString());
        assertEquals(2, row.path("likesCount").asLong(), row.toString());
        assertEquals(4, row.path("savesCount").asLong(), row.toString());
        assertEquals(2, row.path("ratingCount").asLong(), row.toString());
        assertEquals(0, row(stats, other).path("copiesCount").asLong());
        assertEquals(3, stats.path("totalCopies").asLong(), "the sum of the rows, no join counted twice");
        assertEquals(2, stats.path("routines").size());
    }

    @Test
    void copiesMadeAtTheSameMomentAreAllCounted() throws Exception {
        Account creator = register("Rush creator");
        long routine = routine(creator, "Rush routine", true);
        List<Account> copiers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            copiers.add(register("Rush copier " + i));
        }

        List<Integer> statuses = parallel(8, index -> send("POST", "/routines/" + routine + "/copy", copiers.get(index), null).status());
        assertEquals(List.of(200, 200, 200, 200, 200, 200, 200, 200), statuses);
        List<Integer> again = parallel(3, index -> send("POST", "/routines/" + routine + "/copy", copiers.get(0), null).status());
        assertEquals(List.of(200, 200, 200), again);

        assertEquals(11L, jdbc.queryForObject("select count(*) from routines where copied_from_id = ?", Long.class, routine));
        assertEquals(11, row(stats(creator), routine).path("copiesCount").asLong());
    }

    // ---- the copy itself ----

    @Test
    void copyingLeavesTheSourceExactlyAsItWas() throws Exception {
        Account creator = register("Source creator");
        long routine = routine(creator, "Untouched source", true);
        habit(creator, routine, "Breathe", 5);
        habit(creator, routine, "Sit", 10);
        String before = snapshot(routine);

        copy(register("Source copier one"), routine);
        adapt(register("Source copier two"), routine);

        assertEquals(before, snapshot(routine));
        assertEquals(null, jdbc.queryForObject("select copied_from_id from routines where id = ?", Long.class, routine));
    }

    @Test
    void theCopyStillWorksAsBefore() throws Exception {
        Account creator = register("Compatible creator");
        long routine = routine(creator, "Compatible routine", true);
        habit(creator, routine, "Stretch", 10);
        Account copier = register("Compatible copier");

        Reply reply = send("POST", "/routines/" + routine + "/copy", copier, null);
        assertEquals(200, reply.status(), reply.body());
        JsonNode copy = json.readTree(reply.body());
        long copyId = copy.path("id").asLong();
        assertNotEquals(routine, copyId);
        assertEquals("Compatible routine", copy.path("title").asString());
        assertFalse(copy.path("public").asBoolean(), "a copy starts private");
        assertTrue(copy.path("ownedByMe").asBoolean());
        assertEquals(1, links(copyId).size(), "its activity was cloned");

        // The answer has the same fields as reading the routine; nothing about the source is added.
        JsonNode read = json.readTree(send("GET", "/routines/" + copyId, copier, null).body());
        assertEquals(fieldNames(read), fieldNames(copy));
        assertFalse(reply.body().contains("copiedFrom"), reply.body());

        // Editing the copy keeps where it came from.
        Map<String, String> edit = new LinkedHashMap<>();
        edit.put("title", "My version");
        edit.put("description", "Changed");
        edit.put("category", "Morning");
        edit.put("durationMinutes", "45");
        edit.put("isPublic", "true");
        Reply edited = multipart("PUT", "/routines/" + copyId, copier, edit);
        assertEquals(200, edited.status(), edited.body());
        assertEquals(routine, sourceOf(copyId));
    }

    // ---- who may read ----

    @Test
    void visitorsAreRefused() throws Exception {
        assertTrue(List.of(401, 403).contains(send("GET", STATS, null, null).status()));
        Account forged = new Account(0, "not-a-token", "nobody@audit.test");
        assertTrue(List.of(401, 403).contains(send("GET", STATS, forged, null).status()));
    }

    @Test
    void nobodyCanReadAnotherCreatorsNumbers() throws Exception {
        Account creator = register("Owner of numbers");
        String title = "Private numbers " + UUID.randomUUID();
        long routine = routine(creator, title, true);
        routine(creator, "Draft " + title, false);
        copy(register("Copier of owner"), routine);
        Account other = register("Curious member");

        Reply byId = send("GET", "/users/" + creator.id() + "/creator-stats", other, null);
        assertNotEquals(200, byId.status(), byId.body());
        assertFalse(byId.body().contains(title), byId.body());

        JsonNode asked = json.readTree(send("GET", STATS + "?userId=" + creator.id(), other, null).body());
        assertEquals(0, asked.path("routines").size(), "always the caller's own numbers: " + asked);
        assertFalse(asked.toString().contains(title));

        // The public profile shows no copy numbers.
        Reply profile = send("GET", "/users/" + creator.id() + "/profile", other, null);
        assertFalse(profile.body().contains("copiesCount") || profile.body().contains("totalCopies"), profile.body());
    }

    // ---- the database ----

    @Test
    void theDatabaseKeepsTheSourceLinkSafe() throws Exception {
        Account creator = register("Database creator");
        long routine = routine(creator, "Database routine", true);
        long copy = copy(register("Database copier"), routine);

        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update routines set copied_from_id = id where id = ?", copy), "never its own source");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("update routines set copied_from_id = 987654321 where id = ?", copy), "only a real routine");

        assertEquals("n", jdbc.queryForObject("""
                select confdeltype::text from pg_constraint
                where conrelid = 'routines'::regclass and conname = 'fk_routines_copied_from'
                """, String.class), "ON DELETE SET NULL, never CASCADE");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("""
                select convalidated from pg_constraint
                where conrelid = 'routines'::regclass and conname = 'chk_routines_copied_from_not_self'
                """, Boolean.class));
        assertEquals(1, jdbc.queryForList("select 1 from pg_indexes where indexname = 'idx_routines_copied_from'").size());
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = 'routines' and column_name = 'copied_from_id'
                """, String.class));

        // Were a source row ever removed for real, its copies would only lose the link.
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbc.update("delete from routines where id = ?", routine);
            assertEquals(null, jdbc.queryForObject("select copied_from_id from routines where id = ?", Long.class, copy));
            assertEquals(1L, jdbc.queryForObject("select count(*) from routines where id = ?", Long.class, copy));
            transaction.setRollbackOnly();
        });
        assertEquals(routine, sourceOf(copy));
    }

    // The production table predates US-12: no column, and copies made before it never recorded a
    // source. Running the manual script (twice) and the start-up check adds everything, keeps every
    // row and guesses nothing, not even for a routine with the same title as another. All in one
    // rolled-back transaction, so the shared test table ends as it was.
    @Test
    void startingOnAnOlderTableAddsTheLinkAndInventsNoHistory() throws Exception {
        Account creator = register("Legacy creator");
        long original = routine(creator, "Legacy original", true);
        Account copier = register("Legacy copier");
        String script = Files.readString(Path.of("db/manual/routine_copied_from.sql")).lines()
                .filter(line -> !line.startsWith("--") && !line.equals("BEGIN;") && !line.equals("COMMIT;"))
                .collect(Collectors.joining("\n"));

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbc.execute("alter table routines drop constraint chk_routines_copied_from_not_self");
            jdbc.execute("alter table routines drop column copied_from_id");
            // A copy made before US-12: same title and content, no record of where it came from.
            long legacyCopy = jdbc.queryForObject("""
                    insert into routines (title, description, category, duration_minutes, is_public, user_id,
                                          average_rating, rating_count, deleted, completed, built_in)
                    values ('Legacy original', 'Copied long ago', 'Morning', 30, false, ?, 0, 0, false, false, false)
                    returning id
                    """, Long.class, copier.id());
            String rowsBefore = String.valueOf(jdbc.queryForList("select * from routines order by id"));

            jdbc.execute(script);
            jdbc.execute(script);
            routineCopyConstraints.afterSingletonsInstantiated();
            routineCopyConstraints.afterSingletonsInstantiated();

            List<String> columns = jdbc.queryForList("""
                    select column_name from information_schema.columns
                    where table_schema = 'public' and table_name = 'routines' and column_name <> 'copied_from_id'
                    order by ordinal_position
                    """, String.class);
            assertEquals(rowsBefore, String.valueOf(jdbc.queryForList(
                    "select " + String.join(", ", columns) + " from routines order by id")), "no row changed");
            assertEquals(0L, jdbc.queryForObject("select count(*) from routines where copied_from_id is not null", Long.class),
                    "no source guessed for any older routine");
            assertEquals(null, jdbc.queryForObject("select copied_from_id from routines where id = ?", Long.class, legacyCopy));
            assertEquals(2L, jdbc.queryForObject("""
                    select count(*) from pg_constraint where conrelid = 'routines'::regclass and convalidated
                      and conname in ('fk_routines_copied_from', 'chk_routines_copied_from_not_self')
                    """, Long.class));
            assertEquals(1, jdbc.queryForList("select 1 from pg_indexes where indexname = 'idx_routines_copied_from'").size());
            transaction.setRollbackOnly();
        });

        assertEquals(0, row(stats(creator), original).path("copiesCount").asLong());
        assertEquals("YES", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = 'routines' and column_name = 'copied_from_id'
                """, String.class), "the rollback put the column back");
    }

    // ---- browser ----

    @Test
    void browserShowsTheSectionWithTheRightNumbers() {
        JsonNode shown = page("creator");
        assertTrue(shown.path("navVisible").asBoolean(), browser.toString());
        assertEquals("#creator-stats", shown.path("hash").asString(), shown.toString());
        assertTrue(shown.path("sectionShown").asBoolean(), shown.toString());
        assertEquals(page("expected").path("overview"), shown.path("overview"), shown.toString());
        assertEquals(page("expected").path("copies"), shown.path("copies"), shown.toString());
        assertEquals(page("expected").path("order"), shown.path("order"), shown.toString());
        assertFalse(shown.path("privateListed").asBoolean(), shown.toString());
        assertTrue(shown.path("openedFromAddress").asBoolean(), shown.toString());
    }

    @Test
    void browserCountsCopiesMadeOnTheRoutinePage() {
        JsonNode copied = page("copied");
        assertTrue(copied.path("standardNotice").asString().contains("Routine copied"), copied.toString());
        assertTrue(copied.path("adaptedNotice").asString().contains("adapted copy is saved"), copied.toString());
        assertEquals(page("expected").path("copiesAfter"), copied.path("copiesAfter"), copied.toString());
        assertEquals(page("expected").path("totalAfter").asLong(), copied.path("totalAfter").asLong(), copied.toString());
    }

    @Test
    void browserShowsEmptyLoadingAndErrorStates() {
        JsonNode empty = page("empty");
        assertEquals(List.of("0", "0", "0"), texts(empty.path("overview")), empty.toString());
        assertTrue(empty.path("emptyRoutines").asBoolean(), empty.toString());
        assertTrue(empty.path("hints").size() >= 3, empty.toString());
        assertFalse(empty.path("errorShown").asBoolean(), empty.toString());

        JsonNode states = page("states");
        assertTrue(states.path("loadingText").asString().contains("Loading"), states.toString());
        assertTrue(states.path("errorText").asString().contains("could not be loaded"), states.toString());
        assertTrue(states.path("retried").asBoolean(), states.toString());
        assertTrue(states.path("noCopiesNote").asBoolean(), states.toString());
    }

    @Test
    void browserKeepsHostileTitlesAsText() {
        JsonNode shown = page("creator");
        assertTrue(shown.path("hostileAsText").asBoolean(), shown.toString());
        assertFalse(shown.path("xss").asBoolean(), shown.toString());
        assertEquals(0, shown.path("injectedElements").asInt(), shown.toString());
    }

    @Test
    void browserKeepsTheOtherProfileSectionsWorking() {
        JsonNode sections = page("sections");
        assertTrue(sections.size() >= 10, sections.toString());
        sections.properties().forEach(entry -> {
            assertTrue(entry.getValue().path("shown").asBoolean(), entry.getKey() + " " + entry.getValue());
            assertEquals(1, entry.getValue().path("visibleSections").asInt(), entry.getKey() + " " + entry.getValue());
        });
        // The new entry follows Friends; AI Assistant stays between Progress and Friends.
        List<String> order = texts(page("navOrder"));
        assertEquals(order.indexOf("progressNav") + 1, order.indexOf("aiAssistantNav"), order.toString());
        assertEquals(order.indexOf("aiAssistantNav") + 1, order.indexOf("friendsNav"), order.toString());
        assertEquals(order.indexOf("friendsNav") + 1, order.indexOf("creatorStatsNav"), order.toString());

        JsonNode back = page("back");
        assertTrue(back.path("statsHiddenAfterBack").asBoolean(), back.toString());
        assertTrue(back.path("statsShownAfterForward").asBoolean(), back.toString());
    }

    @Test
    void browserKeepsTheLayoutInBothDesignsAndEveryWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(14, layouts.size(), layouts.toString());
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            assertTrue(view.path("overflow").asInt() <= 0, entry.getKey() + " scrolls sideways: " + view);
            assertTrue(view.path("statsInside").asBoolean(), entry.getKey() + " " + view);
            assertTrue(view.path("navInside").asBoolean(), entry.getKey() + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, entry.getKey() + " contrast " + view);
            assertTrue(view.path("themeApplied").asBoolean(), entry.getKey() + " " + view);
        });
    }

    @Test
    void browserNeverFailsOrInterrupts() {
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    // ---- helpers ----

    private void runBrowserCheck() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account creator = register("Browser creator");
        long first = routine(creator, "Browser favourite", true);
        habit(creator, first, "Browser step", 10);
        long second = routine(creator, HOSTILE, true);
        long third = routine(creator, "Browser quiet routine", true);
        long privateRoutine = routine(creator, "Browser private routine", false);
        List<Account> fans = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fans.add(register("Browser fan " + i));
            follow(fans.get(i), creator);
        }
        copy(fans.get(0), first);
        copy(fans.get(1), first);
        copy(fans.get(2), second);
        Account copier = register("Browser copier");
        Account empty = register("Browser empty member");
        Account quiet = register("Browser uncopied creator");
        routine(quiet, "Nobody copied this", true);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("creatorToken", creator.token());
        fixtures.put("copierToken", copier.token());
        fixtures.put("emptyToken", empty.token());
        fixtures.put("quietToken", quiet.token());
        fixtures.put("first", first);
        fixtures.put("second", second);
        fixtures.put("third", third);
        fixtures.put("privateRoutine", privateRoutine);
        fixtures.put("hostile", HOSTILE);

        Path dir = Files.createTempDirectory("creator-stats-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/creator-stats-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(8, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));

        JsonNode results = json.readTree(Files.readString(dir.resolve("results.json")));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("overview", List.of("3", "3", "3"));
        expected.put("copies", Map.of(String.valueOf(first), 2, String.valueOf(second), 1, String.valueOf(third), 0));
        expected.put("order", List.of(first, second, third));
        expected.put("copiesAfter", Map.of(String.valueOf(first), 4, String.valueOf(second), 1, String.valueOf(third), 0));
        expected.put("totalAfter", 5);
        // Read back from text, so numbers compare as the browser's results do.
        ((tools.jackson.databind.node.ObjectNode) results).set("expected", json.readTree(json.writeValueAsString(expected)));
        browser = results;
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    private static List<String> texts(JsonNode list) {
        List<String> texts = new ArrayList<>();
        list.forEach(item -> texts.add(item.asString()));
        return texts;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        names.sort(String::compareTo);
        return names;
    }

    private JsonNode stats(Account who) throws Exception {
        Reply reply = send("GET", STATS, who, null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private static JsonNode row(JsonNode stats, long routine) {
        for (JsonNode row : stats.path("routines")) {
            if (row.path("routineId").asLong() == routine) {
                return row;
            }
        }
        throw new AssertionError("routine " + routine + " not listed: " + stats);
    }

    private static boolean listed(JsonNode stats, long routine) {
        for (JsonNode row : stats.path("routines")) {
            if (row.path("routineId").asLong() == routine) {
                return true;
            }
        }
        return false;
    }

    private Long sourceOf(long routine) {
        return jdbc.queryForObject("select copied_from_id from routines where id = ?", Long.class, routine);
    }

    private long copy(Account who, long routine) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/copy", who, null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long adapt(Account who, long routine) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/adapted-copy", who, Map.of("activities", plan(who, routine)));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    // Every activity of a routine as the adapt form starts it: the source's own plan.
    private List<Map<String, Object>> plan(Account who, long routine) throws Exception {
        Reply reply = send("GET", "/routines/" + routine + "/routine-habits", who, null);
        assertEquals(200, reply.status(), reply.body());
        List<Map<String, Object>> activities = new ArrayList<>();
        for (JsonNode step : json.readTree(reply.body())) {
            Map<String, Object> activity = new HashMap<>();
            activity.put("routineHabitId", step.path("routineHabitId").asLong());
            activity.put("durationMinutes", step.path("durationMinutes").asInt());
            activity.put("startTime", step.path("startTime").isNull() || step.path("startTime").isMissingNode()
                    ? null : step.path("startTime").asString());
            List<String> days = new ArrayList<>();
            step.path("repeatDays").forEach(day -> days.add(day.asString()));
            activity.put("repeatDays", days);
            activity.put("notes", null);
            activities.add(activity);
        }
        return activities;
    }

    private void follow(Account follower, Account creator) throws Exception {
        Reply reply = send("POST", "/users/" + creator.id() + "/follow", follower, null);
        assertEquals(200, reply.status(), reply.body());
    }

    private void habit(Account owner, long routine, String title, int minutes) throws Exception {
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner,
                Map.of("title", title, "description", title + " description", "durationMinutes", minutes));
        assertEquals(200, reply.status(), reply.body());
    }

    private List<Long> links(long routine) {
        return jdbc.queryForList("select id from routine_habits where routine_id = ? and removed_at is null order by position",
                Long.class, routine);
    }

    // [routines, habits, routine links] of a member.
    private long[] belongings(Account account) {
        return new long[]{
                jdbc.queryForObject("select count(*) from routines where user_id = ?", Long.class, account.id()),
                jdbc.queryForObject("select count(*) from habits where user_id = ?", Long.class, account.id()),
                jdbc.queryForObject("select count(*) from routine_habits rh join routines r on r.id = rh.routine_id where r.user_id = ?",
                        Long.class, account.id())
        };
    }

    // Everything stored for a routine and its activities, to compare before and after.
    private String snapshot(long routine) {
        return jdbc.queryForList("""
                select title, description, category, duration_minutes, is_public, deleted, hidden_at, user_id,
                       average_rating, rating_count, image_url, copied_from_id
                from routines where id = ?
                """, routine) + " " + jdbc.queryForList("""
                select rh.id, rh.habit_id, rh.position, rh.start_time, rh.repeat_days, rh.notes, rh.paused_at, rh.removed_at,
                       h.title, h.description, h.duration_minutes, h.user_id, h.routine_id
                from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? order by rh.id
                """, routine);
    }

    @FunctionalInterface
    private interface Call {
        int run(int index) throws Exception;
    }

    // Starts every call at the same moment and answers their statuses in order.
    private List<Integer> parallel(int count, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.run(index);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Account register(String name) throws Exception {
        String email = "stats12_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = send("POST", "/auth/register", null, Map.of("name", name, "email", email, "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString(), email);
    }

    private long routine(Account who, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Creator statistics check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));
        Reply reply = multipart("POST", "/routines", who, fields);
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

    private Reply send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
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
