package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.config.ReportConstraints;
import com.copymylife.copymylife.service.EmailService;
import com.copymylife.copymylife.service.ReportService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-24 Report Content. AC1: a member who reports a user, a routine, a post, or a comment under a
 * routine or a post, with a reason, gets a confirmation and the report reaches the administrators.
 * AC2: reporting the same content again while the first report is still open answers "You have
 * already reported this content." Requests at the same moment still leave one active report, which
 * the database itself enforces. Private, hidden and deleted content answers like missing content,
 * and nothing changes until an administrator decides. Needs TEST_DB_URL (a local *_test database);
 * the browser part also needs node and Chrome.
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
class ReportContentRegressionTest {

    private static final String BOUNDARY = "ReportContentBoundary";
    private static final String HOSTILE = "<img src=x onerror=\"window.__xss=1\"> \"quoted\" & <script>window.__xss=2</script>";
    private static final String SUBMITTED = "Your report has been submitted.";
    private static final String ALREADY = "You have already reported this content.";
    private static final String INVALID_REASON = "Please select a valid report reason.";
    private static final List<String> TYPES = List.of("USER", "COMMENT", "ROUTINE", "POST", "POST_COMMENT");

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReportConstraints reportConstraints;

    @Autowired
    private ReportService reportService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token, String email) {
    }

    private record Reply(int status, String body) {
    }

    // One piece of content of each type, by one author.
    private record Content(Account author, long user, long comment, long routine, long post, long postComment) {

        long of(String type) {
            return switch (type) {
                case "USER" -> user;
                case "COMMENT" -> comment;
                case "ROUTINE" -> routine;
                case "POST" -> post;
                default -> postComment;
            };
        }
    }

    private Account admin;
    private JsonNode browser;

    // ---- AC1: every kind of content can be reported ----

    @Test
    void aMemberReportsEachKindOfContentAndIsToldItWasSubmitted() throws Exception {
        Content content = content("AC1 author");
        Account reporter = register("AC1 reporter");

        for (String type : TYPES) {
            Reply reply = report(type, content.of(type), reporter, "harassment", "Details for " + type);
            assertEquals(200, reply.status(), type + " " + reply.body());

            JsonNode body = json.readTree(reply.body());
            assertEquals(SUBMITTED, body.path("message").asString(), type);
            assertEquals(type, body.path("type").asString());
            assertEquals("HARASSMENT", body.path("reason").asString(), "the reason is stored as its code");
            assertEquals("Details for " + type, body.path("details").asString());
            assertEquals("OPEN", body.path("status").asString());
            assertEquals(content.of(type), body.path(targetField(type)).asLong(), type);
            assertEquals(content.user(), body.path("reportedUserId").asLong(), "the person behind the content");
            TYPES.stream().filter(other -> !other.equals(type) && !other.equals("USER"))
                    .forEach(other -> assertTrue(body.path(targetField(other)).isNull(), type + " names only its own target"));

            Map<String, Object> row = jdbc.queryForMap("select * from reports where id = ?", body.path("id").asLong());
            assertEquals(type, row.get("type"));
            assertEquals(reporter.id(), ((Number) row.get("reporter_id")).longValue());
            assertEquals("OPEN", row.get("status"));
            assertEquals("NONE", row.get("action_taken"));
            assertTrue(row.get("created_at") != null);
        }

        JsonNode admins = json.readTree(send("GET", "/admin-api/reports", admin, null).body());
        for (String type : TYPES) {
            JsonNode seen = only(admins, reporter.id(), type);
            assertEquals("HARASSMENT", seen.path("reason").asString());
            assertEquals("Details for " + type, seen.path("details").asString());
            assertEquals("OPEN", seen.path("status").asString());
            assertTrue(seen.path("targetAvailable").asBoolean(), seen.toString());
            assertEquals(content.user(), seen.path("reportedUserId").asLong());
            assertFalse(seen.path("createdAt").isNull());
        }
        assertEquals("AC1 routine", only(admins, reporter.id(), "ROUTINE").path("routineTitle").asString());
        assertEquals("AC1 post", only(admins, reporter.id(), "POST").path("postText").asString());
        assertEquals("AC1 post comment", only(admins, reporter.id(), "POST_COMMENT").path("postCommentText").asString());
        assertEquals(content.post(), only(admins, reporter.id(), "POST_COMMENT").path("contextPostId").asLong());
        assertEquals(content.routine(), only(admins, reporter.id(), "COMMENT").path("contextRoutineId").asLong());

        JsonNode mine = json.readTree(send("GET", "/reports/mine", reporter, null).body());
        assertEquals(5, mine.size(), mine.toString());
    }

    @Test
    void theEarlierAnswerShapeIsKeptForUserAndRoutineCommentReports() throws Exception {
        Content content = content("Shape author");
        Account reporter = register("Shape reporter");

        for (String type : List.of("USER", "COMMENT")) {
            JsonNode body = json.readTree(report(type, content.of(type), reporter, "SPAM", null).body());
            for (String field : List.of("id", "type", "reportedUserId", "reportedCommentId", "reason", "details", "status", "createdAt")) {
                assertTrue(body.has(field), type + " still answers " + field);
            }
        }
    }

    @Test
    void reportingChangesNothingUntilAnAdministratorDecides() throws Exception {
        Content content = content("Untouched author");
        Account reporter = register("Untouched reporter");
        String before = everything(content);

        for (String type : TYPES) {
            assertEquals(200, report(type, content.of(type), reporter, "INAPPROPRIATE_CONTENT", "please remove").status());
        }

        assertEquals(before, everything(content));
        assertEquals(200, send("GET", "/routines/" + content.routine(), null, null).status());
        assertEquals(200, send("GET", "/posts/" + content.post(), register("Reader"), null).status());
    }

    // ---- AC2: already reported ----

    @Test
    void reportingTheSameContentAgainSaysItIsAlreadyReported() throws Exception {
        Content content = content("AC2 author");
        Account reporter = register("AC2 reporter");

        for (String type : TYPES) {
            assertEquals(200, report(type, content.of(type), reporter, "SPAM", null).status());

            Reply again = report(type, content.of(type), reporter, "OTHER", "second time");
            assertEquals(409, again.status(), type + " " + again.body());
            JsonNode body = json.readTree(again.body());
            assertEquals(ALREADY, body.path("message").asString(), type);
            assertEquals("ALREADY_REPORTED", body.path("code").asString(), type);
            assertEquals(1, activeCount(reporter, type, content.of(type)), type);
        }
    }

    @Test
    void anInReviewReportStillCountsButAClosedOneDoesNot() throws Exception {
        Content content = content("Closed author");
        Account reporter = register("Closed reporter");

        for (String type : TYPES) {
            long first = json.readTree(report(type, content.of(type), reporter, "SPAM", null).body()).path("id").asLong();

            status(first, "IN_REVIEW");
            assertEquals(409, report(type, content.of(type), reporter, "SPAM", null).status(), type + " in review");

            status(first, type.length() % 2 == 0 ? "RESOLVED" : "REJECTED");
            Reply after = report(type, content.of(type), reporter, "MISINFORMATION", "again after the review");
            assertEquals(200, after.status(), type + " " + after.body());
            assertEquals(409, report(type, content.of(type), reporter, "SPAM", null).status(), type + " once more");
            assertEquals(1, activeCount(reporter, type, content.of(type)), type);
            assertEquals(2, jdbc.queryForObject("select count(*) from reports where reporter_id = ? and type = ?",
                    Long.class, reporter.id(), type), type + " keeps the closed report");
        }
    }

    @Test
    void differentPeopleMayReportTheSameContentAndOnePersonDifferentContent() throws Exception {
        Content content = content("Shared author");
        Content more = content("Second author");
        Account first = register("First reporter");
        Account second = register("Second reporter");

        for (String type : TYPES) {
            assertEquals(200, report(type, content.of(type), first, "SPAM", null).status());
            assertEquals(200, report(type, content.of(type), second, "SPAM", null).status());
            assertEquals(200, report(type, more.of(type), first, "SPAM", null).status());
        }

        assertEquals(10, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, first.id()));
        assertEquals(5, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, second.id()));
    }

    // ---- concurrency ----

    @Test
    void twoIdenticalReportsAtTheSameMomentLeaveOne() throws Exception {
        Content content = content("Pair author");

        for (String type : TYPES) {
            assertOneOf(2, type, content.of(type), register("Pair reporter " + type));
        }
    }

    @Test
    void eightIdenticalReportsAtTheSameMomentLeaveOne() throws Exception {
        Content content = content("Burst author");

        for (String type : TYPES) {
            assertOneOf(8, type, content.of(type), register("Burst reporter " + type));
        }
    }

    @Test
    void manyPeopleReportingAtOnceAreAllKept() throws Exception {
        Content content = content("Crowd author");
        List<Account> reporters = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            reporters.add(register("Crowd " + i));
        }

        List<Integer> statuses = parallel(reporters.size(), i -> report("POST", content.post(), reporters.get(i), "SPAM", null));

        assertEquals(List.of(200, 200, 200, 200, 200, 200), statuses);
        assertEquals(6, jdbc.queryForObject("select count(*) from reports where reported_post_id = ?", Long.class, content.post()));
    }

    // ---- the database enforces it ----

    @Test
    void theDatabaseRefusesASecondActiveReportAndAReportWithTwoTargets() throws Exception {
        Content content = content("Database author");
        Account reporter = register("Database reporter");
        assertEquals(200, report("ROUTINE", content.routine(), reporter, "SPAM", null).status());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into reports (reporter_id, reported_user_id, reported_routine_id, type, reason, status, created_at, action_taken)
                values (?, ?, ?, 'ROUTINE', 'SPAM', 'IN_REVIEW', now(), 'NONE')
                """, reporter.id(), content.user(), content.routine()));

        // A closed one beside the active one is fine; that is what keeps the review history.
        jdbc.update("""
                insert into reports (reporter_id, reported_user_id, reported_routine_id, type, reason, status, created_at, action_taken)
                values (?, ?, ?, 'ROUTINE', 'SPAM', 'REJECTED', now(), 'NONE')
                """, reporter.id(), content.user(), content.routine());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into reports (reporter_id, reported_user_id, reported_routine_id, reported_post_id, type, reason, status, created_at, action_taken)
                values (?, ?, ?, ?, 'POST', 'SPAM', 'REJECTED', now(), 'NONE')
                """, reporter.id(), content.user(), content.routine(), content.post()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into reports (reporter_id, reported_user_id, type, reason, status, created_at, action_taken)
                values (?, ?, 'SOMETHING', 'SPAM', 'OPEN', now(), 'NONE')
                """, reporter.id(), content.user()));
    }

    @Test
    void thePartialUniqueIndexesAndChecksAreInPlace() {
        Map<String, String> indexes = new HashMap<>();
        jdbc.queryForList("select indexname, indexdef from pg_indexes where tablename = 'reports'")
                .forEach(row -> indexes.put((String) row.get("indexname"), (String) row.get("indexdef")));

        Map<String, String> expected = Map.of(
                "uk_reports_active_user", "reported_user_id",
                "uk_reports_active_comment", "reported_comment_id",
                "uk_reports_active_routine", "reported_routine_id",
                "uk_reports_active_post", "reported_post_id",
                "uk_reports_active_post_comment", "reported_post_comment_id");
        expected.forEach((name, column) -> {
            String definition = indexes.get(name);
            assertTrue(definition != null, name + " missing: " + indexes);
            assertTrue(definition.startsWith("CREATE UNIQUE INDEX"), definition);
            assertTrue(definition.contains("(reporter_id, " + column + ")"), definition);
            assertTrue(definition.contains("'OPEN'") && definition.contains("'IN_REVIEW'"), definition);
            assertFalse(definition.contains("'RESOLVED'") || definition.contains("'REJECTED'"), definition);
        });

        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select convalidated from pg_constraint where conrelid = 'reports'::regclass and conname = 'chk_reports_target'", Boolean.class));
        String typeCheck = String.join(" ", jdbc.queryForList("""
                select pg_get_constraintdef(c.oid) from pg_constraint c
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                where c.conrelid = 'reports'::regclass and c.contype = 'c' and cardinality(c.conkey) = 1 and a.attname = 'type'
                """, String.class));
        TYPES.forEach(type -> assertTrue(typeCheck.contains("'" + type + "'"), typeCheck));

        // Deleting content empties the link instead of taking the report with it.
        List<String> rules = jdbc.queryForList("""
                select confdeltype::text from pg_constraint
                where conrelid = 'reports'::regclass and conname in ('fk_reports_routine', 'fk_reports_post', 'fk_reports_post_comment')
                """, String.class);
        assertEquals(List.of("n", "n", "n"), rules);
        assertEquals(List.of(), reportConstraints.missingSafeguards(), "reporting is on");
    }

    // The production table predates US-24: its type check allows only USER and COMMENT, and an old
    // race may have left two active reports by one person for one target. All in one rolled-back
    // transaction, so the shared test table ends as it was.
    @Test
    void startingOnAnOlderTableWidensItAndNeverTouchesItsRows() throws Exception {
        Content content = content("Legacy author");
        Account reporter = register("Legacy reporter");
        Account other = register("Legacy other");

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbc.execute("drop index uk_reports_active_user, uk_reports_active_comment, uk_reports_active_routine, "
                    + "uk_reports_active_post, uk_reports_active_post_comment");
            jdbc.execute("alter table reports drop constraint chk_reports_target");
            jdbc.queryForList("""
                    select c.conname from pg_constraint c
                    join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                    where c.conrelid = 'reports'::regclass and c.contype = 'c' and cardinality(c.conkey) = 1 and a.attname = 'type'
                    """, String.class).forEach(name -> jdbc.execute("alter table reports drop constraint " + name));
            // NOT VALID only because other tests here already stored the newer types.
            jdbc.execute("alter table reports add constraint chk_reports_type check (type in ('USER', 'COMMENT')) not valid");

            for (int i = 0; i < 3; i++) {
                jdbc.update("""
                        insert into reports (reporter_id, reported_user_id, type, reason, details, status, created_at, action_taken)
                        values (?, ?, 'USER', 'spam, typed before US-24', ?, 'OPEN', now(), 'NONE')
                        """, reporter.id(), content.user(), "legacy " + i);
            }
            jdbc.update("""
                    insert into reports (reporter_id, reported_user_id, reported_comment_id, type, reason, status, created_at, action_taken)
                    values (?, ?, ?, 'COMMENT', 'rude', 'RESOLVED', now(), 'RESOLVE')
                    """, other.id(), content.user(), content.comment());
            String rowsBefore = String.valueOf(jdbc.queryForList("select * from reports order by id"));
            assertEquals(List.of("uk_reports_active_user", "uk_reports_active_comment", "uk_reports_active_routine",
                    "uk_reports_active_post", "uk_reports_active_post_comment", "chk_reports_type", "chk_reports_target"),
                    reportConstraints.missingSafeguards());

            reportConstraints.afterSingletonsInstantiated();
            reportConstraints.afterSingletonsInstantiated();

            assertEquals(rowsBefore, String.valueOf(jdbc.queryForList("select * from reports order by id")), "no row changed");
            assertTrue(missing("uk_reports_active_user"), "left out while three active rows break it");
            List.of("uk_reports_active_comment", "uk_reports_active_routine", "uk_reports_active_post", "uk_reports_active_post_comment")
                    .forEach(name -> assertFalse(missing(name), name));
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "select convalidated from pg_constraint where conrelid = 'reports'::regclass and conname = 'chk_reports_target'", Boolean.class));
            String typeCheck = jdbc.queryForObject(
                    "select pg_get_constraintdef(oid) from pg_constraint where conrelid = 'reports'::regclass and conname = 'chk_reports_type'", String.class);
            TYPES.forEach(type -> assertTrue(typeCheck.contains("'" + type + "'"), typeCheck));

            // One index short: the server runs, but no report of any type is written until it is there.
            assertEquals(List.of("uk_reports_active_user"), reportConstraints.missingSafeguards());
            long reportsBefore = jdbc.queryForObject("select count(*) from reports", Long.class);
            ReportService.ReportRefusedException off = assertThrows(ReportService.ReportRefusedException.class,
                    () -> reportService.reportPost(other.email(), content.post(), "SPAM", null));
            assertEquals(503, off.getStatusCode().value());
            assertEquals("UNAVAILABLE", off.getCode());
            assertThrows(ReportService.ReportRefusedException.class,
                    () -> reportService.reportUser(other.email(), content.user(), "SPAM", null));
            assertEquals(reportsBefore, jdbc.queryForObject("select count(*) from reports", Long.class));

            jdbc.update("""
                    insert into reports (reporter_id, reported_user_id, reported_post_id, type, reason, status, created_at, action_taken)
                    values (?, ?, ?, 'POST', 'SPAM', 'OPEN', now(), 'NONE')
                    """, other.id(), content.user(), content.post());
            transaction.setRollbackOnly();
        });

        assertFalse(missing("uk_reports_active_user"), "the rollback put the index back");
    }

    @Test
    void reportsMadeBeforeUs24StayReadable() throws Exception {
        Content content = content("Old author");
        Account reporter = register("Old reporter");
        long old = jdbc.queryForObject("""
                insert into reports (reporter_id, reported_user_id, type, reason, details, status, created_at, action_taken)
                values (?, ?, 'USER', 'He keeps sending ads', 'typed freely before US-24', 'RESOLVED', now(), 'NONE') returning id
                """, Long.class, reporter.id(), content.user());

        JsonNode mine = json.readTree(send("GET", "/reports/mine", reporter, null).body());
        assertEquals("He keeps sending ads", mine.get(0).path("reason").asString(), mine.toString());
        JsonNode seen = find(json.readTree(send("GET", "/admin-api/reports", admin, null).body()), old);
        assertEquals("He keeps sending ads", seen.path("reason").asString());
        assertEquals("USER", seen.path("type").asString());
        assertEquals(200, report("USER", content.user(), reporter, "SPAM", null).status(), "a closed old report does not count");
    }

    // ---- validation ----

    @Test
    void aReasonMustBeChosenFromTheList() throws Exception {
        Content content = content("Reason author");
        Account reporter = register("Reason reporter");

        for (String type : TYPES) {
            String path = endpoint(type) + content.of(type);
            List<Reply> refused = List.of(
                    send("POST", path, reporter, Map.of("details", "no reason")),
                    send("POST", path, reporter, Map.of("reason", "")),
                    send("POST", path, reporter, Map.of("reason", "   ")),
                    send("POST", path, reporter, Map.of("reason", "BECAUSE")),
                    send("POST", path, reporter, Map.of("reason", HOSTILE)),
                    send("POST", path, reporter, Map.of("reason", "S".repeat(5000))),
                    raw(path, reporter, "{\"reason\": "),
                    raw(path, reporter, "[1, 2]"),
                    raw(path, reporter, ""));
            for (Reply reply : refused) {
                assertEquals(400, reply.status(), type + " " + reply.body());
                JsonNode body = json.readTree(reply.body());
                assertEquals(INVALID_REASON, body.path("message").asString(), type + " " + reply.body());
                assertEquals("INVALID_REASON", body.path("code").asString());
                assertFalse(reply.body().contains("Exception") || reply.body().contains("at com."), reply.body());
            }
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, reporter.id()));
    }

    @Test
    void theExplanationIsOptionalAndLimited() throws Exception {
        Content content = content("Details author");
        Account reporter = register("Details reporter");

        Reply tooLong = report("ROUTINE", content.routine(), reporter, "OTHER", "d".repeat(1001));
        assertEquals(400, tooLong.status(), tooLong.body());
        assertEquals("DETAILS_TOO_LONG", json.readTree(tooLong.body()).path("code").asString());

        Reply longest = report("ROUTINE", content.routine(), reporter, "OTHER", "  " + "d".repeat(1000) + "  ");
        assertEquals(200, longest.status(), longest.body());
        assertEquals(1000, json.readTree(longest.body()).path("details").asString().length(), "trimmed");

        Reply blank = report("POST", content.post(), reporter, " spam ", "   ");
        assertEquals(200, blank.status(), blank.body());
        assertTrue(json.readTree(blank.body()).path("details").isNull(), "blank explanation is not stored");
        assertEquals("SPAM", json.readTree(blank.body()).path("reason").asString());
    }

    @Test
    void hostileExplanationsAreStoredAndReturnedAsPlainText() throws Exception {
        Content content = content("Xss author");
        Account reporter = register("Xss reporter");

        Reply reply = report("POST_COMMENT", content.postComment(), reporter, "HARASSMENT", HOSTILE);
        assertEquals(200, reply.status(), reply.body());
        long id = json.readTree(reply.body()).path("id").asLong();

        assertEquals(HOSTILE, jdbc.queryForObject("select details from reports where id = ?", String.class, id));
        assertEquals(HOSTILE, find(json.readTree(send("GET", "/admin-api/reports", admin, null).body()), id).path("details").asString());
    }

    // ---- who may report what ----

    @Test
    void visitorsCannotReport() throws Exception {
        Content content = content("Guest author");
        long before = count();

        for (String type : TYPES) {
            int status = report(type, content.of(type), null, "SPAM", null).status();
            assertTrue(status == 401 || status == 403, type + " " + status);
        }
        int mine = send("GET", "/reports/mine", null, null).status();
        assertTrue(mine == 401 || mine == 403, String.valueOf(mine));
        assertEquals(before, count());
    }

    @Test
    void nobodyReportsTheirOwnContent() throws Exception {
        Content content = content("Self author");

        for (String type : TYPES) {
            Reply reply = report(type, content.of(type), content.author(), "SPAM", null);
            assertEquals(400, reply.status(), type + " " + reply.body());
            assertEquals("SELF_REPORT", json.readTree(reply.body()).path("code").asString(), type);
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, content.author().id()));
    }

    @Test
    void missingContentIsNotFound() throws Exception {
        Account reporter = register("Missing reporter");

        for (String type : TYPES) {
            Reply reply = report(type, 987654321L, reporter, "SPAM", null);
            assertEquals(404, reply.status(), type + " " + reply.body());
            assertEquals("NOT_FOUND", json.readTree(reply.body()).path("code").asString());
            Reply notANumber = send("POST", endpoint(type) + "not-a-number", reporter, Map.of("reason", "SPAM"));
            assertEquals(404, notANumber.status(), notANumber.body());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, reporter.id()));
    }

    // A private routine or post, and the comments under them, answer word for word like missing ones.
    @Test
    void privateContentLooksExactlyLikeMissingContent() throws Exception {
        Account author = register("Private author");
        Account stranger = register("Private stranger");
        long routine = routine(author, "Private routine", false);
        long comment = routineComment(author, routine, "private routine comment");
        long post = post(author, "Private post", false);
        long postComment = postComment(author, post, "private post comment");

        Map<String, Long> hidden = Map.of("ROUTINE", routine, "COMMENT", comment, "POST", post, "POST_COMMENT", postComment);
        for (Map.Entry<String, Long> entry : hidden.entrySet()) {
            Reply privateReply = report(entry.getKey(), entry.getValue(), stranger, "SPAM", null);
            Reply missingReply = report(entry.getKey(), 987654321L, stranger, "SPAM", null);
            assertEquals(404, privateReply.status(), entry.getKey());
            assertEquals(missingReply.body(), privateReply.body(), entry.getKey());
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, stranger.id()));
    }

    @Test
    void contentAnAdministratorHidOrThatWasDeletedCannotBeReported() throws Exception {
        Content hiddenContent = content("Hidden author");
        Content deletedContent = content("Deleted author");
        Account reporter = register("Hidden reporter");

        assertEquals(200, send("PUT", "/admin-api/routines/" + hiddenContent.routine() + "/hide", admin, null).status());
        assertEquals(200, send("PUT", "/admin-api/posts/" + hiddenContent.post() + "/hide", admin, null).status());
        for (String type : List.of("ROUTINE", "COMMENT", "POST", "POST_COMMENT")) {
            assertEquals(404, report(type, hiddenContent.of(type), reporter, "SPAM", null).status(), "hidden " + type);
        }

        assertEquals(204, send("DELETE", "/routine-comments/" + deletedContent.comment(), deletedContent.author(), null).status());
        assertEquals(204, send("DELETE", "/posts/" + deletedContent.post() + "/comments/" + deletedContent.postComment(),
                deletedContent.author(), null).status());
        assertEquals(204, send("DELETE", "/posts/" + deletedContent.post(), deletedContent.author(), null).status());
        int routineDelete = send("DELETE", "/routines/" + deletedContent.routine(), deletedContent.author(), null).status();
        assertTrue(routineDelete == 200 || routineDelete == 204, String.valueOf(routineDelete));
        for (String type : List.of("ROUTINE", "COMMENT", "POST", "POST_COMMENT")) {
            assertEquals(404, report(type, deletedContent.of(type), reporter, "SPAM", null).status(), "deleted " + type);
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, reporter.id()));
    }

    @Test
    void bannedPeopleCannotReportAndTheirPostsAndCommentsAreNotThere() throws Exception {
        Content content = content("Banned author");
        Account banned = register("Banned reporter");
        Account reporter = register("Reporter of banned");
        Content target = content("Target of banned");

        assertEquals(200, send("PUT", "/admin-api/users/" + banned.id() + "/ban", admin, null).status());
        for (String type : TYPES) {
            int status = report(type, target.of(type), banned, "SPAM", null).status();
            assertTrue(status == 401 || status == 403, type + " " + status);
        }

        assertEquals(200, send("PUT", "/admin-api/users/" + content.user() + "/ban", admin, null).status());
        assertEquals(404, report("POST", content.post(), reporter, "SPAM", null).status());
        assertEquals(404, report("POST_COMMENT", content.postComment(), reporter, "SPAM", null).status());
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, banned.id()));
    }

    @Test
    void administratorsAreNeitherReportersNorReportedUsers() throws Exception {
        Content content = content("Admin-side author");
        Account reporter = register("Admin-side reporter");

        int status = report("ROUTINE", content.routine(), admin, "SPAM", null).status();
        assertTrue(status == 401 || status == 403, String.valueOf(status));
        assertEquals(404, report("USER", admin.id(), reporter, "SPAM", null).status());
    }

    @Test
    void membersCannotUseTheAdministratorReportApi() throws Exception {
        Content content = content("Api author");
        Account reporter = register("Api reporter");
        long id = json.readTree(report("POST", content.post(), reporter, "SPAM", null).body()).path("id").asLong();

        for (Object[] call : List.of(
                new Object[]{"GET", "/admin-api/reports"},
                new Object[]{"GET", "/admin-api/reports/" + id},
                new Object[]{"PUT", "/admin-api/reports/" + id + "/status"},
                new Object[]{"POST", "/admin-api/reports/" + id + "/ban-user"},
                new Object[]{"POST", "/admin-api/reports/" + id + "/hide-post"},
                new Object[]{"POST", "/admin-api/reports/" + id + "/remove-post"},
                new Object[]{"POST", "/admin-api/reports/" + id + "/remove-post-comment"},
                new Object[]{"POST", "/admin-api/reports/" + id + "/hide-routine"})) {
            Map<String, ?> body = "PUT".equals(call[0]) ? Map.of("status", "REJECTED") : null;
            int status = send((String) call[0], (String) call[1], reporter, body).status();
            assertTrue(status == 401 || status == 403, call[1] + " " + status);
            int guest = send((String) call[0], (String) call[1], null, body).status();
            assertTrue(guest == 401 || guest == 403, call[1] + " " + guest);
        }

        assertEquals("OPEN", jdbc.queryForObject("select status from reports where id = ?", String.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, content.post()));
        assertEquals(false, jdbc.queryForObject("select banned from users where id = ?", Boolean.class, content.user()));
    }

    // ---- moderation ----

    @Test
    void anAdministratorHidesAReportedRoutineOrPost() throws Exception {
        Content content = content("Moderated author");
        Account reporter = register("Moderated reporter");
        long routineReport = reportId("ROUTINE", content.routine(), reporter);
        long postReport = reportId("POST", content.post(), reporter);

        JsonNode routine = admin("POST", "/admin-api/reports/" + routineReport + "/hide-routine");
        assertEquals("RESOLVED", routine.path("status").asString());
        assertEquals("HIDE_ROUTINE", routine.path("actionTaken").asString());
        assertTrue(routine.path("targetHidden").asBoolean());
        assertEquals(404, send("GET", "/routines/" + content.routine(), null, null).status(), "gone from public view");
        assertEquals(200, send("GET", "/routines/" + content.routine(), content.author(), null).status(), "the owner keeps it");

        JsonNode post = admin("POST", "/admin-api/reports/" + postReport + "/hide-post");
        assertEquals("HIDE_POST", post.path("actionTaken").asString());
        assertEquals(404, send("GET", "/posts/" + content.post(), reporter, null).status());
        assertEquals(1, jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, content.post()), "hidden, not removed");

        assertEquals(409, send("POST", "/admin-api/reports/" + postReport + "/hide-post", admin, null).status(), "closed");
    }

    @Test
    void anAdministratorRemovesAReportedPostOrCommentAndTheReportsStay() throws Exception {
        Content content = content("Removed author");
        Account reporter = register("Removed reporter");
        Account second = register("Second removed reporter");
        long postReport = reportId("POST", content.post(), reporter);
        long otherPostReport = reportId("POST", content.post(), second);
        long postCommentReport = reportId("POST_COMMENT", content.postComment(), second);
        long commentReport = reportId("COMMENT", content.comment(), reporter);

        JsonNode removed = admin("POST", "/admin-api/reports/" + postReport + "/remove-post");
        assertEquals("REMOVE_POST", removed.path("actionTaken").asString());
        assertFalse(removed.path("targetAvailable").asBoolean());
        assertEquals(0, jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, content.post()));
        assertEquals(0, jdbc.queryForObject("select count(*) from post_comments where id = ?", Long.class, content.postComment()));

        // The other reports about the post and its comment are kept, without their links.
        assertEquals(3, jdbc.queryForObject("select count(*) from reports where id in (?, ?, ?)",
                Long.class, postReport, otherPostReport, postCommentReport));
        JsonNode list = json.readTree(send("GET", "/admin-api/reports", admin, null).body());
        assertFalse(find(list, otherPostReport).path("targetAvailable").asBoolean());
        assertEquals("OPEN", find(list, otherPostReport).path("status").asString());
        assertFalse(find(list, postCommentReport).path("targetAvailable").asBoolean());
        assertEquals(409, send("POST", "/admin-api/reports/" + otherPostReport + "/remove-post", admin, null).status());
        assertEquals(200, send("PUT", "/admin-api/reports/" + otherPostReport + "/status", admin, Map.of("status", "RESOLVED")).status());

        JsonNode comment = admin("POST", "/admin-api/reports/" + commentReport + "/remove-comment");
        assertEquals("REMOVE_COMMENT", comment.path("actionTaken").asString());
        assertEquals(true, jdbc.queryForObject("select deleted from routine_comments where id = ?", Boolean.class, content.comment()));

        Content more = content("Comment author");
        long moreReport = reportId("POST_COMMENT", more.postComment(), reporter);
        JsonNode postComment = admin("POST", "/admin-api/reports/" + moreReport + "/remove-post-comment");
        assertEquals("REMOVE_POST_COMMENT", postComment.path("actionTaken").asString());
        assertEquals(0, jdbc.queryForObject("select count(*) from post_comments where id = ?", Long.class, more.postComment()));
        assertEquals(1, jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, more.post()), "only the comment");
    }

    @Test
    void anAdministratorBansThePersonBehindAnyReport() throws Exception {
        Content content = content("Ban author");
        Account reporter = register("Ban reporter");
        long id = reportId("ROUTINE", content.routine(), reporter);

        JsonNode banned = admin("POST", "/admin-api/reports/" + id + "/ban-user");
        assertEquals("BAN_USER", banned.path("actionTaken").asString());
        assertTrue(banned.path("reportedUserBanned").asBoolean());
        assertEquals(true, jdbc.queryForObject("select banned from users where id = ?", Boolean.class, content.user()));
        assertEquals(409, send("POST", "/admin-api/reports/" + id + "/ban-user", admin, null).status(), "closed");
    }

    @Test
    void anActionOnlyFitsItsOwnKindOfReport() throws Exception {
        Content content = content("Mismatch author");
        Account reporter = register("Mismatch reporter");
        long routineReport = reportId("ROUTINE", content.routine(), reporter);
        long userReport = reportId("USER", content.user(), reporter);
        String before = everything(content);

        for (String action : List.of("hide-post", "remove-post", "remove-post-comment", "remove-comment")) {
            assertEquals(409, send("POST", "/admin-api/reports/" + routineReport + "/" + action, admin, null).status(), action);
        }
        for (String action : List.of("hide-routine", "hide-post", "remove-post", "remove-post-comment", "remove-comment")) {
            assertEquals(409, send("POST", "/admin-api/reports/" + userReport + "/" + action, admin, null).status(), action);
        }
        assertEquals(404, send("POST", "/admin-api/reports/987654321/hide-routine", admin, null).status());
        assertEquals(before, everything(content));
        assertEquals("OPEN", jdbc.queryForObject("select status from reports where id = ?", String.class, routineReport));

        Reply message = send("POST", "/admin-api/reports/" + userReport + "/hide-post", admin, null);
        assertEquals("This report is not about a post", json.readTree(message.body()).path("message").asString());
    }

    @Test
    void ownersCanStillDeleteContentThatWasReported() throws Exception {
        Content content = content("Owner delete author");
        Account reporter = register("Owner delete reporter");
        long postReport = reportId("POST", content.post(), reporter);
        long postCommentReport = reportId("POST_COMMENT", content.postComment(), reporter);

        assertEquals(204, send("DELETE", "/posts/" + content.post() + "/comments/" + content.postComment(), content.author(), null).status());
        assertEquals(204, send("DELETE", "/posts/" + content.post(), content.author(), null).status());

        assertEquals(2, jdbc.queryForObject("select count(*) from reports where id in (?, ?)", Long.class, postReport, postCommentReport));
        assertEquals(0, jdbc.queryForObject("select count(*) from reports where id in (?, ?) and (reported_post_id is not null or reported_post_comment_id is not null)",
                Long.class, postReport, postCommentReport));
        assertEquals(200, send("GET", "/reports/mine", reporter, null).status());
        assertEquals(200, send("PUT", "/admin-api/reports/" + postReport + "/status", admin, Map.of("status", "REJECTED")).status());
    }

    @Test
    void reportStatusesMoveOnlyForward() throws Exception {
        Content content = content("Status author");
        Account reporter = register("Status reporter");
        long id = reportId("USER", content.user(), reporter);

        assertEquals(400, send("PUT", "/admin-api/reports/" + id + "/status", admin, Map.of("status", "OPEN")).status());
        assertEquals("IN_REVIEW", status(id, "IN_REVIEW").path("status").asString());
        assertEquals("REJECTED", status(id, "REJECTED").path("status").asString());
        assertEquals(409, send("PUT", "/admin-api/reports/" + id + "/status", admin, Map.of("status", "RESOLVED")).status());
    }

    // ---- browser ----

    @Test
    void browserOpensTheDialogFromEveryReportButton() {
        JsonNode opened = page("opened");
        for (String place : List.of("user", "routine", "post", "routineComment", "routineReply", "postComment")) {
            JsonNode view = opened.path(place);
            assertTrue(view.path("visible").asBoolean(), place + " " + view);
            assertEquals(view.path("expectedTitle").asString(), view.path("title").asString(), place);
            assertEquals(5, view.path("reasons").asInt(), place);
            assertTrue(view.path("focusInside").asBoolean(), place + " moves focus into the dialog");
        }
    }

    @Test
    void browserSendsTheRightTargetForEachButton() throws Exception {
        JsonNode sent = page("sent");
        assertEquals(6, sent.size(), sent.toString());
        for (JsonNode one : sent) {
            assertEquals(SUBMITTED, one.path("result").asString(), one.toString());
            long id = one.path("reportId").asLong();
            assertNotEquals(0, id, one.toString());
            assertEquals(one.path("type").asString(), jdbc.queryForObject("select type from reports where id = ?", String.class, id));
            assertEquals(one.path("reason").asString(), jdbc.queryForObject("select reason from reports where id = ?", String.class, id));
        }
    }

    @Test
    void browserRefusesAnEmptyFormAndShowsLoadingAndAlreadyReported() {
        JsonNode form = page("form");
        assertEquals("Please select a reason.", form.path("emptyError").asString(), form.toString());
        assertEquals(0, form.path("requestsForEmpty").asInt(), "nothing sent without a reason");
        assertTrue(form.path("loadingDisabled").asBoolean(), form.toString());
        assertEquals("Submitting…", form.path("loadingText").asString());
        assertEquals(1, form.path("requestsForDoubleClick").asInt(), "a second click while sending sends nothing");
        assertEquals(ALREADY, form.path("alreadyOnOpen").asString());
        assertEquals(ALREADY, form.path("alreadyFromServer").asString());
        assertEquals("This content is no longer available.", form.path("goneError").asString());
    }

    @Test
    void browserDialogWorksWithTheKeyboard() {
        JsonNode keys = page("keyboard");
        assertTrue(keys.path("openedWithEnter").asBoolean(), keys.toString());
        assertTrue(keys.path("tabStaysInside").asBoolean(), keys.toString());
        assertTrue(keys.path("closedWithEscape").asBoolean(), keys.toString());
        assertTrue(keys.path("focusReturned").asBoolean(), keys.toString());
        assertTrue(keys.path("labelled").asBoolean(), keys.toString());
    }

    @Test
    void browserKeepsTheOtherMenusWorking() {
        JsonNode menus = page("menus");
        assertTrue(menus.path("viewProfile").asBoolean(), menus.toString());
        assertTrue(menus.path("addFriend").asBoolean(), menus.toString());
        assertTrue(menus.path("ownCommentHasNoReport").asBoolean(), menus.toString());
        assertTrue(menus.path("ownerSeesNoReport").asBoolean(), menus.toString());
        assertTrue(menus.path("guestAskedToSignIn").asBoolean(), menus.toString());
        assertTrue(menus.path("likeStillWorks").asBoolean(), menus.toString());
    }

    @Test
    void browserAdministratorSeesAndHandlesTheReports() throws Exception {
        JsonNode adminPage = page("admin");
        for (String type : TYPES) {
            assertTrue(adminPage.path("types").path(type).asBoolean(), type + " " + adminPage);
        }
        assertTrue(adminPage.path("hostileShownAsText").asBoolean(), adminPage.toString());
        assertFalse(adminPage.path("xssRan").asBoolean(), adminPage.toString());
        assertEquals("IN_REVIEW", jdbc.queryForObject("select status from reports where id = ?", String.class, adminPage.path("reviewedId").asLong()));
        assertEquals("HIDE_ROUTINE", jdbc.queryForObject("select action_taken from reports where id = ?", String.class, adminPage.path("hiddenId").asLong()));
        assertTrue(adminPage.path("hiddenRowClosed").asBoolean(), adminPage.toString());
    }

    @Test
    void browserKeepsTheLayoutInBothDesignsAndEveryWidth() {
        JsonNode layouts = page("layouts");
        assertEquals(24, layouts.size(), layouts.toString());
        List<String> problems = new ArrayList<>();
        layouts.properties().forEach(entry -> {
            JsonNode view = entry.getValue();
            if (view.path("overflow").asInt() > 0) {
                problems.add(entry.getKey() + " scrolls sideways by " + view.path("overflow").asInt());
            }
            if (!view.path("dialogInside").asBoolean(true)) {
                problems.add(entry.getKey() + " dialog outside the screen");
            }
            if (!view.path("heroInside").asBoolean(true)) {
                problems.add(entry.getKey() + " routine buttons leave the card");
            }
            if (!view.path("themeApplied").asBoolean()) {
                problems.add(entry.getKey() + " wrong design " + view.path("theme").asString());
            }
            if (view.path("minContrast").asDouble(10) < 4.5) {
                problems.add(entry.getKey() + " faint text " + view.path("faintest").asString() + " " + view.path("minContrast").asDouble());
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
    void prepare() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");
        admin = register("Report admin");
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Content content = content("Browser author");
        Account reader = register("Browser reader");
        Account other = register("Browser other");
        long reply = json.readTree(send("POST", "/routine-comments/" + content.routine(), other,
                Map.of("text", "A reply to report", "parentId", content.comment())).body()).path("id").asLong();
        long readerComment = routineComment(reader, content.routine(), "Reader's own comment");
        long gonePost = post(content.author(), "A post that goes away", true);
        long alreadyPost = post(content.author(), "Reported before", true);
        assertEquals(200, report("POST", alreadyPost, reader, "SPAM", null).status());
        long serverAlreadyPost = post(content.author(), "Reported in another tab", true);
        assertEquals(200, report("POST", serverAlreadyPost, reader, "SPAM", null).status());

        // For the admin page: one report of each type, one with a hostile explanation.
        Content adminContent = content("Admin page author");
        Account adminReporter = register("Admin page reporter");
        Map<String, Long> adminReports = new LinkedHashMap<>();
        for (String type : TYPES) {
            adminReports.put(type, json.readTree(report(type, adminContent.of(type), adminReporter, "HARASSMENT",
                    type.equals("POST") ? HOSTILE : "Admin page " + type).body()).path("id").asLong());
        }

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("adminToken", admin.token());
        fixtures.put("readerToken", reader.token());
        fixtures.put("readerId", reader.id());
        fixtures.put("layoutToken", other.token());
        fixtures.put("authorToken", content.author().token());
        fixtures.put("authorId", content.user());
        fixtures.put("routine", content.routine());
        fixtures.put("comment", content.comment());
        fixtures.put("reply", reply);
        fixtures.put("readerComment", readerComment);
        fixtures.put("post", content.post());
        fixtures.put("postComment", content.postComment());
        fixtures.put("gonePost", gonePost);
        fixtures.put("alreadyPost", alreadyPost);
        fixtures.put("serverAlreadyPost", serverAlreadyPost);
        fixtures.put("adminReports", adminReports);
        fixtures.put("hostile", HOSTILE);

        Path dir = Files.createTempDirectory("report-content-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/report-content-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(10, TimeUnit.MINUTES)) {
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

    private Content content(String name) throws Exception {
        Account author = register(name);
        long routine = routine(author, name.startsWith("AC1") ? "AC1 routine" : name + " routine", true);
        long comment = routineComment(author, routine, name + " routine comment");
        long post = post(author, name.startsWith("AC1") ? "AC1 post" : name + " post", true);
        long postComment = postComment(author, post, name.startsWith("AC1") ? "AC1 post comment" : name + " post comment");
        return new Content(author, author.id(), comment, routine, post, postComment);
    }

    private void assertOneOf(int requests, String type, long target, Account reporter) throws Exception {
        List<Integer> statuses = parallel(requests, i -> report(type, target, reporter, "SPAM", "at once " + i));

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), type + " " + statuses);
        assertEquals(requests - 1, statuses.stream().filter(status -> status == 409).count(), type + " " + statuses);
        assertEquals(1, activeCount(reporter, type, target), type);
        assertEquals(1, jdbc.queryForObject("select count(*) from reports where reporter_id = ?", Long.class, reporter.id()), type);
    }

    private interface Call {
        Reply send(int index) throws Exception;
    }

    // Every request waits at one gate, so they reach the server together.
    private List<Integer> parallel(int count, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch gate = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return call.send(index).status();
                }));
            }
            gate.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private long activeCount(Account reporter, String type, long target) {
        return jdbc.queryForObject("select count(*) from reports where reporter_id = ? and type = ? and "
                        + column(type) + " = ? and status in ('OPEN', 'IN_REVIEW')",
                Long.class, reporter.id(), type, target);
    }

    private long count() {
        return jdbc.queryForObject("select count(*) from reports", Long.class);
    }

    private boolean missing(String index) {
        return jdbc.queryForObject("select to_regclass(?) is null", Boolean.class, "public." + index);
    }

    // What reporting must not change: the content, its visibility and its author's account.
    private String everything(Content content) {
        return String.valueOf(jdbc.queryForList("select id, title, is_public, deleted, hidden_at from routines where id = ?", content.routine()))
                + jdbc.queryForList("select id, text, is_public, hidden_at from posts where id = ?", content.post())
                + jdbc.queryForList("select id, text, deleted from routine_comments where id = ?", content.comment())
                + jdbc.queryForList("select id, text from post_comments where id = ?", content.postComment())
                + jdbc.queryForList("select id, banned, profile_hidden_at from users where id = ?", content.user());
    }

    private static String endpoint(String type) {
        return switch (type) {
            case "USER" -> "/reports/users/";
            case "COMMENT" -> "/reports/comments/";
            case "ROUTINE" -> "/reports/routines/";
            case "POST" -> "/reports/posts/";
            default -> "/reports/post-comments/";
        };
    }

    private static String targetField(String type) {
        return switch (type) {
            case "USER" -> "reportedUserId";
            case "COMMENT" -> "reportedCommentId";
            case "ROUTINE" -> "reportedRoutineId";
            case "POST" -> "reportedPostId";
            default -> "reportedPostCommentId";
        };
    }

    private static String column(String type) {
        return switch (type) {
            case "USER" -> "reported_user_id";
            case "COMMENT" -> "reported_comment_id";
            case "ROUTINE" -> "reported_routine_id";
            case "POST" -> "reported_post_id";
            default -> "reported_post_comment_id";
        };
    }

    private Reply report(String type, long target, Account who, String reason, String details) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", reason);
        body.put("details", details);
        return send("POST", endpoint(type) + target, who, body);
    }

    private long reportId(String type, long target, Account who) throws Exception {
        Reply reply = report(type, target, who, "SPAM", null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private JsonNode status(long id, String status) throws Exception {
        Reply reply = send("PUT", "/admin-api/reports/" + id + "/status", admin, Map.of("status", status));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode admin(String method, String path) throws Exception {
        Reply reply = send(method, path, admin, null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode only(JsonNode reports, long reporterId, String type) {
        List<JsonNode> found = new ArrayList<>();
        reports.forEach(report -> {
            if (report.path("reporterId").asLong() == reporterId && type.equals(report.path("type").asString())) {
                found.add(report);
            }
        });
        assertEquals(1, found.size(), type + " " + found);
        return found.get(0);
    }

    private static JsonNode find(JsonNode list, long id) {
        for (JsonNode item : list) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("No report " + id + " in " + list);
    }

    private Account register(String name) throws Exception {
        String email = "report24_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = send("POST", "/auth/register", null, Map.of("name", name, "email", email, "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString(), email);
    }

    private long routine(Account who, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Report content check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));
        Reply reply = multipart("/routines", who, fields);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long routineComment(Account who, long routine, String text) throws Exception {
        Reply reply = send("POST", "/routine-comments/" + routine, who, Map.of("text", text));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long post(Account who, String text, boolean isPublic) throws Exception {
        Reply reply = multipart("/posts", who, Map.of("text", text, "isPublic", String.valueOf(isPublic)));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long postComment(Account who, long post, String text) throws Exception {
        Reply reply = send("POST", "/posts/" + post + "/comments", who, Map.of("text", text));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply multipart(String path, Account who, Map<String, String> fields) throws Exception {
        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + who.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply raw(String path, Account who, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + who.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
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
