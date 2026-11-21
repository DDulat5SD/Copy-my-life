package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
class PrivateRoutineAccessRegressionTest {

    private static final String BOUNDARY = "AccessRegressionBoundary";
    private static final long MISSING_ROUTINE = 987_654_321L;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Reply(int status, String body) {
    }

    // ---- viewing ----

    @Test
    void ownerSeesAndEditsOwnPrivateRoutine() throws Exception {
        String owner = register();
        long routine = routine(owner, "OWNER-PRIVATE", false);
        long link = habit(owner, routine, "OWNER-HABIT");

        assertEquals(200, get("/routines/" + routine, owner).status());
        assertTrue(get("/routines/" + routine + "/routine-habits", owner).body().contains("OWNER-HABIT"));
        assertTrue(get("/routines", owner).body().contains("OWNER-PRIVATE"));

        Reply edited = saveRoutine("PUT", "/routines/" + routine, owner, "OWNER-RENAMED", false);
        assertEquals(200, edited.status(), edited.body());
        assertTrue(get("/routines/" + routine, owner).body().contains("OWNER-RENAMED"));

        assertEquals(200, post("/routine-comments/" + routine, owner, Map.of("text", "own note")).status());
        assertTrue(get("/routine-comments/routine/" + routine, owner).body().contains("own note"));
        assertEquals(200, post("/routine-ratings/" + routine, owner, Map.of("value", 4)).status());
        assertEquals(200, get("/routine-ratings/" + routine, owner).status());
        assertTrue(link > 0);
    }

    @Test
    void strangerAndGuestCannotViewPrivateRoutine() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "HIDDEN-TITLE", false);
        habit(owner, routine, "HIDDEN-HABIT");
        post("/routine-comments/" + routine, owner, Map.of("text", "HIDDEN-COMMENT"));

        for (String path : List.of(
                "/routines/" + routine,
                "/routines/" + routine + "/routine-habits",
                "/routine-comments/routine/" + routine,
                "/routine-ratings/" + routine
        )) {
            Reply asStranger = get(path, stranger);
            Reply asGuest = get(path, null);

            assertEquals(404, asStranger.status(), path);
            // Visitors may read public routines, so a private one answers them as missing too.
            assertEquals(path.startsWith("/routines/") ? 404 : 403, asGuest.status(), path);
            assertNoSecrets(asStranger.body());
            assertNoSecrets(asGuest.body());
        }

        Reply habits = get("/routines/" + routine + "/habits", stranger);
        assertEquals("[]", habits.body());
    }

    @Test
    void privateRoutineLooksExactlyLikeAMissingOne() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "HIDDEN-TITLE", false);

        for (String[] request : List.of(
                new String[]{"GET", "/routines/%d"},
                new String[]{"GET", "/routine-comments/routine/%d"},
                new String[]{"GET", "/routine-ratings/%d"},
                new String[]{"POST", "/routine-ratings/%d"},
                new String[]{"POST", "/routine-comments/%d"},
                new String[]{"POST", "/saved-routines/%d"},
                new String[]{"POST", "/liked-routines/%d"}
        )) {
            Map<String, ?> body = request[1].contains("ratings") ? Map.of("value", 3) : Map.of("text", "hello");
            Reply hidden = send(request[0], request[1].formatted(routine), stranger, request[0].equals("GET") ? null : body);
            Reply missing = send(request[0], request[1].formatted(MISSING_ROUTINE), stranger, request[0].equals("GET") ? null : body);

            assertEquals(404, hidden.status(), request[1]);
            assertEquals(missing.status(), hidden.status(), request[1]);
            assertEquals(withoutPathAndTime(missing.body()), withoutPathAndTime(hidden.body()), request[1]);
        }
    }

    // ---- interactions ----

    @Test
    void strangerCannotInteractWithPrivateRoutine() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "HIDDEN-TITLE", false);
        long ownerComment = json.readTree(post("/routine-comments/" + routine, owner, Map.of("text", "HIDDEN-COMMENT")).body()).path("id").asLong();
        Map<String, Object> before = jdbc.queryForMap("select average_rating, rating_count, title from routines where id = ?", routine);

        assertEquals(404, post("/routine-comments/" + routine, stranger, Map.of("text", "intruder")).status());
        assertEquals(404, post("/routine-comments/" + routine, stranger, Map.of("text", "intruder", "parentId", ownerComment)).status());
        assertEquals(404, post("/routine-ratings/" + routine, stranger, Map.of("value", 1)).status());
        assertEquals(404, post("/liked-routines/" + routine, stranger, null).status());
        assertEquals(404, post("/saved-routines/" + routine, stranger, null).status());
        assertEquals(404, post("/reports/comments/" + ownerComment, stranger, Map.of("reason", "spam")).status());

        assertEquals(before, jdbc.queryForMap("select average_rating, rating_count, title from routines where id = ?", routine));
        assertEquals(0, count("select count(*) from routine_ratings where routine_id = ?", routine));
        assertEquals(0, count("select count(*) from liked_routines where routine_id = ?", routine));
        assertEquals(0, count("select count(*) from saved_routines where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from routine_comments where routine_id = ?", routine));
    }

    @Test
    void guestCannotInteractWithPrivateRoutine() throws Exception {
        String owner = register();
        long routine = routine(owner, "HIDDEN-TITLE", false);

        assertEquals(403, post("/routine-comments/" + routine, null, Map.of("text", "intruder")).status());
        assertEquals(403, post("/routine-ratings/" + routine, null, Map.of("value", 1)).status());
        assertEquals(403, post("/liked-routines/" + routine, null, null).status());
        assertEquals(403, post("/saved-routines/" + routine, null, null).status());
    }

    @Test
    void ownerCannotLikeOrSaveOwnPrivateRoutineButGetsAClearAnswer() throws Exception {
        String owner = register();
        long routine = routine(owner, "OWN", false);

        assertEquals(400, post("/liked-routines/" + routine, owner, null).status());
        assertEquals(400, post("/saved-routines/" + routine, owner, null).status());
    }

    @Test
    void deletedRoutineIsGoneForEveryone() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "DELETED", true);
        assertEquals(204, send("DELETE", "/routines/" + routine, owner, null).status());

        for (String token : List.of(owner, stranger)) {
            assertEquals(404, get("/routines/" + routine, token).status());
            assertEquals(404, get("/routine-comments/routine/" + routine, token).status());
            assertEquals(404, post("/routine-comments/" + routine, token, Map.of("text", "x")).status());
            assertEquals(404, post("/routine-ratings/" + routine, token, Map.of("value", 2)).status());
            assertEquals(404, post("/liked-routines/" + routine, token, null).status());
            assertEquals(404, post("/saved-routines/" + routine, token, null).status());
        }
    }

    // ---- habits reached by id ----

    @Test
    void privateHabitCannotBeSavedByGuessingItsId() throws Exception {
        String owner = register();
        String stranger = register();
        long routine = routine(owner, "HIDDEN-TITLE", false);
        habit(owner, routine, "HIDDEN-HABIT");
        long habitId = habitIdIn(routine);
        long library = json.readTree(post("/habit-library", owner, Map.of("title", "HIDDEN-HABIT", "durationMinutes", 5)).body()).path("id").asLong();

        for (long id : List.of(habitId, library)) {
            Reply reply = post("/saved-habits/" + id, stranger, null);

            assertEquals(404, reply.status());
            assertNoSecrets(reply.body());
            assertEquals(0, count("select count(*) from saved_habits where habit_id = ?", id));
        }
        assertEquals(404, post("/saved-habits/" + MISSING_ROUTINE, stranger, null).status());
    }

    @Test
    void ownHabitsCanStillBeSavedIncludingLibraryOnes() throws Exception {
        String owner = register();
        long routine = routine(owner, "OWN", false);
        habit(owner, routine, "OWN-ROUTINE-HABIT");
        long routineHabit = habitIdIn(routine);
        long library = json.readTree(post("/habit-library", owner, Map.of("title", "OWN-LIBRARY-HABIT", "durationMinutes", 5)).body()).path("id").asLong();

        assertEquals(200, post("/saved-habits/" + routineHabit, owner, null).status());
        assertEquals(200, post("/saved-habits/" + library, owner, null).status());

        Reply saved = get("/saved-habits", owner);
        assertEquals(200, saved.status(), saved.body());
        assertTrue(saved.body().contains("OWN-ROUTINE-HABIT"));
        assertTrue(saved.body().contains("OWN-LIBRARY-HABIT"));
    }

    // ---- public to private and back ----

    @Test
    void goingPrivateHidesEverythingAndGoingPublicRestoresIt() throws Exception {
        String owner = register();
        String fan = register();
        long routine = routine(owner, "TRANSITION-TITLE", true);
        habit(owner, routine, "TRANSITION-HABIT");
        long habitId = habitIdIn(routine);

        assertEquals(200, post("/liked-routines/" + routine, fan, null).status());
        assertEquals(200, post("/saved-routines/" + routine, fan, null).status());
        assertEquals(200, post("/routine-ratings/" + routine, fan, Map.of("value", 5)).status());
        assertEquals(200, post("/routine-comments/" + routine, fan, Map.of("text", "TRANSITION-COMMENT")).status());
        assertEquals(200, post("/saved-habits/" + habitId, fan, null).status());

        for (String list : List.of("/liked-routines", "/saved-routines", "/routine-comments/commented-routines", "/saved-habits")) {
            assertTrue(get(list, fan).body().contains("TRANSITION"), list);
        }

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "TRANSITION-TITLE", false).status());

        for (String list : List.of("/liked-routines", "/saved-routines", "/routine-comments/commented-routines", "/saved-habits")) {
            Reply reply = get(list, fan);

            assertEquals(200, reply.status(), list);
            assertFalse(reply.body().contains("TRANSITION"), list + " -> " + reply.body());
        }
        assertEquals(404, get("/routines/" + routine, fan).status());
        assertEquals(404, get("/routine-comments/routine/" + routine, fan).status());
        assertEquals(404, get("/routine-ratings/" + routine, fan).status());
        assertEquals(404, post("/routine-comments/" + routine, fan, Map.of("text", "again")).status());
        assertEquals(404, post("/routine-ratings/" + routine, fan, Map.of("value", 1)).status());

        // The fan's records are hidden, not destroyed.
        assertEquals(1, count("select count(*) from liked_routines where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from saved_routines where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from routine_ratings where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from routine_comments where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from saved_habits where habit_id = ?", habitId));

        // The owner keeps full access while it is private.
        assertTrue(get("/routine-comments/routine/" + routine, owner).body().contains("TRANSITION-COMMENT"));
        assertEquals(5.0, json.readTree(get("/routines/" + routine, owner).body()).path("averageRating").asDouble());

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "TRANSITION-TITLE", true).status());

        for (String list : List.of("/liked-routines", "/saved-routines", "/routine-comments/commented-routines", "/saved-habits")) {
            assertTrue(get(list, fan).body().contains("TRANSITION"), list);
        }
        assertEquals(200, get("/routines/" + routine, fan).status());
        assertTrue(get("/routine-comments/routine/" + routine, fan).body().contains("TRANSITION-COMMENT"));
        assertEquals(5, json.readTree(get("/routine-ratings/" + routine, fan).body()).path("userRating").asInt());
        assertEquals(200, post("/routine-comments/" + routine, fan, Map.of("text", "welcome back")).status());
    }

    @Test
    void ownerKeepsSeeingOwnRoutineInListsAfterGoingPrivate() throws Exception {
        String owner = register();
        long routine = routine(owner, "OWN-LISTED", true);
        post("/liked-routines/" + routine, owner, null);
        post("/saved-routines/" + routine, owner, null);
        post("/routine-comments/" + routine, owner, Map.of("text", "note"));

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "OWN-LISTED", false).status());

        for (String list : List.of("/liked-routines", "/saved-routines", "/routine-comments/commented-routines")) {
            assertTrue(get(list, owner).body().contains("OWN-LISTED"), list);
        }
    }

    @Test
    void strangerCanStillRemoveOwnRecordOfARoutineThatWentPrivate() throws Exception {
        String owner = register();
        String fan = register();
        long routine = routine(owner, "CLEANUP", true);
        post("/liked-routines/" + routine, fan, null);
        post("/saved-routines/" + routine, fan, null);
        saveRoutine("PUT", "/routines/" + routine, owner, "CLEANUP", false);

        assertEquals(204, send("DELETE", "/liked-routines/" + routine, fan, null).status());
        assertEquals(204, send("DELETE", "/saved-routines/" + routine, fan, null).status());
    }

    // ---- public routines keep working ----

    @Test
    void publicRoutineStillWorksForOtherUsers() throws Exception {
        String owner = register();
        String fan = register();
        long routine = routine(owner, "PUBLIC-TITLE", true);
        habit(owner, routine, "PUBLIC-HABIT");
        long habitId = habitIdIn(routine);

        assertEquals(200, get("/routines/" + routine, fan).status());
        assertTrue(get("/routines/" + routine + "/routine-habits", fan).body().contains("PUBLIC-HABIT"));
        assertTrue(get("/routines/public", null).body().contains("PUBLIC-TITLE"));
        assertEquals(200, post("/liked-routines/" + routine, fan, null).status());
        assertEquals(200, post("/saved-routines/" + routine, fan, null).status());
        assertEquals(200, post("/routine-ratings/" + routine, fan, Map.of("value", 4)).status());
        assertEquals(200, post("/saved-habits/" + habitId, fan, null).status());

        long comment = json.readTree(post("/routine-comments/" + routine, fan, Map.of("text", "PUBLIC-COMMENT")).body()).path("id").asLong();
        assertEquals(200, post("/routine-comments/" + routine, owner, Map.of("text", "reply", "parentId", comment)).status());
        assertTrue(get("/routine-comments/routine/" + routine, fan).body().contains("PUBLIC-COMMENT"));
        assertEquals(4, json.readTree(get("/routine-ratings/" + routine, fan).body()).path("userRating").asInt());
        assertEquals(204, send("DELETE", "/routine-comments/" + comment, fan, null).status());
        assertEquals(200, post("/reports/users/" + idOf(owner), fan, Map.of("reason", "spam")).status());
    }

    @Test
    void builtInRoutinesStillOpenForEveryone() throws Exception {
        String user = register();
        JsonNode builtIn = json.readTree(get("/routines/built-in", null).body());

        assertTrue(builtIn.size() > 0);
        long id = builtIn.get(0).path("id").asLong();
        assertEquals(200, get("/routines/" + id, user).status());
        assertEquals(200, get("/routine-comments/routine/" + id, user).status());
        assertEquals(200, get("/routine-ratings/" + id, user).status());
    }

    // ---- page shown when a routine cannot be opened ----

    @Test
    void routinePageShipsNoFakeRoutineData() throws Exception {
        String page = get("/routine.html", null).body();

        for (String placeholder : List.of("Morning Study Routine", "Daulet", "Created 2 weeks ago", "Study routine", ">10:24<")) {
            assertFalse(page.contains(placeholder), placeholder);
        }
        assertTrue(page.contains("showRoutineUnavailable"));
        assertTrue(get("/routine-run.js", null).body().contains("error.status === 404"));
    }

    // ---- helpers ----

    private void assertNoSecrets(String body) {
        for (String secret : List.of("HIDDEN-TITLE", "HIDDEN-HABIT", "HIDDEN-COMMENT")) {
            assertFalse(body.contains(secret), body);
        }
    }

    private String withoutPathAndTime(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "").replaceAll("\"path\":\"[^\"]*\"", "");
    }

    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private long idOf(String token) throws Exception {
        return json.readTree(get("/users/me", token).body()).path("id").asLong();
    }

    private String register() throws Exception {
        Reply reply = post("/auth/register", null, Map.of(
                "name", "Access QA",
                "email", "access_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("token").asString();
    }

    private long routine(String token, String title, boolean isPublic) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", token, title, isPublic);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long habit(String token, long routine, String title) throws Exception {
        Reply reply = post("/routines/" + routine + "/habits", token, Map.of("title", title, "description", "d", "durationMinutes", 5));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private long habitIdIn(long routine) {
        return jdbc.queryForObject("select habit_id from routine_habits where routine_id = ? order by id limit 1", Long.class, routine);
    }

    private Reply saveRoutine(String method, String path, String token, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "d");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "60");
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Reply post(String path, String token, Map<String, ?> payload) throws Exception {
        return send("POST", path, token, payload);
    }

    private Reply send(String method, String path, String token, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
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
}
