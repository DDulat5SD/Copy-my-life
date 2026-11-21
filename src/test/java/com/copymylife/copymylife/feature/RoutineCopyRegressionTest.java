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
class RoutineCopyRegressionTest {

    private static final String BOUNDARY = "CopyRegressionBoundary";
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

    // ---- what a copy contains ----

    @Test
    void publicRoutineIsCopiedWithAllItsHabitsInOrder() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Original title", "Original description", "Fitness", 60, true);
        habit(owner, source, "First", "first note", 10);
        habit(owner, source, "Second", "second note", 20);
        habit(owner, source, "Third", "third note", 30);
        reverseHabits(owner, source);

        Reply reply = copy(source, copier);

        assertEquals(200, reply.status(), reply.body());
        JsonNode copy = json.readTree(reply.body());
        long copyId = copy.path("id").asLong();
        assertNotEquals(source, copyId);
        assertEquals("Original title", copy.path("title").asString());
        assertEquals("Original description", copy.path("description").asString());
        assertEquals("Fitness", copy.path("category").asString());
        assertEquals(60, copy.path("durationMinutes").asInt());
        assertFalse(copy.path("public").asBoolean());
        assertTrue(copy.path("ownedByMe").asBoolean());
        assertEquals(copier.id(), userOf(copyId));

        JsonNode habits = json.readTree(get("/routines/" + copyId + "/routine-habits", copier).body());
        List<String> titles = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode habit : habits) {
            titles.add(habit.path("title").asString());
            durations.add(habit.path("durationMinutes").asInt());
            notes.add(habit.path("description").asString());
        }
        assertEquals(List.of("Third", "Second", "First"), titles);
        assertEquals(List.of(30, 20, 10), durations);
        assertEquals(List.of("third note", "second note", "first note"), notes);
    }

    @Test
    void everyCopiedHabitIsANewRecordOwnedByTheCopier() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "d", 10);
        habit(owner, source, "H2", "d", 20);

        long copyId = copied(source, copier);

        List<Long> sourceHabits = habitIdsOf(source);
        List<Long> copiedHabits = habitIdsOf(copyId);
        assertEquals(2, copiedHabits.size());
        for (Long habit : copiedHabits) {
            assertFalse(sourceHabits.contains(habit), "Copy must not share habit " + habit);
            assertEquals(copier.id(), jdbc.queryForObject("select user_id from habits where id = ?", Long.class, habit));
            assertEquals(copyId, jdbc.queryForObject("select routine_id from habits where id = ?", Long.class, habit));
        }
    }

    @Test
    void aHabitUsedTwiceInARoutineKeepsThatShapeInTheCopy() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Repeats", "d", "Morning", 30, true);
        long library = json.readTree(post("/habit-library", owner, Map.of("title", "Stretch", "durationMinutes", 5)).body()).path("id").asLong();
        assertEquals(200, post("/routines/" + source + "/routine-habits/" + library, owner, null).status());
        assertEquals(200, post("/routines/" + source + "/routine-habits/" + library, owner, null).status());

        long copyId = copied(source, copier);

        List<Long> habits = habitIdsOf(copyId);
        assertEquals(2, habits.size());
        assertEquals(habits.get(0), habits.get(1));
        assertNotEquals(library, habits.get(0));
    }

    @Test
    void imageIsKeptWhenSafeAndDroppedWhenNot() throws Exception {
        Account owner = register();
        Account copier = register();
        long withImage = routine(owner, "Image", "d", "Morning", 30, true);
        long unsafe = routine(owner, "Unsafe", "d", "Morning", 30, true);
        jdbc.update("update routines set image_url = ? where id = ?", "/uploads/routines/photo.png", withImage);
        jdbc.update("update routines set image_url = ? where id = ?", "javascript:alert(1)", unsafe);

        assertEquals("/uploads/routines/photo.png", json.readTree(copy(withImage, copier).body()).path("imageUrl").asString());
        assertTrue(json.readTree(copy(unsafe, copier).body()).path("imageUrl").isNull());
    }

    @Test
    void routineWithoutHabitsCanStillBeCopied() throws Exception {
        Account owner = register();
        Account copier = register();

        long copyId = copied(routine(owner, "Empty", "d", "Morning", 30, true), copier);

        assertEquals(List.of(), habitIdsOf(copyId));
    }

    @Test
    void builtInRoutineIsCopiedAsAnOrdinaryPrivateRoutine() throws Exception {
        Account copier = register();
        JsonNode builtIn = null;
        for (JsonNode candidate : json.readTree(get("/routines/built-in", null).body())) {
            if (!habitIdsOf(candidate.path("id").asLong()).isEmpty()) {
                builtIn = candidate;
                break;
            }
        }
        assertTrue(builtIn != null, "Seed data should include a built-in routine with habits");
        long source = builtIn.path("id").asLong();
        int count = habitIdsOf(source).size();

        long copyId = copied(source, copier);

        assertEquals(false, jdbc.queryForObject("select built_in from routines where id = ?", Boolean.class, copyId));
        assertEquals(count, habitIdsOf(copyId).size());
        assertEquals(copier.id(), userOf(copyId));
        assertFalse(get("/routines/built-in", null).body().contains("\"id\":" + copyId + ","));
    }

    // ---- who may copy ----

    @Test
    void privateRoutineCannotBeCopiedByAnotherUser() throws Exception {
        Account owner = register();
        Account stranger = register();
        long source = routine(owner, "HIDDEN-TITLE", "d", "Morning", 30, false);
        habit(owner, source, "HIDDEN-HABIT", "d", 10);
        int before = routinesOf(stranger);

        Reply hidden = copy(source, stranger);
        Reply missing = copy(MISSING, stranger);

        assertEquals(404, hidden.status());
        assertEquals(404, missing.status());
        assertEquals(withoutPathAndTime(missing.body()), withoutPathAndTime(hidden.body()));
        assertFalse(hidden.body().contains("HIDDEN"));
        assertEquals(before, routinesOf(stranger));
    }

    @Test
    void deletedRoutineCannotBeCopiedByAnyone() throws Exception {
        Account owner = register();
        Account stranger = register();
        long source = routine(owner, "Deleted", "d", "Morning", 30, true);
        assertEquals(204, send("DELETE", "/routines/" + source, owner, null).status());

        assertEquals(404, copy(source, stranger).status());
        assertEquals(404, copy(source, owner).status());
    }

    @Test
    void copyingRequiresAToken() throws Exception {
        Account owner = register();
        long source = routine(owner, "Public", "d", "Morning", 30, true);
        int routines = jdbc.queryForObject("select count(*) from routines", Integer.class);

        assertEquals(403, send("POST", "/routines/" + source + "/copy", null, null).status());
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/routines/" + source + "/copy"))
                .header("Authorization", "Bearer not.a.token")
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(routines, jdbc.queryForObject("select count(*) from routines", Integer.class));
    }

    @Test
    void ownerCanDuplicateOwnPrivateRoutine() throws Exception {
        Account owner = register();
        long source = routine(owner, "Own", "d", "Morning", 30, false);

        long copyId = copied(source, owner);

        assertNotEquals(source, copyId);
        assertEquals(owner.id(), userOf(copyId));
    }

    @Test
    void theCopyIsPrivateToItsNewOwner() throws Exception {
        Account owner = register();
        Account copier = register();
        Account stranger = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        long copyId = copied(source, copier);

        for (Account other : List.of(owner, stranger)) {
            assertEquals(404, get("/routines/" + copyId, other).status());
            assertEquals(404, saveRoutine("PUT", "/routines/" + copyId, other, "Hacked", "d", "Morning", 30, true).status());
            assertEquals(404, send("DELETE", "/routines/" + copyId, other, null).status());
        }
        assertFalse(get("/routines/public", null).body().contains("\"id\":" + copyId + ","));
        assertTrue(json.readTree(get("/routines", copier).body()).findValues("id").stream().anyMatch(id -> id.asLong() == copyId));
    }

    // ---- independence ----

    @Test
    void originalIsNotTouchedByCopying() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "d", 10);
        String before = snapshot(source);

        copied(source, copier);

        assertEquals(before, snapshot(source));
    }

    @Test
    void changingTheCopyDoesNotAffectTheOriginal() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "first", 10);
        habit(owner, source, "H2", "second", 20);
        String before = snapshot(source);
        long copyId = copied(source, copier);
        List<Long> habits = habitIdsOf(copyId);

        assertEquals(200, saveRoutine("PUT", "/routines/" + copyId, copier, "My version", "mine", "Study", 45, true).status());
        assertEquals(200, send("PUT", "/routines/" + copyId + "/habits/" + habits.get(0), copier,
                Map.of("title", "Changed", "description", "changed", "durationMinutes", 5)).status());
        assertEquals(200, send("PUT", "/habit-library/" + habits.get(1), copier,
                Map.of("title", "Changed in library", "description", "changed", "durationMinutes", 20)).status());
        reverseHabits(copier, copyId);
        JsonNode links = json.readTree(get("/routines/" + copyId + "/routine-habits", copier).body());
        assertEquals(200, send("DELETE", "/routines/" + copyId + "/routine-habits/" + links.get(0).path("id").asLong(), copier, null).status());

        assertEquals(before, snapshot(source));
    }

    @Test
    void deletingTheCopyDoesNotAffectTheOriginal() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "d", 10);
        String before = snapshot(source);
        long copyId = copied(source, copier);

        assertEquals(204, send("DELETE", "/routines/" + copyId, copier, null).status());

        assertEquals(before, snapshot(source));
        assertEquals(200, get("/routines/" + source, owner).status());
    }

    @Test
    void changingOrDeletingTheOriginalDoesNotAffectTheCopy() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "first", 10);
        long copyId = copied(source, copier);
        String before = snapshot(copyId);

        assertEquals(200, saveRoutine("PUT", "/routines/" + source, owner, "Renamed", "x", "Study", 45, true).status());
        assertEquals(200, send("PUT", "/routines/" + source + "/habits/" + habitIdsOf(source).get(0), owner,
                Map.of("title", "Changed", "durationMinutes", 15)).status());
        assertEquals(before, snapshot(copyId));

        assertEquals(204, send("DELETE", "/routines/" + source, owner, null).status());
        assertEquals(before, snapshot(copyId));
        assertEquals(200, get("/routines/" + copyId, copier).status());
    }

    // ---- nothing is inherited ----

    @Test
    void historyAndSocialDataAreNotInherited() throws Exception {
        Account owner = register();
        Account fan = register();
        Account copier = register();
        long source = routine(owner, "Popular", "d", "Morning", 10, true);
        habit(owner, source, "H1", "d", 10);
        post("/routine-ratings/" + source, fan, Map.of("value", 5));
        post("/liked-routines/" + source, fan, null);
        post("/saved-routines/" + source, fan, null);
        post("/routine-comments/" + source, fan, Map.of("text", "SOURCE-COMMENT"));
        post("/routine-runs/" + source + "/start", owner, null);
        jdbc.update("update routines set completed = true where id = ?", source);

        long copyId = copied(source, copier);

        JsonNode copy = json.readTree(get("/routines/" + copyId, copier).body());
        assertEquals(0.0, copy.path("averageRating").asDouble());
        assertEquals(0, copy.path("ratingCount").asInt());
        assertEquals(0, copy.path("likeCount").asInt());
        assertEquals(0, copy.path("saveCount").asInt());
        assertFalse(copy.path("completed").asBoolean());
        assertEquals("[]", get("/routine-comments/routine/" + copyId, copier).body());
        for (String table : List.of("routine_ratings", "liked_routines", "saved_routines", "routine_comments", "routine_completions", "calendar_events")) {
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table + " where routine_id = ?", Integer.class, copyId), table);
        }
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from habit_completions where habit_id in (select habit_id from routine_habits where routine_id = ?)",
                Integer.class, copyId));
    }

    // ---- the copy behaves like any other own routine ----

    @Test
    void newOwnerCanEditManageAndStartTheCopy() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 10, true);
        habit(owner, source, "H1", "d", 10);
        long copyId = copied(source, copier);

        JsonNode details = json.readTree(get("/routines/" + copyId, copier).body());
        assertTrue(details.path("ownedByMe").asBoolean());
        assertEquals(200, saveRoutine("PUT", "/routines/" + copyId, copier, "Renamed", "d", "Morning", 15, false).status());
        assertEquals(200, post("/routines/" + copyId + "/habits", copier, Map.of("title", "Added", "durationMinutes", 5)).status());
        Reply started = post("/routine-runs/" + copyId + "/start", copier, null);
        assertEquals(200, started.status(), started.body());
    }

    @Test
    void savingAndLikingStillDoNotCreateARoutine() throws Exception {
        Account owner = register();
        Account fan = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        int before = routinesOf(fan);

        assertEquals(200, post("/saved-routines/" + source, fan, null).status());
        assertEquals(200, post("/liked-routines/" + source, fan, null).status());

        assertEquals(before, routinesOf(fan));
    }

    // ---- all or nothing ----

    @Test
    void aFailureHalfwayLeavesNothingBehind() throws Exception {
        Account owner = register();
        Account copier = register();
        long source = routine(owner, "Source", "d", "Morning", 30, true);
        habit(owner, source, "H1", "d", 10);
        habit(owner, source, "H2", "d", 20);
        int routinesBefore = routinesOf(copier);
        int habitsBefore = jdbc.queryForObject("select count(*) from habits where user_id = ?", Integer.class, copier.id());

        // The second habit link fails inside the database, after the routine and first link were written.
        jdbc.update("update routine_habits set position = 99 where routine_id = ? and position = 1", source);
        jdbc.execute("""
                create or replace function copy_test_fail() returns trigger as $$
                begin raise exception 'simulated failure'; end;
                $$ language plpgsql
                """);
        jdbc.execute("""
                create trigger copy_test_fail before insert on routine_habits
                for each row when (new.position = 99) execute function copy_test_fail()
                """);

        try {
            assertTrue(copy(source, copier).status() >= 500);
        } finally {
            jdbc.execute("drop trigger copy_test_fail on routine_habits");
            jdbc.execute("drop function copy_test_fail()");
        }

        assertEquals(routinesBefore, routinesOf(copier));
        assertEquals(0, jdbc.queryForObject("select count(*) from routines where user_id = ?", Integer.class, copier.id()));
        assertEquals(habitsBefore, jdbc.queryForObject("select count(*) from habits where user_id = ?", Integer.class, copier.id()));
    }

    // ---- real browser ----

    @BeforeAll
    void useTheCopyButtonInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account owner = register();
        Account copier = register();
        long publicRoutine = routine(owner, "Browser source", "Browser description", "Morning", 5, true);
        habit(owner, publicRoutine, "Browser habit", "d", 5);
        long privateRoutine = routine(owner, "Browser hidden", "d", "Morning", 5, false);
        long vanishing = routine(owner, "Goes private later", "d", "Morning", 5, true);
        long halloween = jdbc.queryForObject(
                "select id from routines where built_in = true and category = 'Halloween' order by id limit 1", Long.class);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("ownerToken", owner.token());
        fixtures.put("copierToken", copier.token());
        fixtures.put("publicRoutine", publicRoutine);
        fixtures.put("privateRoutine", privateRoutine);
        fixtures.put("vanishingRoutine", vanishing);
        fixtures.put("halloweenRoutine", halloween);

        Path dir = Files.createTempDirectory("copy-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/routine-copy-check.mjs", chrome,
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
    void copyButtonIsOfferedOnlyWhereItMakesSense() {
        assertTrue(page("otherUserPublic").path("copyVisible").asBoolean(), browser.toString());
        assertFalse(page("ownerPublic").path("copyVisible").asBoolean());
        assertFalse(page("ownerPrivate").path("copyVisible").asBoolean());
        assertTrue(page("otherUserPrivate").path("unavailable").asBoolean());
        assertTrue(page("halloween").path("copyVisible").asBoolean());
    }

    @Test
    void pressingCopyShowsSuccessAndLinksToTheNewRoutine() {
        JsonNode result = page("copyResult");

        assertTrue(result.path("message").asString().toLowerCase().contains("copied"), result.toString());
        assertTrue(result.path("link").asString().contains("routine.html?id="), result.toString());
        assertFalse(result.path("buttonEnabled").asBoolean());
        assertTrue(page("afterCopy").path("otherButtonsStillThere").asBoolean());
    }

    @Test
    void theCopyIsAnOwnPrivateRoutineWithWorkingButtons() {
        JsonNode opened = page("openedCopy");

        assertEquals("Browser source", opened.path("title").asString());
        assertTrue(opened.path("edit").asBoolean(), opened.toString());
        assertTrue(opened.path("editHabits").asBoolean(), opened.toString());
        assertTrue(opened.path("start").asBoolean(), opened.toString());
        assertFalse(opened.path("copyVisible").asBoolean());
        assertEquals(1, opened.path("habits").asInt());
        assertTrue(page("editModal").path("opened").asBoolean());
        assertTrue(page("habitsModal").path("opened").asBoolean());
        assertEquals("/routine-run.html", page("startResult").path("path").asString());
    }

    @Test
    void theCopyAppearsInMyRoutines() {
        assertTrue(page("myRoutines").path("listed").asBoolean(), browser.toString());
    }

    @Test
    void aFailedCopyExplainsWhatHappened() {
        JsonNode failed = page("failedCopy");

        assertTrue(failed.path("isError").asBoolean(), failed.toString());
        assertTrue(failed.path("message").asString().toLowerCase().contains("no longer"), failed.toString());
        assertTrue(failed.path("buttonEnabled").asBoolean());
    }

    // ---- helpers ----

    private record Account(long id, String token) {
    }

    private String snapshot(long routine) {
        return jdbc.queryForList("""
                select r.title, r.description, r.category, r.duration_minutes, r.is_public, r.image_url, r.user_id,
                       r.deleted, r.average_rating, r.rating_count
                from routines r where r.id = ?
                """, routine) + "|" + jdbc.queryForList("""
                select rh.id, rh.position, h.id as habit, h.title, h.description, h.duration_minutes, h.user_id, h.routine_id
                from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? order by rh.position
                """, routine);
    }

    private List<Long> habitIdsOf(long routine) {
        return jdbc.queryForList("select habit_id from routine_habits where routine_id = ? order by position", Long.class, routine);
    }

    private long userOf(long routine) {
        return jdbc.queryForObject("select user_id from routines where id = ?", Long.class, routine);
    }

    private int routinesOf(Account account) throws Exception {
        return json.readTree(get("/routines", account).body()).size();
    }

    private void reverseHabits(Account account, long routine) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode habit : json.readTree(get("/routines/" + routine + "/routine-habits", account).body())) {
            ids.add(0, habit.path("id").asLong());
        }
        assertEquals(200, send("PUT", "/routines/" + routine + "/routine-habits/order", account, Map.of("habitIds", ids)).status());
    }

    private String withoutPathAndTime(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "").replaceAll("\"path\":\"[^\"]*\"", "");
    }

    private Reply copy(long routine, Account account) throws Exception {
        return post("/routines/" + routine + "/copy", account, null);
    }

    private long copied(long routine, Account account) throws Exception {
        Reply reply = copy(routine, account);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Account register() throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", "Copy QA",
                "email", "copy_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, String description, String category, int minutes, boolean isPublic) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", owner, title, description, category, minutes, isPublic);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private void habit(Account owner, long routine, String title, String description, int minutes) throws Exception {
        Reply reply = post("/routines/" + routine + "/habits", owner, Map.of("title", title, "description", description, "durationMinutes", minutes));
        assertEquals(200, reply.status(), reply.body());
    }

    private Reply saveRoutine(String method, String path, Account account, String title, String description, String category, int minutes, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("category", category);
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + account.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, Account account) throws Exception {
        return send("GET", path, account, null);
    }

    private Reply post(String path, Account account, Map<String, ?> payload) throws Exception {
        return send("POST", path, account, payload);
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
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
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
